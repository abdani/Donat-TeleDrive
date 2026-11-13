package donat.teledrive.telegram

import donat.teledrive.APP_NAME
import donat.teledrive.Log
import donat.teledrive.Prefs
import donat.teledrive.core.RateLimitedException
import donat.teledrive.core.RemoteObject
import donat.teledrive.core.StorageException
import donat.teledrive.core.StorageProvider
import donat.teledrive.data.Manifest
import it.tdlight.Init
import it.tdlight.client.APIToken
import it.tdlight.client.AuthenticationData
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.client.TDLibSettings
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

sealed interface AuthState {
    data object NeedCredentials : AuthState
    data object Connecting : AuthState
    data object WaitPhone : AuthState
    /** Scan [link] as a QR code in Telegram → Settings → Devices → Link Desktop Device. */
    data class WaitQr(val link: String) : AuthState
    data object WaitCode : AuthState
    data class WaitPassword(val hint: String) : AuthState
    data class Ready(val userName: String) : AuthState
    data class Failed(val message: String) : AuthState
}

data class RemoteDrive(val chatId: Long, val title: String)

/** A country for the phone-number picker: ISO code ("ID"), English name, calling code without "+" ("62"). */
data class Country(val iso: String, val name: String, val code: String)

private val nativesLoaded by lazy { Init.init() }
private const val MANIFEST_CAPTION = "$APP_NAME manifest"
private const val DATA_TITLE = "$APP_NAME data"
private const val DATA_MARK = "DTD-DATA:"
/** How TDLib reports a chat that was deleted or that this account can no longer reach. */
private val GONE = Regex("(?i)chat not found|channel_private|channel_invalid|no access|can't access|chat_forbidden")
/** Start of every drive channel's description — how a drive is found again when its data channel is gone. */
private const val DRIVE_MARK = "$APP_NAME storage."
/** Name before the rename: channels made by older builds still say it until [TelegramAccount.rebrand] runs — both are recognised. */
private const val OLD_NAME = "DonatTeleDrive"
internal fun isDataTitle(title: String) = title.startsWith(DATA_TITLE) || title.startsWith("$OLD_NAME data")
internal fun isDriveMark(description: String) = description.startsWith(DRIVE_MARK) || description.startsWith("$OLD_NAME storage.")
private const val PREFETCH = 8L * 1024 * 1024
/** Caption older versions put on every uploaded part: "<file uuid>.p<part>". */
private val OLD_LABEL = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.p\\d+")

/** The only place TDLib types live. Everything outside this file talks AuthState / StorageProvider. */
class TelegramAccount(private val dataDir: File) {
    private val _auth = MutableStateFlow<AuthState>(AuthState.NeedCredentials)
    val auth: StateFlow<AuthState> = _auth

    /** Last error from a login step (wrong code, bad phone...), shown under the input. */
    val authError = MutableStateFlow<String?>(null)

    private var factory: SimpleTelegramClientFactory? = null
    private var client: SimpleTelegramClient? = null
    private var loggingOut = false
    private val uploadListeners = ConcurrentHashMap<String, (TdApi.File) -> Unit>() // by local path
    private val downloadListeners = ConcurrentHashMap<Int, (TdApi.File) -> Unit>() // by TDLib file id
    private val sends = ConcurrentHashMap<Long, CompletableFuture<TdApi.Message>>() // by temporary message id

    private var lastCreds: Pair<Int, String>? = null
    @Volatile private var restarting = false

