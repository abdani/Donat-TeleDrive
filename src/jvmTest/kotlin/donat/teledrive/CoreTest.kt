package donat.teledrive

import donat.teledrive.core.Direction
import donat.teledrive.core.TransferState
import donat.teledrive.core.TransferState.*
import donat.teledrive.core.SafetyState
import donat.teledrive.data.Drive
import donat.teledrive.data.DriveFile
import donat.teledrive.data.Transfer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun transferTransitions() {
        assertTrue(QUEUED.canMoveTo(PREPARING))
        assertTrue(RUNNING.canMoveTo(RETRY_WAIT))
        assertTrue(FAILED.canMoveTo(QUEUED))
        assertFalse(QUEUED.canMoveTo(COMPLETED)) // can't complete without running
        assertFalse(PAUSED.canMoveTo(RUNNING)) // resume goes back through the queue
        TransferState.entries.filter { it.isTerminal }.forEach { done ->
            assertTrue(TransferState.entries.none { done.canMoveTo(it) }, "$done must be terminal")
        }
    }

    @Test
    fun tdlibNativesLoadOnThisMachine() = it.tdlight.Init.init()

    @Test
    fun modernFileDialogsInitialise() {
        assertEquals(org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY, org.lwjgl.util.nfd.NativeFileDialog.NFD_Init())
        org.lwjgl.util.nfd.NativeFileDialog.NFD_Quit()
    }

    @Test
    fun javafxMediaToolkitStarts() {
        val started = java.util.concurrent.CountDownLatch(1)
        javafx.application.Platform.startup { started.countDown() }
        assertTrue(started.await(20, java.util.concurrent.TimeUnit.SECONDS))
        javafx.application.Platform.exit()
    }

    @Test
    fun forgetDriveKeepsOtherDrives() = runBlocking {
        val db = openDatabase(File.createTempFile("teledrive", ".db").apply { delete(); deleteOnExit() }.path)
        with(db.dao()) {
            for (d in listOf("a", "b")) {
                upsert(Drive(d, d, "telegram", d, createdAt = 1))
                upsert(DriveFile("f$d", d, null, "x", 1, "x", null, SafetyState.REMOTE_SAFE, createdAt = 1, updatedAt = 1))
                upsert(Transfer("t$d", "f$d", Direction.UPLOAD, "x", COMPLETED, bytesTotal = 1, createdAt = 1, updatedAt = 1))
            }
            forgetDrive("a")
            assertEquals(listOf("b"), drives().first().map { it.id })
            assertEquals(null, file("fa"))
            assertEquals(null, transfer("ta"))
            assertEquals(listOf("tb"), transfers("b").first().map { it.id })
        }
        db.close()
    }

    @Test
    fun pendingTransfersSurviveRestart() = runBlocking {
        val file = File.createTempFile("teledrive", ".db").apply { delete(); deleteOnExit() }
        openDatabase(file.path).run {
            dao().upsert(Drive("d1", "My Drive", "telegram", "-100123", createdAt = 1))
            listOf("f1", "f2").forEach { dao().upsert(DriveFile(it, "d1", null, it, 1, "x", null, SafetyState.UPLOADING, createdAt = 1, updatedAt = 1)) }
            dao().upsert(Transfer("t1", "f1", Direction.UPLOAD, "C:/a.bin", RUNNING, bytesDone = 512, bytesTotal = 1024, createdAt = 1, updatedAt = 1))
            dao().upsert(Transfer("t2", "f2", Direction.UPLOAD, "C:/b.bin", COMPLETED, bytesTotal = 10, createdAt = 2, updatedAt = 2))
            close()
        }
        openDatabase(file.path).run {
            assertEquals(listOf("t1"), dao().pendingTransfers("d1").map { it.id })
            assertEquals(512, dao().pendingTransfers("d1").single().bytesDone)
            assertEquals("My Drive", dao().drives().first().single().name)
            close()
        }
    }
}
