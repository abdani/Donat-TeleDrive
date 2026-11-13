package donat.teledrive

import donat.teledrive.core.SafetyState
import donat.teledrive.data.DriveFile
import donat.teledrive.ui.fullPhoneNumber
import donat.teledrive.ui.takenDate
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExtrasTest {
    @Test
    fun phoneNumbersForAnyCountry() {
        val full = ::fullPhoneNumber
        assertEquals("+6281234567890", full("62", "0812-3456-7890")) // Indonesia, national leading 0 dropped
        assertEquals("+6281234567890", full("62", "812 3456 7890"))
        assertEquals("+15551234567", full("1", "(555) 123-4567")) // USA / Canada
        assertEquals("+447911123456", full("44", "07911 123456")) // UK mobile
        assertEquals("+60123456789", full("60", "012-345 6789")) // Malaysia
        assertEquals("+33612345678", full("62", "+33 6 12 34 56 78")) // typed with its own "+": the picker is ignored
    }

    @Test
    fun portableModeOnlyWithDataFolder() {
        val dir = java.nio.file.Files.createTempDirectory("dtd-portable").toFile()
        try {
            val exe = java.io.File(dir, "Donat TeleDrive.exe").path
            assertEquals(null, portableDataDir(exe)) // installed / zip without "data": %LOCALAPPDATA%
            java.io.File(dir, "data").mkdirs()
            assertEquals(java.io.File(dir, "data").absoluteFile, portableDataDir(exe))
            assertEquals(null, portableDataDir(null)) // dev run (no exe)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pinHashing() {
        val (salt, hash) = AppLock.make("2468")
        assertTrue(AppLock.matches("2468", salt, hash))
        assertFalse(AppLock.matches("2469", salt, hash))
        assertNotEquals(salt, AppLock.make("2468").first) // fresh random salt every time
    }

    @Test
    fun photoDateFromName() {
        fun f(name: String) = DriveFile("i", "d", null, name, 1, "image/jpeg", null, SafetyState.REMOTE_SAFE, createdAt = 0, updatedAt = 0)
        assertEquals(LocalDate.of(2025, 6, 25), takenDate(f("IMG-20250625-WA0068.jpg")))
        assertEquals(LocalDate.of(2024, 1, 2), takenDate(f("PXL_20240102_101010.jpg")))
        assertEquals(LocalDate.of(2023, 12, 31), takenDate(f("Screenshot 2023-12-31 at 10.00.png")))
    }

    /** Sends 160 KB through the SOCKS5 proxy limited to 64 KB/s upstream: must take ≥ ~2 s and arrive intact. */
    @Test
    fun speedLimitProxy() {
        val echo = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val received = ByteArray(160 * 1024)
        val server = thread { echo.accept().use { s -> DataInputStream(s.getInputStream()).readFully(received) } }
        ThrottleProxy.upBytesPerSec = 64L * 1024
        try {
            Socket(InetAddress.getLoopbackAddress(), ThrottleProxy.start()).use { s ->
                val out = s.getOutputStream()
                val input = DataInputStream(s.getInputStream())
                out.write(byteArrayOf(5, 1, 0)); input.readNBytes(2) // greeting, no auth
                val port = echo.localPort
                out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte())); input.readNBytes(10) // CONNECT
                val data = Random(9).nextBytes(received.size)
                val started = System.nanoTime()
                out.write(data); out.flush()
                server.join(10_000)
                val seconds = (System.nanoTime() - started) / 1e9
                assertContentEquals(data, received)
                assertTrue(seconds >= 1.8, "took ${seconds}s — limit not applied")
            }
        } finally {
            ThrottleProxy.upBytesPerSec = 0
            echo.close()
        }
    }
}