    fun connect(apiId: Int, apiHash: String) {
        close()
        lastCreds = apiId to apiHash
        loggingOut = false
        _auth.value = AuthState.Connecting
        authError.value = null
        try {
            nativesLoaded
            val settings = TDLibSettings.create(APIToken(apiId, apiHash)).apply {
                databaseDirectoryPath = dataDir.toPath().resolve("tdlib")
                downloadedFilesDirectoryPath = dataDir.toPath().resolve("tdlib-files")
                deviceModel = "$APP_NAME (Windows)"
                applicationVersion = donat.teledrive.APP_VERSION
            }
            val f = SimpleTelegramClientFactory().also { factory = it }
            val builder = f.builder(settings)
            // We drive phone/code/password ourselves (see submit) so wrong input can be retried with a visible error.
            builder.setClientInteraction { _, _ -> CompletableFuture() }
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState::class.java) { onAuth(it.authorizationState) }
            builder.addUpdateHandler(TdApi.UpdateConnectionState::class.java) { Log.i("connection: ${it.state.javaClass.simpleName}") }
            builder.addUpdateHandler(TdApi.UpdateFile::class.java) { u ->
                uploadListeners[u.file.local.path]?.invoke(u.file)
                downloadListeners[u.file.id]?.invoke(u.file)
            }
            // Whichever side comes first (this update or sendDocument) creates the future, so no result is lost.
            builder.addUpdateHandler(TdApi.UpdateMessageSendSucceeded::class.java) { sendResult(it.oldMessageId).complete(it.message) }
            builder.addUpdateHandler(TdApi.UpdateMessageSendFailed::class.java) { sendResult(it.oldMessageId).completeExceptionally(TelegramError(it.error)) }
            client = builder.build(AuthenticationSupplier<AuthenticationData> { CompletableFuture() })
        } catch (e: Throwable) {
            close()
            _auth.value = AuthState.Failed(e.message ?: e.toString())
        }
    }

    private fun onAuth(state: TdApi.AuthorizationState) {
        Log.i("auth: ${state.javaClass.simpleName}") // state name only — never the QR link (it's a login token)
        _auth.value = when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> AuthState.WaitPhone
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> AuthState.WaitQr(state.link)
            is TdApi.AuthorizationStateWaitCode -> AuthState.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> AuthState.WaitPassword(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateReady -> {
                client?.getMeAsync()?.thenAccept { _auth.value = AuthState.Ready(listOf(it.firstName, it.lastName).joinToString(" ").trim()) }
                return
            }
            is TdApi.AuthorizationStateClosed -> {
                client = null
                if (restarting) { // QR → phone: start a fresh login with the same API ID/hash (off the TDLib thread)
                    restarting = false
                    lastCreds?.let { (id, hash) -> CompletableFuture.runAsync { connect(id, hash) } }
                    return
                }
                if (loggingOut) AuthState.NeedCredentials else AuthState.Failed("Telegram session closed")
            }
            is TdApi.AuthorizationStateWaitRegistration, is TdApi.AuthorizationStateWaitEmailAddress, is TdApi.AuthorizationStateWaitEmailCode ->
                AuthState.Failed("Finish setting up this Telegram account in the official app first, then try again.")
            else -> return
        }
    }

    /** Answer the current login step. */
    suspend fun submit(value: String) {
        authError.value = null
        try {
            when (val s = auth.value) {
                AuthState.WaitPhone -> call(TdApi.SetAuthenticationPhoneNumber(value.filter { it.isDigit() || it == '+' }, null))
                AuthState.WaitCode -> call(TdApi.CheckAuthenticationCode(value.trim()))
                is AuthState.WaitPassword -> call(TdApi.CheckAuthenticationPassword(value))
                else -> error("Not waiting for input ($s)")
            }
        } catch (e: Exception) {
            Log.e("auth: submit", e) // Telegram's error code only — the typed value is never logged
            authError.value = friendly(e.message.orEmpty())
        }
    }

    /** Telegram's login error codes in words; anything else is shown as-is. */
    private fun friendly(msg: String) = when {
        "PHONE_NUMBER_INVALID" in msg -> "That phone number isn't valid. Check the country and the number."
        "PHONE_NUMBER_BANNED" in msg -> "This phone number is banned by Telegram."
        "PHONE_CODE_INVALID" in msg -> "Wrong code. Check the code Telegram sent and try again."
        "PHONE_CODE_EXPIRED" in msg -> "That code expired. Go back and request a new one."
        "PASSWORD_HASH_INVALID" in msg -> "Wrong password."
        else -> msg
    }

    /** Telegram's own country list with calling codes (works before login). */
    suspend fun countries(): List<Country> = call(TdApi.GetCountries()).countries
        .filter { !it.isHidden }
        .flatMap { c -> c.callingCodes.map { Country(c.countryCode, c.englishName, it) } }
        .sortedBy { it.name }

    /** Switches the login to QR: TDLib answers with WaitOtherDeviceConfirmation(link), refreshed as it expires. */
    suspend fun requestQr() {
        authError.value = null
        Log.i("auth: QR requested")
        try {
            call(TdApi.RequestQrCodeAuthentication(LongArray(0)))
            Log.i("auth: QR request accepted")
        } catch (e: Exception) {
            Log.e("auth: QR request", e)
            authError.value = e.message
        }
    }

    /**
     * QR → phone number. TDLib can't turn an open QR login into a phone login ("Call to setAuthenticationPhoneNumber
     * unexpected"), so the half-done login is closed and started again; it lands on the phone step (nothing was logged in).
     */
    suspend fun restartLogin() {
        authError.value = null
        restarting = true
        _auth.value = AuthState.Connecting
        try {
            call(TdApi.LogOut())
        } catch (e: Exception) {
            restarting = false
            Log.e("auth: restart login", e)
            authError.value = e.message
            lastCreds?.let { (id, hash) -> connect(id, hash) }
        }
    }

    suspend fun logOut() {
        loggingOut = true
        client?.logOutAsync()?.await()
    }

    fun close() {
        client?.sendClose()
        client = null
        factory?.close()
        factory = null
    }

    /**
     * sendUnsafe, not send: tdlight's send() holds every function outside its own whitelist until the account is logged in —
     * RequestQrCodeAuthentication and SetAuthenticationPhoneNumber aren't on it, so the QR button (and phone login) waited forever.
     * After login both behave the same; before login TDLib now answers (or errors) right away.
     */
    internal suspend fun <R : TdApi.Object> call(f: TdApi.Function<R>): R =
        mapErrors { (client ?: throw StorageException("Telegram is not connected")).sendUnsafe(f).await() }

    private fun sendResult(tempId: Long) = sends.computeIfAbsent(tempId) { CompletableFuture() }

    /** Sends a file as a document and waits until Telegram confirms it. Cancelling the coroutine aborts the upload. */
    internal suspend fun sendDocument(chatId: Long, path: String, caption: String, onProgress: (Long) -> Unit): TdApi.Message {
        val content = TdApi.InputMessageDocument(TdApi.InputDocument(TdApi.InputFileLocal(path), null, true), TdApi.FormattedText(caption, emptyArray()))
        uploadListeners[path] = { onProgress(it.remote.uploadedSize) }
        var tempId = 0L
        try {
            tempId = call(TdApi.SendMessage(chatId, null, null, null, null, content)).id
            return mapErrors { sendResult(tempId).await() }
        } catch (e: CancellationException) {
            if (tempId != 0L) client?.send(TdApi.DeleteMessages(chatId, longArrayOf(tempId), true))
            throw e
        } finally {
            uploadListeners.remove(path)
            if (tempId != 0L) sends.remove(tempId)
        }
    }

    /** The file inside a message, however it was sent: as a file by us, or as a photo/video/audio/GIF from a phone. */
    internal class Stored(val file: TdApi.File, val name: String, val mime: String, val thumb: TdApi.File?)

    internal fun stored(m: TdApi.Message): Stored? {
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .format(java.time.Instant.ofEpochSecond(m.date.toLong()).atZone(java.time.ZoneId.systemDefault()))
        return when (val c = m.content) {
            is TdApi.MessageDocument -> c.document.run { Stored(document, fileName.ifBlank { "file_$stamp" }, mimeType, thumbnail?.file) }
            is TdApi.MessageVideo -> c.video.run { Stored(video, fileName.ifBlank { "video_$stamp.mp4" }, mimeType.ifBlank { "video/mp4" }, thumbnail?.file) }
            is TdApi.MessageAudio -> c.audio.run { Stored(audio, fileName.ifBlank { "audio_$stamp.mp3" }, mimeType.ifBlank { "audio/mpeg" }, albumCoverThumbnail?.file) }
            is TdApi.MessageAnimation -> c.animation.run { Stored(animation, fileName.ifBlank { "animation_$stamp.mp4" }, mimeType.ifBlank { "video/mp4" }, thumbnail?.file) }
            is TdApi.MessagePhoto -> c.photo.sizes.sortedBy { it.width.toLong() * it.height }.let { s ->
                s.lastOrNull()?.let { Stored(it.photo, "photo_$stamp.jpg", "image/jpeg", s.first().photo) } // Telegram keeps photos only compressed
            }
            else -> null
        }
    }

    private suspend fun storedAt(chatId: Long, messageId: Long): Stored =
        stored(call(TdApi.GetMessage(chatId, messageId))) ?: throw StorageException("Message $messageId is not a file")

    /** Downloads a file message into TDLib's cache, copies it to [dest], then frees the cache copy. */
    internal suspend fun downloadDocument(chatId: Long, messageId: Long, dest: File, onProgress: (Long) -> Unit) {
        val doc = storedAt(chatId, messageId).file
        downloadListeners[doc.id] = { onProgress(it.local.downloadedSize) }
        try {
            val f = call(TdApi.DownloadFile(doc.id, 32, 0, 0, true))
            if (!f.local.isDownloadingCompleted) throw StorageException("Download of message $messageId did not complete")
            File(f.local.path).copyTo(dest, overwrite = true)
        } catch (e: CancellationException) {
            client?.send(TdApi.CancelDownloadFile(doc.id, false))
            throw e
        } finally {
            downloadListeners.remove(doc.id)
            client?.send(TdApi.DeleteFile(doc.id))
        }
    }

    private val docIds = ConcurrentHashMap<Pair<Long, Long>, Int>() // (chat, message) → TDLib file id

    private suspend fun docId(chatId: Long, messageId: Long): Int = docIds.getOrPut(chatId to messageId) { storedAt(chatId, messageId).file.id }

    /** Downloads just [offset, offset+length) of a document (TDLib writes it at the same offset in its cache file). */
    internal suspend fun readRange(chatId: Long, messageId: Long, offset: Long, length: Long): ByteArray {
        val id = docId(chatId, messageId)
        val f = call(TdApi.DownloadFile(id, 16, offset, length, true))
        val n = minOf(length, f.size - offset).toInt()
        // Keep downloading ahead in the background so the next sequential read (playback) is already local.
        if (offset + n < f.size) client?.send(TdApi.DownloadFile(id, 8, offset + n, PREFETCH, false))
        RandomAccessFile(f.local.path, "r").use { raf ->
            if (raf.length() < offset + n) throw StorageException("Range not available yet")
            raf.seek(offset)
            return ByteArray(n).also { raf.readFully(it) }
        }
    }

    internal fun evict(chatId: Long, messageId: Long) {
        docIds[chatId to messageId]?.let { client?.send(TdApi.DeleteFile(it)) }
    }

    /** What housekeeping removes from a chat: manifests (newest first), "📌 pinned …" notices, files captioned with an old internal label. */
    internal class Clutter(val manifests: List<Long>, val pins: List<Long>, val labelled: List<Long>)

    /**
     * Walks the whole history (server search missed old manifests). Every pin leaves a notice, and old versions captioned
     * each file "<uuid>.p0" — both cluttered the drive channel on the phone between the user's files.
     */
    internal suspend fun clutter(chatId: Long): Clutter {
        val manifests = mutableListOf<Long>()
        val pins = mutableListOf<Long>()
        val labelled = mutableListOf<Long>()
        for (m in history(chatId)) {
            val doc = m.content as? TdApi.MessageDocument
            when {
                doc?.document?.fileName?.startsWith(Manifest.FILE_PREFIX) == true -> manifests += m.id
                m.content is TdApi.MessagePinMessage -> pins += m.id
                doc != null && OLD_LABEL.matches(doc.caption.text) -> labelled += m.id
            }
        }
        return Clutter(manifests.sortedDescending(), pins, labelled)
    }

    /** Every message of a chat, newest first (walks the whole history; drive channels hold one message per file part). */
    private suspend fun history(chatId: Long): List<TdApi.Message> {
        val all = LinkedHashMap<Long, TdApi.Message>()
        var from = 0L
        while (true) {
            val batch = call(TdApi.GetChatHistory(chatId, from, 0, 100, false)).messages
            if (batch.isEmpty() || batch.last().id == from) break
            batch.forEach { all[it.id] = it }
            from = batch.last().id
        }
        return all.values.toList()
    }

    /** Every stored file object of a drive channel (manifests excluded), however it was sent. */
    internal suspend fun files(chatId: Long): List<RemoteObject> = history(chatId).mapNotNull { m ->
        stored(m)?.takeUnless { it.name.startsWith(Manifest.FILE_PREFIX) }?.let {
            RemoteObject(m.id.toString(), it.name, it.file.size.takeIf { s -> s > 0 } ?: it.file.expectedSize, it.mime, m.date * 1000L)
        }
    }

    /** Clears captions one by one, slowly, waiting out any FLOOD_WAIT (it's background housekeeping). */
    internal suspend fun clearCaptions(chatId: Long, ids: List<Long>) {
        for (id in ids) {
            while (true) {
                try {
                    call(TdApi.EditMessageCaption(chatId, id, null, TdApi.FormattedText("", emptyArray()), false)); break
                } catch (e: RateLimitedException) {
                    kotlinx.coroutines.delay(e.retryAfterSeconds * 1000 + 1000)
                } catch (e: StorageException) {
                    Log.e("clear caption $id", e); break // e.g. message gone: skip it, never block the rest
                }
            }
            kotlinx.coroutines.delay(400)
        }
    }

    internal suspend fun deleteMessages(chatId: Long, ids: List<Long>) =
        ids.chunked(100).forEach { call(TdApi.DeleteMessages(chatId, it.toLongArray(), true)) }

    internal suspend fun downloadThumbnail(chatId: Long, messageId: Long, dest: File): Boolean {
        val thumb = storedAt(chatId, messageId).thumb ?: return false
        val f = call(TdApi.DownloadFile(thumb.id, 1, 0, 0, true))
        if (!f.local.isDownloadingCompleted) return false
        File(f.local.path).copyTo(dest, overwrite = true)
        return true
    }

    /**
     * Drives of this account: via their data channels; legacy drives that still pin the manifest themselves; and drive
     * channels whose data channel is gone (found by the storage description on channels this account created).
     */
    suspend fun findDrives(): List<RemoteDrive> {
        val data = dataChannels(refresh = true)
        val viaData = data.keys.mapNotNull { id -> runCatching { RemoteDrive(id, call(TdApi.GetChat(id)).title) }.getOrNull() }
        var orphans = 0
        val other = allChatIds().filter { it !in data.values && it !in data.keys }.mapNotNull { id ->
            runCatching { // one odd chat (deleted, no access…) must never hide the user's drives
                val chat = call(TdApi.GetChat(id))
                val sg = (chat.type as? TdApi.ChatTypeSupergroup)?.takeIf { it.isChannel && !isDataTitle(chat.title) } ?: return@runCatching null
                manifestDocument(id)?.let { return@runCatching RemoteDrive(id, chat.title) }
                // Only channels this account created are asked for their description (keeps the number of requests small).
                if (call(TdApi.GetSupergroup(sg.supergroupId)).status !is TdApi.ChatMemberStatusCreator) return@runCatching null
                RemoteDrive(id, chat.title).takeIf { call(TdApi.GetSupergroupFullInfo(sg.supergroupId)).description.let(::isDriveMark) }?.also { orphans++ }
            }.getOrNull()
        }
        Log.i("findDrives: ${data.size} data channel(s), ${viaData.size} via data, ${other.size - orphans} legacy, $orphans without index")
        return (viaData + other).distinctBy { it.chatId }
    }

    internal fun forgetDataChannel(driveChatId: Long) { dataCache = dataCache?.minus(driveChatId) }

    private suspend fun allChatIds(): List<Long> {
        // LoadChats answers 404 once a list is fully loaded — that is the normal end, not an error.
        // (tdlight's loadChatListMainAsync surfaced that 404 right after a fresh login, so no drive was found.)
        for (list in listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())) while (runCatching { call(TdApi.LoadChats(list, 100)) }.isSuccess) Unit
        return (call(TdApi.GetChats(TdApi.ChatListMain(), 1000)).chatIds + call(TdApi.GetChats(TdApi.ChatListArchive(), 1000)).chatIds).distinct()
    }

    @Volatile private var dataCache: Map<Long, Long>? = null

    /**
     * Data channels hold the manifest so the drive channel shows only the user's files.
     * They are archived and carry "DTD-DATA:<drive chat id>" in their description. Map: drive chat → data chat.
     */
    internal suspend fun dataChannels(refresh: Boolean = false): Map<Long, Long> {
        if (!refresh) dataCache?.let { return it }
        return allChatIds().mapNotNull { id ->
            runCatching {
                val chat = call(TdApi.GetChat(id))
                val sg = (chat.type as? TdApi.ChatTypeSupergroup)?.takeIf { it.isChannel && isDataTitle(chat.title) } ?: return@runCatching null
                call(TdApi.GetSupergroupFullInfo(sg.supergroupId)).description.substringAfter(DATA_MARK, "").takeWhile { it == '-' || it.isDigit() }
                    .toLongOrNull()?.let { it to id }
            }.getOrNull()
        }.toMap().also { dataCache = it }
    }

    /** Channels made before the rename get the new name once: data channel title, and "DonatTeleDrive" in descriptions. */
    internal suspend fun rebrand(vararg chats: Long) = chats.forEach { id ->
        runCatching {
            val chat = call(TdApi.GetChat(id))
            val sg = chat.type as? TdApi.ChatTypeSupergroup ?: return@runCatching
            if (chat.title.startsWith("$OLD_NAME data")) call(TdApi.SetChatTitle(id, DATA_TITLE + chat.title.removePrefix("$OLD_NAME data")))
            val description = call(TdApi.GetSupergroupFullInfo(sg.supergroupId)).description
            if (OLD_NAME in description) call(TdApi.SetChatDescription(id, description.replace(OLD_NAME, APP_NAME)))
        }.onFailure { Log.e("rebrand $id", it) }
    }

    internal suspend fun createDataChannel(driveChatId: Long): Long {
        val title = runCatching { call(TdApi.GetChat(driveChatId)).title }.getOrDefault("Drive")
        val id = call(TdApi.CreateNewSupergroupChat("$DATA_TITLE · $title", false, true, "$DATA_MARK$driveChatId — $APP_NAME keeps its file index here. Do not delete.", null, 0, false)).id
        runCatching { call(TdApi.AddChatToList(id, TdApi.ChatListArchive())) } // out of sight on the phone
        dataCache = (dataCache ?: emptyMap()) + (driveChatId to id)
        return id
    }

    /** Speed limit: 0 = unlimited. Any limit routes TDLib through the local throttling SOCKS5 proxy. */
    suspend fun applySpeedLimit(upBytesPerSec: Long, downBytesPerSec: Long) {
        donat.teledrive.ThrottleProxy.upBytesPerSec = upBytesPerSec
        donat.teledrive.ThrottleProxy.downBytesPerSec = downBytesPerSec
        runCatching { call(TdApi.GetProxies()).proxies.filter { it.proxy.server == "127.0.0.1" }.forEach { call(TdApi.RemoveProxy(it.id)) } }
        if (upBytesPerSec > 0 || downBytesPerSec > 0) {
            val port = donat.teledrive.ThrottleProxy.start()
            call(TdApi.AddProxy(TdApi.Proxy("127.0.0.1", port, TdApi.ProxyTypeSocks5("", "")), true, "$APP_NAME speed limit"))
        } else {
            runCatching { call(TdApi.DisableProxy()) }
        }
    }

    suspend fun createDriveChannel(name: String): Long =
        call(TdApi.CreateNewSupergroupChat(name, false, true, "$DRIVE_MARK Do not delete or post here.", null, 0, false)).id

    internal suspend fun manifestDocument(chatId: Long): TdApi.Message? {
        val pinned = try {
            call(TdApi.GetChatPinnedMessage(chatId))
        } catch (e: StorageException) {
            if (e.message?.contains("404") == true) return null else throw e
        }
        val name = (pinned.content as? TdApi.MessageDocument)?.document?.fileName ?: return null
        return pinned.takeIf { name.startsWith(Manifest.FILE_PREFIX) }
    }

    private suspend fun <T> mapErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val te = (e as? TelegramError) ?: (e as? CompletionException)?.cause as? TelegramError ?: throw e
        throw if (te.errorCode == 429) {
            RateLimitedException(Regex("\\d+").find(te.errorMessage)?.value?.toLong() ?: 30)
        } else {
            StorageException("Telegram ${te.errorCode}: ${te.errorMessage}")
        }
    }
}

