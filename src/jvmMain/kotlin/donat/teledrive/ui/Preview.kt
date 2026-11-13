@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)

package donat.teledrive.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import donat.teledrive.AppModel
import donat.teledrive.DriveService
import donat.teledrive.Live
import donat.teledrive.PreviewRequest
import donat.teledrive.StreamServer
import donat.teledrive.category
import donat.teledrive.core.FileCategory
import donat.teledrive.core.SafetyState
import donat.teledrive.data.DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.pow

private val textExt = setOf("txt", "md", "csv", "json", "xml", "log", "ini", "yaml", "yml", "kt", "java", "py", "js", "ts", "html", "css", "sql", "bat", "ps1", "sh")
/** Opacity of the preview's black backdrop (the title bar takes the same color while a preview is open). */
const val PREVIEW_BACKDROP = 0.94f
private val onDark = Color.White
private val onDarkMuted = Color.White.copy(alpha = 0.7f)

/** Pan/zoom state for one image. Scale 1 = fit to the window. */
private class Zoom {
    var scale by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var center = Offset.Zero

    /** Zooms by [factor] keeping the point under [at] where it is. */
    fun zoomAt(at: Offset, factor: Float) {
        val next = (scale * factor).coerceIn(1f, 10f)
        val f = next / scale
        offset = if (next == 1f) Offset.Zero else (at - center) - ((at - center) - offset) * f
        scale = next
    }

    fun reset() { scale = 1f; offset = Offset.Zero }
}

/**
 * Minimal full-window preview (lightbox): dark backdrop, slim icon bar, side arrows.
 * Images: wheel = zoom at cursor, drag = pan, double-click = fit/2×. Media streams and plays in-app.
 * Keys: ←/→ navigate, +/- zoom, 0 fit, Esc close.
 */
@Composable
fun PreviewOverlay(model: AppModel, service: DriveService, req: PreviewRequest) {
    var index by remember(req) { mutableStateOf(req.index) }
    val f = req.files.getOrNull(index) ?: return
    val focus = remember { FocusRequester() }
    val zoom = remember(f.id) { Zoom() }
    val player = remember(f.id) { PlayerControls() }
    val close = { model.preview.value = null }
    val hasPrev = index > 0
    val hasNext = index < req.files.lastIndex
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Prefetch the neighbours so ← / → feel instant.
    LaunchedEffect(index) {
        kotlinx.coroutines.delay(800)
        for (n in listOf(index + 1, index - 1).mapNotNull { req.files.getOrNull(it) }.filter { it.isSaved && it.category == FileCategory.IMAGES }) {
            try { service.cached(n) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    Box(
        Modifier.fillMaxSize()
            .popIn()
            .background(Color.Black.copy(alpha = PREVIEW_BACKDROP))
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } } // keep clicks off the app underneath
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (k.key) {
                    Key.Escape -> { close(); true }
                    Key.DirectionLeft -> { if (hasPrev) index--; true }
                    Key.DirectionRight -> { if (hasNext) index++; true }
                    Key.Plus, Key.Equals, Key.NumPadAdd -> { zoom.zoomAt(zoom.center, 1.25f); true }
                    Key.Minus, Key.NumPadSubtract -> { zoom.zoomAt(zoom.center, 0.8f); true }
                    Key.Zero, Key.NumPad0 -> { zoom.reset(); true }
                    Key.Spacebar -> { player.toggle(); true }
                    Key.J -> { player.skip(-10); true }
                    Key.L -> { player.skip(10); true }
                    else -> false
                }
            },
    ) {
        key(f.id) { PreviewContent(model, service, f, zoom, player) }

        // Slim top bar
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(f.name, color = onDark, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${index + 1} / ${req.files.size} · ${f.category.label} · ${formatSize(f.size)}", color = onDarkMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (f.category == FileCategory.IMAGES) {
                TipIcon(Icons.Filled.ZoomOut, "Zoom out (−)", tint = onDark) { zoom.zoomAt(zoom.center, 0.8f) }
                TextButton(onClick = { zoom.reset() }) { Text("${(zoom.scale * 100).toInt()}%", color = onDark) }
                TipIcon(Icons.Filled.ZoomIn, "Zoom in (+)", tint = onDark) { zoom.zoomAt(zoom.center, 1.25f) }
                Spacer(Modifier.width(8.dp))
            }
            TipIcon(Icons.AutoMirrored.Filled.OpenInNew, "Open with default app", tint = onDark, enabled = f.isSaved) { openExternally(model, service, f) }
            TipIcon(Icons.Filled.Download, "Download…", tint = onDark, enabled = f.isSaved) { model.download(listOf(f)) }
            Spacer(Modifier.width(8.dp))
            // Filled and labelled, so it never reads as the window's own close button just above it.
            androidx.compose.material3.Button(
                onClick = close, shape = RoundedCornerShape(20.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Filled.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Close preview (Esc)")
            }
        }

        if (hasPrev) SideArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous (←)", Modifier.align(Alignment.CenterStart)) { index-- }
        if (hasNext) SideArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next (→)", Modifier.align(Alignment.CenterEnd)) { index++ }
    }
}

