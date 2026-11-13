package donat.teledrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration

/** Formats JavaFX can play in-app. Everything else goes to the default Windows app. */
val playableExt = setOf("mp4", "m4v", "mov", "mp3", "m4a", "aac", "wav", "aif", "aiff")

/** Lets the preview's keyboard handler drive the player (Space = play/pause, J/L = −/+10 s). */
class PlayerControls {
    var toggle: () -> Unit = {}
    var skip: (seconds: Int) -> Unit = {}
    /** Set by the player; lets a minimized mini player show the right play/pause icon. */
    var playing by mutableStateOf(false)
}

/**
 * In-app audio/video player. [url] is a local file URI (≤300 MB) or a StreamServer URL (seeks without downloading
 * the whole file). JavaFX decodes; the controls are Compose. All player calls go through the FX thread.
 */
@Composable
fun MediaPlayerView(url: String, video: Boolean, controls: PlayerControls, onError: (String) -> Unit) {
    val panel = remember { JFXPanel() } // creating it starts the JavaFX toolkit
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var position by remember { mutableStateOf(0.0) } // ms
    var duration by remember { mutableStateOf(0.0) } // ms, 0 until known
    var buffered by remember { mutableStateOf(0.0) } // ms
    var volume by remember { mutableStateOf(0.8f) }
    var muted by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    fun fx(block: MediaPlayer.() -> Unit) { player?.let { p -> Platform.runLater { p.block() } } }
    fun ms(d: Duration?) = d?.toMillis()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
    controls.toggle = { fx { if (status == MediaPlayer.Status.PLAYING) pause() else play() } }
    controls.skip = { s -> fx { seek(Duration.millis((ms(currentTime) + s * 1000).coerceIn(0.0, ms(totalDuration).takeIf { it > 0 } ?: Double.MAX_VALUE))) } }

    DisposableEffect(url) {
        Platform.setImplicitExit(false) // closing a preview must not shut JavaFX down for good
        var p: MediaPlayer? = null
        Platform.runLater {
            try {
                val media = Media(url)
                media.setOnError { onError(media.error?.message ?: "Can't play this file") }
                p = MediaPlayer(media).apply {
                    setOnError { onError(error?.message ?: "Can't play this file (codec not supported)") }
                    setOnReady { duration = ms(totalDuration); ready = true; play() }
                    statusProperty().addListener { _, _, s -> controls.playing = s == MediaPlayer.Status.PLAYING }
                    setOnEndOfMedia { seek(Duration.ZERO); pause() }
                    this.volume = volume.toDouble()
                }
                if (video) {
                    val root = StackPane().apply { style = "-fx-background-color: black;" }
                    root.children += MediaView(p).apply {
                        isPreserveRatio = true
                        fitWidthProperty().bind(root.widthProperty())
                        fitHeightProperty().bind(root.heightProperty())
                    }
                    panel.scene = Scene(root)
                }
                player = p
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            }
        }
        onDispose { Platform.runLater { p?.dispose() } }
    }

    // Poll the player (JavaFX does not reliably fire time/duration change events for HTTP streams).
    androidx.compose.runtime.LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            Platform.runLater { // JavaFX state must be read on its own thread
                position = ms(p.currentTime)
                duration = ms(p.totalDuration).takeIf { it > 0 } ?: ms(p.media.duration)
                buffered = ms(p.bufferProgressTime)
            }
            kotlinx.coroutines.delay(250)
        }
    }

    // Video: picture on top, controls below. Audio (mini player): just the controls.
    Column(if (video) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        if (video) Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            SwingPanel(factory = { panel }, modifier = Modifier.fillMaxSize())
            if (!ready) CircularProgressIndicator()
        } else if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        MediaControls(
            position, duration, buffered.takeIf { url.startsWith("http") }, ready, volume, muted, controls, // a local file is all "buffered"
            onSeek = { v -> position = v; fx { seek(Duration.millis(v)) } },
            onVolume = { v -> volume = v; muted = false; fx { isMute = false; this.volume = v.toDouble() } },
            onMute = { m -> muted = m; fx { isMute = m } },
        )
    }
}

/** Seek bar, ±10 s, play/pause, time and volume — shared by the JavaFX and the VLC player. Times in ms, volume 0..1. */
@Composable
fun MediaControls(
    position: Double, duration: Double, buffered: Double?, ready: Boolean, volume: Float, muted: Boolean,
    controls: PlayerControls, onSeek: (Double) -> Unit, onVolume: (Float) -> Unit, onMute: (Boolean) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val total = duration.toFloat().coerceAtLeast(1f)
    val time = MaterialTheme.typography.labelMedium
    // Row 1: elapsed · seek bar · length (fixed-width times, so nothing wraps or jumps).
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(clockOf(dragging?.toDouble() ?: position), Modifier.width(48.dp), style = time, textAlign = TextAlign.Center)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Buffered part of a stream (how far playback can go without waiting), under the seek track.
            if (buffered != null && duration > 0) LinearProgressIndicator(
                { (buffered / duration).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 14.dp).height(2.dp),
            )
            SlimSlider(
                (dragging ?: position.toFloat()).coerceIn(0f, total), { dragging = it }, Modifier.fillMaxWidth(),
                range = 0f..total, enabled = ready && duration > 0,
                onFinished = { dragging?.let { onSeek(it.toDouble()) }; dragging = null },
            )
        }
        Text(if (duration > 0) clockOf(duration) else "--:--", Modifier.width(48.dp), style = time, textAlign = TextAlign.Center)
    }
    // Row 2: transport on the left, volume on the right.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val playing = controls.playing
        TipIcon(Icons.Filled.Replay10, "Back 10 s (J)", tint = LocalContentColor.current, enabled = ready) { controls.skip(-10) }
        TipIcon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause (Space)" else "Play (Space)", tint = LocalContentColor.current, enabled = ready, iconSize = 28) { controls.toggle() }
        TipIcon(Icons.Filled.Forward10, "Forward 10 s (L)", tint = LocalContentColor.current, enabled = ready) { controls.skip(10) }
        Spacer(Modifier.weight(1f))
        TipIcon(
            if (muted || volume == 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            if (muted) "Unmute" else "Mute", tint = LocalContentColor.current, iconSize = 20,
        ) { onMute(!muted) }
        SlimSlider(if (muted) 0f else volume, onVolume, Modifier.width(96.dp))
        Text("${(if (muted) 0f else volume).times(100).toInt()}%", Modifier.width(44.dp), style = time, textAlign = TextAlign.Center)
    }
}

/** Material slider with a 4 dp track and a slim thumb — the default 16 dp track and 44 dp thumb overwhelm a compact player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlimSlider(
    value: Float, onChange: (Float) -> Unit, modifier: Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f, enabled: Boolean = true, onFinished: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value, onValueChange = onChange, modifier = modifier.height(28.dp), enabled = enabled,
        onValueChangeFinished = onFinished, interactionSource = interaction, valueRange = range,
        thumb = { SliderDefaults.Thumb(interaction, enabled = enabled, thumbSize = DpSize(4.dp, 18.dp)) },
        track = { SliderDefaults.Track(it, Modifier.height(4.dp), enabled = enabled, drawStopIndicator = null, thumbTrackGapSize = 3.dp) },
    )
}

private fun clockOf(ms: Double): String = duration((ms / 1000).toLong().coerceAtLeast(0))
