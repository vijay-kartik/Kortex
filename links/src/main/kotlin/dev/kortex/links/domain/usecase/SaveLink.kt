package dev.kortex.links.domain.usecase

import dev.kortex.links.domain.model.LinkDraft
import dev.kortex.links.domain.port.Clock
import dev.kortex.links.domain.repository.LinksRepository
import dev.kortex.links.domain.repository.SaveLinkResult

/**
 * Saves right away, stamped now. Doesn't wait for the page or its image: whatever is still
 * loading finishes in the background and lands on the saved link.
 */
class SaveLink(private val repository: LinksRepository, private val clock: Clock) {
    suspend operator fun invoke(draft: LinkDraft): SaveLinkResult =
        repository.saveLink(draft.copy(url = draft.url.trim(), title = draft.title.trim()), clock.nowMillis())
}
