package donat.teledrive

import com.sun.jna.Native
import kotlinx.coroutines.future.await
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.nfd.NFDOpenDialogArgs
import org.lwjgl.util.nfd.NFDPickFolderArgs
import org.lwjgl.util.nfd.NFDSaveDialogArgs
import org.lwjgl.util.nfd.NFDWindowHandle
import org.lwjgl.util.nfd.NativeFileDialog.*
import java.awt.Window
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Modern Windows file dialogs (IFileOpenDialog: address bar, copyable paths, Quick access) via nativefiledialog.
 * Run on one dedicated COM thread so the UI keeps painting; modal to the app window.
 */
object NativeDialogs {
    var parent: Window? = null
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "file-dialogs").apply { isDaemon = true } }
    private var initialized = false

    suspend fun openFiles(): List<File> = onDialogThread { s ->
        val out = s.callocPointer(1)
        val args = NFDOpenDialogArgs.calloc(s).apply { parentHandle(s)?.let(::parentWindow) }
        when (NFD_OpenDialogMultiple_With(out, args)) {
            NFD_OKAY -> {
                val set = out.get(0)
                val count = IntArray(1).also { NFD_PathSet_GetCount(set, it) }[0]
                (0 until count).map { i ->
                    NFD_PathSet_GetPath(set, i, out)
                    File(out.getStringUTF8(0)).also { NFD_PathSet_FreePath(out.get(0)) }
                }.also { NFD_PathSet_Free(set) }
            }
            NFD_CANCEL -> emptyList()
            else -> error(NFD_GetError() ?: "File dialog failed")
        }
    }

    suspend fun saveFile(defaultName: String): File? = onDialogThread { s ->
        val out = s.callocPointer(1)
        val args = NFDSaveDialogArgs.calloc(s).defaultName(s.UTF8(defaultName)).apply { parentHandle(s)?.let(::parentWindow) }
        result(NFD_SaveDialog_With(out, args), takePath(out))
    }

    suspend fun pickFolder(): File? = onDialogThread { s ->
        val out = s.callocPointer(1)
        val args = NFDPickFolderArgs.calloc(s).apply { parentHandle(s)?.let(::parentWindow) }
        result(NFD_PickFolder_With(out, args), takePath(out))
    }

    private fun takePath(out: org.lwjgl.PointerBuffer): String? =
        if (out.get(0) == 0L) null else out.getStringUTF8(0).also { NFD_FreePath(out.get(0)) }

    private fun result(code: Int, path: String?): File? = when (code) {
        NFD_OKAY -> path?.let(::File)
        NFD_CANCEL -> null
        else -> error(NFD_GetError() ?: "File dialog failed")
    }

    private fun parentHandle(s: MemoryStack): NFDWindowHandle? {
        val w = parent?.takeIf { it.isDisplayable } ?: return null
        return NFDWindowHandle.calloc(s).type(NFD_WINDOW_HANDLE_TYPE_WINDOWS.toLong()).handle(Native.getWindowID(w))
    }

    private suspend fun <T> onDialogThread(block: (MemoryStack) -> T): T = CompletableFuture.supplyAsync({
        if (!initialized) {
            check(NFD_Init() == NFD_OKAY) { NFD_GetError() ?: "Can't initialise file dialogs" }
            initialized = true
        }
        MemoryStack.stackPush().use(block)
    }, thread).await()
}
