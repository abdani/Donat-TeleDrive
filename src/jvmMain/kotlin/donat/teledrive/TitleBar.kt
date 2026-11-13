package donat.teledrive

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/** Colors the native Windows title bar to match the app theme (Windows 11; dark-mode flag also works on Windows 10). */
object TitleBar {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }
    private const val USE_DARK_MODE = 20
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    /** [caption]/[text] as 0xRRGGBB. */
    fun apply(window: Window, caption: Int, text: Int, dark: Boolean) {
        val api = dwm ?: return
        runCatching {
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
            fun set(attr: Int, v: Int) = api.DwmSetWindowAttribute(hwnd, attr, IntByReference(v), 4)
            set(USE_DARK_MODE, if (dark) 1 else 0)
            set(CAPTION_COLOR, bgr(caption))
            set(TEXT_COLOR, bgr(text))
        }
    }

    private fun bgr(rgb: Int) = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF) // COLORREF is 0x00BBGGRR
}
