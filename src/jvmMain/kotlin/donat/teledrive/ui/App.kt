@file:OptIn(ExperimentalLayoutApi::class)

package donat.teledrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Star
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import donat.teledrive.APP_NAME
import donat.teledrive.AppModel
import donat.teledrive.DriveService
import donat.teledrive.clock
import donat.teledrive.telegram.AuthState
import donat.teledrive.telegram.Country
import androidx.compose.foundation.layout.heightIn

enum class Screen(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Files("Files", Icons.AutoMirrored.Filled.List),
    Gallery("Gallery", Icons.Filled.PhotoLibrary),
    Favorites("Favorites", Icons.Filled.Star),
    Recent("Recent", Icons.Filled.History),
    Insights("Insights", Icons.Filled.PieChart),
    Transfers("Transfers", Icons.Filled.SwapVert),
    Trash("Trash", Icons.Filled.Delete),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun App(model: AppModel) {
    val theme by model.theme.collectAsState()
    MaterialTheme(colorScheme = colorsFor(theme), shapes = AppShapes) {
        // Tinted backdrop; screens put their content on rounded `surface` sheets on top of it (Android 16 style).
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            val auth by model.account.auth.collectAsState()
            val service by model.service.collectAsState()
            val preview by model.preview.collectAsState()
            Box {
                Column {
                    Box(Modifier.weight(1f)) {
                        val s = service
                        val locked by model.locked.collectAsState()
                        val loggingOut by model.loggingOut.collectAsState()
                        when {
                            loggingOut -> Centered { // never the drive list in between
                                LogoHeader()
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Hint("Logging out and clearing this PC…")
                                }
                            }
                            locked -> LockScreen(model)
                            auth !is AuthState.Ready -> ConnectScreen(model, auth)
                            s == null -> DriveSetupScreen(model)
                            else -> MainShell(model, s, (auth as AuthState.Ready).userName)
                        }
                    }
                    StatusBar(model)
                }
                val s = service
                val p = preview
                val locked = model.locked.collectAsState().value
                if (s != null && p != null && !locked) PreviewOverlay(model, s, p)
                // Above the preview, so it stays usable (and keeps playing) whatever is open.
                val audio by model.audio.collectAsState()
                audio?.let { if (s != null && !locked) MiniAudioPlayer(model, s, it, Modifier.align(Alignment.BottomEnd).padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun StatusBar(model: AppModel) {
    val msg by model.message.collectAsState()
    val busy by model.busy.collectAsState()
    val undo by model.undo.collectAsState()
    if (msg == null && !busy) return
    // Snackbar-style pill under the content (never over it: no file row or the phone's Upload button gets covered).
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(), shape = MaterialTheme.shapes.small, shadowElevation = 3.dp,
            color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inversePrimary)
                Text(msg.orEmpty(), Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
                if (undo != null) TextButton(onClick = { model.runUndo() }) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
                if (!busy) TextButton(onClick = { model.message.value = null; model.undo.value = null }) { Text("Dismiss", color = MaterialTheme.colorScheme.inversePrimary) }
            }
        }
    }
}

/** QR code drawn as plain squares, dark on white (scanners need that contrast even in dark mode). */
@Composable
fun QrCode(text: String, size: androidx.compose.ui.unit.Dp) {
    val qr = remember(text) { io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM) }
    androidx.compose.foundation.Canvas(Modifier.size(size).background(androidx.compose.ui.graphics.Color.White).padding(12.dp)) {
        // Whole-pixel cells: fractional ones left anti-aliased seams between modules, which some scanners trip over.
        val cell = kotlin.math.floor(this.size.minDimension / qr.size)
        val start = kotlin.math.floor((this.size.minDimension - cell * qr.size) / 2)
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.getModule(x, y)) drawRect(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.geometry.Offset(start + x * cell, start + y * cell), androidx.compose.ui.geometry.Size(cell, cell))
        }
    }
}

