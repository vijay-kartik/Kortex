package dev.kortex.links.domain.model

data class LinkDraft(val url: String, val title: String, val tags: List<String>, val image: LinkImageSource = LinkImageSource.Unknown, val imageHidden: Boolean = false)