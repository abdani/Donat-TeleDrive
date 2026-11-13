package donat.teledrive

import donat.teledrive.core.SafetyState
import donat.teledrive.data.Drive
import donat.teledrive.data.DriveFile
import donat.teledrive.data.Manifest
import donat.teledrive.data.merge
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private fun file(id: String, name: String, t: Long, folder: String? = null) =
        DriveFile(id, "d", folder, name, 1, "x", "h", SafetyState.REMOTE_SAFE, createdAt = 0, updatedAt = t)

    private fun manifest(rev: Long, vararg files: DriveFile) = Manifest(driveId = "d", driveName = "D", revision = rev, updatedAt = 0, folders = emptyList(), files = files.toList(), parts = emptyList())

    @Test
    fun mergeRules() {
        val base = manifest(1, file("a", "a", 0), file("b", "b", 0), file("c", "c", 0))
        val local = manifest(2, file("a", "a-local", 5), file("b", "b", 0), file("c", "c", 0), file("new", "new", 6)) // rename a, add new
        val remote = manifest(2, file("a", "a", 0), file("b", "b", 0, folder = "x")) // move b, delete c
        val m = merge(base, local, remote)
        val byId = m.manifest.files.associateBy { it.id }
        assertEquals("a-local", byId["a"]!!.name) // only local changed → local wins
        assertEquals("x", byId["b"]!!.folderId) // only remote changed → remote wins
        assertNull(byId["c"]) // deleted remotely, untouched locally → deleted
        assertTrue("new" in byId) // new local file kept
        assertTrue(m.conflicts.isEmpty())

        // Same file renamed differently on both sides: newer kept, flagged, nothing lost.
        val both = merge(base, manifest(2, file("a", "mine", 9)), manifest(3, file("a", "theirs", 7)))
        assertEquals("mine", both.manifest.files.single().name)
        assertEquals(SafetyState.CONFLICT, both.manifest.files.single().safety)
        assertEquals(1, both.conflicts.size)
        assertEquals(3, both.manifest.revision)

        // Verifying is not an edit: verified here + renamed there = no conflict; verified here + deleted forever there = deleted.
        val verified = file("a", "a", 0).copy(safety = SafetyState.VERIFIED)
        val vr = merge(base, manifest(2, verified, file("b", "b", 0).copy(safety = SafetyState.VERIFIED)), manifest(3, file("a", "renamed", 4)))
        assertTrue(vr.conflicts.isEmpty())
        assertEquals("renamed", vr.manifest.files.single { it.id == "a" }.name)
        assertEquals(SafetyState.VERIFIED, vr.manifest.files.single { it.id == "a" }.safety)
        assertNull(vr.manifest.files.firstOrNull { it.id == "b" })

        // Unknown base: nothing is inferred as deleted.
        val noBase = merge(null, manifest(1, file("a", "a", 0)), manifest(1, file("b", "b", 0)))
        assertEquals(setOf("a", "b"), noBase.manifest.files.map { it.id }.toSet())
    }

    @Test
    fun twoPcsEditTheSameDrive() = runBlocking {
        val root = Files.createTempDirectory("teledrive-sync").toFile().apply { deleteOnExit() }
        val provider = FolderProvider(File(root, "remote").apply { mkdirs() })
        val drive = Drive("drive", "D", "test", "r", createdAt = 1)

        // PC1 uploads two files.
        val pc1Dir = File(root, "pc1").apply { mkdirs() }
        val db1 = openDatabase(File(pc1Dir, "i.db").path).apply { dao().upsert(drive) }
        val pc1 = DriveService(db1, provider, drive, pc1Dir)
        val job1 = launch { pc1.runTransfers() }
        listOf("one.bin", "two.bin").forEach { pc1.enqueueUpload(File(root, it).apply { writeBytes(Random(it.length).nextBytes(10)) }, null) }
        withTimeout(15_000) { while (db1.dao().files(drive.id, null).first().any { it.safety != SafetyState.REMOTE_SAFE }) delay(20) }
        job1.cancel()

        // PC2 restores the drive.
        val pc2Dir = File(root, "pc2").apply { mkdirs() }
        val db2 = openDatabase(File(pc2Dir, "i.db").path)
        DriveService.restore(db2, provider, "r", "test", pc2Dir)
        val pc2 = DriveService(db2, provider, db2.dao().drives().first().single(), pc2Dir)

        // PC1 renames "one"; PC2 trashes "two" and also renames "one" differently.
        val one1 = db1.dao().files(drive.id, null).first().first { it.name == "one.bin" }
        pc1.rename(one1, "one-from-pc1.bin")
        pc1.saveManifestNow()
        val (one2, two2) = db2.dao().files(drive.id, null).first().sortedBy { it.name }
        pc2.trash(listOf(two2), emptyList())
        delay(5) // make PC2's rename strictly newer
        pc2.rename(db2.dao().file(one2.id)!!, "one-from-pc2.bin")
        pc2.saveManifestNow() // pulls PC1's change first, merges, then writes

        val names = db2.dao().activeFiles(drive.id).first().associate { it.id to it }
        assertEquals("one-from-pc2.bin", names[one2.id]!!.name) // both renamed → newer kept…
        assertEquals(SafetyState.CONFLICT, names[one2.id]!!.safety) // …and flagged, not silently overwritten
        assertTrue(pc2.conflicts.value.isNotEmpty())
        assertTrue(db2.dao().file(two2.id)!!.trashedAt != null) // PC2's own change survives

        // PC1 pulls and sees PC2's result.
        pc1.pull()
        assertTrue(db1.dao().file(two2.id)!!.trashedAt != null)
        assertEquals("one-from-pc2.bin", db1.dao().file(one2.id)!!.name)
        db1.close(); db2.close()
    }

    @Test
    fun streamingRanges() = runBlocking {
        assertEquals(0L to 99L, StreamServer.parseRange(null, 100))
        assertEquals(10L to 99L, StreamServer.parseRange("bytes=10-", 100))
        assertEquals(10L to 19L, StreamServer.parseRange("bytes=10-19", 100))
        assertEquals(90L to 99L, StreamServer.parseRange("bytes=-10", 100))
        assertNull(StreamServer.parseRange("bytes=200-", 100))

        // readRange crosses part boundaries correctly when called in sequence (1000-byte parts).
        val root = Files.createTempDirectory("teledrive-stream").toFile().apply { deleteOnExit() }
        val provider = FolderProvider(File(root, "remote").apply { mkdirs() })
        val db = openDatabase(File(root, "i.db").path)
        val drive = Drive("d", "D", "test", "r", createdAt = 1).also { db.dao().upsert(it) }
        val s = DriveService(db, provider, drive, root)
        val bytes = Random(3).nextBytes(2500)
        s.enqueueUpload(File(root, "v.mp4").apply { writeBytes(bytes) }, null)
        val job = launch { s.runTransfers() }
        withTimeout(15_000) { while (db.dao().files(drive.id, null).first().single().safety != SafetyState.REMOTE_SAFE) delay(20) }
        job.cancel()
        val f = db.dao().files(drive.id, null).first().single()
        val out = java.io.ByteArrayOutputStream()
        var pos = 900L
        while (pos < 2100) { val c = s.readRange(f, pos, minOf(300, (2100 - pos).toInt())); out.write(c); pos += c.size }
        assertContentEquals(bytes.copyOfRange(900, 2100), out.toByteArray())
        db.close()
    }
}
