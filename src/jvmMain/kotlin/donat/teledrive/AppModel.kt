package donat.teledrive

import donat.teledrive.core.NoManifestException
import donat.teledrive.data.AppDatabase
import donat.teledrive.data.Drive
import donat.teledrive.data.DriveFile
import donat.teledrive.data.Folder
import donat.teledrive.ui.AppTheme
import donat.teledrive.ui.isSaved
import donat.teledrive.telegram.AuthState
import donat.teledrive.telegram.TelegramAccount
import donat.teledrive.telegram.TelegramProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

enum class SortBy(val label: String) { NAME("Name"), MODIFIED("Modified"), SIZE("Size"), TYPE("Type") }

/** Small UI preferences that survive restarts (theme, view, sort). */
object Prefs {
    private val file = File(appDataDir, "settings.properties")
    private val props = java.util.Properties().apply { runCatching { file.inputStream().use(::load) } }

    operator fun get(key: String): String? = props.getProperty(key)

    operator fun set(key: String, value: String) {
        props.setProperty(key, value)
        save()
    }

    fun remove(key: String) {
        props.remove(key)
        save()
    }

    private fun save() = runCatching { file.outputStream().use { props.store(it, APP_NAME) } }
}

/** What the full-window preview shows: a list to page through and the current position. */
data class PreviewRequest(val files: List<DriveFile>, val index: Int)

/** App-level wiring: Telegram session → drive → DriveService. */
class AppModel(val db: AppDatabase, private val scope: CoroutineScope) {
    val account = TelegramAccount(appDataDir)
    val service = MutableStateFlow<DriveService?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    /** Optional "Undo" for the current [message]. */
    val undo = MutableStateFlow<(suspend () -> Unit)?>(null)
    val gridView = MutableStateFlow(Prefs["view"] != "details") // shared by Home and Files
    val sort = MutableStateFlow( // (column, ascending)
        (runCatching { SortBy.valueOf(Prefs["sort"]!!) }.getOrDefault(SortBy.NAME)) to (Prefs["sortAsc"] != "false"),
    )
    val theme = MutableStateFlow(runCatching { AppTheme.valueOf(Prefs["theme"]!!) }.getOrDefault(AppTheme.SYSTEM))
    val preview = MutableStateFlow<PreviewRequest?>(null)
    /** Audio playing in the corner mini player; stays across screens until closed. */
    val audio = MutableStateFlow<DriveFile?>(null)

    /** Opens a file: audio goes to the mini player, everything else to the full-window preview. */
    fun open(files: List<DriveFile>, index: Int) {
        val f = files.getOrNull(index) ?: return
        if (f.category == donat.teledrive.core.FileCategory.AUDIO && f.isSaved) audio.value = f else preview.value = PreviewRequest(files, index)
    }
    private var creds: Pair<Int, String>? = Secrets.load()
    /** The PIN guards a logged-in account. No account on this PC (logged out) = no PIN: a stale one is dropped. */
    val locked = MutableStateFlow(AppLock.isSet() && creds != null).also { if (creds == null) AppLock.set(null) }
    /** Screen shown in the main shell; every drive opens on Home. */
    val screen = androidx.compose.runtime.mutableStateOf(donat.teledrive.ui.Screen.Home)
    val homePath = androidx.compose.runtime.mutableStateOf(listOf<Folder>())
    val filesPath = androidx.compose.runtime.mutableStateOf(listOf<Folder>())

    fun setGrid(on: Boolean) { gridView.value = on; Prefs["view"] = if (on) "grid" else "details" }
    fun setSort(by: SortBy, asc: Boolean) { sort.value = by to asc; Prefs["sort"] = by.name; Prefs["sortAsc"] = "$asc" }
    fun setTheme(t: AppTheme) { theme.value = t; Prefs["theme"] = t.name }

    /** One file → "Save as"; several or folders → pick a destination folder. Modern Windows dialogs. */
    fun download(files: List<DriveFile>, folders: List<Folder> = emptyList()) = scope.launch {
        val s = service.value ?: return@launch
        try {
            if (files.size == 1 && folders.isEmpty()) {
                NativeDialogs.saveFile(files[0].name)?.let { s.enqueueDownload(files[0], it); notify("Downloading ${files[0].name}") }
            } else {
                NativeDialogs.pickFolder()?.let { dir -> s.enqueueDownloads(files, folders, dir); notify("Downloading ${files.size + folders.size} item(s) to ${dir.name}") }
            }
        } catch (e: Exception) {
            message.value = e.message
        }
    }

