package dev.kortex.links.domain.model

data class Link(
    val id: Long,
    val url: String,
    val title: String,
    val createdAtMillis: Long,
    val thumbnailPath: String?,
    val tags: List<String>
)

data class TagCount(val name: String, val linkCount: Int)