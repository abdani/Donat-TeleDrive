@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)

package donat.teledrive.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import kotlinx.coroutines.flow.sample
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import donat.teledrive.AppModel
import donat.teledrive.DriveService
import donat.teledrive.Live
import donat.teledrive.NativeDialogs
import donat.teledrive.SortBy
import donat.teledrive.category
import donat.teledrive.clock
import donat.teledrive.core.FileCategory
import donat.teledrive.core.SafetyState
import donat.teledrive.core.TransferState
import donat.teledrive.data.DriveFile
import donat.teledrive.data.Folder
import donat.teledrive.data.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.text.DateFormat
import java.util.Date

// ---------- Category colors (dataviz-validated categorical slots 1–5, per light/dark surface) ----------

@Composable
fun categoryColor(c: FileCategory): Color {
    val dark = isDarkUi()
    return when (c) {
        FileCategory.IMAGES -> if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
        FileCategory.AUDIO -> if (dark) Color(0xFFD95926) else Color(0xFFEB6834)
        FileCategory.VIDEO -> if (dark) Color(0xFF199E70) else Color(0xFF1BAF7A)
        FileCategory.DOCUMENTS -> if (dark) Color(0xFFC98500) else Color(0xFFEDA100)
        FileCategory.OTHERS -> if (dark) Color(0xFFD55181) else Color(0xFFE87BA4)
    }
}

/** File icon tint: the category color, except "Others" — plain gray, like the preview's generic file icon. */
@Composable
fun iconColor(c: FileCategory): Color =
    if (c != FileCategory.OTHERS) categoryColor(c) else if (isDarkUi()) Color(0xFFB3B3B3) else Color(0xFF8C8C8C)

fun categoryIcon(c: FileCategory): ImageVector = when (c) {
    FileCategory.IMAGES -> Icons.Filled.Image
    FileCategory.AUDIO -> Icons.Filled.AudioFile
    FileCategory.VIDEO -> Icons.Filled.VideoFile
    FileCategory.DOCUMENTS -> Icons.Filled.Description
    FileCategory.OTHERS -> Icons.Filled.InsertDriveFile
}

// ---------- Compact storage card (Home header) ----------