/** One private channel = one drive. remoteRef = Telegram message id. */
class TelegramProvider(private val account: TelegramAccount, private val chatId: Long, private val tmpDir: File) : StorageProvider {
    override val maxPartSize = 512L * 1024 * 1024 // well under Telegram's 2000 MiB, keeps retries cheap

    /** No caption: on the phone the channel shows just the file name. A split part is named by [label] (e.g. "movie.mkv.part1of3"). */
    override suspend fun uploadPart(localPath: String, offset: Long, size: Long, label: String, onProgress: (Long) -> Unit): String {
        val whole = offset == 0L && size == File(localPath).length()
        val dir = File(tmpDir, java.util.UUID.randomUUID().toString())
        val path = if (whole) localPath else File(dir.apply { mkdirs() }, label).also { copyRange(File(localPath), offset, size, it) }.path
        try {
            return account.sendDocument(chatId, path, "", onProgress).id.toString()
        } finally {
            dir.deleteRecursively()
        }
    }

    override suspend fun downloadPart(remoteRef: String, destPath: String, onProgress: (Long) -> Unit) =
        account.downloadDocument(chatId, remoteRef.toLong(), File(destPath), onProgress)

    override suspend fun deletePart(remoteRef: String) {
        account.call(TdApi.DeleteMessages(chatId, longArrayOf(remoteRef.toLong()), true))
    }

