package dev.kortex.links.domain.usecase

import dev.kortex.links.domain.model.AlreadySavedLink
import dev.kortex.links.domain.model.linkDomain
import dev.kortex.links.domain.repository.LinksRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The saved link [typedUrl] is an address of, following saves as they happen; null while it isn't a usable address. */
class ObserveDuplicate(private val repository: LinksRepository) {
    operator fun invoke(typedUrl: String): Flow<AlreadySavedLink?> =
        if (linkDomain(typedUrl) == null) {
            flowOf(null)
        } else {
            repository.observeSavedLink(typedUrl).map { saved -> saved?.let { AlreadySavedLink(typedUrl, it.title) } }
        }
}
