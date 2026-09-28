package dev.kortex.links.domain.usecase

import dev.kortex.links.domain.model.LinkAnalysis
import dev.kortex.links.domain.model.LinkImageState
import dev.kortex.links.domain.model.PageReadPhase
import dev.kortex.links.domain.model.linkDomain
import dev.kortex.links.domain.port.ImageDownloads
import dev.kortex.links.domain.port.PageReader
import dev.kortex.links.domain.port.TagSuggester
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Reads [url]'s page in phases: the page, then tag suggestions, with the share image downloading
 * alongside. Stays open while the image can still change, so a retry shows up; cancel it (e.g. with
 * `flatMapLatest`) when the address changes.
 */
class AnalyzeLink(
    private val pages: PageReader,
    private val suggester: TagSuggester,
    private val images: ImageDownloads,
) {
    operator fun invoke(url: String): Flow<LinkAnalysis> = flow {
        if (linkDomain(url) == null) return@flow emit(LinkAnalysis(url))
        emit(LinkAnalysis(url, phase = PageReadPhase.ReadingPage))

        val page = pages.read(url)
        val read = LinkAnalysis(
            url = url,
            suggestedTitle = page.title,
            candidateTags = tagCandidates(page),
            phase = PageReadPhase.SuggestingTags,
            imageUrl = page.imageUrl,
        )
        // Null until the suggester answers.
        val tags: Flow<List<String>?> = flow {
            emit(null)
            emit(suggester.suggest(page).map { it.tagName })
        }
        val image: Flow<LinkImageState?> = page.imageUrl?.let(images::image) ?: flowOf(null)
        emitAll(
            combine(tags, image) { suggested, download ->
                read.copy(
                    suggestedTags = suggested.orEmpty(),
                    phase = if (suggested == null) PageReadPhase.SuggestingTags else PageReadPhase.Done,
                    image = download,
                )
            },
        )
    }
}

/** Tries a failed share-image download again; [AnalyzeLink]'s flow for that page follows along. */
class RetryLinkImage(private val images: ImageDownloads) {
    operator fun invoke(imageUrl: String) = images.retry(imageUrl)
}