    override suspend fun thumbnail(remoteRef: String, destPath: String) = account.downloadThumbnail(chatId, remoteRef.toLong(), File(destPath))

    override suspend fun readRange(remoteRef: String, offset: Long, length: Long) = account.readRange(chatId, remoteRef.toLong(), offset, length)

    override suspend fun evict(remoteRef: String) = account.evict(chatId, remoteRef.toLong())

    @Volatile private var dataChat: Long? = null

    /** The archived data channel for this drive, or null for a legacy drive that has not saved since the update. */
    private suspend fun dataChat(create: Boolean): Long? =
        dataChat ?: (account.dataChannels()[chatId] ?: if (create) account.createDataChannel(chatId) else null)?.also { dataChat = it }

    /** The data channel was deleted (by hand, on the phone…): the next save makes a new one from this PC's index. */
    private fun gone(e: StorageException) = GONE.containsMatchIn(e.message.orEmpty())

    private fun forgetDataChat() {
        dataChat = null
        account.forgetDataChannel(chatId)
    }

    /** Where the newest manifest lives: data channel if it has one, else the drive channel (legacy). */
    private suspend fun manifestSource(): Pair<Long, TdApi.Message>? {
        dataChat(false)?.let { d ->
            try {
                account.manifestDocument(d)?.let { return d to it }
                // Pin moved to something else? The data channel holds only manifests: the newest message id is the current one.
                account.clutter(d).manifests.firstOrNull()?.let { return d to account.call(TdApi.GetMessage(d, it)) }
            } catch (e: StorageException) {
                if (!gone(e)) throw e
                Log.i("data channel of drive $chatId is gone (${e.message})")
                forgetDataChat()
            }
        }
        return account.manifestDocument(chatId)?.let { chatId to it }
    }

