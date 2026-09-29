package dev.kortex.myinfo.topics.domain.usecase

/**
 * The id YouTube's embedded player takes, read from a video's address. Only YouTube plays in the
 * app (docs/TOPIC_VIDEOS_PLAN.md); every other video keeps opening outside it.
 */
@JvmInline
value class YouTubeVideoId private constructor(val value: String) {
    companion object {
        /**
         * The id in `youtube.com/watch?v=`, `/shorts/`, `/live/`, `/embed/` and `youtu.be/` addresses,
         * on the `www.`, `m.` and `music.` hosts too; null for anything else, or an id that isn't
         * one YouTube could have made.
         */
        fun parse(url: String): YouTubeVideoId? {
            val uri = WebAddress.parse(url) ?: return null
            val host = uri.host.lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
            val path = uri.path.orEmpty()
            val id = when (host) {
                "youtube.com", "youtube-nocookie.com" -> when {
                    path.trimEnd('/') == "/watch" -> queryParameter(uri.rawQuery, "v")
                    else -> PathPrefixes.firstOrNull { path.startsWith(it, ignoreCase = true) }
                        ?.let { path.substring(it.length).substringBefore('/') }
                }
                "youtu.be" -> path.removePrefix("/").substringBefore('/')
                else -> null
            }
            return id?.takeIf(IdPattern::matches)?.let(::YouTubeVideoId)
        }

        private fun queryParameter(query: String?, name: String): String? =
            query?.split('&')?.firstNotNullOfOrNull { pair ->
                pair.takeIf { it.substringBefore('=') == name }?.substringAfter('=', "")
            }

        private val PathPrefixes = listOf("/shorts/", "/live/", "/embed/")

        // Every video id so far is 11 characters of this alphabet.
        private val IdPattern = Regex("""^[A-Za-z0-9_-]{11}$""")
    }
}
