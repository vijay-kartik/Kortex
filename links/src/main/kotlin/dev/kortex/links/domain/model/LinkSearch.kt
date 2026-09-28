package dev.kortex.links.domain.model

/** Whether [query] appears in the title, the address or a tag name, ignoring case. An empty query matches every link. */
fun Link.matches(query: String): Boolean =
    query.isEmpty() ||
        title.contains(query, ignoreCase = true) ||
        url.contains(query, ignoreCase = true) ||
        tags.any { it.contains(query, ignoreCase = true) }