// ---------- Login ----------

@Composable
private fun ConnectScreen(model: AppModel, auth: AuthState) {
    val error by model.account.authError.collectAsState()
    Centered {
        LogoHeader()
        CardTitle("Connect Telegram")
        when (auth) {
            AuthState.NeedCredentials, is AuthState.Failed -> {
                var apiId by remember { mutableStateOf("") }
                var apiHash by remember { mutableStateOf("") }
                val ready = apiId.isNotEmpty() && apiHash.length >= 32
                val connect = { if (ready) model.connect(apiId.toInt(), apiHash) }
                Hint("Create an app at my.telegram.org → API development tools. These stay on this PC, encrypted with Windows DPAPI.")
                OutlinedTextField(apiId, { apiId = it.filter(Char::isDigit) }, label = { Text("API ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().keys(connect))
                OutlinedTextField(
                    apiHash, { apiHash = it.trim() }, label = { Text("API Hash") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().keys(connect),
                )
                if (auth is AuthState.Failed) ErrorText(auth.message)
                Button(onClick = connect, enabled = ready) { Text("Connect") }
            }
            AuthState.Connecting -> CircularProgressIndicator()
            AuthState.WaitPhone -> {
                // Two equal ways in; QR first — no code to type, and it works while the phone number is unknown to this PC.
                Button(onClick = { model.requestQr() }, Modifier.fillMaxWidth()) {
                    Icon(androidx.compose.material.icons.Icons.Filled.QrCode2, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("Log in with a QR code (scan with your phone)")
                }
                Text("or with your phone number", Modifier.fillMaxWidth().padding(vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                PhoneInput(model, error)
            }
            is AuthState.WaitQr -> {
                Hint("On your phone: Telegram → Settings → Devices → Link Desktop Device, then scan this code.")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(auth.link, 240.dp) }
                Hint("The code refreshes by itself. If you use two-step verification you'll be asked for the password next.")
                error?.let { ErrorText(it) }
                // Back to the phone step (Telegram needs a fresh login for that; the API ID/hash are kept).
                TextButton(onClick = { model.usePhoneInstead() }) { Text("Use phone number instead") }
            }
            AuthState.WaitCode -> Ask(model, "Login code", "Sent to your Telegram app (or SMS).", error)
            is AuthState.WaitPassword -> Ask(model, "Two-step verification password", "Hint: ${auth.hint.ifEmpty { "none" }}", error, secret = true)
            is AuthState.Ready -> Unit
        }
    }
}

/** "+<code><number>" from what was typed: a number typed with its own "+" wins; a national leading 0 is dropped (0812… → +62812…). */
fun fullPhoneNumber(code: String, input: String): String {
    val digits = input.filter(Char::isDigit)
    return if (input.trimStart().startsWith("+")) "+$digits" else "+$code${digits.trimStart('0')}"
}

/** Country picker (Telegram's own list, Indonesia +62 first) + national number. */
@Composable
private fun PhoneInput(model: AppModel, error: String?) {
    val countries by androidx.compose.runtime.produceState<List<Country>?>(null) {
        value = runCatching { model.account.countries() }.onFailure { donat.teledrive.Log.e("countries", it) }.getOrNull()
            ?: listOf(Country("ID", "Indonesia", "62")) // still usable: any other country can be typed with its own "+"
    }
    var country by remember { mutableStateOf(Country("ID", "Indonesia", "62")) }
    var number by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ok = number.any(Char::isDigit)
    val submit = { if (ok) model.submit(fullPhoneNumber(country.code, number)) }
    Hint("Choose your country, then type your number (starting with 0 is fine).")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Same component as the number field (read-only, own label) → identical height, border and baseline.
        Box(Modifier.width(132.dp)) {
            OutlinedTextField(
                "${country.iso} +${country.code}", {}, readOnly = true, singleLine = true, label = { Text("Country") },
                trailingIcon = { Text("▾") }, modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clickable { filter = ""; open = true }) // the whole field opens the list
            androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.heightIn(max = 380.dp)) {
                OutlinedTextField(
                    filter, { filter = it }, placeholder = { Text("Search country or code") }, singleLine = true,
                    modifier = Modifier.padding(horizontal = 8.dp).width(280.dp),
                )
                val q = filter.trim().removePrefix("+")
                val shown = countries.orEmpty().filter { q.isEmpty() || it.name.contains(q, true) || it.code.startsWith(q) || it.iso.equals(q, true) }
                if (countries == null) androidx.compose.material3.DropdownMenuItem(text = { Text("Loading countries…") }, onClick = {}, enabled = false)
                shown.forEach { c ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text("${c.name}  +${c.code}") }, onClick = { country = c; open = false })
                }
            }
        }
        OutlinedTextField(
            number, { number = it.filter { ch -> ch.isDigit() || ch in " -+()" } }, label = { Text("Phone number") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
            modifier = Modifier.weight(1f).focusRequester(focus).keys(submit),
        )
    }
    if (ok) Hint("Sends ${fullPhoneNumber(country.code, number)}")
    error?.let { ErrorText(it) }
    Button(onClick = submit, enabled = ok) { Text("Continue") }
}

@Composable
private fun Ask(model: AppModel, label: String, hint: String, error: String?, secret: Boolean = false) {
    var value by remember(label) { mutableStateOf("") }
    val focus = remember(label) { FocusRequester() }
    LaunchedEffect(label) { focus.requestFocus() }
    val submit = { if (value.isNotBlank()) model.submit(value) }
    Hint(hint)
    OutlinedTextField(
        value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus).keys(submit),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
    error?.let { ErrorText(it) }
    Button(onClick = submit, enabled = value.isNotBlank()) { Text("Continue") }
}

// ---------- Drive setup ----------

@Composable
private fun DriveSetupScreen(model: AppModel) {
    val busy by model.busy.collectAsState()
    val syncing by model.syncingDrives.collectAsState()
    val syncError by model.driveSyncError.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Every drive in this Telegram account is brought onto this PC automatically (AppModel.syncDrives), so this is the full list.
    val local by remember { model.db.dao().drives() }.collectAsState(null as List<donat.teledrive.data.Drive>?)
    val back = local?.let { l -> l.firstOrNull { it.id == donat.teledrive.Prefs["lastDrive"] } ?: l.firstOrNull() }
    val cancel = back?.let { { model.openDrive(it) } }
    Centered {
        CardTitle("Your drives")
        local.orEmpty().forEach { d ->
            val stats by androidx.compose.runtime.produceState<String?>(null, d.id, local) {
                value = "${model.db.dao().fileCount(d.id)} files · ${model.db.dao().folderCount(d.id)} folders"
            }
            ListItem(
                headlineContent = { Text(d.name) },
                supportingContent = { Text(stats ?: "") },
                trailingContent = { Button(onClick = { model.openDrive(d) }, enabled = !busy) { Text("Open") } },
            )
        }
        when {
            syncing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Hint("Loading your drives from Telegram (folders and file lists only)…")
            }
            syncError != null -> {
                ErrorText("Couldn't load your drives from Telegram: $syncError")
                OutlinedButton(onClick = { scope.launch { model.syncDrives() } }) { Text("Try again") }
            }
            local?.isEmpty() == true -> Hint("No $APP_NAME drive found in this Telegram account yet.")
        }
        Spacer(Modifier.padding(12.dp))
        var name by remember { mutableStateOf("My Drive") }
        val canCreate = name.isNotBlank() && !busy && !syncing // never before the search is done: that made duplicate empty drives
        val create = { if (canCreate) model.create(name.trim()) }
        Text("Create a new drive", style = MaterialTheme.typography.titleMedium)
        Hint("A new, empty private Telegram channel. Your existing drives are listed above.")
        OutlinedTextField(name, { name = it }, label = { Text("Drive name") }, singleLine = true, modifier = Modifier.fillMaxWidth().keys(create, cancel))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = create, enabled = canCreate) { Text("Create drive") }
            if (cancel != null) TextButton(onClick = cancel, enabled = !busy) { Text("Cancel") }
        }
    }
}

