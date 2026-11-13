@file:OptIn(ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package donat.teledrive.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import donat.teledrive.data.Transfer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import donat.teledrive.AppModel
import donat.teledrive.DriveService
import donat.teledrive.core.Direction
import donat.teledrive.core.TransferState.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.File

private enum class Tab(val label: String) { Active("Active"), Done("Completed"), Problems("Failed / cancelled") }

@Composable
fun TransfersScreen(model: AppModel, service: DriveService) {
    val loadedTransfers by remember(service) { model.db.dao().transfers(service.drive.id) }.collectAsState(null as List<donat.teledrive.data.Transfer>?)
    val transfers = loadedTransfers ?: return // loading: draw nothing rather than "Nothing here"
    val live by remember(service) { service.live.sample(300) }.collectAsState(emptyMap())
    val pausedUntil by service.pausedUntil.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.Active) }
    // Re-render every second so countdowns ("retry at…") stay truthful.
    val nowMs by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }

    val groups = transfers.groupBy {
        when (it.state) {
            COMPLETED -> Tab.Done
            FAILED, CANCELLED -> Tab.Problems
            else -> Tab.Active
        }
    }
    val active = groups[Tab.Active].orEmpty()
    // Uploaded parts waiting for the batched manifest are still RUNNING in the DB, but they no longer use a transfer slot.
    val saving = active.count { live[it.id]?.phase?.startsWith("Uploaded") == true }
    val running = active.count { it.state == RUNNING || it.state == PREPARING } - saving

    // One centered column: header, tabs and rows share the same width, so the actions line up with the progress lines.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).widthIn(min = 240.dp)) {
                    Text("Transfers", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        listOf(
                            "$running of ${DriveService.PARALLEL} running" to true, "$saving saving" to (saving > 0),
                            "${active.count { it.state == QUEUED }} queued" to true, "${active.count { it.state == RETRY_WAIT }} retrying" to (active.any { it.state == RETRY_WAIT }),
                            "${active.count { it.state == PAUSED }} paused" to (active.any { it.state == PAUSED }),
                        ).filter { it.second }.joinToString(" · ") { it.first },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TipIcon(Icons.Filled.Pause, "Pause all", enabled = active.isNotEmpty()) { scope.launch { service.pauseAll() } }
                TipIcon(Icons.Filled.PlayArrow, "Resume all", enabled = transfers.any { it.state == PAUSED || it.state == FAILED }) { scope.launch { service.resumeAll() } }
                TipIcon(Icons.Filled.ClearAll, "Clear finished", enabled = transfers.any { it.state == COMPLETED || it.state == CANCELLED }) { scope.launch { service.clearFinished() } }
            }
            if (pausedUntil > nowMs) {
                ErrorText("Telegram asked us to slow down (FLOOD_WAIT). Nothing new starts until ${donat.teledrive.clock(pausedUntil)}; everything resumes automatically.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tab.entries.forEach { t -> FilterChip(tab == t, { tab = t }, label = { Text("${t.label} (${groups[t].orEmpty().size})") }) }
            }
            val shown = groups[tab].orEmpty()
            if (shown.isEmpty()) return@Column EmptyState("Nothing here", "Uploads and downloads show here with their exact state.")
            LazyColumn {
                items(shown, key = { it.id }) { t -> TransferRow(t, transferStatus(t, live[t.id], pausedUntil), live[t.id]?.bytes, service, scope) }
            }
        }
    }
}

@Composable
private fun TransferRow(t: Transfer, s: FileStatus, liveBytes: Long?, service: DriveService, scope: kotlinx.coroutines.CoroutineScope) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (t.direction == Direction.UPLOAD) Icons.Filled.Upload else Icons.Filled.Download, t.direction.name,
            Modifier.padding(end = 10.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipArea(tooltip = { Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) { Text(t.localPath, Modifier.padding(8.dp)) } }, Modifier.weight(1f)) {
                    Text(File(t.localPath).name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "${formatSize(liveBytes ?: t.bytesDone)} / ${formatSize(t.bytesTotal)}" + (if (t.retryCount > 0) " · try ${t.retryCount + 1}" else ""),
                    Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (t.state != COMPLETED && s.progress != null) LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth().padding(vertical = 3.dp))
            StatusText(s)
        }
        // Actions sit right at the end of the progress line.
        if (t.state == COMPLETED && t.direction == Direction.DOWNLOAD && File(t.localPath).exists()) {
            TipIcon(Icons.Filled.FolderOpen, "Show in folder", iconSize = 18) { ProcessBuilder("explorer.exe", "/select,", t.localPath).start() }
        }
        when (t.state) {
            PAUSED, FAILED -> TipIcon(Icons.Filled.PlayArrow, if (t.state == FAILED) "Retry" else "Resume", iconSize = 18) { scope.launch { service.resume(t) } }
            QUEUED, PREPARING, RUNNING, RETRY_WAIT -> TipIcon(Icons.Filled.Pause, "Pause", iconSize = 18) { scope.launch { service.pause(t) } }
            else -> Unit
        }
        if (!t.state.isTerminal) TipIcon(Icons.Filled.Close, "Cancel", tint = MaterialTheme.colorScheme.error, iconSize = 18) { scope.launch { service.cancel(t) } }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