/** Decided up front from the file type, so a binary file can never fall through to the text renderer. */
private enum class Kind { NOT_SAVED, AUDIO, MEDIA, IMAGE, PDF, TEXT, ZIP, OTHER }

private const val TEXT_PREVIEW_LIMIT = 256 * 1024
private const val STALL_MS = 60_000L
/** Media up to this size is fetched (verified) and played from disk — the most reliable path for JavaFX; bigger files stream. */
private const val LOCAL_MEDIA = 300L * 1024 * 1024

/** Loaded preview content. */
private sealed interface Loaded {
    data class Img(val bitmap: ImageBitmap) : Loaded
    data class Lines(val lines: List<String>, val truncated: Boolean) : Loaded
    data class Entries(val entries: List<Pair<String, Long>>) : Loaded
    data class Pdf(val file: File) : Loaded
    data class Local(val file: File) : Loaded
}

@Composable
private fun PreviewContent(model: AppModel, service: DriveService, f: DriveFile, zoom: Zoom, player: PlayerControls) {
    var progress by remember { mutableStateOf<Live?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val ext = f.name.substringAfterLast('.', "").lowercase()
    val kind = when {
        !f.isSaved -> Kind.NOT_SAVED
        f.category == FileCategory.AUDIO -> Kind.AUDIO
        f.category == FileCategory.VIDEO -> Kind.MEDIA
        f.category == FileCategory.IMAGES -> Kind.IMAGE
        ext == "pdf" -> Kind.PDF
        ext == "zip" -> Kind.ZIP
        ext in textExt -> Kind.TEXT
        else -> Kind.OTHER
    }

    LaunchedEffect(f.id, attempt) {
        if (kind !in setOf(Kind.IMAGE, Kind.PDF, Kind.TEXT, Kind.ZIP) && !(kind == Kind.MEDIA && f.size <= LOCAL_MEDIA)) return@LaunchedEffect
        error = null
        progress = null
        var lastProgress = System.currentTimeMillis()
        // Watchdog: if Telegram sends nothing for a while, say so instead of spinning forever.
        val watchdog = launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                if (System.currentTimeMillis() - lastProgress > STALL_MS) { error = "Telegram hasn't sent any data for a minute."; break }
            }
        }
        try {
            val l = service.cached(f) { progress = it; lastProgress = System.currentTimeMillis() }
            loaded = withContext(Dispatchers.IO) {
                when (kind) {
                    Kind.IMAGE -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(l.readBytes()).toComposeImageBitmap() }.getOrNull()?.let { Loaded.Img(it) }
                    Kind.PDF -> Loaded.Pdf(l)
                    Kind.MEDIA -> Loaded.Local(l)
                    Kind.ZIP -> Loaded.Entries(runCatching { ZipFile(l).use { z -> z.entries().toList().map { it.name to it.size } } }.getOrElse { listOf("Can't read ZIP: ${it.message}" to -1L) })
                    else -> {
                        val bytes = l.inputStream().use { it.readNBytes(TEXT_PREVIEW_LIMIT) }
                        Loaded.Lines(bytes.decodeToString().lines(), l.length() > TEXT_PREVIEW_LIMIT)
                    }
                }
            }
            if (loaded == null) error = "This image format can't be shown here — use Open with default app."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            donat.teledrive.Log.e("preview ${f.id}", e)
            error = e.message ?: e.toString()
        } finally {
            watchdog.cancel()
        }
    }

    Box(Modifier.fillMaxSize().padding(top = 64.dp, bottom = 16.dp, start = 72.dp, end = 72.dp), contentAlignment = Alignment.Center) {
        val data = loaded
        when {
            kind == Kind.NOT_SAVED -> Text("This file isn't saved to Telegram yet.", color = onDark)
            kind == Kind.AUDIO -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LaunchedEffect(f.id) { model.audio.value = f } // reached with ← / →: plays in the corner player
                Icon(categoryIcon(f.category), null, Modifier.size(96.dp), tint = onDarkMuted)
                Text("Playing in the mini player (bottom right).", color = onDarkMuted)
            }
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error!!, color = onDark)
                if (kind != Kind.MEDIA) TextButton(onClick = { attempt++ }) { Text("Retry", color = onDark) }
            }
            kind == Kind.MEDIA && f.size <= LOCAL_MEDIA && data !is Loaded.Local -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = onDark)
                val p = progress
                Text(if (p == null) "Fetching from Telegram…" else "${formatSize(p.bytes)} / ${formatSize(f.size)}", color = onDarkMuted, modifier = Modifier.padding(top = 8.dp))
            }
            kind == Kind.MEDIA -> DarkMedia { MediaPlayback(service, f, (data as? Loaded.Local)?.file, player, video = true) }
            data is Loaded.Img -> ZoomableImage(data.bitmap, zoom)
            data is Loaded.Pdf -> PdfView(data.file, zoom)
            data is Loaded.Entries -> TextCard {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Text("${data.entries.size} entries", style = MaterialTheme.typography.labelLarge) }
                    items(data.entries) { (name, size) -> Row { Text(name, Modifier.weight(1f)); if (size >= 0) Text(formatSize(size)) } }
                }
            }
            data is Loaded.Lines -> TextCard {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(data.lines) { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false) }
                    if (data.truncated) item { Text("… showing the first 256 KB. Use Open with default app for the whole file.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            kind != Kind.OTHER -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = onDark)
                val p = progress
                Text(if (p == null) "Fetching from Telegram…" else "${formatSize(p.bytes)} / ${formatSize(f.size)}", color = onDarkMuted, modifier = Modifier.padding(top = 8.dp))
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(categoryIcon(f.category), null, Modifier.size(96.dp), tint = onDarkMuted)
                Text("No in-app preview for this type.", color = onDarkMuted)
                TextButton(onClick = { openExternally(model, service, f) }) { Text("Open with default app", color = onDark) }
            }
        }
    }
}