// ---------- Main shell ----------

@Composable
private fun MainShell(model: AppModel, service: DriveService, userName: String) {
    var screen by remember { mutableStateOf(Screen.Home) }
    val transfers by remember(service) { model.db.dao().transfers(service.drive.id) }.collectAsState(emptyList())
    val active = transfers.count { !it.state.isTerminal && it.state != donat.teledrive.core.TransferState.FAILED }
    val failed = transfers.count { it.state == donat.teledrive.core.TransferState.FAILED }
    val content: @Composable () -> Unit = {
        when (screen) {
            Screen.Home -> FileBrowser(model, service, home = true)
            Screen.Files -> FileBrowser(model, service, home = false)
            Screen.Transfers -> TransfersScreen(model, service)
            Screen.Gallery -> GalleryScreen(model, service)
            Screen.Favorites -> FileBrowser(model, service, home = false, fixed = "fav")
            Screen.Recent -> FileBrowser(model, service, home = false, fixed = "recent")
            Screen.Insights -> InsightsScreen(model, service)
            Screen.Trash -> TrashScreen(model, service)
            Screen.Settings -> SettingsScreen(model, service, userName)
        }
    }
    // Home in the menu always means the top of the drive (it kept the last opened folder, so a drop "on Home" landed in that folder).
    val go: (Screen) -> Unit = { if (it == Screen.Home) model.homePath.value = emptyList(); screen = it }
    androidx.compose.foundation.layout.BoxWithConstraints {
        if (maxWidth < 600.dp) PhoneShell(model, service, userName, screen, go, active, failed, content)
        else DesktopShell(service, screen, go, active, failed, content)
    }
}

