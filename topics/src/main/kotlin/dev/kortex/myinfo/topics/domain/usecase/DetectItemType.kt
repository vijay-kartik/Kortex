package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.ItemType

/**
 * Guesses what pasted text is, for the quick-capture sheet (Figma: Topics 1d). The user can
 * always change the guess, so this stays a cheap offline check on the address: known video
 * hosts, then file extensions. Any other address is a plain link; anything else is a note.
 */
class DetectItemType {
    operator fun invoke(text: String): Detection {
        val uri = WebAddress.parse(text) ?: return Detection(ItemType.Note, url = null)
        val host = uri.host.lowercase().removePrefix("www.").removePrefix("m.")
        val path = uri.path.orEmpty().lowercase()
        val type = when {
            isVideo(host, path) -> ItemType.Video
            DocExtensions.any(path::endsWith) -> ItemType.Doc
            ImageExtensions.any(path::endsWith) -> ItemType.Image
            else -> ItemType.Link
        }
        return Detection(type, uri.toString())
    }

    private fun isVideo(host: String, path: String): Boolean = when (host) {
        "youtube.com" -> path == "/watch" || VideoPathPrefixes.any(path::startsWith)
        "youtu.be" -> path.length > 1
        "vimeo.com" -> VimeoVideoPath.matches(path)
        else -> false
    }

    private companion object {
        val VideoPathPrefixes = listOf("/shorts/", "/live/", "/embed/")
        val VimeoVideoPath = Regex("""^/\d+/?$""")
        val DocExtensions = listOf(".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".csv")
        val ImageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic")
    }
}

/** The guessed [type]; [url] is the address, scheme added, or null when the text is a note. */
data class Detection(val type: ItemType, val url: String?)