/** Dark Material colors + white content, for players drawn on a dark background (preview backdrop, mini player). */
@Composable
private fun DarkMedia(content: @Composable () -> Unit) = MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides onDark, content = content)
}

/** Plays [f] from [local] (fetched, verified) or streamed. JavaFX first; anything it can't decode (MKV, HEVC, FLAC…) goes to VLC. */
@Composable
private fun MediaPlayback(service: DriveService, f: DriveFile, local: File?, player: PlayerControls, video: Boolean) {
    val url = remember(f.id, local) { local?.toURI()?.toString() ?: StreamServer.open(service, f) }
    DisposableEffect(url) { onDispose { if (local == null) StreamServer.close(url) } }
    var useVlc by remember { mutableStateOf(f.name.substringAfterLast('.', "").lowercase() !in playableExt) }
    var vlcReady by remember { mutableStateOf(Vlc.available) }
    when {
        !useVlc -> MediaPlayerView(url, video, player) { useVlc = true }
        vlcReady -> VlcPlayerView(local?.absolutePath ?: url, player, video)
        else -> VlcInstallPrompt { vlcReady = true }
    }
}

/**
 * Corner audio player (like a Windows notification): stays across screens until closed; minimizes to one line.
 * Files up to 300 MB are fetched and verified first (JavaFX plays local files reliably); bigger ones stream.
 */