    private val tmpDir = File(appDataDir, "tmp").apply { deleteRecursively(); mkdirs() }
    private var transfersJob: Job? = null
    /** True while looking for this account's drives in Telegram; [driveSyncError] = why the last look failed. */
    val syncingDrives = MutableStateFlow(false)
    val driveSyncError = MutableStateFlow<String?>(null)
    private var syncJob: Job? = null
    /** From the start of log out until the login form is up: the UI shows a neutral screen, never the drive list. */
    val loggingOut = MutableStateFlow(false)

    init {
        creds?.let { (id, hash) -> account.connect(id, hash) }
        scope.launch {
            account.auth.collect {
                // Persisted once Telegram's servers accepted them (a QR or code was issued), so a restart mid-login keeps them.
                if (it is AuthState.WaitQr || it is AuthState.WaitCode || it is AuthState.WaitPassword || it is AuthState.Ready) {
                    creds?.let { (id, hash) -> Secrets.save(id, hash) }
                }
                if (it is AuthState.Ready) {
                    runCatching { account.applySpeedLimit(speedLimit("up"), speedLimit("down")) }.onFailure { e -> Log.e("speed limit", e) }
                    syncJob = scope.launch {
                        // The last drive opens at once if it has files; meanwhile every drive in Telegram is brought in.
                        val last = db.dao().drives().first().firstOrNull { d -> d.id == Prefs["lastDrive"] }
                        if (last != null && db.dao().fileCount(last.id) > 0) open(last)
                        syncDrives()
                        if (service.value == null) bestDrive()?.let(::open)
                    }
                }
            }
        }
    }

