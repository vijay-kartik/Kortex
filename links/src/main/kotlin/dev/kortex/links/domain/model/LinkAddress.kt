package dev.kortex.links.domain.model

import java.net.MalformedURLException
import java.net.URL

/** Host of a well-formed http(s) URL without "www.", or null while the address isn't usable yet. */
fun linkDomain(url: String): String? {
    // java.net.URL rather than android.net.Uri keeps this JVM-testable; like Uri, it doesn't reject odd path characters.
    val address = try {
        URL(url.trim())
    } catch (e: MalformedURLException) {
        return null
    }
    if (address.protocol !in setOf("http", "https")) return null
    val host = address.host?.takeIf { '.' in it && !it.startsWith('.') && !it.endsWith('.') } ?: return null
    return host.removePrefix("www.")
}
