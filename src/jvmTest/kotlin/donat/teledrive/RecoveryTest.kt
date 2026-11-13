package donat.teledrive

import donat.teledrive.core.Direction
import donat.teledrive.core.RemoteObject
import donat.teledrive.core.SafetyState
import donat.teledrive.core.TransferState
import donat.teledrive.data.AppDatabase
import donat.teledrive.data.Drive
import donat.teledrive.data.DriveFile
import donat.teledrive.data.Manifest
import donat.teledrive.data.Transfer
import donat.teledrive.data.groupObjects
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recovery safety net + sync robustness: lost index, unlisted objects, two PCs saving at the same moment. */
class RecoveryTest {
    private val root = Files.createTempDirectory("teledrive-recovery").toFile().apply { deleteOnExit() }
    private val remote = File(root, "remote").apply { mkdirs() }
    private val provider = FolderProvider(remote)
    private val drive = Drive("drive-r", "My Drive", "test", "remote-r", createdAt = 1)

    private fun pc(name: String): Pair<AppDatabase, DriveService> {
        val dir = File(root, name).apply { mkdirs() }
        val db = openDatabase(File(dir, "i.db").path)
        runBlocking { db.dao().upsert(drive) }
        return db to DriveService(db, provider, drive, dir)
    }

    private fun DriveService.drain(db: AppDatabase) = runBlocking {
        val job = launch { runTransfers() }
        withTimeout(15_000) { while (db.dao().transfers(drive.id).first().any { it.state != TransferState.COMPLETED }) delay(20) }
        job.cancel()
    }

    private fun upload(s: DriveService, db: AppDatabase, name: String, bytes: ByteArray, folder: String? = null): ByteArray = runBlocking {
        s.enqueueUpload(File(root, name).apply { writeBytes(bytes) }, folder)
        s.drain(db)
        bytes
    }

    private fun download(s: DriveService, f: DriveFile): ByteArray = runBlocking { s.cached(f).readBytes() }

    private fun remoteManifest() = runBlocking { Manifest.decode(provider.readManifest()!!) }

    @Test
    fun splitPiecesAreJoinedOnlyWhenComplete() {
        fun o(ref: String, name: String) = RemoteObject(ref, name, 10, "x", 0)
        val groups = groupObjects(
            listOf(
                o("1", "movie.mkv.part2of3"), o("2", "movie.mkv.part1of3"), o("3", "movie.mkv.part3of3"), // complete, out of order
                o("4", "big.zip.part1of2"), // incomplete: must stay visible as it is
                o("5", "photo.jpg"),
            ),
        ).associate { (name, pieces) -> name to pieces.map { it.ref } }
        assertEquals(listOf("2", "1", "3"), groups["movie.mkv"])
        assertEquals(listOf("4"), groups["big.zip.part1of2"])
        assertEquals(listOf("5"), groups["photo.jpg"])
        assertEquals(3, groups.size)
    }

    @Test
    fun deletedIndexIsRewrittenByThePcThatHasIt() = runBlocking<Unit> {
        val (db, s) = pc("pc1")
        s.createFolder("Docs", null)
        val folder = db.dao().folders(drive.id, null).first().single()
        val bytes = upload(s, db, "a.txt", Random(1).nextBytes(2500), folder.id)

        provider.loseIndex() // the data channel was deleted on the phone
        s.pull()             // notices the backend has no index any more…
        val job = launch { s.runTransfers() } // …and the runner saves this PC's index again
        withTimeout(15_000) { while (provider.manifestVersion() == null) delay(20) }
        job.cancel()

        // A brand-new PC restores everything — folder included — from the rewritten index.
        val (db2, _) = pc("pc2")
        DriveService.restore(db2, provider, "remote-r", "test", File(root, "pc2"))
        val f = db2.dao().files(drive.id, folder.id).first().single()
        assertEquals("a.txt", f.name)
        assertContentEquals(bytes, download(DriveService(db2, provider, drive, File(root, "pc2")), f))
    }

    @Test
    fun rebuildNeverWipesTheIndexWhenTheBackendHasNone() = runBlocking<Unit> {
        val (db, s) = pc("pc1")
        upload(s, db, "keep.txt", Random(2).nextBytes(100))
        provider.loseIndex()
        val error = runCatching { s.rebuildIndex() }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(listOf("keep.txt"), db.dao().files(drive.id, null).first().map { it.name }) // still there
    }

    @Test
    fun totalLossRecoversEveryFileByName() = runBlocking<Unit> {
        val (db, s) = pc("pc1")
        val a = upload(s, db, "report.pdf", Random(3).nextBytes(2600)) // 3 parts → "report.pdf" again after recovery
        val b = upload(s, db, "song.mp3", Random(4).nextBytes(500))
        db.close()
        provider.loseIndex() // index gone AND this PC is gone

        val dir = File(root, "fresh").apply { mkdirs() }
        val db2 = openDatabase(File(dir, "i.db").path)
        assertEquals(2, DriveService.recover(db2, provider, "remote-r", "test", "My Drive", dir))
        val recovered = db2.dao().drives().first().single()
        val files = db2.dao().files(recovered.id, null).first().associateBy { it.name }
        assertEquals(setOf("report.pdf", "song.mp3"), files.keys)
        val s2 = DriveService(db2, provider, recovered, dir)
        assertContentEquals(a, download(s2, files.getValue("report.pdf")))
        assertContentEquals(b, download(s2, files.getValue("song.mp3")))
        // The first full download recorded the hashes; from now on damage is detected like for any other file.
        assertNotNull(db2.dao().file(files.getValue("report.pdf").id)!!.sha256)
        assertTrue(db2.dao().parts(files.getValue("report.pdf").id).all { it.sha256.isNotEmpty() })
        assertNotNull(provider.manifestVersion()) // and a new index exists, so other PCs restore normally
    }

