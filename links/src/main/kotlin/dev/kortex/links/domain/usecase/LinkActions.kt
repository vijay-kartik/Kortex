package dev.kortex.links.domain.usecase

import dev.kortex.links.domain.repository.LinksRepository

/** Deletes the link, its thumbnail, and any tag it leaves with no links. */
class DeleteLink(private val repository: LinksRepository) {
    suspend operator fun invoke(linkId: Long) = repository.deleteLink(linkId)
}

/** Replaces the link's tags, creating new ones and dropping ones no link carries any more. */
class SetLinkTags(private val repository: LinksRepository) {
    suspend operator fun invoke(linkId: Long, tagNames: List<String>) = repository.setLinkTags(linkId, tagNames)
}

/** Retitles the link, trimmed. A blank title clears it, so the card falls back to the link's domain. */
class RenameLink(private val repository: LinksRepository) {
    suspend operator fun invoke(linkId: Long, title: String) = repository.setLinkTitle(linkId, title.trim())
}

/** Creates a tag before any link carries it. A blank name, or one that exists ignoring case, is a no-op. */
class CreateTag(private val repository: LinksRepository) {
    suspend operator fun invoke(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) repository.createTag(trimmed)
    }
}
