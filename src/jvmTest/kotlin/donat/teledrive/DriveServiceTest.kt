package donat.teledrive

import donat.teledrive.core.SafetyState
import donat.teledrive.core.RemoteObject
import donat.teledrive.core.StorageProvider
import donat.teledrive.core.TransferState
import donat.teledrive.data.AppDatabase
import donat.teledrive.data.Drive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stores parts as files in a folder — stands in for Telegram. Manifests are kept as numbered versions, like the data channel. */
class FolderProvider(private val dir: File) : StorageProvider {
    override val maxPartSize = 1000L
    var manifestWrites = 0
    /** Runs once, right before the next manifest write — another PC saving in the middle of our save. */
    var beforeWrite: (suspend () -> Unit)? = null

    override suspend fun uploadPart(localPath: String, offset: Long, size: Long, label: String, onProgress: (Long) -> Unit): String {
        val ref = "${newId()}-$label" // labels are display names now (may repeat), like Telegram message ids are not
        File(dir, ref).writeBytes(File(localPath).readBytes().copyOfRange(offset.toInt(), (offset + size).toInt()))
        return ref
    }
    override suspend fun downloadPart(remoteRef: String, destPath: String, onProgress: (Long) -> Unit) { File(dir, remoteRef).copyTo(File(destPath), true) }
    override suspend fun deletePart(remoteRef: String) { File(dir, remoteRef).delete() }
    override suspend fun thumbnail(remoteRef: String, destPath: String) = false
    override suspend fun readRange(remoteRef: String, offset: Long, length: Long) =
        File(dir, remoteRef).readBytes().copyOfRange(offset.toInt(), minOf(File(dir, remoteRef).length(), offset + length).toInt())

    private fun manifests() = dir.listFiles { f -> f.name.startsWith("manifest-v") }!!.sortedByDescending { it.name.removePrefix("manifest-v").toInt() }
    override suspend fun manifestVersions() = manifests().map { it.name }
    override suspend fun manifestVersion() = manifests().firstOrNull()?.name
    override suspend fun readManifest() = manifests().firstOrNull()?.readBytes()
    override suspend fun readManifest(version: String) = File(dir, version).takeIf { it.exists() }?.readBytes()
    override suspend fun writeManifest(data: ByteArray): String {
        beforeWrite?.let { beforeWrite = null; it() }
        return "manifest-v${++manifestWrites}".also { File(dir, it).writeBytes(data) }
    }
    override suspend fun listObjects() = dir.listFiles { f -> !f.name.startsWith("manifest-v") }!!.map {
        RemoteObject(it.name, it.name.replace(Regex("^[0-9a-f-]{36}-"), ""), it.length(), "application/octet-stream", it.lastModified())
    }

    /** Simulates the data channel being deleted: every manifest is gone, the files stay. */
    fun loseIndex() = manifests().forEach { it.delete() }
}

class DriveServiceTest {
    private val root = Files.createTempDirectory("teledrive-test").toFile().apply { deleteOnExit() }
    private val remote = File(root, "remote").apply { mkdirs() }
    private val provider = FolderProvider(remote)

    /** Runs the transfer loop until every transfer reaches [expect]. */
    private fun DriveService.drain(db: AppDatabase, expect: TransferState = TransferState.COMPLETED) = runBlocking {
        val job = launch { runTransfers() }
        withTimeout(15_000) { while (db.dao().transfers(drive.id).first().any { it.state != expect }) delay(20) }
        job.cancel()
    }

    @Test
    fun uploadDeleteIndexRebuildDownload() = runBlocking {
        val original = File(root, "photo.bin").apply { writeBytes(Random(1).nextBytes(2500)) } // 3 parts

        // Upload on "PC 1".
        val db1 = openDatabase(File(root, "a.db").path)
        val drive = Drive("drive-1", "My Drive", "test", "remote-1", createdAt = 1)
        db1.dao().upsert(drive)
        val s1 = DriveService(db1, provider, drive, root)
        s1.createFolder("Photos", null)
        s1.enqueueUpload(original, null)
        s1.drain(db1)
        val uploaded = db1.dao().files(drive.id, null).first().single()
        assertEquals(SafetyState.REMOTE_SAFE, uploaded.safety)
        assertEquals(3, db1.dao().parts(uploaded.id).size)
        assertEquals(1, provider.manifestWrites, "folder + upload must be saved in one manifest batch")
        db1.close()

        // SQLite lost: rebuild on a fresh database purely from the remote manifest.
        val db2 = openDatabase(File(root, "b.db").path)
        val m = DriveService.restore(db2, provider, "remote-1", "test", root)
        assertEquals(listOf("Photos"), m.folders.map { it.name })
        val restored = db2.dao().drives().first().single()
        assertEquals("drive-1", restored.id)
        val file = db2.dao().files(restored.id, null).first().single()

        // Local original deleted; download from remote must reproduce identical bytes.
        val bytes = original.readBytes()
        original.delete()
        val s2 = DriveService(db2, provider, restored, root)
        val dest = File(root, "restored.bin")
        s2.enqueueDownload(file, dest)
        s2.drain(db2)
        assertContentEquals(bytes, dest.readBytes())

        // Corrupt remote part: download must not complete and must say why.
        File(remote, db2.dao().parts(file.id)[1].remoteRef).writeBytes(ByteArray(1000))
        db2.dao().deleteAllTransfers()
        s2.enqueueDownload(file, File(root, "bad.bin"))
        s2.drain(db2, TransferState.RETRY_WAIT)
        val t = db2.dao().transfers(restored.id).first().single()
        assertTrue(t.error!!.contains("integrity"), t.error)
        assertTrue(!File(root, "bad.bin").exists())
        db2.close()
    }

