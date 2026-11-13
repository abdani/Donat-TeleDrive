package donat.teledrive

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import donat.teledrive.data.DriveFile
import donat.teledrive.ui.isSaved
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What the log may say about a LAN request: the route name only — the rest of the path holds the key / share token. */
internal fun logRoute(rawPath: String) = "/${rawPath.split('/').getOrNull(1).orEmpty()}/…"

/** Pages and scripts must never run on the LAN origin (its URLs hold the key): such files are always downloaded. */
internal fun activeContent(f: DriveFile) =
    f.mime.lowercase().let { m -> "html" in m || "xml" in m || "svg" in m || "javascript" in m } ||
        f.name.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "xhtml", "svg", "xml", "js", "mjs")

/**
 * Local-network companion: a small web page (browse, download, stream, upload) for phones/PCs on the same Wi-Fi,
 * plus expiring per-file share links. Off by default; every URL carries a random key.
 * ponytail: LAN only — internet-wide links need a relay server (V2).
 */
object LanServer {
    private class Share(val file: DriveFile, val expires: Long)

    private const val PORT = 8765
    private var server: HttpServer? = null
    private var current: () -> DriveService? = { null }
    private val shares = ConcurrentHashMap<String, Share>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val inbox = File(appDataDir, "lan-inbox").apply { mkdirs() }

    val running get() = server != null

    /** Access key for the web page; regenerate to revoke every old link. 128 bits (old 48-bit keys are replaced once). */
    var key: String = Prefs["lanKey"]?.takeIf { it.length >= 32 } ?: newKey()
        private set

    private fun newKey() = newId().replace("-", "").also { Prefs["lanKey"] = it }

    fun regenerateKey() { key = newKey(); shares.clear() }

    fun start(service: () -> DriveService?) {
        current = service
        if (server != null) return
        // ponytail: files sent from phones wait here until uploaded; anything older than a week is surely done.
        inbox.listFiles()?.filter { now() - it.lastModified() > 7 * 86_400_000L }?.forEach { it.deleteRecursively() }
        val s = runCatching { HttpServer.create(InetSocketAddress(PORT), 0) }.getOrElse { HttpServer.create(InetSocketAddress(0), 0) }
        // The URL holds the key / share token: log only the route name, never the path.
        s.createContext("/") { ex -> try { route(ex) } catch (e: Exception) { Log.e("lan ${ex.requestMethod} ${logRoute(ex.requestURI.rawPath)}", e); runCatching { ex.sendResponseHeaders(500, -1) } } finally { ex.close() } }
        s.executor = Executors.newCachedThreadPool { Thread(it, "lan").apply { isDaemon = true } }
        s.start()
        server = s
        Log.i("LAN server on port ${s.address.port}")
    }

    fun stop() { server?.stop(0); server = null }

    fun baseUrl(): String = "http://${lanAddress()}:${server?.address?.port ?: PORT}"
    fun webUrl() = "${baseUrl()}/w/$key/"

    fun share(file: DriveFile, hours: Long): String {
        val token = newId().replace("-", "")
        shares.values.removeIf { it.expires <= now() }
        shares[token] = Share(file, now() + hours * 3_600_000)
        return "${baseUrl()}/s/$token/" + enc(file.name)
    }