@Composable
fun MiniAudioPlayer(model: AppModel, service: DriveService, f: DriveFile, modifier: Modifier) {
    val player = remember(f.id) { PlayerControls() }
    var minimized by remember { mutableStateOf(false) }
    var progress by remember(f.id) { mutableStateOf<Live?>(null) }
    var error by remember(f.id) { mutableStateOf<String?>(null) }
    val local by androidx.compose.runtime.produceState<File?>(null, f.id) {
        if (f.size > LOCAL_MEDIA) return@produceState
        value = try { service.cached(f) { progress = it } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message; null }
    }
    val ready = f.size > LOCAL_MEDIA || local != null
    Surface(modifier.width(380.dp).popIn(), shape = RoundedCornerShape(12.dp), color = Color(0xFF1F2024), shadowElevation = 10.dp) {
        DarkMedia {
            Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(categoryIcon(f.category), null, Modifier.size(28.dp), tint = categoryColor(f.category))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${formatSize(f.size)} · Audio", style = MaterialTheme.typography.bodySmall, color = onDarkMuted)
                    }
                    if (minimized) TipIcon(if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play / pause", tint = onDark, enabled = ready) { player.toggle() }
                    TipIcon(if (minimized) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (minimized) "Show player" else "Minimize", tint = onDark) { minimized = !minimized }
                    TipIcon(Icons.Filled.Close, "Close player", tint = onDark) { model.audio.value = null }
                }
                // Minimizing only hides the controls — the player stays composed, so playback continues.
                Box(if (minimized) Modifier.height(0.dp).clipToBounds() else Modifier.padding(end = 8.dp, top = 2.dp)) {
                    when {
                        error != null -> Text(error!!, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                        !ready -> Column {
                            val p = progress
                            LinearProgressIndicator({ if (p == null) 0f else (p.bytes.toFloat() / f.size).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            Text(if (p == null) "Fetching from Telegram…" else "${formatSize(p.bytes)} / ${formatSize(f.size)}", style = MaterialTheme.typography.bodySmall, color = onDarkMuted)
                        }
                        else -> key(f.id) { MediaPlayback(service, f, local, player, video = false) }
                    }
                }
            }
        }
    }
}

/** Short pop-up entrance: fade + scale 92 % → 100 % in 160 ms. One float read in the draw phase — no relayout, no recomposition. */
@Composable
fun Modifier.popIn(): Modifier {
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    // 300 ms, decelerating (fast start, soft landing) — reads as smooth; linear/short felt like a jump.
    LaunchedEffect(Unit) { p.animateTo(1f, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.EaseOutCubic)) }
    return graphicsLayer { alpha = p.value; val s = 0.92f + 0.08f * p.value; scaleX = s; scaleY = s }
}

@Composable
private fun ZoomableImage(bmp: ImageBitmap, zoom: Zoom) {
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds()
            .onSizeChanged { zoom.center = Offset(it.width / 2f, it.height / 2f) }
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val ch = ev.changes.first()
                zoom.zoomAt(ch.position, 1.15f.pow(-ch.scrollDelta.y))
                ch.consume()
            }
            .onPointerEvent(PointerEventType.Press) { ev ->
                val awt = ev.nativeEvent as? java.awt.event.MouseEvent ?: return@onPointerEvent
                if (awt.clickCount == 2) { if (zoom.scale > 1f) zoom.reset() else zoom.zoomAt(ev.changes.first().position, 2f) }
            }
            .pointerInput(Unit) { detectDragGestures { change, drag -> if (zoom.scale > 1f) { zoom.offset += drag; change.consume() } } },
    ) {
        Image(
            bmp, null,
            Modifier.fillMaxSize().graphicsLayer(scaleX = zoom.scale, scaleY = zoom.scale, translationX = zoom.offset.x, translationY = zoom.offset.y),
            contentScale = ContentScale.Fit,
        )
    }
}