    /**
     * Every Donat TeleDrive drive in this Telegram account that this PC doesn't know yet is restored from its manifest
     * (folders + file list only; no file is downloaded). Runs after every login/start, so logging in loads everything.
     */
    suspend fun syncDrives() {
        syncingDrives.value = true
        driveSyncError.value = null
        try {
            val known = db.dao().drives().first().map { it.remoteId }.toSet()
            val found = account.findDrives()
            Log.i("drives in Telegram: ${found.size}, new on this PC: ${found.count { it.chatId.toString() !in known }}")
            for (r in found.filter { it.chatId.toString() !in known }) {
                try {
                    DriveService.restore(db, provider(r.chatId), r.chatId.toString(), "telegram", appDataDir)
                } catch (e: NoManifestException) {
                    // Its data channel was deleted and no PC has the index: bring every file back by name (folders are lost).
                    DriveService.recover(db, provider(r.chatId), r.chatId.toString(), "telegram", r.title, appDataDir)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("restore drive ${r.chatId}", e)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("findDrives", e)
            driveSyncError.value = e.message ?: e.toString()
        } finally {
            syncingDrives.value = false
        }
    }

    /** The last drive if it holds files, else the fullest one (an accidentally created empty drive never hides your files). */
    private suspend fun bestDrive(): Drive? {
        val drives = db.dao().drives().first()
        val count = drives.associate { it.id to db.dao().fileCount(it.id) }
        return drives.firstOrNull { it.id == Prefs["lastDrive"] && count.getValue(it.id) > 0 }
            ?: drives.filter { count.getValue(it.id) > 0 }.maxByOrNull { count.getValue(it.id) }
            ?: drives.firstOrNull { it.id == Prefs["lastDrive"] } ?: drives.firstOrNull()
    }

    fun connect(apiId: Int, apiHash: String) {
        creds = apiId to apiHash
        account.connect(apiId, apiHash)
    }

    fun submit(value: String) = scope.launch { account.submit(value) }
    fun requestQr() = scope.launch { account.requestQr() }
    fun usePhoneInstead() = scope.launch { account.restartLogin() }

    fun create(name: String) = task("Creating $name…") {
        val chatId = account.createDriveChannel(name)
        val drive = Drive(newId(), name, "telegram", chatId.toString(), now())
        db.dao().upsert(drive)
        open(drive).saveManifestNow() // first manifest makes the channel discoverable from other devices
    }

    fun rebuild() = task("Rebuilding index from Telegram…") {
        val m = service.value!!.rebuildIndex()
        message.value = "Index rebuilt: ${m.files.size} files, ${m.folders.size} folders (revision ${m.revision})."
    }

    /** Forgets this account on this PC; the drives and files stay in Telegram and come back on the next login. */
    fun logOut() = scope.launch {
        loggingOut.value = true
        try {
            syncJob?.cancel() // a drive sync still running could otherwise put a drive back after the wipe
            transfersJob?.cancel()
            service.value = null
            preview.value = null
            runCatching { account.logOut() }.onFailure { Log.e("log out", it) } // TDLib deletes its own session database
            withTimeoutOrNull(15_000) { account.auth.first { it !is AuthState.Ready } } // → login form (API ID / hash)
            Secrets.clear()
            creds = null
            AppLock.set(null) // the PIN belonged to that session; the next login starts without one
            locked.value = false
            db.dao().deleteAllDrives() // local index is only a cache
            db.dao().deleteAllTransfers()
            // Everything else this account left here: previews, thumbnails, TDLib downloads, phone uploads, sync bases.
            listOf("cache", "thumbs", "tdlib-files", "lan-inbox").forEach { File(appDataDir, it).deleteRecursively() }
            appDataDir.listFiles { f -> f.name.startsWith("manifest-base-") }?.forEach { it.delete() }
            homePath.value = emptyList()
            filesPath.value = emptyList()
            message.value = null
        } finally {
            loggingOut.value = false
        }
    }

    /** Shows a short confirmation with an optional Undo; it disappears by itself after a few seconds. */
    fun notify(text: String, undoAction: (suspend () -> Unit)? = null) {
        message.value = text
        undo.value = undoAction
        scope.launch {
            delay(8_000)
            if (message.value == text) { message.value = null; undo.value = null }
        }
    }

    fun runUndo() {
        val u = undo.value ?: return
        undo.value = null
        task("Undoing…", u)
    }

    /** Runs a UI action with a busy flag; failures land in [message] instead of crashing. */
    fun task(label: String, block: suspend () -> Unit) = scope.launch {
        busy.value = true
        message.value = label
        try {
            block()
            if (message.value == label) message.value = null
        } catch (e: Exception) {
            Log.e("task '$label'", e)
            message.value = e.message ?: e.toString()
        } finally {
            busy.value = false
        }
    }

    private fun provider(chatId: Long) = TelegramProvider(account, chatId, tmpDir)

    /** Bytes/second for "up" or "down"; 0 = unlimited. */
    fun speedLimit(dir: String): Long = Prefs["limit_$dir"]?.toLongOrNull() ?: 0

    fun setSpeedLimit(dir: String, bytesPerSec: Long) {
        Prefs["limit_$dir"] = "$bytesPerSec"
        task("Applying speed limit…") { account.applySpeedLimit(speedLimit("up"), speedLimit("down")) }
    }

    /** Switch to another drive of this account. */
    fun openDrive(drive: Drive) { if (service.value?.drive?.id != drive.id) { homePath.value = emptyList(); filesPath.value = emptyList(); open(drive) } }

    /** Deletes a drive that is not open: its Telegram channels (all files, on every device) and its index on this PC. */
    fun deleteDrive(drive: Drive) = task("Deleting ${drive.name}…") {
        check(service.value?.drive?.id != drive.id) { "Open another drive first" }
        provider(drive.remoteId.toLong()).deleteDrive()
        db.dao().forgetDrive(drive.id)
        File(appDataDir, "manifest-base-${drive.id}.json").delete()
        notify("Deleted ${drive.name} from Telegram")
    }

    /** Shows the drive list (open another drive or create one); also picks up drives made elsewhere since the last look. */
    fun addDrive() { transfersJob?.cancel(); service.value = null; syncJob = scope.launch { syncDrives() } }

    private fun open(drive: Drive): DriveService {
        Prefs["lastDrive"] = drive.id // the drive id comes from the manifest, so it stays the same after log out + restore
        screen.value = donat.teledrive.ui.Screen.Home
        transfersJob?.cancel()
        val s = DriveService(db, provider(drive.remoteId.toLong()), drive, appDataDir)
        service.value = s
        transfersJob = scope.launch { s.runTransfers() }
        return s
    }
}
