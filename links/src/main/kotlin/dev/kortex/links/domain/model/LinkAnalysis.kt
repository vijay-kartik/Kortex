package dev.kortex.links.domain.model

/** How far reading the typed address has got. */
enum class PageReadPhase {
    /** No usable address yet, or it hasn't settled. */
    Idle,
    ReadingPage,

    /** The page is in; tags are still being matched. The image loads alongside, on its own clock. */
    SuggestingTags,
    Done,
}

/** What reading [url]'s page has found so far. */
data class LinkAnalysis(
    val url: String = "",
    /** Title the page gives itself. */
    val suggestedTitle: String? = null,
    /** Existing tags that fit the page, best first. */
    val suggestedTags: List<String> = emptyList(),
    /** New tag names read off the page; callers drop the ones the user already has. */
    val candidateTags: List<String> = emptyList(),
    val phase: PageReadPhase = PageReadPhase.Idle,
    /** The page's share image; null until the page is read, or when it names none. */
    val imageUrl: String? = null,
    /** [imageUrl]'s download; null while there is none to follow. */
    val image: LinkImageState? = null,
) {
    /** What saving [url] now can tell about its image: only a page already read for that address knows. */
    fun imageSourceFor(url: String): LinkImageSource = when {
        this.url != url || phase == PageReadPhase.Idle || phase == PageReadPhase.ReadingPage -> LinkImageSource.Unknown
        imageUrl != null -> LinkImageSource.Known(imageUrl)
        else -> LinkImageSource.None
    }
}

/** The saved link that [url] (the field's trimmed text when checked) is an address of. */
data class AlreadySavedLink(val url: String, val title: String)