/** PDF pages rendered with PDFBox, one at a time; the page image reuses the pan/zoom of photos. */
@Composable
private fun PdfView(file: File, zoom: Zoom) {
    val doc = remember(file) { runCatching { org.apache.pdfbox.Loader.loadPDF(file) }.getOrNull() }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    if (doc == null) return Text("This PDF can't be opened here — use Open with default app.", color = onDark)
    val renderer = remember(doc) { org.apache.pdfbox.rendering.PDFRenderer(doc) }
    var page by remember { mutableStateOf(0) }
    val pages = doc.numberOfPages
    var failed by remember { mutableStateOf<String?>(null) }
    val bitmap by androidx.compose.runtime.produceState<ImageBitmap?>(null, page) {
        value = null
        failed = null
        zoom.reset()
        // A damaged or unusual PDF (e.g. "Missing descendant font dictionary") must not crash the app: say so for that page.
        value = withContext(Dispatchers.IO) {
            try {
                synchronized(doc) { renderer.renderImageWithDPI(page, 150f).toComposeImageBitmap() }
            } catch (e: Exception) {
                donat.teledrive.Log.e("pdf page ${page + 1}", e)
                failed = e.message ?: e.toString()
                null
            }
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                failed != null -> Text("Page ${page + 1} can't be shown here ($failed) — use Open with default app.", color = onDark)
                bitmap != null -> ZoomableImage(bitmap!!, zoom)
                else -> CircularProgressIndicator(color = onDark)
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PageButton("‹ Previous page", page > 0) { page-- }
            Text("Page ${page + 1} of $pages", color = onDark, modifier = Modifier.padding(horizontal = 16.dp))
            PageButton("Next page ›", page < pages - 1) { page++ }
        }
    }
}

/** Filled pill on the dark backdrop; clearly dimmed when there's no page that way. */
@Composable
private fun PageButton(label: String, enabled: Boolean, onClick: () -> Unit) = androidx.compose.material3.Button(
    onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp),
    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
        containerColor = Color.White, contentColor = Color.Black,
        disabledContainerColor = Color.White.copy(alpha = 0.12f), disabledContentColor = Color.White.copy(alpha = 0.38f),
    ),
) { Text(label) }

@Composable
private fun TextCard(content: @Composable () -> Unit) {
    Surface(Modifier.widthIn(max = 960.dp).fillMaxSize(), shape = RoundedCornerShape(12.dp)) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SideArrow(icon: ImageVector, tip: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.padding(12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
        TipIcon(icon, tip, tint = onDark, iconSize = 32, onClick = onClick)
    }
}

private fun openExternally(model: AppModel, service: DriveService, f: DriveFile) {
    model.task("Opening ${f.name}…") {
        val l = service.cached(f)
        Desktop.getDesktop().open(l)
    }
}

/** Icon button with a hover tooltip — used for compact toolbars. */
@Composable
fun TipIcon(icon: ImageVector, tip: String, tint: Color = Color.Unspecified, enabled: Boolean = true, iconSize: Int = 22, onClick: () -> Unit) {
    TooltipArea(tooltip = { Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp, tonalElevation = 4.dp) { Text(tip, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) } }) {
        // 40 dp instead of Material's 48 dp touch target: a desktop toolbar, clicked with a mouse.
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(maxOf(40, iconSize + 12).dp)) {
            val base = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint
            Icon(icon, tip, Modifier.size(iconSize.dp), tint = if (enabled) base else base.copy(alpha = 0.38f))
        }
    }
}

val DriveFile.isSaved get() = safety == SafetyState.REMOTE_SAFE || safety == SafetyState.VERIFIED || safety == SafetyState.CONFLICT