    override suspend fun manifestVersion(): String? = manifestSource()?.let { (chat, msg) -> "$chat:${msg.id}" }

    override suspend fun readManifest(): ByteArray? = manifestSource()?.let { (chat, msg) -> download(chat, msg.id) }

    override suspend fun readManifest(version: String): ByteArray? {
        val (chat, msg) = version.split(':').let { it[0].toLong() to it[1].toLong() }
        return try {
            download(chat, msg)
        } catch (e: StorageException) {
            if (gone(e) || "404" in e.message.orEmpty() || "not a file" in e.message.orEmpty()) null else throw e // pruned or deleted
        }
    }

    override suspend fun manifestVersions(): List<String> {
        val d = dataChat(false) ?: return listOfNotNull(manifestVersion())
        return try {
            account.clutter(d).manifests.map { "$d:$it" }
        } catch (e: StorageException) {
            if (!gone(e)) throw e
            forgetDataChat()
            emptyList()
        }
    }

    override suspend fun listObjects(): List<RemoteObject> = account.files(chatId)

    private suspend fun download(chat: Long, msg: Long): ByteArray {
        val tmp = File(tmpDir, "manifest-read-$chat-$msg.json")
        try {
            account.downloadDocument(chat, msg, tmp) {}
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    /**
     * Each revision is a new pinned message (never edit-in-place: the old one stays valid until the new one is confirmed).
     * Afterwards only the newest [KEEP_MANIFESTS] are kept as history; older ones (and their pins) are deleted.
     * A deleted data channel is replaced by a new one (this PC's index is complete, so nothing is lost).
     */
    override suspend fun writeManifest(data: ByteArray): String {
        val file = File(tmpDir, "${Manifest.FILE_PREFIX}.json").apply { writeBytes(data) }
        try {
            var d = dataChat(create = true)!!
            val msg = try {
                account.sendDocument(d, file.path, MANIFEST_CAPTION, {})
            } catch (e: StorageException) {
                if (!gone(e)) throw e
                Log.i("data channel of drive $chatId is gone (${e.message}) — creating a new one")
                forgetDataChat()
                d = dataChat(create = true)!!
                account.sendDocument(d, file.path, MANIFEST_CAPTION, {})
            }
            account.call(TdApi.PinChatMessage(d, msg.id, true, false))
            runCatching { pruneData(d) } // housekeeping only; a failure here never fails the save
            return "$d:${msg.id}"
        } finally {
            file.delete()
        }
    }

    /** Deletes the drive from Telegram for good: the drive channel (every file) first, then its data channel (manifests). */
    suspend fun deleteDrive() {
        val d = runCatching { dataChat(false) }.getOrNull()
        try {
            account.call(TdApi.DeleteChat(chatId))
        } catch (e: StorageException) {
            if (!gone(e)) throw e // already deleted (e.g. on the phone): nothing left to do there
        }
        d?.let { runCatching { account.call(TdApi.DeleteChat(it)) } } // without its drive channel it's never listed anyway
    }

    /** The data channel holds only manifests: keep the newest [KEEP_MANIFESTS] as history, drop the pin notices. */
    private suspend fun pruneData(d: Long) = account.clutter(d).let { it.manifests.drop(KEEP_MANIFESTS) + it.pins }.also { account.deleteMessages(d, it) }.size

    /**
     * Startup housekeeping. Manifests and their pin notices never belong in the drive channel (old versions put them
     * there): delete them, but only once the data channel holds the current manifest. Old "<uuid>.p0" captions are
     * cleared, so the phone shows just file names. Remembered per drive (one history walk).
     */
    override suspend fun tidy() {
        val d = dataChat(false) ?: return
        account.manifestDocument(d) ?: return
        val pruned = pruneData(d)
        if (Prefs["tidy3-$chatId"] == null) {
            val c = account.clutter(chatId)
            account.deleteMessages(chatId, c.manifests + c.pins)
            Log.i("tidy: removed ${c.manifests.size} manifest(s) and ${c.pins.size} pin notice(s); clearing ${c.labelled.size} caption(s)")
            account.clearCaptions(chatId, c.labelled)
            Prefs["tidy3-$chatId"] = "done"
            Log.i("tidy: drive channel clean")
        }
        if (pruned > 0) Log.i("tidy: removed $pruned old manifest(s)/pin notice(s) from the data channel")
        if (Prefs["rebrand-$chatId"] == null) { account.rebrand(chatId, d); Prefs["rebrand-$chatId"] = "done"; Log.i("tidy: channels renamed to $APP_NAME") }
    }

    companion object {
        const val KEEP_MANIFESTS = 5
    }

    private fun copyRange(src: File, offset: Long, size: Long, dest: File) {
        RandomAccessFile(src, "r").channel.use { input ->
            RandomAccessFile(dest, "rw").channel.use { out ->
                out.truncate(0)
                var done = 0L
                while (done < size) done += input.transferTo(offset + done, size - done, out)
            }
        }
    }
}
