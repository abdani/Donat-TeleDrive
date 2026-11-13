package donat.teledrive.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import donat.teledrive.appDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Video engine for what JavaFX can't play (MKV, HEVC, AVI, WebM, FLAC…). Not bundled, not installed into Windows:
 * the official portable VLC is downloaded once on first use into app data (SHA-256 checked); only libvlc + needed plugins kept.
 */
object Vlc {
    private val dir = File(appDataDir, "vlc")
    private const val BASE = "https://download.videolan.org/pub/videolan/vlc/last/win64/"
    private val skip = listOf("gui/", "lua/", "visualization/", "stream_out/", "services_discovery/", "control/", "video_splitter/")
    @Volatile private var found: Boolean? = null

    val available: Boolean get() = found ?: discover().also { found = it }

    /** Only looks in our own folder — vlcj's default discovery scans disks recursively (seconds, and it froze the UI). */
    private fun discover(): Boolean {
        if (!File(dir, "libvlc.dll").exists()) return false
        val ours = object : uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy {
            override fun supported() = true
            override fun discover() = dir.absolutePath
            override fun onFound(path: String): Boolean { com.sun.jna.NativeLibrary.addSearchPath("libvlc", path); return true }
            override fun onSetPluginPath(path: String) = true // libvlc finds plugins/ next to itself
        }
        return runCatching { NativeDiscovery(ours).discover() }.getOrDefault(false) // vlcj remembers success for later factories
    }

    /** One-time download; [onProgress] gets (done, total) bytes. */
    suspend fun install(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val zipName = Regex("""vlc-[\d.]+-win64\.zip(?=")""").find(URI(BASE).toURL().readText())!!.value
        val expected = URI("$BASE$zipName.sha256").toURL().readText().trim().split(Regex("\\s+")).first()
        val zip = File(appDataDir, "tmp/$zipName").apply { parentFile.mkdirs() }
        val conn = URI(BASE + zipName).toURL().openConnection()
        val total = conn.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        conn.getInputStream().use { input ->
            zip.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                    onProgress(done, total)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) { zip.delete(); error("Video engine download is corrupted (checksum mismatch)") }
        dir.deleteRecursively()
        ZipFile(zip).use { z ->
            z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                val rel = e.name.substringAfter('/')
                val keep = rel == "libvlc.dll" || rel == "libvlccore.dll" || (rel.startsWith("plugins/") && skip.none { rel.startsWith("plugins/$it") })
                if (keep) File(dir, rel).apply { parentFile.mkdirs() }.outputStream().use { o -> z.getInputStream(e).use { it.copyTo(o) } }
            }
        }
        zip.delete()
        found = null
    }
}

/** Shown instead of the player until the engine has been downloaded once. */
@Composable
fun VlcInstallPrompt(onReady: () -> Unit) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This format needs the video engine (about 80 MB, downloaded once, no install).", color = Color.White)
        val p = progress
        if (p == null) {
            Button(onClick = {
                progress = 0L to 0L
                scope.launch {
                    try {
                        Vlc.install { d, t -> progress = d to t }
                        if (Vlc.available) onReady() else { error = "The engine could not be loaded."; progress = null }
                    } catch (e: Exception) {
                        error = e.message; progress = null
                    }
                }
            }) { Text("Download video engine & play") }
        } else {
            LinearProgressIndicator({ if (p.second > 0) p.first.toFloat() / p.second else 0f }, Modifier.width(320.dp))
            Text("${formatSize(p.first)} / ${formatSize(p.second)}", color = Color.White)
        }
        error?.let { Text(it, color = Color(0xFFFF8A80)) }
    }
}

@Composable
fun VlcPlayerView(mrl: String, controls: PlayerControls, video: Boolean = true) {
    val comp = remember { CallbackMediaPlayerComponent() }
    val mp = comp.mediaPlayer()
    var time by remember { mutableStateOf(0L) }
    var length by remember { mutableStateOf(0L) }
    var volume by remember { mutableStateOf(0.8f) }
    var muted by remember { mutableStateOf(false) }
    controls.toggle = { mp.controls().pause() }
    controls.skip = { s -> mp.controls().skipTime(s * 1000L) }
    DisposableEffect(mrl) { onDispose { mp.controls().stop(); comp.release() } }
    LaunchedEffect(mrl) {
        while (!comp.isDisplayable) delay(50) // VLC needs the video surface on screen before play()
        mp.media().play(mrl)
        while (true) {
            time = mp.status().time(); length = mp.status().length(); controls.playing = mp.status().isPlaying
            mp.audio().setVolume((volume * 100).toInt()) // VLC resets it when playback starts; keep ours (0–100 = 0–100%)
            delay(250)
        }
    }
    Column(if (video) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        // ponytail: audio still needs a displayable surface for this component, so it gets a 1 dp one; a headless player if it matters.
        SwingPanel(Color.Black, factory = { comp }, modifier = if (video) Modifier.weight(1f).fillMaxWidth() else Modifier.size(1.dp))
        MediaControls(
            time.toDouble(), length.toDouble(), null, ready = length > 0, volume, muted, controls,
            onSeek = { mp.controls().setTime(it.toLong()) },
            onVolume = { volume = it; muted = false; mp.audio().setMute(false); mp.audio().setVolume((it * 100).toInt()) },
            onMute = { muted = it; mp.audio().setMute(it) },
        )
    }
}