/** Phone-width layout modelled on Telegram: top bar with ☰, slide-out drawer with an account header and the menu. */
@Composable
private fun PhoneShell(
    model: AppModel, service: DriveService, userName: String, screen: Screen, onScreen: (Screen) -> Unit,
    active: Int, failed: Int, content: @Composable () -> Unit,
) {
    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val files by remember { model.db.dao().activeFiles(service.drive.id) }.collectAsState(emptyList())
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(Modifier.width(300.dp)) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Text(initials(userName), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Text(userName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${service.drive.name} · ${formatSize(files.sumOf { it.size })} · ${files.size} files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(8.dp))
                Screen.entries.forEach { s ->
                    androidx.compose.material3.NavigationDrawerItem(
                        icon = { Icon(s.icon, null) },
                        label = { Text(s.label) },
                        selected = s == screen,
                        badge = { val n = if (s == Screen.Transfers) active + failed else 0; if (n > 0) Text("$n", color = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
                        onClick = { onScreen(s); scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TipIcon(Icons.Filled.Menu, "Menu") { scope.launch { drawer.open() } }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(screen.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        val sync by service.syncStatus.collectAsState()
                        Text(sync, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (active + failed > 0 && screen != Screen.Transfers) {
                        TipIcon(Icons.Filled.SwapVert, "$active active, $failed failed transfers", tint = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) { onScreen(Screen.Transfers) }
                    }
                }
            }
            // Content on a rounded sheet under the tinted top bar.
            Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) { content() }
            }
        }
    }
}

private fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

@Composable
private fun DesktopShell(service: DriveService, screen: Screen, onScreen: (Screen) -> Unit, active: Int, failed: Int, content: @Composable () -> Unit) {
    Row {
        // Rail on the tinted backdrop, content on a rounded sheet beside it (no divider lines).
        NavigationRail(containerColor = androidx.compose.ui.graphics.Color.Transparent) {
            // Scrolls when the window is too short for all nine items (min height 480).
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Screen.entries.forEach {
                    NavigationRailItem(
                        selected = screen == it,
                        onClick = { onScreen(it) },
                        icon = {
                            val badge = if (it == Screen.Transfers) active + failed else 0
                            if (badge > 0) {
                                androidx.compose.material3.BadgedBox(badge = {
                                    androidx.compose.material3.Badge(containerColor = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) { Text("$badge") }
                                }) { Icon(it.icon, contentDescription = it.label) }
                            } else {
                                Icon(it.icon, contentDescription = it.label)
                            }
                        },
                        label = { Text(it.label, fontWeight = if (screen == it) androidx.compose.ui.text.font.FontWeight.SemiBold else null) },
                    )
                }
            }
        }
        Surface(Modifier.fillMaxSize().padding(top = 8.dp, end = 8.dp, bottom = 8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                SyncBar(service)
                Box(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) { content() }
            }
        }
    }
}

/** Always-visible truth about Telegram sync: saved / waiting / rate limited. */
@Composable
private fun SyncBar(service: DriveService) {
    val sync by service.syncStatus.collectAsState()
    val pausedUntil by service.pausedUntil.collectAsState()
    val conflicts by service.conflicts.collectAsState()
    var showConflicts by remember { mutableStateOf(false) }
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(1000); value = System.currentTimeMillis() } }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // Status pill (tonal chip) instead of a full-width bar.
        Surface(Modifier.weight(1f, fill = false), shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Filled.Cloud, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(sync, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (conflicts.isNotEmpty()) {
            TextButton(onClick = { showConflicts = true }) { Text("${conflicts.size} change(s) merged from another PC — review", color = MaterialTheme.colorScheme.error) }
        }
        if (pausedUntil > now) {
            Text(
                "Telegram rate limit · resumes in ${duration((pausedUntil - now) / 1000)} (${clock(pausedUntil)})",
                Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1,
            )
        }
    }
    if (showConflicts) {
        AppDialog("Changes from another PC", { showConflicts = false }, "Got it", { service.conflicts.value = emptyList(); showConflicts = false }, dismiss = null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint("Both PCs changed the same items. Nothing was deleted; the newer change was kept. Files marked “changed on two PCs” can be renamed/moved as you like, then right-click → Mark conflict resolved.")
                conflicts.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(model: AppModel, service: DriveService, userName: String) {
    val busy by model.busy.collectAsState()
    var cacheSize by remember { mutableStateOf(service.cacheSize()) }
    val theme by model.theme.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppTheme.entries.forEach { t -> ThemeCard(t, t == theme) { model.setTheme(t) } }
        }
        HorizontalDivider()
        Text("App", style = MaterialTheme.typography.titleMedium)
        var parallel by remember { mutableStateOf(DriveService.PARALLEL) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Transfers at the same time")
            (1..5).forEach { n ->
                androidx.compose.material3.FilterChip(parallel == n, { parallel = n; DriveService.PARALLEL = n; donat.teledrive.Prefs["parallel"] = "$n" }, label = { Text("$n") })
            }
        }
        Hint("Fewer = lighter on your internet; more = faster for many small files.")
        SpeedLimit(model, "up", "Upload speed limit")
        SpeedLimit(model, "down", "Download speed limit")
        Hint("Limits all Telegram traffic so the rest of your internet stays usable.")
        PrefSwitch("Windows notifications when transfers finish or fail", "notify")
        PrefSwitch("Keep running in the tray when the window is closed (uploads continue)", "closeToTray")
        var autostart by remember { mutableStateOf(donat.teledrive.Autostart.enabled()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(autostart, { runCatching { donat.teledrive.Autostart.set(it); autostart = it } })
            Text("Start with Windows", Modifier.padding(start = 12.dp))
        }
        HorizontalDivider()
        Text("Security", style = MaterialTheme.typography.titleMedium)
        var pinDialog by remember { mutableStateOf(false) }
        var pinSet by remember { mutableStateOf(donat.teledrive.AppLock.isSet()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pinDialog = true }) { Text(if (pinSet) "Change PIN" else "Set app PIN") }
            if (pinSet) OutlinedButton(onClick = { donat.teledrive.AppLock.set(null); pinSet = false }) { Text("Remove PIN") }
        }
        Hint("With a PIN, $APP_NAME asks for it on start and when reopened from the tray.")
        if (pinDialog) PinDialog { pinDialog = false; pinSet = donat.teledrive.AppLock.isSet() }
        OutlinedButton(onClick = {
            model.task("Verifying files…") {
                val (checked, bad) = service.verifyAll { i, n -> model.message.value = "Verifying $i of $n…" }
                model.notify(if (bad == 0) "Verified $checked file(s): all identical to the originals" else "$bad of $checked file(s) are damaged — marked Error")
            }
        }, enabled = !busy) { Text("Verify all files") }
        Hint("Re-downloads files not yet verified and compares every byte (SHA-256). Good files are marked Verified.")
        UnlistedFiles(model, service, busy)

        HorizontalDivider()
        Text("Web & sharing (local network)", style = MaterialTheme.typography.titleMedium)
        var lanOn by remember { mutableStateOf(donat.teledrive.LanServer.running) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(lanOn, {
                if (it) donat.teledrive.LanServer.start { model.service.value } else donat.teledrive.LanServer.stop()
                donat.teledrive.Prefs["lan"] = "$it"
                lanOn = it
            })
            Text("Open $APP_NAME in a browser on your phone or another PC", Modifier.padding(start = 12.dp))
        }
        if (lanOn) {
            var key by remember { mutableStateOf(donat.teledrive.LanServer.key) }
            val url = remember(key) { donat.teledrive.LanServer.webUrl() }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(url, 150.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(url, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copy(url); model.notify("Link copied") }) { Text("Copy link") }
                        OutlinedButton(onClick = { donat.teledrive.LanServer.regenerateKey(); key = donat.teledrive.LanServer.key }) { Text("New key (revoke old links)") }
                    }
                    Hint("Same Wi-Fi only. Browse, play, download and upload from the browser. Anyone with the link can use it — keep it private.")
                }
            }
        }

        HorizontalDivider()
        Text("Account & drives", style = MaterialTheme.typography.titleMedium)
        Text("Telegram account: $userName")
        val drives by remember { model.db.dao().drives() }.collectAsState(null as List<donat.teledrive.data.Drive>?)
        var confirmLogout by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<Pair<donat.teledrive.data.Drive, Pair<Int, Int>>?>(null) }
        drives.orEmpty().forEach { d ->
            val counts by androidx.compose.runtime.produceState<Pair<Int, Int>?>(null, d.id, drives) { value = model.db.dao().fileCount(d.id) to model.db.dao().folderCount(d.id) }
            val stats = counts?.let { (f, g) -> " · $f files · $g folders" }.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cloud, null, Modifier.size(18.dp), tint = if (d.id == service.drive.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(d.name + stats + if (d.id == service.drive.id) "  (open)" else "", Modifier.padding(horizontal = 8.dp).weight(1f))
                if (d.id != service.drive.id) {
                    TextButton(onClick = { model.openDrive(d) }) { Text("Open") }
                    TextButton(onClick = { counts?.let { deleting = d to it } }, enabled = !busy && counts != null) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        deleting?.let { (d, c) ->
            val (files, folders) = c
            var sure by remember(d.id) { mutableStateOf(files == 0) } // an empty drive needs no extra tick
            AppDialog(
                "Delete “${d.name}” forever?", { deleting = null }, "Delete forever",
                { deleting = null; model.deleteDrive(d) }, enabled = sure,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$files files · $folders folders. Its Telegram channel and its hidden data channel are deleted on every device. This can't be undone.")
                    if (files > 0) Row(Modifier.clickable { sure = !sure }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(sure, { sure = it })
                        Text("I understand that these $files files will be gone")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.addDrive() }, enabled = !busy) { Text("All drives / create a drive") }
            OutlinedButton(onClick = { confirmLogout = true }, enabled = !busy) { Text("Switch account (log out)") }
        }
        if (confirmLogout) AppDialog("Log out of $userName?", { confirmLogout = false }, "Log out", { confirmLogout = false; model.logOut() }) {
            Text("This PC forgets the Telegram session, the app PIN, the file list, previews and thumbnails. Your drives and files stay in Telegram and come back when you log in again.")
        }
        Hint("Log out removes the Telegram session and local index from this PC; your files stay in Telegram. Then log in with any account.")
        OutlinedButton(onClick = { model.rebuild() }, enabled = !busy) { Text("Rebuild index from Telegram") }
        Hint("Recreates the local database from the drive manifest in Telegram. Files are not re-downloaded.")
        OutlinedButton(onClick = { service.clearCache(); cacheSize = service.cacheSize() }) { Text("Clear preview cache (${formatSize(cacheSize)})") }
        Hint("Previews and thumbnails are kept on this PC for speed. Clearing them never touches your drive.")
    }
}

/**
 * Files in the drive's Telegram channel that no file of the drive points to — sent there from a phone, left over by an
 * interrupted delete, or everything after the index was lost. Add them to the drive, or delete them (double-confirmed).
 */
@Composable
private fun UnlistedFiles(model: AppModel, service: DriveService, busy: Boolean) {
    var found by remember { mutableStateOf<List<donat.teledrive.core.RemoteObject>?>(null) }
    var deleting by remember { mutableStateOf<List<donat.teledrive.core.RemoteObject>?>(null) }
    OutlinedButton(onClick = {
        model.task("Checking Telegram for unlisted files…") {
            val list = service.findUnlisted()
            if (list.isEmpty()) model.notify("Everything in Telegram belongs to a file of this drive.") else found = list
        }
    }, enabled = !busy) { Text("Check for unlisted files") }
    Hint("Finds files in this drive's Telegram channel that the drive doesn't list — e.g. sent there from your phone, or left over by an interrupted delete.")
    found?.let { list ->
        AppDialog(
            "${list.size} unlisted file(s) in Telegram", { found = null }, "Add to drive",
            { found = null; model.task("Adding to the drive…") { model.notify("Added ${service.adopt(list, "Recovered")} file(s) to the “Recovered” folder") } },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${formatSize(list.sumOf { it.size })} in total. “Add to drive” puts them in a “Recovered” folder (split parts are joined back).")
                list.take(8).forEach { Text("• ${it.name} · ${formatSize(it.size)}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                if (list.size > 8) Text("… and ${list.size - 8} more", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { found = null; deleting = list }) { Text("Delete them from Telegram instead…", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    deleting?.let { list ->
        var sure by remember(list) { mutableStateOf(false) }
        AppDialog(
            "Delete ${list.size} file(s) from Telegram forever?", { deleting = null }, "Delete forever",
            { deleting = null; model.task("Deleting unlisted files…") { model.notify("Deleted ${service.deleteUnlisted(list)} unlisted file(s) from Telegram") } },
            enabled = sure,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("They aren't in this drive, so nothing in the drive changes — but they are gone from Telegram on every device.")
                Row(Modifier.clickable { sure = !sure }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(sure, { sure = it })
                    Text("I understand these ${list.size} file(s) will be gone")
                }
            }
        }
    }
}

private val speedOptions = listOf(0L to "Unlimited", 256L * 1024 to "256 KB/s", 512L * 1024 to "512 KB/s", 1L shl 20 to "1 MB/s", 2L shl 20 to "2 MB/s", 5L shl 20 to "5 MB/s")

@Composable
private fun SpeedLimit(model: AppModel, dir: String, label: String) {
    var current by remember { mutableStateOf(model.speedLimit(dir)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.width(170.dp).align(Alignment.CenterVertically))
        speedOptions.forEach { (bps, text) ->
            androidx.compose.material3.FilterChip(current == bps, { current = bps; model.setSpeedLimit(dir, bps) }, label = { Text(text) })
        }
    }
}

/** A small live sample of the theme: its background, a card, the accent and text — rendered with that theme's own colors. */
@Composable
private fun ThemeCard(t: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val c = colorsFor(t)
    Column(
        Modifier.width(160.dp).height(140.dp).clip(RoundedCornerShape(12.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp).background(c.background).padding(10.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.surfaceContainerHighest).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(60.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.onSurface.copy(alpha = 0.8f)))
                Box(Modifier.width(90.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.onSurfaceVariant.copy(alpha = 0.6f)))
                Box(Modifier.width(36.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.primary))
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.CheckCircle, "Selected", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** On/off preference stored in Prefs (default on). */
@Composable
private fun PrefSwitch(label: String, key: String) {
    var on by remember { mutableStateOf(donat.teledrive.Prefs[key] != "false") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Switch(on, { on = it; donat.teledrive.Prefs[key] = "$it" })
        Text(label, Modifier.padding(start = 12.dp))
    }
}

// ---------- Bits ----------

/** Enter (main or numpad) / Esc for any form or dialog. */
fun Modifier.keys(onEnter: (() -> Unit)? = null, onEsc: (() -> Unit)? = null) = onPreviewKeyEvent { k ->
    if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when {
        onEnter != null && (k.key == Key.Enter || k.key == Key.NumPadEnter) -> { onEnter(); true }
        onEsc != null && k.key == Key.Escape -> { onEsc(); true }
        else -> false
    }
}

/**
 * Every pop-up goes through here: Enter = [confirm] action (when [enabled]), Esc = [onDismiss].
 * [focusContent] = false when the content focuses its own text field.
 */
@Composable
fun AppDialog(
    title: String, onDismiss: () -> Unit, confirm: String, onConfirm: () -> Unit, enabled: Boolean = true,
    dismiss: String? = "Cancel", focusContent: Boolean = true, content: @Composable () -> Unit,
) {
    val ok = { if (enabled) onConfirm() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val focus = remember { FocusRequester() }
            Box(Modifier.focusRequester(focus).focusable(focusContent).keys(ok, onDismiss)) { content() }
            if (focusContent) LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { Button(onClick = ok, enabled = enabled) { Text(confirm) } },
        dismissButton = dismiss?.let { { TextButton(onClick = onDismiss) { Text(it) } } },
    )
}

/** Login, PIN, drive list, logging out: one rounded card on a soft accent gradient (the first thing a user sees). */
@Composable
fun Centered(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val backdrop = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.primaryContainer.copy(alpha = 0.55f), c.surfaceContainer, c.surfaceContainer))
    Box(Modifier.fillMaxSize().background(backdrop), contentAlignment = Alignment.Center) {
        // Scrolls when the window is shorter than the form (QR + hints on a small window used to be cut off).
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = c.surface, shadowElevation = 2.dp) {
                Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
            }
        }
    }
}

/** Centered headline of a [Centered] card (lines up with the centered logo above it). */
@Composable
fun CardTitle(text: String) = Text(
    text, Modifier.fillMaxWidth().padding(bottom = 6.dp), style = MaterialTheme.typography.headlineSmall,
    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
)

@Composable
fun Hint(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

@Composable
fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

@Composable
fun EmptyState(title: String, hint: String) {
    Column(Modifier.fillMaxSize().padding(top = 32.dp), verticalArrangement = Arrangement.Top, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(hint, Modifier.widthIn(max = 420.dp).padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else "%.1f %s".format(v, units[i])
}
