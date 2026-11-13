package donat.teledrive

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import donat.teledrive.data.AppDatabase
import donat.teledrive.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import java.io.File

const val APP_NAME = "Donat TeleDrive"

/**
 * %LOCALAPPDATA%\Donat TeleDrive. The folder of the old name ("DonatTeleDrive") is moved here once, session and all;
 * if it can't be moved (an old build still has it open) it keeps being used, so nothing is ever lost or duplicated.
 */
val appDataDir: File = resolveAppDataDir(File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"))).apply { mkdirs() }

internal fun resolveAppDataDir(base: File): File {
    val dir = File(base, APP_NAME)
    val old = File(base, "DonatTeleDrive")
    return if (!dir.exists() && old.isDirectory && !old.renameTo(dir)) old else dir
}

fun openDatabase(path: String = File(appDataDir, "index.db").absolutePath): AppDatabase =
    Room.databaseBuilder<AppDatabase>(path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // SQLite is only a cache of the Remote Drive Manifest, so a schema change may safely wipe and rebuild it.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

fun main() {
    // One instance only: TDLib's session database can't be shared by two processes.
    val lock = java.io.RandomAccessFile(File(appDataDir, "app.lock"), "rw").channel.tryLock()
    if (lock == null) {
        File(appDataDir, "show.signal").writeText("") // already running (maybe hidden in the tray): bring that window up instead
        return
    }
    val model = AppModel(openDatabase(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
    if (Prefs["lan"] == "true") LanServer.start { model.service.value }
    application {
        var visible by remember { mutableStateOf(true) }
        val tray = rememberTrayState()
        // Tray: a small icon drawn with high-quality filtering. The window gets the whole icon-<n>.png set (see below).
        val icon = remember {
            androidx.compose.ui.graphics.painter.BitmapPainter(donat.teledrive.ui.resourceImage("/icon-64.png"), filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
        }
        // JavaFX (media player) runs non-daemon threads; stop it too or the process outlives the window.
        val quit = { model.account.close(); javafx.application.Platform.exit(); exitApplication() }
        val show = { if (!visible && AppLock.isSet()) model.locked.value = true; visible = true } // re-lock when coming back from the tray
        Tray(icon = icon, state = tray, tooltip = APP_NAME, onAction = show, menu = {
            Item("Open $APP_NAME", onClick = show)
            Item("Exit", onClick = quit)
        })
        // Signals from outside: a second launch asks to show the window; "quit.signal" asks for a clean exit (used by dev restarts).
        LaunchedEffect(Unit) {
            val showSignal = File(appDataDir, "show.signal")
            val quitSignal = File(appDataDir, "quit.signal")
            showSignal.delete(); quitSignal.delete()
            while (true) {
                if (showSignal.delete()) show()
                if (quitSignal.delete()) { Log.i("quit signal received"); quit() }
                kotlinx.coroutines.delay(700)
            }
        }
        // Windows notifications for finished/failed transfers.
        val service by model.service.collectAsState()
        LaunchedEffect(service) {
            service?.events?.collect { if (Prefs["notify"] != "false") tray.sendNotification(Notification(APP_NAME, it)) }
        }
        Window(
            onCloseRequest = {
                if (Prefs["closeToTray"] == "false") quit()
                else {
                    visible = false // keep uploading in the background
                    tray.sendNotification(Notification(APP_NAME, "Still running in the tray. Right-click the cloud icon → Exit to quit."))
                }
            },
            visible = visible,
            title = APP_NAME,
            icon = icon, // until the exact-size set below is in place
            state = rememberWindowState(width = 1280.dp, height = 820.dp),
        ) {
            NativeDialogs.parent = window
            // Every size pre-rendered from docs/images/logo.png (tools/logo.py): Windows picks the exact one for the title bar (16–24) and taskbar (32–48)
            // instead of shrinking one big image itself (that looked jagged).
            LaunchedEffect(Unit) {
                window.iconImages = listOf(16, 20, 24, 32, 40, 48, 64, 256).map { javax.imageio.ImageIO.read(object {}.javaClass.getResource("/icon-$it.png")) }
            }
            // Native title bar follows the theme (caption = app backdrop, text = onSurface) — and the dark backdrop while a preview is open.
            val theme by model.theme.collectAsState()
            val previewOpen = model.preview.collectAsState().value != null
            val scheme = donat.teledrive.ui.colorsFor(theme)
            LaunchedEffect(scheme, previewOpen) {
                // Resize bug: while the window grows, Windows paints the new strip with the AWT background (light gray
                // #F0F0F0) until Compose draws the next frame. In the backdrop color that strip is invisible.
                java.awt.Color(scheme.surfaceContainer.toArgb()).let { window.background = it; window.contentPane.background = it }
                val caption = if (previewOpen) Color.Black.copy(alpha = donat.teledrive.ui.PREVIEW_BACKDROP).compositeOver(scheme.surfaceContainer) else scheme.surfaceContainer
                val text = if (previewOpen) Color.White else scheme.onSurface
                TitleBar.apply(window, caption.toArgb() and 0xFFFFFF, text.toArgb() and 0xFFFFFF, caption.luminance() < 0.5f)
            }
            window.minimumSize = java.awt.Dimension(560, 480)
            App(model)
        }
    }
    Thread.sleep(1500) // let TDLib flush its session database after sendClose
    kotlin.system.exitProcess(0) // guarantee the process ends (native/JavaFX threads must not keep it alive)
}
