package dev.kortex.links.domain.usecase

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.TagCount
import dev.kortex.links.domain.repository.LinksRepository
import kotlinx.coroutines.flow.Flow

/** Every saved link, newest first. */
class ObserveLinks(private val repository: LinksRepository) {
    operator fun invoke(): Flow<List<Link>> = repository.observeLinks()
}

/** Every tag with how many links carry it, unused ones too, by name. */
class ObserveTagCounts(private val repository: LinksRepository) {
    operator fun invoke(): Flow<List<TagCount>> = repository.observeTagCounts()
}

/** Every tag's stored spelling, by name. */
class ObserveTagNames(private val repository: LinksRepository) {
    operator fun invoke(): Flow<List<String>> = repository.observeTagNames()
}
