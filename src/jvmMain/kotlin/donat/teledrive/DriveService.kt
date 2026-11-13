package donat.teledrive

import donat.teledrive.core.Direction
import donat.teledrive.core.FileCategory
import donat.teledrive.core.IntegrityException
import donat.teledrive.core.NoManifestException
import donat.teledrive.core.RemoteObject
import donat.teledrive.data.groupObjects
import kotlinx.coroutines.flow.first
import donat.teledrive.core.RateLimitedException
import donat.teledrive.core.SafetyState
import donat.teledrive.core.StorageException
import donat.teledrive.core.StorageProvider
import donat.teledrive.core.TransferState
import donat.teledrive.core.TransferState.*
import donat.teledrive.data.AppDatabase
import donat.teledrive.data.Drive
import donat.teledrive.data.DriveFile
import donat.teledrive.data.FilePart
import donat.teledrive.data.Folder
import donat.teledrive.data.Manifest
import donat.teledrive.data.SyncState
import donat.teledrive.data.Transfer
import donat.teledrive.data.merge
import donat.teledrive.data.sameContent
import donat.teledrive.data.snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun now() = System.currentTimeMillis()
fun newId() = UUID.randomUUID().toString()
fun clock(ms: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
val DriveFile.category get() = FileCategory.of(mime, name)

/** What a running transfer is doing right now (memory only; the DB holds confirmed state). */
data class Live(val phase: String, val bytes: Long = 0, val bytesPerSec: Long = 0)

/** Drive operations on top of any [StorageProvider]. No Telegram types here. */
class DriveService(
    private val db: AppDatabase,
    private val provider: StorageProvider,
    val drive: Drive,
    workDir: File,
) {
    val dao = db.dao()
    private val tmpDir = File(workDir, "tmp").apply { mkdirs() }
    private val cacheDir = File(workDir, "cache").apply { mkdirs() }
    private val thumbDir = File(workDir, "thumbs").apply { mkdirs() }
    private val manifestLock = Mutex()
    private val thumbs = Semaphore(3)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val active = ConcurrentHashMap<String, Job>()

    /** Uploads whose bytes are all in Telegram but that the manifest doesn't list yet. Saved in one batch. */
    private val awaitingManifest = ConcurrentHashMap.newKeySet<String>()
    private var awaitingSince = 0L
    @Volatile private var dirtyGen = 0L
    @Volatile private var savedGen = 0L
    private var dirtySince = 0L
    private var manifestRetryAt = 0L

    private val baseFile = File(workDir, "manifest-base-${drive.id}.json")
    private var lastPull = 0L

    val live = MutableStateFlow<Map<String, Live>>(emptyMap())
    /** Telegram FLOOD_WAIT: no new network work starts before this time. Applies to all transfers at once. */
    val pausedUntil = MutableStateFlow(0L)
    val syncStatus = MutableStateFlow("")
    /** Human-readable notes from merging another PC's changes; shown until the user dismisses them. */
    val conflicts = MutableStateFlow<List<String>>(emptyList())
    /** Short user-facing events for Windows notifications (uploads saved, downloads done, failures). */
    val events = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 32)

    // ---------- Queue API ----------

    suspend fun enqueueUpload(local: File, folderId: String?) {
        val t = now()
        val mime = runCatching { Files.probeContentType(local.toPath()) }.getOrNull() ?: "application/octet-stream"
        val file = DriveFile(newId(), drive.id, folderId, local.name, local.length(), mime, null, SafetyState.UPLOADING, createdAt = t, updatedAt = t)
        dao.upsert(file)
        dao.upsert(Transfer(newId(), file.id, Direction.UPLOAD, local.absolutePath, QUEUED, bytesTotal = file.size, createdAt = t, updatedAt = t))
        wake.trySend(Unit)
    }

    /** Files and whole folders (dropped or picked); folders are recreated in the drive. */
    suspend fun enqueueUploads(paths: List<File>, folderId: String?) {
        for (p in paths) {
            if (p.isDirectory) {
                val folder = Folder(newId(), drive.id, folderId, p.name, updatedAt = now())
                change { dao.upsert(folder) }
                enqueueUploads(p.listFiles()?.sortedBy { it.name }.orEmpty(), folder.id)
            } else if (p.isFile) {
                enqueueUpload(p, folderId)
            }
        }
    }

    suspend fun enqueueDownload(file: DriveFile, dest: File) {
        val t = now()
        dao.upsert(Transfer(newId(), file.id, Direction.DOWNLOAD, dest.absolutePath, QUEUED, bytesTotal = file.size, createdAt = t, updatedAt = t))
        wake.trySend(Unit)
    }

    /** Downloads files and whole folders (recreating the folder tree) into [dir]. */
    suspend fun enqueueDownloads(files: List<DriveFile>, folders: List<Folder>, dir: File) {
        files.forEach { enqueueDownload(it, uniqueFile(dir, it.name)) }
        for (folder in folders) {
            val sub = uniqueFile(dir, folder.name).apply { mkdirs() }
            enqueueDownloads(dao.childFiles(folder.id).filter { it.trashedAt == null && it.versionOf == null }, dao.childFolders(folder.id).filter { it.trashedAt == null }, sub)
        }
    }

    suspend fun pause(t: Transfer) {
        stop(t.id)
        val cur = dao.transfer(t.id) ?: return
        if (cur.state.canMoveTo(PAUSED)) save(cur.moved(PAUSED))
    }

    suspend fun resume(t: Transfer) {
        val cur = dao.transfer(t.id) ?: return
        if (cur.state == PAUSED || cur.state == FAILED) save(cur.moved(QUEUED).copy(retryCount = 0, nextRetryAt = null, error = null))
        wake.trySend(Unit)
    }

    /** Cancels a transfer. An upload that never became safe is removed along with any bytes it already sent. */
    suspend fun cancel(t: Transfer) {
        stop(t.id)
        val cur = dao.transfer(t.id) ?: return
        if (cur.state.canMoveTo(CANCELLED)) save(cur.moved(CANCELLED).copy(error = null))
        if (cur.direction == Direction.DOWNLOAD) {
            File(cur.localPath + ".tdpart").delete()
            return
        }
        val file = dao.file(cur.fileId) ?: return
        if (file.safety == SafetyState.REMOTE_SAFE || file.safety == SafetyState.VERIFIED) return
        val refs = dao.parts(file.id).map { it.remoteRef }
        // This upload may already have replaced an older file of the same name (it became a hidden version): bring it back.
        dao.versionList(file.id).maxByOrNull { it.createdAt }?.let { dao.reparentVersions(file.id, it.id); markDirty() }
        dao.deleteFile(file.id)
        refs.filter { dao.refCount(drive.id, it) == 0 }.forEach { runCatching { provider.deletePart(it) } }
    }

    suspend fun pauseAll() = dao.pendingTransfers(drive.id).forEach { pause(it) }
    suspend fun resumeAll() = dao.transfersIn(drive.id, listOf(PAUSED, FAILED)).forEach { resume(it) }
    suspend fun clearFinished() = dao.clearFinishedTransfers()

    private suspend fun stop(id: String) {
        active[id]?.cancelAndJoin()
        awaitingManifest.remove(id)
        live.update { it - id }
    }

    // ---------- File operations (local first, manifest saved in the background) ----------

    suspend fun createFolder(name: String, parentId: String?) = change { dao.upsert(Folder(newId(), drive.id, parentId, name.trim(), updatedAt = now())) }
    suspend fun rename(file: DriveFile, name: String) = change { dao.upsert(file.copy(name = name.trim(), updatedAt = now())) }
    suspend fun rename(folder: Folder, name: String) = change { dao.upsert(folder.copy(name = name.trim(), updatedAt = now())) }
    suspend fun setFavorite(file: DriveFile, on: Boolean) = change { dao.upsert(file.copy(favorite = on, updatedAt = now())) }

    suspend fun move(files: List<DriveFile>, folders: List<Folder>, target: String?) = change {
        val blocked = folders.flatMap { descendantFolders(it) + it }.map { it.id }.toSet()
        require(target == null || target !in blocked) { "Can't move a folder into itself" }
        files.forEach { dao.upsert(it.copy(folderId = target, updatedAt = now())) }
        folders.forEach { dao.upsert(it.copy(parentId = target, updatedAt = now())) }
    }

    suspend fun trash(files: List<DriveFile>, folders: List<Folder>) = change {
        files.forEach { dao.upsert(it.copy(trashedAt = now(), updatedAt = now())) }
        folders.forEach { dao.upsert(it.copy(trashedAt = now(), updatedAt = now())) }
    }

    /** Back out of the Trash. Items whose parent folder is still trashed (or gone) land at the top level, so they're visible again. */
    suspend fun restore(files: List<DriveFile>, folders: List<Folder>) = change {
        val restoring = folders.map { it.id }.toSet()
        suspend fun visible(id: String?): Boolean = id == null || dao.folder(id)?.let { (it.trashedAt == null || it.id in restoring) && visible(it.parentId) } == true
        files.forEach { dao.upsert(it.copy(trashedAt = null, folderId = it.folderId.takeIf { p -> visible(p) }, updatedAt = now())) }
        folders.forEach { dao.upsert(it.copy(trashedAt = null, parentId = it.parentId.takeIf { p -> visible(p) }, updatedAt = now())) }
    }

    /** Re-downloads every part and checks SHA-256. Good → VERIFIED; bytes differ → ERROR. Network problems just throw. */
    suspend fun verify(file: DriveFile): Boolean = verifyOne(file).also { markDirty() }

    /** Verifies every not-yet-verified file (one manifest save at the end); returns (checked, damaged). */
    suspend fun verifyAll(onProgress: (Int, Int) -> Unit = { _, _ -> }): Pair<Int, Int> {
        val list = dao.unverifiedFiles(drive.id)
        var bad = 0
        try {
            list.forEachIndexed { i, f -> onProgress(i + 1, list.size); if (!verifyOne(f)) bad++ }
        } finally {
            if (list.isNotEmpty()) markDirty()
        }
        return list.size to bad
    }

    private suspend fun verifyOne(file: DriveFile): Boolean {
        val tmp = File(tmpDir, "${file.id}.verify")
        return try {
            fetch(file, tmp) {}
            // A recovered file had no hash to compare against: this download only recorded it. "Verified" comes next time.
            if (file.sha256 != null) dao.setSafety(file.id, SafetyState.VERIFIED)
            true
        } catch (e: IntegrityException) {
            Log.e("verify ${file.id}", e)
            dao.setSafety(file.id, SafetyState.ERROR)
            events.tryEmit("Integrity check failed: ${file.name}")
            false
        } finally {
            tmp.delete()
        }
    }

    /** Makes an older version the current file again (the current one becomes a version). */
    suspend fun restoreVersion(version: DriveFile, current: DriveFile) = change {
        dao.upsert(version.copy(versionOf = null, folderId = current.folderId, name = current.name, trashedAt = null, updatedAt = now()))
        dao.supersede(current.id, version.id)
    }

    /** User looked at a CONFLICT file and accepts its current name/location. */
    suspend fun resolveConflict(file: DriveFile) = change { dao.upsert(file.copy(safety = SafetyState.REMOTE_SAFE, updatedAt = now())) }

    /**
     * Deletes forever. Order matters: first the manifest stops referencing the files, only then are the Telegram
     * messages deleted, and only if no other file (a deduplicated copy) still points at them.
     */
    suspend fun purge(files: List<DriveFile>, folders: List<Folder>) {
        val allFolders = folders + folders.flatMap { descendantFolders(it) }
        val allFiles = (files + allFolders.flatMap { dao.childFiles(it.id) }).let { l -> (l + l.flatMap { dao.versionList(it.id) }).distinctBy { it.id } }
        val refs = allFiles.flatMap { dao.parts(it.id) }.map { it.remoteRef }.distinct()
        allFiles.forEach { f ->
            dao.transfersOf(f.id).forEach { stop(it.id) }
            dao.deleteFile(f.id)
            File(cacheDir, f.id).deleteRecursively()
            File(thumbDir, "${f.id}.jpg").delete()
        }
        allFolders.forEach { dao.deleteFolder(it.id) }
        markDirty()
        saveManifestNow()
        // If this is interrupted, the messages stay in the channel unlisted: Settings → "Check for unlisted files" removes them.
        refs.filter { dao.refCount(drive.id, it) == 0 }.forEach { runCatching { provider.deletePart(it) } }
    }

    private suspend fun descendantFolders(f: Folder): List<Folder> = dao.childFolders(f.id).flatMap { listOf(it) + descendantFolders(it) }

    private suspend fun change(block: suspend () -> Unit) {
        block()
        markDirty()
    }

    // ---------- Preview / thumbnails ----------

    /** Local copy for preview/open, verified against stored hashes. Kept in the cache until cleared. */
    suspend fun cached(file: DriveFile, onLive: (Live) -> Unit = {}): File =
        cacheLocks.computeIfAbsent(file.id) { Mutex() }.withLock { // preview + prefetch of the same file share one download
            val dest = File(File(cacheDir, file.id).apply { mkdirs() }, safeName(file.name))
            if (dest.exists() && dest.length() == file.size) return@withLock dest
            val tmp = File(dest.path + ".part")
            fetch(file, tmp, onLive)
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING) // only complete, verified files land in the cache
            dest
        }

    private val cacheLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun thumbnail(file: DriveFile): File? {
        val f = File(thumbDir, "${file.id}.jpg")
        if (f.exists()) return f.takeIf { it.length() > 0 }
        if (file.category != FileCategory.IMAGES && file.category != FileCategory.VIDEO) return null
        val ref = dao.parts(file.id).firstOrNull()?.remoteRef ?: return null
        thumbs.withPermit {
            if (!f.exists()) runCatching { if (!provider.thumbnail(ref, f.path)) f.createNewFile() } // empty file = "has no thumbnail"
        }
        return f.takeIf { it.exists() && it.length() > 0 }
    }

    /** Streaming: bytes from [offset] up to [max], never crossing a part boundary. */
    suspend fun readRange(file: DriveFile, offset: Long, max: Int): ByteArray {
        val p = dao.parts(file.id).firstOrNull { offset >= it.offset && offset < it.offset + it.size } ?: throw StorageException("Offset $offset outside ${file.name}")
        return provider.readRange(p.remoteRef, offset - p.offset, minOf(max.toLong(), p.offset + p.size - offset))
    }

    suspend fun evictStream(file: DriveFile) = dao.parts(file.id).forEach { provider.evict(it.remoteRef) }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        thumbDir.listFiles()?.forEach { it.delete() }
    }

    fun cacheSize(): Long = listOf(cacheDir, thumbDir).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    // ---------- Manifest ----------

    private suspend fun markDirty() {
        dirtyGen++
        if (dirtySince == 0L) dirtySince = now()
        dao.upsert((dao.syncState(drive.id) ?: SyncState(drive.id)).copy(status = DIRTY))
        syncStatus.value = "Changes not yet saved to Telegram"
        wake.trySend(Unit)
    }

    /**
     * Writes a new manifest revision now (callers must only do this once remote content is persisted).
     * Always merges in what another PC saved first, so its changes are never overwritten.
     */
    suspend fun saveManifestNow(pending: Set<String> = emptySet()) = manifestLock.withLock {
        pullLocked()
        var mergedFrom = dao.syncState(drive.id)?.remoteVersion // newest remote manifest this PC's index contains
        var base = runCatching { Manifest.decode(baseFile.readBytes()) }.getOrNull()
        val written = mutableSetOf<String>()
        for (round in 1..RACE_ROUNDS) {
            val gen = dirtyGen
            val state = dao.syncState(drive.id) ?: SyncState(drive.id)
            val rev = state.manifestRevision + 1
            syncStatus.value = "Saving to Telegram…"
            val m = dao.snapshot(drive, rev, now(), pending)
            val version = provider.writeManifest(m.encode())
            written += version
            baseFile.writeBytes(m.encode())
            val clean = gen == dirtyGen
            dao.upsert(state.copy(manifestRevision = rev, lastSyncAt = now(), status = if (clean) "OK" else DIRTY, remoteVersion = version))
            if (clean) {
                savedGen = gen
                dirtySince = 0
            }
            syncStatus.value = "All changes saved to Telegram · revision $rev"

            // Another PC may have saved between our pull and our write: a manifest newer than what we merged that isn't ours.
            // Merge it in and write again, so neither PC's changes are lost.
            val raced = racedVersion(mergedFrom, written) ?: return@withLock
            val theirs = provider.readManifest(raced)?.let { Manifest.decode(it) } ?: return@withLock
            Log.i("manifest: another PC saved at the same time — merging its changes and saving again (round $round)")
            val local = dao.snapshot(drive, rev, now(), pending)
            val result = merge(base, local, theirs)
            dao.applyMerged(result.manifest, local, (dao.syncState(drive.id) ?: state).copy(manifestRevision = maxOf(rev, theirs.revision)))
            if (result.conflicts.isNotEmpty()) conflicts.value = conflicts.value + result.conflicts
            base = theirs
            mergedFrom = raced
        }
        markDirty() // still racing after the last round: what we merged is saved on the next batch
    }

    /** A manifest newer than [mergedFrom] that this PC didn't write: another PC saved while we were saving. */
    private suspend fun racedVersion(mergedFrom: String?, written: Set<String>): String? {
        val versions = provider.manifestVersions() // newest first
        val newer = if (mergedFrom == null) versions else versions.indexOf(mergedFrom).let { i -> if (i < 0) return null else versions.take(i) }
        return newer.firstOrNull { it !in written } // i < 0: what we merged was pruned or its channel replaced — nothing to compare
    }

    /** Checks for another PC's newer manifest and merges it in. Safe to call any time. */
    suspend fun pull() = manifestLock.withLock { pullLocked() }

    private suspend fun pullLocked() {
        lastPull = now()
        val state = dao.syncState(drive.id) ?: SyncState(drive.id)
        val version = provider.manifestVersion()
        if (version == null) {
            // The backend lost its index (data channel deleted) while this PC still has the whole drive: save it again.
            if (state.remoteVersion != null && dirtyGen == savedGen) {
                Log.i("manifest: missing in the backend — saving this PC's index again")
                markDirty()
            }
            return
        }
        if (version == state.remoteVersion) return
        val remote = Manifest.decode(provider.readManifest(version) ?: return)
        if (remote.driveId != drive.id) throw StorageException("This channel holds a different drive (${remote.driveName})")
        val base = runCatching { Manifest.decode(baseFile.readBytes()) }.getOrNull()
        val local = dao.snapshot(drive, state.manifestRevision, now())
        val result = merge(base, local, remote)
        dao.applyMerged(result.manifest, local, state.copy(manifestRevision = maxOf(state.manifestRevision, remote.revision), lastSyncAt = now(), remoteVersion = version))
        baseFile.writeBytes(remote.encode()) // we now contain everything the other PC saved
        if (result.conflicts.isNotEmpty()) conflicts.value = conflicts.value + result.conflicts
        if (!sameContent(result.manifest, remote)) markDirty() // our own changes still need saving
        syncStatus.value = if (sameContent(local, remote)) "All changes saved to Telegram · revision ${remote.revision}"
        else "Merged changes from another PC · revision ${remote.revision}"
    }

    /**
     * Recreates the local index for this drive from the remote manifest. If the backend has none right now, this PC's
     * index is the only full copy: it is kept (never wiped) and saved to the backend again.
     */
    suspend fun rebuildIndex(): Manifest = try {
        manifestLock.withLock { restore(db, provider, drive.remoteId, drive.providerType, baseFile.parentFile) }
    } catch (e: NoManifestException) {
        markDirty()
        throw StorageException("Telegram has no index for this drive right now. This PC's index was kept and is being saved to Telegram again.")
    }

    // ---------- Unlisted objects (phone uploads, leftovers of an interrupted delete, lost index) ----------

    /** Objects in the backend that no file of this drive points to (trashed files, old versions and uploads count as pointed to). */
    suspend fun findUnlisted(): List<RemoteObject> {
        // An upload in flight has sent bytes whose part isn't recorded yet — it would look unlisted.
        if (dao.pendingTransfers(drive.id).any { it.direction == Direction.UPLOAD }) throw StorageException("Wait until uploads finish, then check again.")
        val known = dao.allRefs(drive.id).toSet()
        return provider.listObjects().filter { it.ref !in known }
    }

    /**
     * Adds unlisted objects as files — split pieces ("name.part1of3"…) joined back into one — in the top-level folder
     * [folderName] (created if needed), or at the top level. Contents are remote (REMOTE_SAFE); hashes are filled in by the
     * first full download or "Verify".
     */
    suspend fun adopt(objects: List<RemoteObject>, folderName: String?): Int {
        val known = dao.allRefs(drive.id).toSet()
        val groups = groupObjects(objects.filter { it.ref !in known })
        if (groups.isEmpty()) return 0
        val folderId = folderName?.let { name ->
            (dao.folders(drive.id, null).first().firstOrNull { it.name == name } ?: Folder(newId(), drive.id, null, name, updatedAt = now()).also { dao.upsert(it) }).id
        }
        for ((name, pieces) in groups) {
            val mime = pieces.first().mime.takeUnless { it.isBlank() || it == "application/octet-stream" }
                ?: runCatching { Files.probeContentType(java.nio.file.Path.of(name)) }.getOrNull() ?: "application/octet-stream"
            val t = pieces.minOf { it.createdAt }
            val f = DriveFile(newId(), drive.id, folderId, name, pieces.sumOf { it.size }, mime, null, SafetyState.REMOTE_SAFE, createdAt = t, updatedAt = t)
            dao.upsert(f)
            var offset = 0L
            pieces.forEachIndexed { i, p -> dao.upsertParts(listOf(FilePart(f.id, i, p.ref, offset, p.size, ""))); offset += p.size }
        }
        markDirty()
        return groups.size
    }

    /** Deletes unlisted objects from the backend for good — re-checked right before, so nothing a file points to is ever deleted. */
    suspend fun deleteUnlisted(objects: List<RemoteObject>): Int {
        val known = dao.allRefs(drive.id).toSet()
        val doomed = objects.filter { it.ref !in known }
        doomed.forEach { provider.deletePart(it.ref) }
        return doomed.size
    }

    // ---------- Runner ----------

    /** Runs forever: up to [PARALLEL] durable transfers at a time; state lives in SQLite so it survives restarts. */
    suspend fun runTransfers() = coroutineScope {
        dao.pendingTransfers(drive.id).filter { it.state == PREPARING || it.state == RUNNING }
            .forEach { save(it.moved(RETRY_WAIT).copy(nextRetryAt = now(), error = "Interrupted by app exit")) }
        val sync = dao.syncState(drive.id)
        if (sync?.status == DIRTY && dirtyGen == savedGen) { // unsaved changes from a previous run
            dirtyGen++
            dirtySince = now()
        }
        syncStatus.value = if (sync?.status == DIRTY) "Changes not yet saved to Telegram" else "All changes saved to Telegram · revision ${sync?.manifestRevision ?: 0}"
        launch(Dispatchers.IO) { runCatching { provider.tidy() }.onFailure { if (it is CancellationException) throw it; Log.e("tidy", it) } }
        while (true) {
            if (now() >= pausedUntil.value && active.size < PARALLEL) {
                dao.pendingTransfers(drive.id)
                    .filter { !active.containsKey(it.id) && (it.state == QUEUED || (it.state == RETRY_WAIT && (it.nextRetryAt ?: 0) <= now())) }
                    .take(PARALLEL - active.size)
                    .forEach { t ->
                        val job = launch(Dispatchers.IO, CoroutineStart.LAZY) { process(t) }
                        active[t.id] = job
                        job.invokeOnCompletion { active.remove(t.id); wake.trySend(Unit) }
                        job.start()
                    }
            }
            maybeSaveManifest()
            if (now() - lastPull > PULL_EVERY && now() >= pausedUntil.value) {
                try {
                    pull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is RateLimitedException) pausedUntil.value = now() + e.retryAfterSeconds * 1000
                    syncStatus.value = "Couldn't check for changes from other PCs: ${e.message}"
                }
            }
            withTimeoutOrNull(1_000) { wake.receive() }
        }
    }

    private suspend fun maybeSaveManifest() {
        val dirty = dirtyGen != savedGen
        if (!dirty && awaitingManifest.isEmpty()) return
        if (now() < maxOf(manifestRetryAt, pausedUntil.value)) return
        val since = listOf(dirtySince, awaitingSince).filter { it > 0 }.minOrNull() ?: now()
        val waited = now() - since
        // Batch: wait while uploads are still running, unless the batch is big or old. Then coalesce quick edits briefly.
        if (active.isNotEmpty() && awaitingManifest.size < BATCH && waited < MAX_BATCH_WAIT) {
            syncStatus.value = "${awaitingManifest.size} uploaded file(s) will be saved to the drive manifest in one batch"
            return
        }
        if (waited < SETTLE) return

        val batch = awaitingManifest.toList()
        val transfers = batch.mapNotNull { dao.transfer(it) }
        transfers.forEach { t ->
            // Same name in the same folder = a new version of that file, not a duplicate entry.
            dao.file(t.fileId)?.takeIf { it.versionOf == null }?.let { f -> dao.sameName(drive.id, f.folderId, f.name, f.id)?.let { dao.supersede(it.id, f.id) } }
        }
        try {
            val ids = transfers.map { it.fileId }.toSet()
            saveManifestNow(ids)
            ids.forEach { dao.setSafety(it, SafetyState.REMOTE_SAFE) } // only now: a written manifest lists them
            for (t in transfers) {
                dao.transfer(t.id)?.takeIf { it.state == RUNNING }?.let { save(it.moved(COMPLETED).copy(error = null)) }
                live.update { it - t.id }
            }
            awaitingManifest.removeAll(batch.toSet())
            if (transfers.isNotEmpty()) events.tryEmit("${transfers.size} file(s) uploaded and saved to Telegram")
            awaitingSince = if (awaitingManifest.isEmpty()) 0 else now()
            manifestRetryAt = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val wait = (e as? RateLimitedException)?.retryAfterSeconds?.times(1000) ?: 15_000
            if (e is RateLimitedException) pausedUntil.value = now() + wait
            manifestRetryAt = now() + wait
            syncStatus.value = "Couldn't save to Telegram (${e.message}). Retrying at ${clock(manifestRetryAt)}"
            transfers.forEach { setLive(it.id, Live("Uploaded · saving to drive manifest failed, retry at ${clock(manifestRetryAt)}", it.bytesTotal)) }
        }
    }

    private suspend fun process(queued: Transfer) {
        val start = if (queued.state == RETRY_WAIT) queued.moved(QUEUED) else queued
        try {
            val t = save(start.moved(PREPARING).copy(error = null))
            if (t.direction == Direction.UPLOAD) {
                upload(t)
                if (awaitingManifest.isEmpty()) awaitingSince = now()
                awaitingManifest += t.id
                setLive(t.id, Live("Uploaded · waiting to save to drive manifest", t.bytesTotal))
                wake.trySend(Unit)
            } else {
                val file = dao.file(t.fileId) ?: throw StorageException("File was deleted from the drive")
                val dest = File(t.localPath)
                val tmp = File(dest.path + ".tdpart")
                save(t.moved(RUNNING).copy(bytesDone = 0))
                fetch(file, tmp) { setLive(t.id, it) }
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                dao.transfer(t.id)?.let { save(it.copy(bytesDone = it.bytesTotal).moved(COMPLETED)) }
                events.tryEmit("Downloaded ${dest.name}")
                live.update { it - t.id }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            live.update { it - queued.id }
            fail(queued.id, e)
        }
    }

    private suspend fun fail(id: String, e: Exception) {
        Log.e("transfer $id", e)
        val cur = dao.transfer(id)?.takeIf { it.state == PREPARING || it.state == RUNNING } ?: return
        val msg = e.message ?: e.toString()
        when {
            e is RateLimitedException -> {
                val until = now() + e.retryAfterSeconds * 1000
                pausedUntil.value = maxOf(pausedUntil.value, until)
                save(cur.moved(RETRY_WAIT).copy(nextRetryAt = until, error = "Telegram rate limit (FLOOD_WAIT ${e.retryAfterSeconds}s)"))
            }
            cur.retryCount < MAX_RETRIES -> save(
                cur.moved(RETRY_WAIT).copy(retryCount = cur.retryCount + 1, nextRetryAt = now() + (5_000L shl cur.retryCount), error = msg),
            )
            else -> {
                save(cur.moved(FAILED).copy(error = msg))
                events.tryEmit("Transfer failed: ${java.io.File(cur.localPath).name} — $msg")
                if (cur.direction == Direction.UPLOAD) dao.setSafety(cur.fileId, SafetyState.LOCAL_ONLY)
            }
        }
    }

    private suspend fun upload(start: Transfer) {
        val local = File(start.localPath)
        if (!local.isFile) throw StorageException("Source file is gone: ${local.path}")
        val file = dao.file(start.fileId) ?: throw StorageException("File was removed from the drive")
        val total = local.length()
        val ctx = currentCoroutineContext()
        val plan = withContext(Dispatchers.IO) {
            hashParts(local, provider.maxPartSize) { done ->
                ctx.ensureActive()
                setLive(start.id, Live("Checking file · ${percent(done, total)}", done))
            }
        }
        dao.upsert(file.copy(size = total, sha256 = plan.sha256, updatedAt = now()))
        var t = save(start.moved(RUNNING).copy(bytesTotal = total, bytesDone = 0))

        dao.duplicateOf(drive.id, plan.sha256, total, file.id)?.let { dup ->
            dao.upsertParts(dao.parts(dup.id).map { it.copy(fileId = file.id) }) // same bytes already stored: reuse them
            save(t.copy(bytesDone = total))
            setLive(t.id, Live("Same content as “${dup.name}” · reusing stored copy, nothing to upload", total))
            return
        }

        val existing = dao.parts(file.id).associateBy { it.partIndex }
        val n = plan.parts.size
        for ((i, p) in plan.parts.withIndex()) {
            if (p.size > 0 && existing[i]?.sha256 != p.sha256) { // Telegram rejects empty files: a 0-byte file has no parts // resume: parts already uploaded with the same bytes are skipped
                val began = now()
                val label = if (n > 1) "Uploading part ${i + 1}/$n" else "Uploading"
                setLive(t.id, Live(label, p.offset))
                val ref = provider.uploadPart(local.path, p.offset, p.size, if (n > 1) "${file.name}.part${i + 1}of$n" else file.name) { sent ->
                    setLive(t.id, Live(label, p.offset + sent, sent * 1000 / maxOf(1, now() - began)))
                }
                dao.upsert(FilePart(file.id, i, ref, p.offset, p.size, p.sha256))
            }
            t = save(t.copy(bytesDone = p.offset + p.size))
        }
    }

    /** Downloads all parts into [dest], checking every part and the whole file against stored SHA-256. */
    private suspend fun fetch(file: DriveFile, dest: File, onLive: (Live) -> Unit) = withContext(Dispatchers.IO) {
        val parts = dao.parts(file.id)
        if (parts.isEmpty() && file.size > 0) throw StorageException("No stored parts for ${file.name}")
        dest.writeBytes(ByteArray(0))
        val whole = MessageDigest.getInstance("SHA-256")
        // ponytail: a retried download restarts from part 0; keep finished parts on disk if multi-GB restarts hurt.
        for (p in parts) {
            val partFile = File(tmpDir, "${file.id}.p${p.partIndex}.down")
            val began = now()
            val label = if (parts.size > 1) "Downloading part ${p.partIndex + 1}/${parts.size}" else "Downloading"
            try {
                provider.downloadPart(p.remoteRef, partFile.path) { got -> onLive(Live(label, p.offset + got, got * 1000 / maxOf(1, now() - began))) }
                onLive(Live("Verifying", p.offset + p.size))
                val got = sha256(partFile)
                if (p.sha256.isEmpty()) dao.fillPartSha(file.id, p.partIndex, got) // recovered part: its first download sets the hash
                else if (got != p.sha256) throw IntegrityException("Part ${p.partIndex + 1} of ${file.name} failed integrity check")
                partFile.inputStream().use { input ->
                    FileOutputStream(dest, true).use { out -> DigestOutputStream(out, whole).use { input.copyTo(it) } }
                }
            } finally {
                partFile.delete()
            }
        }
        val wholeHex = whole.digest().toHex()
        if (file.sha256 == null) dao.fillFileSha(file.id, wholeHex)
        else if (wholeHex != file.sha256) {
            dest.delete()
            throw IntegrityException("${file.name} failed integrity check")
        }
    }

    private fun setLive(id: String, l: Live) = live.update { it + (id to l) }

    private suspend fun save(t: Transfer): Transfer = t.copy(updatedAt = now()).also { dao.upsert(it) }

    private fun Transfer.moved(to: TransferState): Transfer {
        check(state.canMoveTo(to)) { "Illegal transfer transition $state -> $to" }
        return copy(state = to)
    }

    companion object {
        const val MAX_RETRIES = 5
        /** Parallel transfers (Settings, 1–5). */
        @Volatile var PARALLEL = Prefs["parallel"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3
        const val BATCH = 25
        const val MAX_BATCH_WAIT = 60_000L
        const val SETTLE = 2_000L
        const val PULL_EVERY = 60_000L
        /** Save rounds when another PC keeps saving at the same moment (each round merges its changes first). */
        const val RACE_ROUNDS = 3
        private const val DIRTY = "DIRTY"

        /** Reads the remote manifest and replaces the local index for that drive. */
        suspend fun restore(db: AppDatabase, provider: StorageProvider, remoteId: String, providerType: String, workDir: File): Manifest {
            val version = provider.manifestVersion() ?: throw NoManifestException()
            val m = Manifest.decode(provider.readManifest(version) ?: throw NoManifestException())
            db.dao().restore(Drive(m.driveId, m.driveName, providerType, remoteId, createdAt = m.updatedAt), m, now(), version)
            File(workDir, "manifest-base-${m.driveId}.json").writeBytes(m.encode())
            return m
        }

        /**
         * The drive's index is gone from the backend (its data channel was deleted) and no PC has it: every stored object
         * comes back as a file at the top level with its name and content. Folders can't — they only lived in the index.
         */
        suspend fun recover(db: AppDatabase, provider: StorageProvider, remoteId: String, providerType: String, name: String, workDir: File): Int {
            val drive = Drive(newId(), name, providerType, remoteId, createdAt = now())
            db.dao().upsert(drive)
            val s = DriveService(db, provider, drive, workDir)
            val n = s.adopt(provider.listObjects(), folderName = null)
            s.saveManifestNow() // a new index in a new data channel: other PCs find the drive normally from now on
            Log.i("recovered drive $remoteId without an index: $n file(s)")
            return n
        }
    }
}

private fun percent(done: Long, total: Long) = if (total <= 0) "100%" else "${done * 100 / total}%"

private val WINDOWS_DEVICES = Regex("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?")

/**
 * A drive name as one plain Windows file name. Names come from the manifest / Telegram (a forwarded document's file
 * name is chosen by its sender), so "..\..\Startup\x.bat", "a:stream" or "CON" must never reach the file system as-is.
 */
fun safeName(name: String): String {
    val s = name.map { if (it < ' ' || it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().trimEnd('.', ' ')
    return when {
        s.isEmpty() || s.all { it == '.' } -> "_"
        WINDOWS_DEVICES.matches(s) -> "_$s"
        else -> s.take(200)
    }
}

private fun uniqueFile(dir: File, raw: String): File {
    val name = safeName(raw)
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var f = File(dir, name)
    var i = 1
    while (f.exists()) f = File(dir, "$base (${i++})$ext")
    return f
}

class PartPlan(val offset: Long, val size: Long, val sha256: String)
class UploadPlan(val sha256: String, val parts: List<PartPlan>)

/** One pass over the file: whole-file SHA-256 plus one SHA-256 per part. */
fun hashParts(file: File, partSize: Long, onProgress: (Long) -> Unit = {}): UploadPlan {
    val whole = MessageDigest.getInstance("SHA-256")
    val parts = mutableListOf<PartPlan>()
    val buf = ByteArray(1 shl 20)
    file.inputStream().use { input ->
        var offset = 0L
        val total = file.length()
        do {
            val size = minOf(partSize, total - offset)
            val part = MessageDigest.getInstance("SHA-256")
            var left = size
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw StorageException("${file.name} changed while reading")
                part.update(buf, 0, n)
                whole.update(buf, 0, n)
                left -= n
                onProgress(offset + size - left)
            }
            parts += PartPlan(offset, size, part.digest().toHex())
            offset += size
        } while (offset < total)
    }
    return UploadPlan(whole.digest().toHex(), parts)
}

fun sha256(file: File): String {
    val d = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> DigestOutputStream(java.io.OutputStream.nullOutputStream(), d).use { input.copyTo(it) } }
    return d.digest().toHex()
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
