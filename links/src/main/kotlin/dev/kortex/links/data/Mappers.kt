package dev.kortex.links.data

import dev.kortex.links.domain.model.Link

fun LinkWithTags.toDomain(): Link = link.toDomain(tags = tagNames)

/**
 * [tags] defaults to none: a bare row doesn't carry them. Map a [LinkWithTags] instead wherever
 * the tags matter.
 */
fun LinkEntity.toDomain(tags: List<String> = emptyList()): Link = Link(
    id = id,
    url = url,
    title = title,
    createdAtMillis = createdAtMillis,
    // A hidden image stays on disk so it can come back, but nothing shows it meanwhile.
    thumbnailPath = imagePath.takeUnless { imageHidden },
    tags = tags,
)
