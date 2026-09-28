package dev.kortex.links.domain.model

import android.net.Uri
import kotlin.collections.contains

/** Host of a well-formed http(s) URL without "www.", or null while the address isn't usable yet. */
fun linkDomain(url: String): String? {
    val uri = Uri.parse(url.trim())
    if (uri.scheme !in setOf("http", "https")) return null
    val host = uri.host?.takeIf { '.' in it && !it.startsWith('.') && !it.endsWith('.') } ?: return null
    return host.removePrefix("www.")
}