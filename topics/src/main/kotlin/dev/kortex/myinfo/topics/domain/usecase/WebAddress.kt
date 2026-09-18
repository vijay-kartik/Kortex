package dev.kortex.myinfo.topics.domain.usecase

import java.net.URI

/** Recognises web addresses in pasted text, with or without a scheme. */
internal object WebAddress {
    // host.tld with optional port and path; the scheme is added before parsing.
    private val schemeless = Regex(
        """^([a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(:\d{1,5})?([/?#]\S*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** [text] as an http(s) address, `https://` added when it has no scheme; null when it isn't one. */
    fun parse(text: String): URI? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
        val candidate = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            schemeless.matches(trimmed) -> "https://$trimmed"
            else -> return null
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        return uri.takeUnless { it.host.isNullOrEmpty() }
    }
}