    /** First private IPv4 of an active, non-virtual adapter (what phones on the same Wi-Fi can reach). */
    fun lanAddress(): String = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.displayName.contains("Virtual", true) }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: "127.0.0.1"

    private fun route(ex: HttpExchange) {
        val parts = ex.requestURI.rawPath.split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, Charsets.UTF_8) }
        val service = current() ?: return text(ex, 503, "$APP_NAME has no drive open.")
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer") // the key is in the URL
        when {
            parts.size >= 2 && parts[0] == "s" -> {
                val share = shares[parts[1]]?.takeIf { it.expires > now() } ?: return text(ex, 404, "This link has expired.")
                // Re-read: a file trashed, deleted or moved to another drive since sharing is no longer served.
                val f = runBlocking { liveFile(service, share.file.id) } ?: return text(ex, 404, "This file is no longer shared.")
                StreamServer.serveFile(ex, service, f, lock(f), attachment = activeContent(f))
            }
            parts.size >= 2 && parts[0] == "w" && sameKey(parts[1]) -> web(ex, service, parts.drop(2))
            else -> text(ex, 404, "Not found")
        }
    }

    private fun sameKey(given: String) = java.security.MessageDigest.isEqual(given.toByteArray(), key.toByteArray())

    /** A saved, not-trashed file of the open drive — never another drive's, a trashed or a half-uploaded one. */
    internal suspend fun liveFile(service: DriveService, id: String): DriveFile? =
        service.dao.file(id)?.takeIf { it.driveId == service.drive.id && it.trashedAt == null && it.versionOf == null && it.isSaved }

    /** A folder of the open drive (null = top level or anything that isn't one — e.g. a crafted ?f=). */
    private suspend fun liveFolder(service: DriveService, id: String?) =
        id?.let { service.dao.folder(it) }?.takeIf { it.driveId == service.drive.id && it.trashedAt == null }

    private fun web(ex: HttpExchange, service: DriveService, rest: List<String>) = runBlocking {
        when {
            rest.firstOrNull() == "file" && rest.size >= 2 -> {
                val f = liveFile(service, rest[1]) ?: return@runBlocking text(ex, 404, "File not found")
                StreamServer.serveFile(ex, service, f, lock(f), attachment = ex.requestURI.query == "dl" || activeContent(f))
            }
            rest.firstOrNull() == "up" && rest.size >= 3 && ex.requestMethod == "PUT" -> {
                // An unknown folder id would leave the upload invisible (no such parent) — it goes to the top level instead.
                val folder = liveFolder(service, rest[1].takeIf { it != "root" })
                val dest = File(File(inbox, newId()).apply { mkdirs() }, safeName(rest[2]))
                ex.requestBody.use { input -> dest.outputStream().use { input.copyTo(it) } }
                service.enqueueUpload(dest, folder?.id)
                text(ex, 200, "ok")
            }
            else -> {
                // Only a real folder id ever reaches the page (it is written into a link and a script).
                val folder = liveFolder(service, ex.requestURI.query?.removePrefix("f="))
                val folders = service.dao.folders(service.drive.id, folder?.id).first()
                val files = service.dao.files(service.drive.id, folder?.id).first()
                html(ex, page(service.drive.name, folder?.name, folder?.parentId, folder?.id, folders.map { it.id to it.name }, files))
            }
        }
    }

    private fun page(drive: String, folderName: String?, parentId: String?, folderId: String?, folders: List<Pair<String, String>>, files: List<DriveFile>) = buildString {
        val base = "/w/$key/"
        append("""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(folderName ?: drive)} · $APP_NAME</title><style>
:root{--bg:#f7f8fa;--card:#fff;--fg:#1a1c1e;--mut:#5a6170;--acc:#1f6feb;--line:#e1e5eb}
@media (prefers-color-scheme:dark){:root{--bg:#121417;--card:#1c1f24;--fg:#e3e5e8;--mut:#a9b0bc;--acc:#8ab4f8;--line:#2a2e35}}
body{margin:0;font:15px system-ui,Segoe UI,sans-serif;background:var(--bg);color:var(--fg)}
header{position:sticky;top:0;background:var(--card);border-bottom:1px solid var(--line);padding:12px 16px;display:flex;gap:12px;align-items:center}
h1{font-size:17px;margin:0;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
a{color:var(--acc);text-decoration:none} ul{list-style:none;margin:0;padding:0}
li{display:flex;align-items:center;gap:12px;padding:12px 16px;border-bottom:1px solid var(--line)}
.ic{width:40px;height:40px;border-radius:50%;background:var(--line);display:grid;place-items:center;flex:none}
.n{flex:1;min-width:0} .n a,.n span{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap} small{color:var(--mut)}
label.up{background:var(--acc);color:var(--bg);padding:8px 14px;border-radius:20px;cursor:pointer}
#st{padding:8px 16px;color:var(--mut)}</style></head><body>
<header>""")
        if (folderName != null) append("""<a href="$base${parentId?.let { "?f=$it" } ?: ""}">&#8592;</a>""")
        append("""<h1>${esc(folderName ?: drive)}</h1><label class="up">Upload<input id="u" type="file" multiple hidden></label></header><div id="st"></div><ul>""")
        folders.forEach { (id, name) -> append("""<li><div class="ic">&#128193;</div><div class="n"><a href="$base?f=$id">${esc(name)}</a><small>Folder</small></div></li>""") }
        files.forEach { f ->
            val url = "${base}file/${f.id}/${enc(f.name)}"
            append("""<li><div class="ic">&#128196;</div><div class="n"><a href="$url" target="_blank">${esc(f.name)}</a><small>${fmt(f.size)}</small></div><a href="$url?dl">&#11015;</a></li>""")
        }
        if (folders.isEmpty() && files.isEmpty()) append("<li><small>This folder is empty.</small></li>")
        append("""</ul><script>
document.getElementById('u').onchange=async e=>{const st=document.getElementById('st');const fs=[...e.target.files];
for(let i=0;i<fs.length;i++){st.textContent='Sending '+(i+1)+'/'+fs.length+': '+fs[i].name;
await fetch('${base}up/${folderId ?: "root"}/'+encodeURIComponent(fs[i].name),{method:'PUT',body:fs[i]});}
st.textContent='Sent '+fs.length+' file(s). $APP_NAME is uploading them to Telegram.';};
</script></body></html>""")
    }

    private fun lock(f: DriveFile) = locks.computeIfAbsent(f.id) { Mutex() }
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun fmt(b: Long) = if (b < 1024) "$b B" else if (b < 1 shl 20) "%.1f KB".format(b / 1024.0) else if (b < 1 shl 30) "%.1f MB".format(b / 1048576.0) else "%.1f GB".format(b / 1073741824.0)

    private fun html(ex: HttpExchange, body: String) = send(ex, 200, "text/html; charset=utf-8", body)
    private fun text(ex: HttpExchange, code: Int, body: String) = send(ex, code, "text/plain; charset=utf-8", body)
    private fun send(ex: HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }
}
