package donat.teledrive

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Speed limit for Telegram traffic. TDLib has no bandwidth setting, but it can use a SOCKS5 proxy:
 * this is a tiny loopback-only SOCKS5 (CONNECT, no auth) that paces bytes with one token bucket per direction.
 * Limits change live (0 = unlimited).
 */
object ThrottleProxy {
    @Volatile var upBytesPerSec = 0L
    @Volatile var downBytesPerSec = 0L

    private var server: ServerSocket? = null
    private val up = Bucket { upBytesPerSec }
    private val down = Bucket { downBytesPerSec }

    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = s
        thread(isDaemon = true, name = "throttle-proxy") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "throttle-conn") { runCatching { handle(c) } }
            }
        }
        return s.localPort
    }

    private fun handle(client: Socket) = client.use {
        val input = DataInputStream(client.getInputStream())
        val out = client.getOutputStream()
        if (input.readUnsignedByte() != 5) return
        input.skipNBytes(input.readUnsignedByte().toLong()) // offered auth methods
        out.write(byteArrayOf(5, 0)) // "no authentication"
        input.readUnsignedByte()
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte()
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(input.readNBytes(4)).hostAddress
            3 -> String(input.readNBytes(input.readUnsignedByte()))
            4 -> InetAddress.getByAddress(input.readNBytes(16)).hostAddress
            else -> return
        }
        val port = input.readUnsignedShort()
        val reply = { code: Int -> out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); out.flush() }
        if (cmd != 1) return reply(7) // only CONNECT
        val remote = runCatching { Socket(host, port).apply { tcpNoDelay = true } }.getOrElse { return reply(5) }
        remote.use {
            reply(0)
            val upstream = thread(isDaemon = true, name = "throttle-up") { pump(input, remote.getOutputStream(), up); close(client, remote) }
            pump(remote.getInputStream(), out, down)
            close(client, remote)
            upstream.join()
        }
    }

    private fun pump(from: InputStream, to: OutputStream, bucket: Bucket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                bucket.take(n)
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
            // connection closed
        }
    }

    private fun close(vararg s: Socket) = s.forEach { runCatching { it.close() } }

    /** Global token bucket: all connections in one direction share the rate. Burst = 1 second's worth. */
    class Bucket(private val rate: () -> Long) {
        private var tokens = 0.0
        private var last = System.nanoTime()

        @Synchronized
        fun take(n: Int) {
            val r = rate()
            if (r <= 0) return
            val now = System.nanoTime()
            tokens = minOf(r.toDouble(), tokens + (now - last) / 1e9 * r)
            last = now
            tokens -= n
            if (tokens < 0) Thread.sleep((-tokens / r * 1000).toLong())
        }
    }
}
