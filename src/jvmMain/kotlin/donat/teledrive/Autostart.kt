package donat.teledrive

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
import java.io.File

/** "Start with Windows" via HKCU\...\Run (per-user, no admin). */
object Autostart {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = APP_NAME

    fun enabled(): Boolean = runCatching { Advapi32Util.registryValueExists(HKEY_CURRENT_USER, KEY, NAME) }.getOrDefault(false)

    fun set(on: Boolean) {
        // Entry of the old name (pointed at the old project folder) is never left behind.
        runCatching { if (Advapi32Util.registryValueExists(HKEY_CURRENT_USER, KEY, "DonatTeleDrive")) Advapi32Util.registryDeleteValue(HKEY_CURRENT_USER, KEY, "DonatTeleDrive") }
        if (on) Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, KEY, NAME, command())
        else if (enabled()) Advapi32Util.registryDeleteValue(HKEY_CURRENT_USER, KEY, NAME)
    }

    /** Installed exe if we run from one, otherwise the project's run.bat (dev setup). */
    private fun command(): String =
        System.getProperty("jpackage.app-path")?.let { "\"$it\"" }
            ?: "cmd /c start \"\" /min \"${File(System.getProperty("user.dir"), "run.bat").absolutePath}\""
}
