package donat.teledrive.core

enum class FileCategory(val label: String) {
    IMAGES("Images"), AUDIO("Audio"), VIDEO("Video"), DOCUMENTS("Documents"), OTHERS("Others");

    companion object {
        private val docExt = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "md", "csv", "epub", "json", "xml", "html", "htm",
        )
        private val imageExt = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "svg", "ico")
        private val audioExt = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma")
        private val videoExt = setOf("mp4", "mkv", "mov", "avi", "webm", "wmv", "m4v", "3gp", "flv")

        /** MIME first, extension as fallback (Windows often reports application/octet-stream). */
        fun of(mime: String, name: String): FileCategory {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mime.startsWith("image/") || ext in imageExt -> IMAGES
                mime.startsWith("audio/") || ext in audioExt -> AUDIO
                mime.startsWith("video/") || ext in videoExt -> VIDEO
                mime.startsWith("text/") || mime == "application/pdf" || "document" in mime || "msword" in mime || ext in docExt -> DOCUMENTS
                else -> OTHERS
            }
        }
    }
}
