package donat.teledrive

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import donat.teledrive.core.StorageException
import donat.teledrive.data.DriveFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loopback-only HTTP server that streams drive files straight from Telegram with Range support,
 * so a player can start and seek without downloading the whole file. Each URL carries a random token.
 */
object StreamServer {
    private class Source(val service: DriveService, val file: DriveFile, val lock: Mutex = Mutex())

    private const val CHUNK = 1 shl 20
    private val sources = ConcurrentHashMap<String, Source>()
    private val server by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/s/") { ex -> try { handle(ex) } finally { ex.close() } }
            executor = Executors.newCachedThreadPool { Thread(it, "stream").apply { isDaemon = true } }
            start()
        }
    }

    fun open(service: DriveService, file: DriveFile): String {
        val token = newId()
        sources[token] = Source(service, file)
        val name = URLEncoder.encode(file.name, Charsets.UTF_8).replace("+", "%20") // players sniff the extension
        return "http://127.0.0.1:${server.address.port}/s/$token/$name"
    }

    fun close(url: String) {
        val src = sources.remove(url.substringAfter("/s/").substringBefore('/')) ?: return
        CoroutineScope(Dispatchers.IO).launch { runCatching { src.service.evictStream(src.file) } } // free TDLib's partial copy
    }

    private fun handle(ex: HttpExchange) {
        val src = sources[ex.requestURI.path.removePrefix("/s/").substringBefore('/')] ?: return ex.sendResponseHeaders(404, -1)
        serveFile(ex, src.service, src.file, src.lock)
    }

    /** Streams [file] with HTTP Range support (shared by the loopback player server and the LAN server). */
    fun serveFile(ex: HttpExchange, service: DriveService, file: DriveFile, lock: Mutex, attachment: Boolean = false) {
        val size = file.size
        val range = ex.requestHeaders.getFirst("Range")
        val (start, end) = parseRange(range, size) ?: run {
            ex.responseHeaders.add("Content-Range", "bytes */$size")
            return ex.sendResponseHeaders(416, -1)
        }
        ex.responseHeaders.add("Content-Type", file.mime)
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        if (attachment) ex.responseHeaders.add("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(file.name, Charsets.UTF_8).replace("+", "%20"))
        if (range != null) ex.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
        val code = if (range != null) 206 else 200
        if (ex.requestMethod == "HEAD") return ex.sendResponseHeaders(code, -1)
        ex.sendResponseHeaders(code, if (end < start) -1 else end - start + 1) // 0-byte file: -1 = no body (0 would mean chunked)
        var pos = start
        try {
            while (pos <= end) {
                // One TDLib range download per file at a time; a new offset would cancel the previous one.
                val chunk = runBlocking { lock.withLock { service.readRange(file, pos, minOf(CHUNK.toLong(), end - pos + 1).toInt()) } }
                if (chunk.isEmpty()) throw StorageException("No data at $pos") // would otherwise spin forever on this thread
                ex.responseBody.write(chunk)
                pos += chunk.size
            }
        } catch (_: IOException) {
            // player closed the connection (seek / stop) — normal
        } catch (e: Exception) {
            Log.e("stream ${file.name} at $pos", e)
        }
    }

    /** "bytes=a-b", "bytes=a-", "bytes=-n" → inclusive [start, end]; null = unsatisfiable. */
    fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
        if (header == null) return 0L to size - 1
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val a = spec.substringBefore('-').trim()
        val b = spec.substringAfter('-').trim()
        val (start, end) = when {
            a.isEmpty() -> maxOf(0, size - (b.toLongOrNull() ?: return null)) to size - 1
            else -> (a.toLongOrNull() ?: return null) to (b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1))
        }
        return if (start in 0..end && start < size) start to end else null
    }
}
