package dev.kortex.links.data

import java.net.MalformedURLException
import java.net.URL

/**
 * The identity of a link's address: two URLs with the same key are the same saved link.
 *
 * Ignores what varies between shares of one page — http vs https, a leading `www.`, host case,
 * default ports, a trailing slash, the `#fragment` and tracking parameters (`utm_*`, `fbclid`,
 * `gclid`). Path case and every other query parameter still count, since they can select a
 * different page. Non-http(s) or unparseable input is only trimmed.
 *
 * `https://www.Example.com/post/?utm_source=x#top` → `example.com/post`
 */
fun linkUrlKey(url: String): String {
    val trimmed = url.trim()
    val parsed = try {
        URL(trimmed)
    } catch (e: MalformedURLException) {
        return trimmed
    }
    if (!parsed.protocol.equals("http", ignoreCase = true) && !parsed.protocol.equals("https", ignoreCase = true)) {
        return trimmed
    }

    val host = parsed.host.lowercase().removePrefix("www.")
    val port = parsed.port.takeIf { it != -1 && it != 80 && it != 443 }?.let { ":$it" }.orEmpty()
    val path = parsed.path.trimEnd('/')
    val query = parsed.query
        ?.split('&')
        ?.filter { it.isNotEmpty() && !isTrackingParam(it.substringBefore('=')) }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "&", prefix = "?")
        .orEmpty()
    return host + port + path + query
}

private fun isTrackingParam(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("utm_") || lower == "fbclid" || lower == "gclid"
}
