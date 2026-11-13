package donat.teledrive

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import donat.teledrive.core.Direction
import donat.teledrive.core.SafetyState
import donat.teledrive.core.StorageProvider
import donat.teledrive.core.TransferState
import donat.teledrive.data.Drive
import donat.teledrive.data.DriveFile
import donat.teledrive.data.FilePart
import donat.teledrive.data.Folder
import donat.teledrive.data.Transfer
import donat.teledrive.telegram.AuthState
import donat.teledrive.telegram.TelegramAccount
import donat.teledrive.ui.App
import donat.teledrive.ui.AppTheme
import donat.teledrive.ui.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test

/**
 * Real app UI on demo data, for screenshots without the user's account (their PIN / session stay untouched).
 * Runs only with -PuiDemo=true (unit-test app data = build/test-appdata). Control file ui.signal in that app-data folder:
 * "theme:NEON" | "view:grid" | "view:details" | "screen:Gallery" | "preview:<file id>" | "auth:phone" | "auth:qr" |
 * "auth:ready" | "lock" | "quit".
 */
class UiDemoTest {
    /** A synthetic landscape (sky gradient, sun or stars, three mountain ridges) — stands in for a photo. */
    private fun scene(seed: Int, sky: Pair<Int, Int>, hills: List<Int>, w: Int = 1200, h: Int = 800, stars: Boolean = false): BufferedImage {
        val r = Random(seed)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color(sky.first), 0f, h * 0.75f, Color(sky.second)); g.fillRect(0, 0, w, h)
        if (stars) repeat(160) { g.color = Color(255, 255, 255, r.nextInt(80, 230)); val s = r.nextInt(1, 4); g.fillOval(r.nextInt(w), r.nextInt(h / 2), s, s) }
        g.color = Color(255, 250, 230, 210); val sun = h / 7; g.fillOval((w * (0.25 + r.nextDouble() * 0.5)).toInt(), h / 5, sun, sun)
        hills.forEachIndexed { i, c ->
            val base = h * (0.52 + i * 0.13)
            val p = Polygon(); p.addPoint(0, h)
            var x = 0; while (x <= w) { p.addPoint(x, (base - r.nextDouble() * h * 0.16 + i * 8).toInt()); x += w / 9 }
            p.addPoint(w, h); g.color = Color(c); g.fillPolygon(p)
        }
        g.dispose(); return img
    }

    private fun jpeg(img: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()

    @Test
    fun uiDemo() {
        if (System.getProperty("donat.uidemo") != "true") return
        File(appDataDir, "index.db").delete()
        val root = File(appDataDir, "demo").apply { deleteRecursively(); mkdirs() }
        val remote = File(root, "remote").apply { mkdirs() }
        val pictures = mutableMapOf<String, BufferedImage>() // ref → picture for thumbnails
        val folderProvider = FolderProvider(remote)
        val provider = object : StorageProvider by folderProvider {
            override suspend fun thumbnail(remoteRef: String, destPath: String): Boolean {
                val src = pictures[remoteRef] ?: return false
                val t = BufferedImage(360, 240, BufferedImage.TYPE_INT_RGB)
                t.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); drawImage(src, 0, 0, 360, 240, null); dispose() }
                return ImageIO.write(t, "jpg", File(destPath))
            }
        }
        val sunset = scene(1, 0xFF9A5A to 0x6B3FA0, listOf(0x5B2C6F, 0x3D1F4F, 0x22122E))
        val ocean = scene(2, 0x7FD6F5 to 0x2A6FB8, listOf(0x2F5D8C, 0x1D4167, 0x0F2A45))
        val forest = scene(3, 0xCDEFD8 to 0x6FBF8E, listOf(0x3E7D55, 0x2A5E3E, 0x173D27))
        val night = scene(4, 0x0B1430 to 0x2B3A78, listOf(0x1A2147, 0x111735, 0x0A0E22), stars = true)
        val desert = scene(5, 0xFFD9A8 to 0xF29E7A, listOf(0xD9825B, 0xB8613F, 0x8C452C))
        val aurora = scene(6, 0x0E2A3A to 0x2E8C7E, listOf(0x1C4B52, 0x123438, 0x0A2023), stars = true)
        val db = openDatabase()
        val drive = Drive("demo-drive", "My Drive", "test", "1", createdAt = 1)
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        runBlocking {
            db.dao().upsert(drive)
            listOf("Photos", "Work", "Music", "Documents", "Travel · Bali 2026", "Backups").forEachIndexed { i, n ->
                db.dao().upsert(Folder("d$i", drive.id, null, n, updatedAt = now - i * 5 * hour))
            }
            fun file(name: String, mime: String, size: Long, age: Long, safety: SafetyState = SafetyState.VERIFIED, fav: Boolean = false, picture: BufferedImage? = null, sha: String? = null, trashed: Boolean = false) = runBlocking {
                val id = "f-" + name.filter(Char::isLetterOrDigit)
                val ref = "ref-$id"
                File(remote, ref).writeBytes(picture?.let(::jpeg) ?: name.toByteArray())
                picture?.let { pictures[ref] = it }
                db.dao().upsert(DriveFile(id, drive.id, null, name, size, mime, sha, safety, favorite = fav, trashedAt = if (trashed) now - hour else null, createdAt = now - age, updatedAt = now - age))
                db.dao().upsertParts(listOf(FilePart(id, 0, ref, 0, size, "")))
                id
            }
            file("IMG_20260712_181530.jpg", "image/jpeg", 4_400_000, 2 * hour, fav = true, picture = sunset)
            file("IMG_20260712_183002.jpg", "image/jpeg", 3_900_000, 3 * hour, picture = ocean)
            file("PXL_20260604_071245.jpg", "image/jpeg", 5_300_000, 30 * hour, picture = forest)
            file("IMG_20260521_204410.jpg", "image/jpeg", 2_900_000, 60 * hour, picture = night)
            file("IMG_20260415_160012.jpg", "image/jpeg", 3_400_000, 90 * hour, fav = true, picture = desert)
            file("PXL_20260302_221500.jpg", "image/jpeg", 4_900_000, 120 * hour, picture = aurora)
            // More shots on the same days, so the Gallery looks like a real camera roll.
            val palettes = listOf(
                Triple(0xFFC38A to 0xE0567A, listOf(0xA33A64, 0x6E2447, 0x3F122A), false), Triple(0x9BE2FF to 0x3C7FD1, listOf(0x34689E, 0x214A75, 0x10294A), false),
                Triple(0xFFE7B3 to 0xF7A35C, listOf(0xC9703E, 0x9A4E28, 0x5E2C15), false), Triple(0x1A1F4A to 0x5A3C8C, listOf(0x2C2459, 0x1C173D, 0x0E0B22), true),
                Triple(0xD6F5E6 to 0x7CCBA2, listOf(0x4C9A70, 0x2F7050, 0x174230), false), Triple(0xF6D0FF to 0xA678E0, listOf(0x7E54B8, 0x573A87, 0x2F1F4F), false),
            )
            listOf("20260712" to 6, "20260604" to 4, "20260521" to 3, "20260415" to 3).forEach { (day, n) ->
                repeat(n) { i ->
                    val (sky, hills, stars) = palettes[(day.hashCode() + i).mod(palettes.size)]
                    file("IMG_${day}_${100000 + i * 731}.jpg", "image/jpeg", 2_600_000L + i * 410_000, 40 * hour + i, picture = scene(day.hashCode() + i, sky, hills, stars = stars))
                }
            }
            file("Bali sunset — drone.mp4", "video/mp4", 412_000_000, 5 * hour, picture = scene(7, 0xFFB36B to 0xC2477A, listOf(0x8A3363, 0x5E2245, 0x3A142B)))
            file("Product launch keynote.mp4", "video/mp4", 1_840_000_000, 26 * hour, fav = true, picture = aurora)
            file("Midnight City.mp3", "audio/mpeg", 9_800_000, 8 * hour)
            file("Lo-fi focus mix.flac", "audio/flac", 48_200_000, 50 * hour)
            file("Annual report 2026.pdf", "application/pdf", 2_100_000, 4 * hour)
            file("Budget 2026.xlsx", "application/vnd.ms-excel", 41_000, 1 * hour, SafetyState.REMOTE_SAFE)
            file("Contract — signed.pdf", "application/pdf", 860_000, 70 * hour, sha = "dup1")
            file("Contract — signed (copy).pdf", "application/pdf", 860_000, 20 * hour, sha = "dup1")
            file("design-system-v4.zip", "application/zip", 128_000_000, 10 * hour)
            file("Old draft.docx", "application/msword", 310_000, 200 * hour, trashed = true)
            file("IMG_20250101_000001.jpg", "image/jpeg", 2_200_000, 300 * hour, trashed = true, picture = night)
            file("Screen recording — demo.mp4", "video/mp4", 860_000_000, 0, SafetyState.UPLOADING, picture = ocean)
            file("Family photos 2025.zip", "application/zip", 2_300_000_000, 0, SafetyState.UPLOADING)
        }
        /** Added only after AppModel's own transfer runner is stopped (it would mark them failed: no such local files). */
        fun transfers() = runBlocking {
            val dir = File(root, "Videos").path
            db.dao().upsert(Transfer("t-rec", "f-Screenrecordingdemomp4", Direction.UPLOAD, "$dir/Screen recording — demo.mp4", TransferState.RUNNING, bytesDone = 520_000_000, bytesTotal = 860_000_000, createdAt = now, updatedAt = now))
            db.dao().upsert(Transfer("t-fam", "f-Familyphotos2025zip", Direction.UPLOAD, "$dir/Family photos 2025.zip", TransferState.QUEUED, bytesTotal = 2_300_000_000, createdAt = now, updatedAt = now))
            db.dao().upsert(Transfer("t-key", "f-Productlaunchkeynotemp4", Direction.DOWNLOAD, "$dir/Product launch keynote.mp4", TransferState.PAUSED, bytesDone = 690_000_000, bytesTotal = 1_840_000_000, createdAt = now, updatedAt = now))
            db.dao().upsert(Transfer("t-rep", "f-Annualreport2026pdf", Direction.DOWNLOAD, "$dir/Annual report 2026.pdf", TransferState.COMPLETED, bytesDone = 2_100_000, bytesTotal = 2_100_000, createdAt = now - hour, updatedAt = now - hour))
        }
        Prefs["lastDrive"] = drive.id
        val model = AppModel(db, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        @Suppress("UNCHECKED_CAST")
        val auth = TelegramAccount::class.java.getDeclaredField("_auth").apply { isAccessible = true }.get(model.account) as MutableStateFlow<AuthState>
        fun ready() {
            auth.value = AuthState.Ready("Donat Demo")
            runBlocking { delay(3_000) } // AppModel tries Telegram (offline) and opens the drive with it…
            model.addDrive() // …stop that drive's transfer runner (it would touch the demo transfers)
            runBlocking { delay(1_000) }
            transfers()
            model.service.value = DriveService(db, provider, drive, root).apply {
                syncStatus.value = "All changes saved to Telegram · revision 128"
                live.value = mapOf("t-rec" to Live("Uploading part 2/2", 534_000_000, 6_400_000))
            }
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
                            cmd == "auth:qr" -> auth.value = AuthState.WaitQr("tg://login?token=demo-screenshot-not-a-real-login-token")
                            cmd == "auth:ready" -> kotlin.concurrent.thread { ready() }
                            cmd.startsWith("theme:") -> model.setTheme(AppTheme.valueOf(cmd.removePrefix("theme:")))
                            cmd.startsWith("view:") -> model.setGrid(cmd == "view:grid")
                            cmd.startsWith("screen:") -> { model.preview.value = null; model.screen.value = Screen.valueOf(cmd.removePrefix("screen:")) }
                            cmd.startsWith("preview:") -> runBlocking { db.dao().file(cmd.removePrefix("preview:"))?.let { model.preview.value = PreviewRequest(listOf(it), 0) } }
                        }
                        delay(300)
                    }
                }
                App(model)
            }
        }
    }
}
