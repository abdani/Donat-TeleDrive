@file:OptIn(ExperimentalLayoutApi::class)

package donat.teledrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import donat.teledrive.APP_NAME
import donat.teledrive.AppLock
import donat.teledrive.AppModel
import donat.teledrive.DriveService
import donat.teledrive.LanServer
import donat.teledrive.category
import donat.teledrive.core.FileCategory
import donat.teledrive.data.DriveFile
import donat.teledrive.data.Folder
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------- Gallery ----------

private val nameDate = Regex("""(20\d{2})[-_]?(0[1-9]|1[0-2])[-_]?(0[1-9]|[12]\d|3[01])""")

/** When the photo was taken: from names like IMG-20250625-WA0068 / PXL_20250625_… , else the upload date. */
fun takenDate(f: DriveFile): LocalDate =
    nameDate.find(f.name)?.destructured?.let { (y, m, d) -> runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() }
        ?: Instant.ofEpochMilli(f.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
fun GalleryScreen(model: AppModel, service: DriveService) {
    val files by remember { model.db.dao().activeFiles(service.drive.id) }.collectAsState(null as List<DriveFile>?)
    val media = remember(files) {
        files.orEmpty().filter { it.isSaved && (it.category == FileCategory.IMAGES || it.category == FileCategory.VIDEO) }
            .sortedWith(compareByDescending<DriveFile> { takenDate(it) }.thenByDescending { it.name })
    }
    if (files == null) return
    if (media.isEmpty()) return EmptyState("No photos or videos yet", "Upload images or videos and they appear here, grouped by day.")
    val groups = remember(media) { media.groupBy { takenDate(it) } }
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy") }
    LazyVerticalGrid(GridCells.Adaptive(130.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { (day, list) ->
            item(key = "h$day", span = { GridItemSpan(maxLineSpan) }) {
                Text("${day.format(fmt)} · ${list.size}", Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(list, key = { it.id }) { f ->
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { model.open(media, media.indexOf(f)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Thumb(service, f, 36.dp)
                    if (f.category == FileCategory.VIDEO) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, "Video", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Insights: largest files/folders, duplicates ----------

@Composable
fun InsightsScreen(model: AppModel, service: DriveService) {
    val files by remember { model.db.dao().activeFiles(service.drive.id) }.collectAsState(null as List<DriveFile>?)
    val folders by remember { model.db.dao().activeFolders(service.drive.id) }.collectAsState(null as List<Folder>?)
    val all = files ?: return
    val dirs = folders ?: return
    val byId = remember(dirs) { dirs.associateBy { it.id } }
    fun pathOf(folderId: String?): String = generateSequence(folderId?.let(byId::get)) { it.parentId?.let(byId::get) }.toList().reversed()
        .joinToString(" › ") { it.name }.ifEmpty { service.drive.name }
    val largest = remember(all) { all.sortedByDescending { it.size }.take(15) }
    val folderSizes = remember(all, dirs) {
        val direct = all.groupBy { it.folderId }.mapValues { (_, l) -> l.sumOf { it.size } }
        val children = dirs.groupBy { it.parentId }
        fun total(id: String): Long = (direct[id] ?: 0) + children[id].orEmpty().sumOf { total(it.id) }
        dirs.map { it to total(it.id) }.sortedByDescending { it.second }.take(10)
    }
    val dupes = remember(all) {
        all.filter { it.sha256 != null }.groupBy { it.sha256 to it.size }.values.filter { it.size > 1 }.sortedByDescending { it.first().size * (it.size - 1) }
    }
    val wasted = dupes.sumOf { it.first().size * (it.size - 1) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Storage insights", style = MaterialTheme.typography.headlineSmall) }
        item { Section("Largest files") }
        items(largest, key = { "l" + it.id }) { f -> InsightRow(f.name, pathOf(f.folderId), formatSize(f.size)) { model.open(largest, largest.indexOf(f)) } }
        item { Section("Largest folders") }
        items(folderSizes, key = { "d" + it.first.id }) { (d, size) -> InsightRow(d.name, pathOf(d.parentId), formatSize(size)) {} }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Section("Duplicates · ${dupes.size} group(s) · ${formatSize(wasted)} reclaimable", Modifier.weight(1f))
                if (dupes.isNotEmpty()) TextButton(onClick = {
                    model.task("Moving duplicate copies to trash…") { service.trash(dupes.flatMap { it.drop(1) }, emptyList()); model.notify("Moved ${dupes.sumOf { it.size - 1 }} duplicate copies to trash") }
                }) { Text("Keep one of each, trash the rest") }
            }
        }
        if (dupes.isEmpty()) item { Hint("No duplicates — every file's content is unique.") }
        items(dupes, key = { "x" + it.first().sha256 }) { group ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${group.size} copies · ${formatSize(group.first().size)} each", style = MaterialTheme.typography.labelLarge)
                    group.forEach { Text("• ${it.name}  —  ${pathOf(it.folderId)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier) =
    Text(title, modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)

@Composable
private fun InsightRow(name: String, where: String, size: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(size, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider()
}

// ---------- App lock ----------

@Composable
fun LockScreen(model: AppModel) {
    var pin by remember { mutableStateOf("") }
    // Persisted in AppLock: restarting the app must not reset the guessing brake.
    var failures by remember { mutableStateOf(AppLock.failures()) }
    var waitUntil by remember { mutableStateOf(AppLock.blockedUntil()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(waitUntil) { while (System.currentTimeMillis() < waitUntil) { delay(500); now = System.currentTimeMillis() }; now = System.currentTimeMillis() }
    val blocked = now < waitUntil
    fun unlock() {
        if (blocked || pin.isEmpty()) return
        if (AppLock.check(pin)) { model.locked.value = false; return }
        pin = ""
        waitUntil = AppLock.failed()
        failures = AppLock.failures()
    }
    Centered {
        LogoHeader()
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Lock, null, Modifier.size(22.dp).padding(end = 6.dp), tint = MaterialTheme.colorScheme.primary)
            Text("$APP_NAME is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        OutlinedTextField(
            pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true, enabled = !blocked,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).keys(::unlock),
        )
        if (blocked) ErrorText("Too many wrong PINs. Try again in ${(waitUntil - now) / 1000 + 1}s.")
        else if (failures > 0) ErrorText("Wrong PIN.")
        Button(onClick = { unlock() }, enabled = !blocked && pin.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
    }
}

/** Set (two entries must match) or remove the PIN. */
@Composable
fun PinDialog(onDismiss: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AppDialog(if (AppLock.isSet()) "Change PIN" else "Set a PIN", onDismiss, "Save PIN", { AppLock.set(a); onDismiss() }, enabled = a.length >= 4 && a == b, focusContent = false) {
        LaunchedEffect(Unit) { focus.requestFocus() } // inside the dialog, so the field already exists
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("Asked when the app starts and when it's reopened from the tray. 4–12 digits. Logging out removes it.")
            OutlinedTextField(a, { a = it.filter(Char::isDigit).take(12) }, label = { Text("New PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.focusRequester(focus))
            OutlinedTextField(b, { b = it.filter(Char::isDigit).take(12) }, label = { Text("Repeat PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            if (b.isNotEmpty() && a != b) ErrorText("PINs don't match.")
        }
    }
}

// ---------- Share link (local network) ----------

@Composable
fun ShareDialog(model: AppModel, file: DriveFile, onDismiss: () -> Unit) {
    var hours by remember { mutableStateOf(24L) }
    val url = remember(hours) {
        LanServer.start { model.service.value }
        LanServer.share(file, hours)
    }
    AppDialog("Share “${file.name}”", onDismiss, "Copy link", { copy(url); model.notify("Link copied") }, dismiss = "Close") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1L to "1 hour", 24L to "24 hours", 168L to "7 days").forEach { (h, l) -> FilterChip(hours == h, { hours = h }, label = { Text(l) }) }
            }
            QrCode(url, 200.dp)
            Text(url, style = MaterialTheme.typography.bodySmall)
            Hint("Works for phones and PCs on the same Wi-Fi while this PC is on. Windows may ask once to allow $APP_NAME on private networks.")
        }
    }
}

fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

// ---------- Version history ----------

@Composable
fun VersionsDialog(model: AppModel, service: DriveService, file: DriveFile, onDismiss: () -> Unit) {
    val versions by remember(file.id) { model.db.dao().versions(file.id) }.collectAsState(null as List<DriveFile>?)
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()) }
    AppDialog("Versions of “${file.name}”", onDismiss, "Close", onDismiss, dismiss = null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Current · ${fmt.format(Instant.ofEpochMilli(file.createdAt))} · ${formatSize(file.size)}", fontWeight = FontWeight.SemiBold)
            val list = versions
            when {
                list == null -> Unit
                list.isEmpty() -> Hint("No older versions. Uploading a file with the same name into this folder creates one.")
                else -> list.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmt.format(Instant.ofEpochMilli(v.createdAt))} · ${formatSize(v.size)}", Modifier.weight(1f))
                        TextButton(onClick = { model.download(listOf(v)) }) { Text("Download") }
                        TextButton(onClick = { onDismiss(); model.task("Restoring version…") { service.restoreVersion(v, file); model.notify("Restored the older version") } }) { Text("Restore") }
                    }
                }
            }
        }
    }
}


// ---------- Logo ----------

fun resourceImage(name: String): androidx.compose.ui.graphics.ImageBitmap =
    org.jetbrains.skia.Image.makeFromEncoded(object {}.javaClass.getResourceAsStream(name)!!.use { it.readBytes() }).toComposeImageBitmap()

/**
 * Centered logo + name, shown on the login and PIN screens. The logo (wide artwork, 144 × 96 dp) is pre-rendered from
 * docs/images/logo.png at the exact pixel size for 100–200 % display scale (logo-144…288.png, named by width; tools/logo.py)
 * and drawn 1:1: any runtime downscale left jagged edges.
 */
@Composable
fun LogoHeader() {
    val px = (144 * androidx.compose.ui.platform.LocalDensity.current.density).toInt()
    val file = listOf(144, 180, 216, 252, 288).firstOrNull { it >= px } ?: 288 // odd scales: nearest larger
    val image = remember(file) { resourceImage("/logo-$file.png") }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Image(
            image, "$APP_NAME logo", Modifier.size(144.dp, 96.dp),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium, // only matters when the size isn't exact
        )
        Text(APP_NAME, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}
