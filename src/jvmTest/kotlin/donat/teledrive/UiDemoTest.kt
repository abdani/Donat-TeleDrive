package donat.teledrive

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import donat.teledrive.core.SafetyState
import donat.teledrive.core.StorageProvider
import donat.teledrive.core.TransferState
import donat.teledrive.core.Direction
import donat.teledrive.data.Drive
import donat.teledrive.data.DriveFile
import donat.teledrive.data.FilePart
import donat.teledrive.data.Folder
import donat.teledrive.data.Transfer
import donat.teledrive.telegram.AuthState
import donat.teledrive.telegram.TelegramAccount
import donat.teledrive.ui.App
import donat.teledrive.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Real app UI on demo data, for screenshots without the user's account (their PIN / session stay untouched).
 * Runs only with -PuiDemo=true (unit-test app data = build/test-appdata). Control files in that app-data folder:
 * ui.signal = "theme:NEON" | "view:grid" | "view:details" | "auth:phone" | "auth:ready" | "lock" | "quit".
 */
class UiDemoTest {
    @Test
    fun uiDemo() {
        if (System.getProperty("donat.uidemo") != "true") return
        File(appDataDir, "index.db").delete()
        val root = File(appDataDir, "demo").apply { deleteRecursively(); mkdirs() }
        val remote = File(root, "remote").apply { mkdirs() }
        val folderProvider = FolderProvider(remote)
        // Thumbnails: soft gradients standing in for photos / video frames.
        val provider = object : StorageProvider by folderProvider {
            override suspend fun thumbnail(remoteRef: String, destPath: String): Boolean {
                val h = remoteRef.hashCode()
                val img = BufferedImage(320, 230, BufferedImage.TYPE_INT_RGB)
                img.createGraphics().apply { paint = GradientPaint(0f, 0f, Color(h or 0x404040), 320f, 230f, Color((h shr 7) or 0x202020)); fillRect(0, 0, 320, 230) }
                return ImageIO.write(img, "jpg", File(destPath))
            }
        }
        val db = openDatabase()
        val drive = Drive("demo-drive", "My Drive", "test", "1", createdAt = 1)
        val now = System.currentTimeMillis()
        runBlocking {
            db.dao().upsert(drive)
            listOf("Audio", "Documents", "Memories", "Work projects 2026").forEach { db.dao().upsert(Folder("d-$it", drive.id, null, it, updatedAt = now)) }
            fun file(name: String, mime: String, size: Long, safety: SafetyState = SafetyState.VERIFIED, fav: Boolean = false) = runBlocking {
                val id = "f-$name"
                File(remote, "ref-$id").writeText(name)
                db.dao().upsert(DriveFile(id, drive.id, null, name, size, mime, null, safety, favorite = fav, createdAt = now, updatedAt = now - name.length * 3_600_000L))
                db.dao().upsertParts(listOf(FilePart(id, 0, "ref-$id", 0, size, "")))
                id
            }
            file("IMG-20250625-WA0068.jpg", "image/jpeg", 2_400_000, fav = true)
            file("PXL_20240102_101010.jpg", "image/jpeg", 3_100_000)
            file("Holiday in Bali.mp4", "video/mp4", 95_000_000)
            file("04 - Scar Tissue.mp3", "audio/mpeg", 9_500_000, fav = true)
            file("Quarterly report with a very long file name.pdf", "application/pdf", 695_000)
            file("Budget 2026.xlsx", "application/vnd.ms-excel", 41_000, SafetyState.REMOTE_SAFE)
            file("setup-tools.zip", "application/zip", 4_000_000)
            val up = file("Screen recording.mp4", "video/mp4", 52_000_000, SafetyState.UPLOADING)
            db.dao().upsert(Transfer("t-up", up, Direction.UPLOAD, File(root, "missing.mp4").path, TransferState.PAUSED, bytesDone = 21_000_000, bytesTotal = 52_000_000, createdAt = now, updatedAt = now))
        }
        Prefs["lastDrive"] = drive.id
        val model = AppModel(db, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        @Suppress("UNCHECKED_CAST")
        val auth = TelegramAccount::class.java.getDeclaredField("_auth").apply { isAccessible = true }.get(model.account) as MutableStateFlow<AuthState>
        fun ready() {
            auth.value = AuthState.Ready("Donat Demo")
            runBlocking { delay(3_000) } // AppModel tries Telegram (fails offline) and opens the drive; then swap in the demo backend
            model.service.value = DriveService(db, provider, drive, root).apply { syncStatus.value = "All changes saved to Telegram · revision 56" }
        }
        ready()
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, title = "DTD UI demo", state = rememberWindowState(width = 1280.dp, height = 820.dp)) {
                LaunchedEffect(Unit) {
                    val signal = File(appDataDir, "ui.signal")
                    while (true) {
                        val cmd = signal.takeIf { it.exists() }?.readText()?.trim().also { signal.delete() }
                        when {
                            cmd == null -> Unit
                            cmd == "quit" -> exitApplication()
                            cmd == "lock" -> model.locked.value = !model.locked.value
                            cmd == "auth:phone" -> auth.value = AuthState.WaitPhone
                            cmd == "auth:ready" -> kotlin.concurrent.thread { ready() }
                            cmd.startsWith("theme:") -> model.setTheme(AppTheme.valueOf(cmd.removePrefix("theme:")))
                            cmd.startsWith("view:") -> model.setGrid(cmd == "view:grid")
                        }
                        delay(300)
                    }
                }
                App(model)
            }
        }
    }
}
