package donat.teledrive

import java.io.File

/**
 * Tiny diagnostics log at %LOCALAPPDATA%\Donat TeleDrive\app.log (kept under 2 MB).
 * Never log API hash, phone numbers, codes or passwords.
 */
object Log {
    private val file by lazy { File(appDataDir, "app.log").apply { if (length() > 2_000_000) delete() } }

    @Synchronized
    fun i(msg: String) {
        if (System.getProperty("donat.log") == "off") return // unit tests must not write into the real app log
        runCatching { file.appendText("${java.time.LocalDateTime.now()} [${Thread.currentThread().name}] $msg\n") }
    }

    fun e(msg: String, t: Throwable) = i("ERROR $msg: ${t::class.simpleName}: ${t.message}")
}