@Composable
private fun CompactStorage(model: AppModel, service: DriveService, modifier: Modifier) {
    val loadedFiles by remember { model.db.dao().activeFiles(service.drive.id) }.collectAsState(null as List<DriveFile>?)
    val loadedFolders by remember { model.db.dao().activeFolders(service.drive.id) }.collectAsState(null as List<Folder>?)
    if (loadedFiles == null || loadedFolders == null) { // same footprint while loading, so nothing jumps or flashes "0 B"
        Box(modifier) { Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().height(66.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {} }
        return
    }
    val files = loadedFiles.orEmpty()
    val folders = loadedFolders.orEmpty()
    val byCat = FileCategory.entries.associateWith { c -> files.filter { it.category == c } }
    val bytes = byCat.mapValues { (_, l) -> l.sumOf { it.size } }
    val total = files.sumOf { it.size }
    val nonEmpty = FileCategory.entries.filter { bytes.getValue(it) > 0 }

    // Hover shows the full table (every category, size, share, count) — the bar alone never carries the numbers.
    TooltipArea(
        modifier = modifier,
        tooltip = {
            Surface(shape = RoundedCornerShape(8.dp), shadowElevation = 6.dp, tonalElevation = 4.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Storage by type", style = MaterialTheme.typography.labelLarge)
                    FileCategory.entries.forEach { c ->
                        Row {
                            Swatch(c)
                            Text(c.label, Modifier.width(90.dp).padding(start = 6.dp).alignByBaseline(), style = MaterialTheme.typography.bodySmall)
                            Text(formatSize(bytes.getValue(c)), Modifier.width(80.dp).alignByBaseline(), style = MaterialTheme.typography.bodySmall)
                            Text(pct(bytes.getValue(c), total), Modifier.width(56.dp).alignByBaseline(), style = MaterialTheme.typography.bodySmall)
                            Text("${byCat.getValue(c).size} files", Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("${files.count { it.isSaved }} of ${files.size} files saved to Telegram", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(formatSize(total), Modifier.alignByBaseline(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "  used · ${files.size} files · ${folders.size} folders", Modifier.weight(1f).alignByBaseline(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // 1dp optical nudge: the legend's text box has more empty space above its letters than the title has below.
                // Android 16-style segmented bar: pill ends and 2 dp gaps between the segments (a tiny segment keeps a visible sliver).
                Row(Modifier.padding(top = 1.dp).fillMaxWidth().height(8.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (total == 0L) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                    nonEmpty.forEach { c -> Box(Modifier.weight(maxOf(bytes.getValue(c).toFloat(), total * 0.006f)).fillMaxHeight().clip(CircleShape).background(categoryColor(c))) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    nonEmpty.forEach { c ->
                        Row {
                            Swatch(c)
                            Text(" ${c.label} ${formatSize(bytes.getValue(c))}", Modifier.alignByBaseline(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Legend color square sitting on the text baseline, so it lines up with the capital letters next to it. */
@Composable
private fun RowScope.Swatch(c: FileCategory) =
    Box(Modifier.alignBy { it.measuredHeight }.size(8.dp).clip(CircleShape).background(categoryColor(c)))

private fun pct(part: Long, total: Long) = if (total == 0L) "0%" else "%.1f%%".format(part * 100.0 / total)

// ---------- File browser (Home + Files) ----------

private sealed interface Entry {
    val key: String
    val name: String

    data class Dir(val f: Folder) : Entry {
        override val key get() = "d" + f.id
        override val name get() = f.name
    }

    data class Doc(val f: DriveFile) : Entry {
        override val key get() = "f" + f.id
        override val name get() = f.name
    }
}

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class RenameFile(val f: DriveFile) : Dialog
    data class RenameFolder(val f: Folder) : Dialog
    data class Move(val files: List<DriveFile>, val folders: List<Folder>) : Dialog
}

/**
 * [home] = compact storage card in the header; otherwise search + type filters.
 * [fixed] = "fav" / "recent": a dedicated Favorites / Recent screen (no folder navigation).
 */
@Composable
fun FileBrowser(model: AppModel, service: DriveService, home: Boolean, fixed: String? = null) {
    val dao = model.db.dao()
    val scope = rememberCoroutineScope()
    val grid by model.gridView.collectAsState()
    val sort by model.sort.collectAsState()
    var path by (if (home) model.homePath else model.filesPath) // lives in the model so a layout switch (desktop ↔ phone) keeps the folder
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<FileCategory?>(null) }
    val special = fixed // "fav" | "recent"
    var age by remember { mutableStateOf(Age.ANY) }
    var sizeRange by remember { mutableStateOf(SizeRange.ANY) }
    var inFolder by remember { mutableStateOf(false) }
    var versionsOf by remember { mutableStateOf<DriveFile?>(null) }
    var shareOf by remember { mutableStateOf<DriveFile?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var anchor by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val parentId = path.lastOrNull()?.id
    val searching = !home && (query.isNotBlank() || filter != null || special != null || age != Age.ANY || sizeRange != SizeRange.ANY)
    // null = not loaded yet. Never show "empty" before the database has answered (that caused a flash on every screen switch).
    val folders by remember(parentId, query, searching, special) {
        if (special != null) kotlinx.coroutines.flow.flowOf(emptyList()) else if (searching) dao.searchFolders(service.drive.id, query.trim()) else dao.folders(service.drive.id, parentId)
    }.collectAsState(null as List<Folder>?)
    val rawFiles by remember(parentId, query, searching, special) {
        if (special == "fav") dao.favoriteFiles(service.drive.id) else if (special == "recent") dao.recentFiles(service.drive.id) else if (searching) dao.searchFiles(service.drive.id, query.trim()) else dao.files(service.drive.id, parentId)
    }.collectAsState(null as List<DriveFile>?)
    val loaded = folders != null && rawFiles != null
    val allFolders by remember { dao.activeFolders(service.drive.id) }.collectAsState(emptyList())
    val transfers by remember { dao.transfers(service.drive.id) }.collectAsState(emptyList())
    // Progress ticks arrive many times per second; ~3 redraws/s is plenty for a progress bar.
    val live by remember(service) { service.live.sample(300) }.collectAsState(emptyMap())
    val pausedUntil by service.pausedUntil.collectAsState()
    val uploadOf = remember(transfers) { transfers.filter { it.direction.name == "UPLOAD" }.groupBy { it.fileId }.mapValues { it.value.first() } }

    // Advanced search: type, age, size, and optionally only inside the current folder (and its subfolders).
    val subtree = remember(inFolder, path, allFolders, searching) {
        if (!searching || !inFolder || path.isEmpty()) null
        else {
            val kids = allFolders.groupBy { it.parentId }
            val out = HashSet<String>()
            fun collect(id: String) { out += id; kids[id].orEmpty().forEach { collect(it.id) } }
            collect(path.last().id)
            out
        }
    }
    val files = remember(rawFiles, filter, sort, age, sizeRange, subtree, special) {
        val since = age.since()
        val kept = rawFiles.orEmpty().filter {
            (filter == null || it.category == filter) && it.updatedAt >= since && it.size in sizeRange.range && (subtree == null || it.folderId in subtree)
        }
        if (special == "recent") kept else sortFiles(kept, sort.first, sort.second) // Recent stays newest first
    }
    val shownFolders = remember(folders, filter, sort, age, sizeRange, subtree) {
        if (filter != null || age != Age.ANY || sizeRange != SizeRange.ANY) emptyList()
        else sortFolders(folders.orEmpty().filter { subtree == null || it.id in subtree }, sort.first, sort.second)
    }
    val entries: List<Entry> = remember(shownFolders, files) { shownFolders.map { Entry.Dir(it) } + files.map { Entry.Doc(it) } }
    val selFiles = files.filter { "f" + it.id in selected }
    val selFolders = shownFolders.filter { "d" + it.id in selected }
    val target = if (searching) null else parentId
    val targetName = path.lastOrNull()?.name ?: service.drive.name

    fun clearSelection() { selected = emptySet() }

    fun open(f: Folder) {
        val byId = allFolders.associateBy { it.id }
        path = generateSequence(f) { it.parentId?.let(byId::get) }.toList().reversed()
        query = ""; filter = null; clearSelection()
    }

    fun open(e: Entry) = when (e) {
        is Entry.Dir -> open(e.f)
        is Entry.Doc -> model.open(files, files.indexOf(e.f).coerceAtLeast(0))
    }

    fun upload(paths: List<File>, into: Folder? = null) = scope.launch {
        if (paths.isEmpty()) return@launch
        service.enqueueUploads(paths, into?.id ?: target)
        donat.teledrive.Log.i("upload ${paths.size} item(s) into “${into?.name ?: targetName}” (${if (into != null) "dropped on folder card" else "current folder"})")
        model.notify("Queued ${paths.size} item(s) for upload to ${into?.name ?: targetName}")
    }

    fun trash(fs: List<DriveFile>, ds: List<Folder>) {
        if (fs.isEmpty() && ds.isEmpty()) return
        clearSelection()
        model.task("Moving to trash…") {
            service.trash(fs, ds)
            model.notify("Moved ${fs.size + ds.size} item(s) to trash") { service.restore(fs, ds) }
        }
    }

    fun press(index: Int, e: Entry, ctrl: Boolean, shift: Boolean, clicks: Int) {
        focus.requestFocus()
        when {
            clicks >= 2 -> open(e)
            ctrl -> { selected = if (e.key in selected) selected - e.key else selected + e.key; anchor = index }
            shift -> selected = entries.slice(minOf(anchor, index)..maxOf(anchor, index)).map { it.key }.toSet()
            else -> { selected = setOf(e.key); anchor = index }
        }
    }

    fun menu(e: Entry): List<ContextMenuItem> {
        if (e.key in selected && selected.size > 1) return listOf(
            ContextMenuItem("Download ${selected.size} items…") { model.download(selFiles.filter { it.isSaved }, selFolders) },
            ContextMenuItem("Move ${selected.size} items to…") { dialog = Dialog.Move(selFiles, selFolders) },
            ContextMenuItem("Move ${selected.size} items to trash") { trash(selFiles, selFolders) },
        )
        return when (e) {
            is Entry.Dir -> listOf(
                ContextMenuItem("Open") { open(e.f) },
                ContextMenuItem("Download…") { model.download(emptyList(), listOf(e.f)) },
                ContextMenuItem("Rename (F2)") { dialog = Dialog.RenameFolder(e.f) },
                ContextMenuItem("Move to…") { dialog = Dialog.Move(emptyList(), listOf(e.f)) },
                ContextMenuItem("Move to trash (Del)") { trash(emptyList(), listOf(e.f)) },
            )
            is Entry.Doc -> buildList {
                val f = e.f
                add(ContextMenuItem("Open") { open(e) })
                if (f.isSaved) add(ContextMenuItem("Download…") { model.download(listOf(f)) })
                add(ContextMenuItem("Rename (F2)") { dialog = Dialog.RenameFile(f) })
                add(ContextMenuItem("Move to…") { dialog = Dialog.Move(listOf(f), emptyList()) })
                add(ContextMenuItem(if (f.favorite) "Remove from favorites" else "Add to favorites") { model.task("Saving…") { service.setFavorite(f, !f.favorite) } })
                if (f.safety == SafetyState.CONFLICT) add(ContextMenuItem("Mark conflict resolved") { model.task("Saving…") { service.resolveConflict(f) } })
                if (f.isSaved || f.safety == SafetyState.ERROR) add(ContextMenuItem("Verify integrity") {
                    model.task("Verifying ${f.name}…") { model.notify(if (service.verify(f)) "${f.name} verified: identical to the original" else "${f.name} is damaged in Telegram") }
                })
                add(ContextMenuItem("Version history…") { versionsOf = f })
                if (f.isSaved) add(ContextMenuItem("Share link (local network)…") { shareOf = f })
                add(ContextMenuItem("Move to trash (Del)") { trash(listOf(f), emptyList()) })
            }
        }
    }

    // Drag an item (or the whole selection) onto a folder to move it there.
    var dragKeys by remember { mutableStateOf(emptySet<String>()) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dropKey by remember { mutableStateOf<String?>(null) }
    var dropName by remember { mutableStateOf<String?>(null) } // folder under an Explorer drag
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { HashMap<String, Rect>() }
    val entriesNow by rememberUpdatedState(entries)
    fun Modifier.movable(e: Entry) = onGloballyPositioned { bounds[e.key] = it.boundsInRoot() }
        .pointerInput(e.key) {
            detectDragGestures(
                onDragStart = { start ->
                    dragKeys = if (e.key in selected) selected else setOf(e.key)
                    dragPos = (bounds[e.key]?.topLeft ?: Offset.Zero) + start
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragPos += amount
                    dropKey = entriesNow.firstOrNull { it is Entry.Dir && it.key !in dragKeys && bounds[it.key]?.contains(dragPos) == true }?.key
                },
                onDragEnd = {
                    val target = entriesNow.firstOrNull { it.key == dropKey } as? Entry.Dir
                    val keys = dragKeys
                    dragKeys = emptySet(); dropKey = null
                    if (target != null) {
                        val fs = entriesNow.filterIsInstance<Entry.Doc>().filter { it.key in keys }.map { it.f }
                        val ds = entriesNow.filterIsInstance<Entry.Dir>().filter { it.key in keys }.map { it.f }
                        clearSelection()
                        model.task("Moving…") { service.move(fs, ds, target.f.id); model.notify("Moved ${fs.size + ds.size} item(s) to “${target.f.name}”") }
                    }
                },
                onDragCancel = { dragKeys = emptySet(); dropKey = null },
            )
        }
    val item: @Composable (Entry, @Composable () -> Unit) -> Unit = { e, content ->
        // A folder card is its own drop target: files from Explorer dropped on it go INTO that folder.
        val folderDrop = (e as? Entry.Dir)?.let { d ->
            remember(d.f.id) {
                object : DragAndDropTarget {
                    override fun onEntered(event: DragAndDropEvent) { dragging = true; dropKey = d.key; dropName = d.f.name }
                    override fun onExited(event: DragAndDropEvent) { if (dropKey == d.key) { dropKey = null; dropName = null } }
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        dragging = false; dropKey = null; dropName = null
                        upload(droppedFiles(event), d.f)
                        return true
                    }
                }
            }
        }
        val dropModifier = if (folderDrop != null) Modifier.dragAndDropTarget(shouldStartDragAndDrop = { isFileDrag(it) }, target = folderDrop) else Modifier
        // Scrolled-away items keep no stale bounds, or a drag could "hit" a folder that is no longer under the cursor.
        DisposableEffect(e.key) { onDispose { bounds.remove(e.key) } }
        ContextMenuArea(items = { menu(e) }) { Box(Modifier.movable(e).then(dropModifier)) { content() } }
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            // Files dragged in from Explorer onto empty space → the open folder (folder cards have their own target, see `item`).
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEnded(event: DragAndDropEvent) { dragging = false; dropKey = null; dropName = null }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                upload(droppedFiles(event))
                return true
            }
        }
    }

    BoxWithConstraints {
        val narrow = maxWidth < 760.dp
        val phone = maxWidth < 600.dp // Telegram-style list rows + floating Upload button

        // Toolbar actions; while something is selected the same slot shows selection actions (no layout jump).
        val actions: @Composable () -> Unit = {
            if (selected.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TipIcon(Icons.Filled.Close, "Clear selection (Esc)", tint = MaterialTheme.colorScheme.onSecondaryContainer) { clearSelection() }
                        Text("${selected.size} selected", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                        TipIcon(Icons.Filled.Download, "Download", tint = MaterialTheme.colorScheme.onSecondaryContainer) { model.download(selFiles.filter { it.isSaved }, selFolders) }
                        TipIcon(Icons.AutoMirrored.Filled.DriveFileMove, "Move to…", tint = MaterialTheme.colorScheme.onSecondaryContainer) { dialog = Dialog.Move(selFiles, selFolders) }
                        TipIcon(Icons.Filled.Delete, "Move to trash (Del)", tint = MaterialTheme.colorScheme.onSecondaryContainer) { trash(selFiles, selFolders) }
                        TipIcon(Icons.Filled.SelectAll, "Select all (Ctrl+A)", tint = MaterialTheme.colorScheme.onSecondaryContainer) { selected = entries.map { it.key }.toSet() }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SortButton(model, sort)
                    // Connected toggle (Android 16 button group): the active view sits on a filled pill.
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(2.dp)) {
                            listOf(true to Icons.Filled.GridView, false to Icons.AutoMirrored.Filled.ViewList).forEach { (g, icon) ->
                                val on = grid == g
                                Box(Modifier.clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                                    TipIcon(icon, if (g) "Grid view" else "Details view", tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, iconSize = 20) { model.setGrid(g) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TipIcon(Icons.Filled.CreateNewFolder, "New folder", enabled = !searching) { dialog = Dialog.NewFolder }
                    TipIcon(Icons.Filled.DriveFolderUpload, "Upload folder") { scope.launch { NativeDialogs.pickFolder()?.let { upload(listOf(it)) } } }
                    if (!phone) {
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { scope.launch { upload(NativeDialogs.openFiles()) } }) {
                            Icon(Icons.Filled.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Upload")
                        }
                    }
                }
            }
        }

        val breadcrumb: @Composable (Modifier) -> Unit = { m ->
            Row(m.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (fixed != null) {
                    Text(if (fixed == "fav") "Favorites" else "Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
                } else if (searching) {
                    Text("Search results", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
                } else {
                    TextButton(onClick = { path = emptyList(); clearSelection() }) { Text(service.drive.name, maxLines = 1) }
                    path.forEachIndexed { i, f -> Text("›"); TextButton(onClick = { path = path.take(i + 1); clearSelection() }) { Text(f.name, maxLines = 1) } }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val leading: @Composable (Modifier) -> Unit = if (home) { m -> CompactStorage(model, service, m) } else breadcrumb
            if (narrow) {
                leading(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) { actions() }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    leading(Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    actions()
                }
            }
            if (home) {
                if (path.isNotEmpty()) breadcrumb(Modifier.fillMaxWidth())
            } else if (fixed == null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactSearch(query, { query = it }, "Search all files…", Modifier.width(if (phone) 220.dp else 280.dp))
                    FilterChip(filter == null, { filter = null }, label = { Text("All") })
                    FileCategory.entries.forEach { c -> FilterChip(filter == c, { filter = if (filter == c) null else c }, label = { Text(c.label) }) }
                    ChoiceChip(age.label, Age.entries.map { it.label }) { age = Age.entries[it] }
                    ChoiceChip(sizeRange.label, SizeRange.entries.map { it.label }) { sizeRange = SizeRange.entries[it] }
                    if (path.isNotEmpty()) FilterChip(inFolder, { inFolder = !inFolder }, label = { Text("Only in “${path.last().name}”") })
                }
            }

            // Content: drop target, keyboard shortcuts, click on empty space clears the selection.
            Box(
                Modifier.fillMaxSize()
                    .dragAndDropTarget(shouldStartDragAndDrop = { isFileDrag(it) }, target = dropTarget)
                    .onGloballyPositioned { contentOrigin = it.positionInRoot() }
                    .focusRequester(focus)
                    .focusable()
                    .onPointerEvent(PointerEventType.Press) { ev -> if (ev.changes.none { it.isConsumed }) { clearSelection(); focus.requestFocus() } }
                    .onPreviewKeyEvent { k ->
                        if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val single = entries.singleOrNull { it.key in selected }
                        when {
                            k.isCtrlPressed && k.key == Key.A -> { selected = entries.map { it.key }.toSet(); true }
                            k.key == Key.Delete -> { trash(selFiles, selFolders); true }
                            k.key == Key.Escape -> { clearSelection(); true }
                            (k.key == Key.Enter || k.key == Key.NumPadEnter) && single != null -> { open(single); true }
                            k.key == Key.F2 && single != null -> {
                                dialog = when (single) { is Entry.Dir -> Dialog.RenameFolder(single.f); is Entry.Doc -> Dialog.RenameFile(single.f) }; true
                            }
                            (k.key == Key.Backspace || (k.isAltPressed && k.key == Key.DirectionUp)) && path.isNotEmpty() -> { path = path.dropLast(1); clearSelection(); true }
                            else -> false
                        }
                    },
            ) {
                val status: (DriveFile) -> FileStatus = { f -> fileStatus(f, uploadOf[f.id], live[uploadOf[f.id]?.id], pausedUntil) }
                if (!loaded) {
                    // still loading: draw nothing rather than a misleading "empty"
                } else if (entries.isEmpty()) {
                    EmptyState(
                        if (searching) "No matches" else "This folder is empty",
                        if (searching) "Try another name or type." else "Drag files or folders here, or use Upload. They are stored in your private Telegram channel.",
                    )
                } else if (grid) {
                    // Folders = compact one-line tiles (no empty thumbnail box), then files as cards; entries = folders first.
                    val nDirs = shownFolders.size
                    val labels = nDirs > 0 && files.isNotEmpty()
                    val section: androidx.compose.foundation.lazy.grid.LazyGridScope.(String) -> Unit = { label ->
                        this.item(key = "section-$label", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(label, Modifier.padding(start = 4.dp, top = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    LazyVerticalGrid(GridCells.Adaptive(if (phone) 130.dp else 156.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (labels) section("Folders")
                        items(nDirs, key = { entries[it].key }) { i ->
                            val e = entries[i] as Entry.Dir
                            item(e) { FolderTile(e.name, (e.key in selected || e.key == dropKey), selected.isNotEmpty(), { c, s, n -> press(i, e, c, s, n) }) }
                        }
                        if (labels) section("Files")
                        items(entries.size - nDirs, key = { entries[nDirs + it].key }) { j ->
                            val i = nDirs + j
                            val e = entries[i] as Entry.Doc
                            item(e) {
                                GridCard(e.name, formatSize(e.f.size), status(e.f), e.key in selected, selected.isNotEmpty(), { c, s, n -> press(i, e, c, s, n) }, e.f.favorite) {
                                    Thumb(service, e.f, 44.dp)
                                }
                            }
                        }
                    }
                } else if (phone) {
                    LazyColumn {
                        items(entries.size, key = { entries[it].key }) { i ->
                            val e = entries[i]
                            item(e) {
                                when (e) {
                                    is Entry.Dir -> ChatRow(e.name, null, "Folder", e.f.updatedAt, (e.key in selected || e.key == dropKey), { c, s, n -> press(i, e, c, s, n) }) {
                                        Avatar(MaterialTheme.colorScheme.primary) { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onPrimary) }
                                    }
                                    is Entry.Doc -> ChatRow(e.name, status(e.f), formatSize(e.f.size), e.f.updatedAt, (e.key in selected || e.key == dropKey), { c, s, n -> press(i, e, c, s, n) }, e.f.favorite) {
                                        Avatar(iconColor(e.f.category).copy(alpha = 0.2f)) { Thumb(service, e.f, 24.dp) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val cols = Columns(type = !narrow, modified = !narrow, status = true)
                    Column {
                        DetailsHeader(model, sort, cols)
                        LazyColumn {
                            items(entries.size, key = { entries[it].key }) { i ->
                                val e = entries[i]
                                item(e) {
                                    when (e) {
                                        is Entry.Dir -> DetailsRow(e.name, "Folder", "—", e.f.updatedAt, null, cols, (e.key in selected || e.key == dropKey), { c, s, n -> press(i, e, c, s, n) }) {
                                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                        is Entry.Doc -> DetailsRow(e.name, e.f.category.label, formatSize(e.f.size), e.f.updatedAt, status(e.f), cols, (e.key in selected || e.key == dropKey), { c, s, n -> press(i, e, c, s, n) }, e.f.favorite) {
                                            Icon(categoryIcon(e.f.category), null, tint = iconColor(e.f.category))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (dragKeys.isNotEmpty()) {
                    val p = dragPos - contentOrigin
                    Surface(
                        Modifier.offset { IntOffset(p.x.toInt() + 14, p.y.toInt() + 14) },
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary, shadowElevation = 6.dp,
                    ) {
                        Text(if (dropKey != null) "Move ${dragKeys.size} item(s) here" else "Drag onto a folder", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                if (phone && selected.isEmpty()) {
                    androidx.compose.material3.FloatingActionButton(
                        onClick = { scope.launch { upload(NativeDialogs.openFiles()) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Icon(Icons.Filled.Upload, "Upload files") }
                }
                if (dragging) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.CloudUpload, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Drop to upload to “${dropName ?: targetName}”", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text("Folders are uploaded with everything inside them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    versionsOf?.let { VersionsDialog(model, service, it) { versionsOf = null } }
    shareOf?.let { ShareDialog(model, it) { shareOf = null } }

    when (val d = dialog) {
        null -> Unit
        Dialog.NewFolder -> TextDialog("New folder", "", "Create", { dialog = null }) { name -> model.task("Creating folder…") { service.createFolder(name, parentId) } }
        is Dialog.RenameFile -> TextDialog("Rename", d.f.name, "Rename", { dialog = null }) { name -> model.task("Renaming…") { service.rename(d.f, name) } }
        is Dialog.RenameFolder -> TextDialog("Rename folder", d.f.name, "Rename", { dialog = null }) { name -> model.task("Renaming…") { service.rename(d.f, name) } }
        is Dialog.Move -> MoveDialog(allFolders, d.folders, { dialog = null }) { dest ->
            clearSelection()
            model.task("Moving…") { service.move(d.files, d.folders, dest); model.notify("Moved ${d.files.size + d.folders.size} item(s)") }
        }
    }
}

private enum class Age(val label: String, val days: Long) {
    ANY("Any time", -1), TODAY("Today", 0), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), YEAR("Last year", 365);

    fun since(): Long = if (days < 0) 0 else java.time.LocalDate.now().minusDays(days).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private enum class SizeRange(val label: String, val range: LongRange) {
    ANY("Any size", 0..Long.MAX_VALUE), SMALL("Under 1 MB", 0 until (1L shl 20)), MEDIUM("1–100 MB", (1L shl 20) until (100L shl 20)),
    LARGE("100 MB – 1 GB", (100L shl 20) until (1L shl 30)), HUGE("Over 1 GB", (1L shl 30)..Long.MAX_VALUE),
}

/** 40 dp search field (a regular OutlinedTextField is at least 56 dp — too tall next to 32 dp chips). Same Material look. */
@Composable
private fun CompactSearch(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors()
    androidx.compose.foundation.text.BasicTextField(
        value, onValueChange, modifier.height(40.dp), singleLine = true, interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
    ) { inner ->
        androidx.compose.material3.OutlinedTextFieldDefaults.DecorationBox(
            value = value, innerTextField = inner, enabled = true, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None, interactionSource = interaction,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(20.dp)) },
            colors = colors,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            container = {
                androidx.compose.material3.OutlinedTextFieldDefaults.Container(
                    enabled = true, isError = false, interactionSource = interaction, colors = colors, shape = RoundedCornerShape(8.dp),
                )
            },
        )
    }
}

/** Chip that opens a small menu of mutually exclusive options. */
@Composable
private fun ChoiceChip(label: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(label != options.first(), { open = true }, label = { Text("$label ▾") })
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

private fun isFileDrag(e: DragAndDropEvent) = e.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

@Suppress("UNCHECKED_CAST")
private fun droppedFiles(e: DragAndDropEvent) = e.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>

private fun sortFiles(list: List<DriveFile>, by: SortBy, asc: Boolean): List<DriveFile> {
    val sorted = when (by) {
        SortBy.NAME -> list.sortedBy { it.name.lowercase() }
        SortBy.MODIFIED -> list.sortedBy { it.updatedAt }
        SortBy.SIZE -> list.sortedBy { it.size }
        SortBy.TYPE -> list.sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))
    }
    return if (asc) sorted else sorted.reversed()
}

private fun sortFolders(list: List<Folder>, by: SortBy, asc: Boolean): List<Folder> {
    val sorted = if (by == SortBy.MODIFIED) list.sortedBy { it.updatedAt } else list.sortedBy { it.name.lowercase() }
    return if (asc) sorted else sorted.reversed()
}

@Composable
private fun SortButton(model: AppModel, sort: Pair<SortBy, Boolean>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TipIcon(Icons.AutoMirrored.Filled.Sort, "Sort: ${sort.first.label} ${if (sort.second) "↑" else "↓"}") { open = true }
        DropdownMenu(open, { open = false }) {
            SortBy.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label + if (s == sort.first) (if (sort.second) "  ↑" else "  ↓") else "") },
                    onClick = { open = false; model.setSort(s, if (s == sort.first) !sort.second else true) },
                )
            }
        }
    }
}

// ---------- Status ----------

data class FileStatus(val text: String, val kind: Kind, val progress: Float? = null) {
    enum class Kind { OK, BUSY, WAIT, BAD }
}

/** One accurate sentence about where a file stands, derived from its upload transfer + live progress. */
fun fileStatus(f: DriveFile, t: Transfer?, l: Live?, pausedUntil: Long): FileStatus = when (f.safety) {
    SafetyState.VERIFIED -> FileStatus("Verified", FileStatus.Kind.OK)
    SafetyState.REMOTE_SAFE -> FileStatus("Saved", FileStatus.Kind.OK)
    SafetyState.LOCAL_ONLY -> FileStatus("Not uploaded" + (t?.error?.let { " · $it" } ?: ""), FileStatus.Kind.BAD)
    SafetyState.CONFLICT -> FileStatus("Saved · changed on two PCs (right-click to resolve)", FileStatus.Kind.BAD)
    SafetyState.ERROR -> FileStatus("Error", FileStatus.Kind.BAD)
    SafetyState.SYNCING -> FileStatus("Syncing", FileStatus.Kind.BUSY)
    SafetyState.UPLOADING -> if (t == null) FileStatus("Uploading", FileStatus.Kind.BUSY) else transferStatus(t, l, pausedUntil)
}

fun transferStatus(t: Transfer, l: Live?, pausedUntil: Long): FileStatus {
    val now = System.currentTimeMillis()
    val frac = if (t.bytesTotal > 0) ((l?.bytes ?: t.bytesDone).toFloat() / t.bytesTotal).coerceIn(0f, 1f) else null
    return when (t.state) {
        TransferState.QUEUED -> FileStatus(if (pausedUntil > now) "Queued · Telegram rate limit until ${clock(pausedUntil)}" else "Queued", FileStatus.Kind.WAIT, frac)
        TransferState.PREPARING, TransferState.RUNNING -> {
            if (l == null) return FileStatus(if (t.state == TransferState.PREPARING) "Preparing" else "Working", FileStatus.Kind.BUSY, frac)
            val speed = if (l.bytesPerSec > 0) " · ${formatSize(l.bytesPerSec)}/s" else ""
            val eta = if (l.bytesPerSec > 0 && t.bytesTotal > l.bytes) " · ${duration((t.bytesTotal - l.bytes) / l.bytesPerSec)} left" else ""
            val pctText = if (l.phase.startsWith("Uploading") || l.phase.startsWith("Downloading")) " ${((frac ?: 0f) * 100).toInt()}%" else ""
            FileStatus(l.phase + pctText + speed + eta, FileStatus.Kind.BUSY, frac)
        }
        TransferState.RETRY_WAIT -> FileStatus("Waiting · retry at ${clock(t.nextRetryAt ?: now)}" + (t.error?.let { " · $it" } ?: ""), FileStatus.Kind.WAIT, frac)
        TransferState.PAUSED -> FileStatus("Paused", FileStatus.Kind.WAIT, frac)
        TransferState.FAILED -> FileStatus("Failed · ${t.error ?: "unknown error"}", FileStatus.Kind.BAD)
        TransferState.COMPLETED -> FileStatus("Done", FileStatus.Kind.OK)
        TransferState.CANCELLED -> FileStatus("Cancelled", FileStatus.Kind.WAIT)
    }
}

fun duration(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
fun StatusText(s: FileStatus, maxLines: Int = 1) {
    val color = when (s.kind) {
        FileStatus.Kind.OK -> if (isDarkUi()) Color(0xFF0CA30C) else Color(0xFF006300)
        FileStatus.Kind.BUSY -> MaterialTheme.colorScheme.primary
        FileStatus.Kind.WAIT -> MaterialTheme.colorScheme.onSurfaceVariant
        FileStatus.Kind.BAD -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.kind == FileStatus.Kind.OK) Icon(Icons.Filled.CloudDone, null, Modifier.size(14.dp).padding(end = 2.dp), tint = color)
        if (s.kind == FileStatus.Kind.BAD) Icon(Icons.Filled.Warning, null, Modifier.size(14.dp).padding(end = 2.dp), tint = color)
        Text(s.text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Grid / details items ----------

/** Explorer-style press: click selects, Ctrl/Shift extend, double-click opens. Consumed so the background doesn't clear it. */
private fun Modifier.explorerPress(onPress: (ctrl: Boolean, shift: Boolean, clicks: Int) -> Unit) =
    onPointerEvent(PointerEventType.Press) { ev ->
        val awt = ev.nativeEvent as? java.awt.event.MouseEvent ?: return@onPointerEvent
        if (awt.button != java.awt.event.MouseEvent.BUTTON1 || ev.changes.any { it.isConsumed }) return@onPointerEvent
        onPress(awt.isControlDown, awt.isShiftDown, awt.clickCount)
        ev.changes.forEach { it.consume() }
    }

/** The checkbox toggles without disturbing the rest of the selection (like Ctrl+click). */
@Composable
private fun SelectBox(selected: Boolean, visible: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    // Fixed-size slot so rows don't shift when the checkbox appears.
    Box(modifier.size(36.dp).onPointerEvent(PointerEventType.Press) { ev -> if (visible) { onToggle(); ev.changes.forEach { it.consume() } } }, contentAlignment = Alignment.Center) {
        if (visible) Checkbox(selected, onCheckedChange = null)
    }
}

/** Full name on hover (names are cut to one line everywhere). */
@Composable
private fun NameText(name: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) =
    TooltipArea(tooltip = { Surface(shape = RoundedCornerShape(8.dp), shadowElevation = 4.dp) { Text(name, Modifier.padding(8.dp)) } }, modifier = modifier) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, fontWeight = FontWeight.Medium)
    }

/** Grid folder: one 56 dp line (icon, name) — the checkbox takes the icon's place on hover or while selecting. */
@Composable
private fun FolderTile(name: String, selected: Boolean, selecting: Boolean, onPress: (Boolean, Boolean, Int) -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Surface(
        Modifier.fillMaxWidth().height(56.dp).hoverable(hover).explorerPress(onPress),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hovered || selecting) SelectBox(selected, true, { onPress(true, false, 1) })
            else Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            NameText(name, MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GridCard(
    name: String, sub: String, status: FileStatus, selected: Boolean, selecting: Boolean,
    onPress: (Boolean, Boolean, Int) -> Unit, favorite: Boolean = false, icon: @Composable () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Card(
        Modifier.fillMaxWidth().hoverable(hover).explorerPress(onPress),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        // Inset, rounded preview (the card's 20 dp corner minus the 6 dp inset).
        Box(Modifier.padding(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { icon() }
            SelectBox(selected, hovered || selecting, { onPress(true, false, 1) }, Modifier.align(Alignment.TopStart))
            if (favorite) Icon(Icons.Filled.Star, "Favorite", Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp), tint = Color(0xFFEDA100))
        }
        Column(Modifier.padding(start = 12.dp, end = 10.dp, bottom = 10.dp)) {
            NameText(name, MaterialTheme.typography.bodyMedium)
            // Size and status on one line: one row shorter than before, nothing lost.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$sub · ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Box(Modifier.weight(1f, fill = false)) { StatusText(status) }
            }
            if (status.kind != FileStatus.Kind.OK && status.progress != null) LinearProgressIndicator({ status.progress }, Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape))
        }
    }
}

/** Which optional columns fit the current width. */
private data class Columns(val type: Boolean, val modified: Boolean, val status: Boolean)

@Composable
private fun DetailsHeader(model: AppModel, sort: Pair<SortBy, Boolean>, cols: Columns) {
    @Composable
    fun RowScope.Col(label: String, by: SortBy?, weight: Float) {
        val arrow = if (by == sort.first) (if (sort.second) " ↑" else " ↓") else ""
        Text(
            label + arrow,
            Modifier.weight(weight).then(if (by != null) Modifier.clickable { model.setSort(by, if (by == sort.first) !sort.second else true) } else Modifier).padding(vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(72.dp))
        Col("Name", SortBy.NAME, 3f)
        if (cols.type) Col("Type", SortBy.TYPE, 1f)
        Col("Size", SortBy.SIZE, 1f)
        if (cols.modified) Col("Modified", SortBy.MODIFIED, 1.4f)
        if (cols.status) Col("Status", null, 2.4f)
    }
    HorizontalDivider()
}

@Composable
private fun DetailsRow(
    name: String, type: String, size: String, modified: Long, status: FileStatus?, cols: Columns, selected: Boolean,
    onPress: (Boolean, Boolean, Int) -> Unit, favorite: Boolean = false, icon: @Composable () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val small = MaterialTheme.typography.bodySmall
    Row(
        Modifier.fillMaxWidth().hoverable(hover).explorerPress(onPress).clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.secondaryContainer
                    hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
            ).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectBox(selected, hovered || selected, { onPress(true, false, 1) })
        Box(Modifier.width(32.dp)) { icon() }
        Column(Modifier.weight(3f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (favorite) Icon(Icons.Filled.Star, "Favorite", Modifier.size(16.dp).padding(start = 4.dp), tint = Color(0xFFEDA100))
            }
            if (!cols.status && status != null) StatusText(status) // narrow: status moves under the name
        }
        if (cols.type) Text(type, Modifier.weight(1f), style = small, maxLines = 1)
        Text(size, Modifier.weight(1f), style = small, maxLines = 1)
        if (cols.modified) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(modified)), Modifier.weight(1.4f), style = small, maxLines = 1)
        if (cols.status) {
            Column(Modifier.weight(2.4f)) {
                if (status != null) {
                    StatusText(status)
                    if (status.kind != FileStatus.Kind.OK && status.progress != null) LinearProgressIndicator({ status.progress }, Modifier.fillMaxWidth().padding(top = 2.dp, end = 8.dp))
                }
            }
        }
    }
}

/** Phone-width list row in the style of Telegram's chat list: round avatar, bold name, one status line, date on the right. */
@Composable
private fun ChatRow(
    name: String, status: FileStatus?, sub: String, modified: Long, selected: Boolean,
    onPress: (Boolean, Boolean, Int) -> Unit, favorite: Boolean = false, avatar: @Composable () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column {
        Row(
            Modifier.fillMaxWidth().height(68.dp).hoverable(hover).explorerPress(onPress).clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.secondaryContainer
                        hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        else -> Color.Transparent
                    },
                ).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tapping the avatar toggles selection (Telegram uses long-press; a desktop has no long-press).
            Box(Modifier.size(48.dp).onPointerEvent(PointerEventType.Press) { ev -> onPress(true, false, 1); ev.changes.forEach { it.consume() } }) {
                avatar()
                if (selected) {
                    Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (favorite) Icon(Icons.Filled.Star, "Favorite", Modifier.size(14.dp), tint = Color(0xFFEDA100))
                    Text(shortDate(modified), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    if (status != null) { Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant); Box(Modifier.weight(1f)) { StatusText(status) } }
                }
                if (status != null && status.kind != FileStatus.Kind.OK && status.progress != null) LinearProgressIndicator({ status.progress }, Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun Avatar(background: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) { content() }
}

/** Telegram-style timestamp: time for today, date otherwise. */
private fun shortDate(ms: Long): String {
    val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    return when {
        d.toLocalDate() == today -> d.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        d.year == today.year -> d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))
    }
}

/** Decoded thumbnails kept in memory, so scrolling back doesn't decode again (LRU, ~500 small images). */
private object ThumbCache {
    private val map = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 500
    }

    @Synchronized operator fun get(id: String): ImageBitmap? = map[id]

    @Synchronized operator fun set(id: String, b: ImageBitmap) { map[id] = b }
}

@Composable
fun Thumb(service: DriveService, f: DriveFile, iconSize: Dp) {
    val bmp by produceState(ThumbCache[f.id], f.id, f.safety) {
        if (value == null && f.isSaved) value = withContext(Dispatchers.IO) { service.thumbnail(f)?.let { decodeImage(it) } }?.also { ThumbCache[f.id] = it }
    }
    val b = bmp
    if (b != null) Image(b, f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Icon(categoryIcon(f.category), null, Modifier.size(iconSize), tint = iconColor(f.category))
}

private fun decodeImage(file: File): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()

// ---------- Dialogs ----------

@Composable
private fun TextDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AppDialog(title, onDismiss, action, { onDismiss(); onConfirm(value) }, enabled = value.isNotBlank() && value != initial, focusContent = false) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.focusRequester(focus))
    }
}

@Composable
private fun MoveDialog(all: List<Folder>, moving: List<Folder>, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    val children = all.groupBy { it.parentId }
    val blocked = mutableSetOf<String>().apply {
        fun add(f: Folder) { add(f.id); children[f.id].orEmpty().forEach { add(it) } }
        moving.forEach { add(it) }
    }
    val rows = mutableListOf<Pair<Folder, Int>>().apply {
        fun walk(parent: String?, depth: Int) { children[parent].orEmpty().filter { it.id !in blocked }.sortedBy { it.name.lowercase() }.forEach { add(it to depth); walk(it.id, depth + 1) } }
        walk(null, 0)
    }
    var target by remember { mutableStateOf<String?>(null) }
    AppDialog("Move to", onDismiss, "Move here", { onDismiss(); onConfirm(target) }) {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clickable { target = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(target == null, { target = null }); Text("My Drive (top level)")
                }
            }
            items(rows, key = { it.first.id }) { (f, depth) ->
                Row(Modifier.fillMaxWidth().clickable { target = f.id }.padding(start = (depth * 20).dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(target == f.id, { target = f.id }); Icon(Icons.Filled.Folder, null, Modifier.padding(end = 6.dp)); Text(f.name)
                }
            }
        }
    }
}

// ---------- Trash ----------

@Composable
fun TrashScreen(model: AppModel, service: DriveService) {
    val dao = model.db.dao()
    val trashedFiles by remember { dao.trashedFiles(service.drive.id) }.collectAsState(null as List<DriveFile>?)
    val trashedFolders by remember { dao.trashedFolders(service.drive.id) }.collectAsState(null as List<Folder>?)
    if (trashedFiles == null || trashedFolders == null) return // loading
    val files = trashedFiles.orEmpty()
    val folders = trashedFolders.orEmpty()
    var confirm by remember { mutableStateOf<Pair<List<DriveFile>, List<Folder>>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trash", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
            OutlinedButton(onClick = { model.task("Restoring…") { service.restore(files, folders) } }, enabled = files.isNotEmpty() || folders.isNotEmpty()) { Text("Restore all") }
            OutlinedButton(onClick = { confirm = files to folders }, enabled = files.isNotEmpty() || folders.isNotEmpty()) { Text("Empty trash") }
        }
        if (files.isEmpty() && folders.isEmpty()) return@Column EmptyState("Trash is empty", "Deleted files stay here and can be restored until you delete them forever.")
        Hint("Items here still use space in Telegram. “Delete forever” removes them from the drive and the channel.")
        LazyColumn {
            items(folders, key = { "d" + it.id }) { f ->
                TrashRow(f.name, "Folder", f.trashedAt, Icons.Filled.Folder, { model.task("Restoring…") { service.restore(emptyList(), listOf(f)) } }) { confirm = emptyList<DriveFile>() to listOf(f) }
            }
            items(files, key = { "f" + it.id }) { f ->
                TrashRow(f.name, formatSize(f.size), f.trashedAt, categoryIcon(f.category), { model.task("Restoring…") { service.restore(listOf(f), emptyList()) } }) { confirm = listOf(f) to emptyList() }
            }
        }
    }
    confirm?.let { (fs, ds) ->
        AppDialog("Delete forever?", { confirm = null }, "Delete forever", { confirm = null; model.task("Deleting forever…") { service.purge(fs, ds) } }) {
            Text("${fs.size + ds.size} item(s) will be removed from the drive and their messages deleted from Telegram. This can't be undone.")
        }
    }
}

@Composable
private fun TrashRow(name: String, sub: String, trashedAt: Long?, icon: ImageVector, onRestore: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val deleted = trashedAt?.let { " · deleted " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }.orEmpty()
            Text(sub + deleted, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onRestore) { Text("Restore") }
        TextButton(onClick = onDelete) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
    }
}