    @Test
    fun verifyAndVersions() = runBlocking {
        val db = openDatabase(File(root, "v.db").path)
        val drive = Drive("drive-v", "D", "test", "remote-v", createdAt = 1)
        db.dao().upsert(drive)
        val s = DriveService(db, provider, drive, root)
        val doc = File(root, "report.txt")

        doc.writeText("version one")
        s.enqueueUpload(doc, null)
        s.drain(db)
        doc.writeText("version two, longer")
        s.enqueueUpload(doc, null)
        s.drain(db)

        // Same name re-uploaded: one visible file, the old one kept as a version.
        val visible = db.dao().files(drive.id, null).first()
        assertEquals(1, visible.size)
        val current = visible.single()
        val old = db.dao().versionList(current.id).single()
        assertEquals("version one".length.toLong(), old.size)

        // Restore the old version: it becomes current, the newer one becomes its version.
        s.restoreVersion(old, current)
        assertEquals(old.id, db.dao().files(drive.id, null).first().single().id)
        assertEquals(current.id, db.dao().versionList(old.id).single().id)

        // Integrity: good copy → VERIFIED; damaged part → ERROR.
        assertTrue(s.verify(db.dao().file(old.id)!!))
        assertEquals(SafetyState.VERIFIED, db.dao().file(old.id)!!.safety)
        File(remote, db.dao().parts(current.id).single().remoteRef).writeText("tampered!!")
        assertTrue(!s.verify(db.dao().file(current.id)!!))
        assertEquals(SafetyState.ERROR, db.dao().file(current.id)!!.safety)
        db.close()
    }

    @Test
    fun duplicateReusesPartsAndPurgeKeepsSharedBytes() = runBlocking {
        val db = openDatabase(File(root, "c.db").path)
        val drive = Drive("drive-2", "D", "test", "remote-2", createdAt = 1)
        db.dao().upsert(drive)
        val s = DriveService(db, provider, drive, root)
        val a = File(root, "a.bin").apply { writeBytes(Random(2).nextBytes(1500)) }
        val b = File(root, "b.bin").apply { writeBytes(a.readBytes()) }

        val stored = { remote.listFiles()!!.count { !it.name.startsWith("manifest-v") } }
        s.enqueueUpload(a, null)
        s.drain(db)
        val remoteFiles = stored()
        s.enqueueUpload(b, null)
        s.drain(db)
        assertEquals(remoteFiles, stored(), "identical content must not be uploaded twice")

        val (fa, fb) = db.dao().files(drive.id, null).first().sortedBy { it.name }
        assertEquals(db.dao().parts(fa.id).map { it.remoteRef }, db.dao().parts(fb.id).map { it.remoteRef })

        // Deleting one copy forever must keep the bytes the other copy still uses.
        s.trash(listOf(fa), emptyList())
        s.purge(listOf(db.dao().file(fa.id)!!), emptyList())
        val refs = db.dao().parts(fb.id).map { it.remoteRef }
        assertTrue(refs.all { File(remote, it).exists() })

        // Deleting the last copy frees them.
        s.purge(listOf(fb), emptyList())
        assertTrue(refs.none { File(remote, it).exists() })
        assertTrue(db.dao().files(drive.id, null).first().isEmpty())
        db.close()
    }

    @Test
    fun trashedFolderHidesContentAndEmptyFileRoundTrips() = runBlocking {
        val db = openDatabase(File(root, "t.db").path)
        val drive = Drive("drive-t", "D", "test", "remote-t", createdAt = 1)
        db.dao().upsert(drive)
        val s = DriveService(db, provider, drive, root)
        val dir = File(root, "Album").apply { mkdirs() }
        File(dir, "Inner").mkdirs()
        File(dir, "Inner/pic.jpg").writeBytes(Random(4).nextBytes(10))
        File(dir, "empty.txt").writeBytes(ByteArray(0))
        s.enqueueUploads(listOf(dir), null)
        s.drain(db)
        val dao = db.dao()
        assertEquals(2, dao.activeFiles(drive.id).first().size)

        // Trash the top folder: everything below disappears from search/gallery/stats, not only the folder itself.
        val album = dao.folders(drive.id, null).first().single()
        s.trash(emptyList(), listOf(album))
        assertTrue(dao.activeFiles(drive.id).first().isEmpty())
        assertTrue(dao.searchFiles(drive.id, "pic").first().isEmpty())
        assertTrue(dao.activeFolders(drive.id).first().isEmpty())

        // A file restored on its own from inside a trashed folder comes back at the top level.
        val pic = dao.childFiles(dao.childFolders(album.id).single().id).single()
        s.restore(listOf(pic), emptyList())
        assertEquals(null, dao.file(pic.id)!!.folderId)

        // 0-byte file: stored without parts, downloads as an empty file.
        val empty = dao.childFiles(album.id).single()
        assertEquals(SafetyState.REMOTE_SAFE, empty.safety)
        val out = File(root, "empty-out.txt")
        s.enqueueDownload(empty, out)
        s.drain(db)
        assertEquals(0L, out.length())
        assertTrue(out.exists())
        db.close()
    }
}