    @Test
    fun unlistedObjectsCanBeAddedOrDeleted() = runBlocking<Unit> {
        val (db, s) = pc("pc1")
        upload(s, db, "listed.txt", Random(5).nextBytes(300))
        val fromPhone = Random(6).nextBytes(700).also { File(remote, "holiday.jpg").writeBytes(it) } // sent to the channel from a phone

        val unlisted = s.findUnlisted()
        assertEquals(listOf("holiday.jpg"), unlisted.map { it.name }) // the drive's own file is never reported
        assertEquals(1, s.adopt(unlisted, "Recovered"))
        val recovered = db.dao().folders(drive.id, null).first().single { it.name == "Recovered" }
        val f = db.dao().files(drive.id, recovered.id).first().single()
        assertEquals("holiday.jpg", f.name)
        assertContentEquals(fromPhone, download(s, f))
        assertTrue(s.findUnlisted().isEmpty())

        // Leftover of an interrupted delete: the file is out of the drive, its bytes still in the channel.
        val gone = db.dao().files(drive.id, null).first().single { it.name == "listed.txt" }
        val ref = db.dao().parts(gone.id).single().remoteRef
        db.dao().deleteFile(gone.id)
        val leftovers = s.findUnlisted()
        assertEquals(listOf(ref), leftovers.map { it.ref })
        assertEquals(1, s.deleteUnlisted(leftovers))
        assertTrue(!File(remote, ref).exists())
        assertTrue(File(remote, db.dao().parts(f.id).single().remoteRef).exists()) // adopted file untouched
    }

    @Test
    fun deleteUnlistedNeverDeletesWhatAFileUses() = runBlocking<Unit> {
        val (db, s) = pc("pc1")
        upload(s, db, "mine.txt", Random(7).nextBytes(50))
        val ref = db.dao().allRefs(drive.id).single()
        assertEquals(0, s.deleteUnlisted(listOf(RemoteObject(ref, "mine.txt", 50, "x", 0)))) // stale list → re-checked
        assertTrue(File(remote, ref).exists())
    }

    @Test
    fun aSaveRacingAnotherPcKeepsBothChanges() = runBlocking<Unit> {
        val (db1, pc1) = pc("pc1")
        upload(pc1, db1, "one.txt", Random(8).nextBytes(10))
        upload(pc1, db1, "two.txt", Random(9).nextBytes(10))
        val (db2, pc2) = pc("pc2")
        DriveService.restore(db2, provider, "remote-r", "test", File(root, "pc2"))

        // PC1 renames "one"; PC2 saves its rename of "two" exactly between PC1's pull and PC1's write.
        val one = db1.dao().files(drive.id, null).first().single { it.name == "one.txt" }
        pc1.rename(one, "one-pc1.txt")
        provider.beforeWrite = {
            val two = db2.dao().files(drive.id, null).first().single { it.name == "two.txt" }
            pc2.rename(two, "two-pc2.txt")
            pc2.saveManifestNow()
        }
        pc1.saveManifestNow()

        val names = remoteManifest().files.map { it.name }.toSet()
        assertEquals(setOf("one-pc1.txt", "two-pc2.txt"), names) // without the race check, PC2's rename was lost
        pc2.pull()
        assertEquals(setOf("one-pc1.txt", "two-pc2.txt"), db2.dao().files(drive.id, null).first().map { it.name }.toSet())
    }

    @Test
    fun cancellingAReplacingUploadBringsTheOldFileBack() = runBlocking<Unit> {
        val (db, s) = pc("pc1")
        val dao = db.dao()
        val old = DriveFile("old", drive.id, null, "notes.txt", 1, "x", "h", SafetyState.REMOTE_SAFE, createdAt = 1, updatedAt = 1)
        val new = old.copy(id = "new", safety = SafetyState.UPLOADING, createdAt = 2, updatedAt = 2)
        dao.upsert(old); dao.upsert(new)
        dao.supersede("old", "new") // a same-name upload already replaced it, then its manifest save failed
        val t = Transfer("t", "new", Direction.UPLOAD, "C:/notes.txt", TransferState.RETRY_WAIT, bytesTotal = 1, createdAt = 2, updatedAt = 2)
        dao.upsert(t)
        s.cancel(t)
        assertNull(dao.file("new"))
        assertNull(dao.file("old")!!.versionOf) // visible again
        assertEquals(listOf("notes.txt"), dao.files(drive.id, null).first().map { it.name })
    }
}
