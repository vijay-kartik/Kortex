package dev.kortex.links.domain.agent

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.linkDomain
import dev.kortex.links.domain.model.matches
import dev.kortex.links.domain.usecase.FindOrSaveLink
import dev.kortex.links.domain.usecase.FindOrSaveResult
import dev.kortex.links.domain.usecase.ObserveLinks
import dev.kortex.links.domain.usecase.ObserveTagCounts
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/** What a links tool tells the agent: [ok] false is a problem it should relay or fix. */
data class LinksAnswer(val ok: Boolean, val text: String)

/**
 * What the agent's links tools do, on the same use cases the Links tab and Topics use. Answers
 * are short plain text for the model to read; days are yyyy-MM-dd in [zone].
 */
class LinksAgent(
    private val observeLinks: ObserveLinks,
    private val observeTagCounts: ObserveTagCounts,
    private val findOrSaveLink: FindOrSaveLink,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** find_links: [query] in the title, address or a tag, carrying every one of [tags]; newest first. */
    suspend fun findLinks(query: String?, tags: List<String>, limit: Int = 10): LinksAnswer {
        val needle = query?.trim().orEmpty()
        val wanted = tags.map { it.trim() }.filter { it.isNotEmpty() }
        val found = observeLinks().first().filter { link ->
            link.matches(needle) && wanted.all { tag -> link.tags.any { it.equals(tag, ignoreCase = true) } }
        }
        if (found.isEmpty()) {
            val names = observeTagCounts().first().map { it.name }
            val known = if (names.isEmpty()) "There are no tags yet." else "Tags: ${names.joinToString()}."
            return LinksAnswer(true, "No saved links match. $known")
        }
        val shown = found.take(limit.coerceIn(1, 50))
        val lines = shown.joinToString("\n") { line(it) }
        val more = if (found.size > shown.size) "\n…and ${found.size - shown.size} more." else ""
        return LinksAnswer(true, "${count(found.size)}:\n$lines$more")
    }

    /** list_link_tags: every tag with how many links carry it, by name. */
    suspend fun listTags(): LinksAnswer {
        val tags = observeTagCounts().first()
        if (tags.isEmpty()) return LinksAnswer(true, "There are no tags yet.")
        return LinksAnswer(true, tags.joinToString("\n") { "${it.name}: ${count(it.linkCount)}" })
    }

    /** save_link: an address already saved is reported as it is, not saved again or retagged. */
    suspend fun saveLink(url: String, title: String?, tags: List<String>): LinksAnswer {
        if (linkDomain(url) == null) return LinksAnswer(false, "\"$url\" is not a web address; give a full http(s) URL.")
        val wanted = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        return when (val result = findOrSaveLink(url, title, wanted.ifEmpty { null })) {
            is FindOrSaveResult.Saved -> LinksAnswer(true, "Saved to Links: ${line(result.link)}")
            is FindOrSaveResult.AlreadySaved -> LinksAnswer(true, "Already in Links, unchanged: ${line(result.link)}")
            FindOrSaveResult.Failed -> LinksAnswer(false, "Couldn't save $url.")
        }
    }

    private fun line(link: Link): String {
        val tags = if (link.tags.isEmpty()) "no tags" else "tags: ${link.tags.joinToString()}"
        val day = Instant.ofEpochMilli(link.createdAtMillis).atZone(zone).toLocalDate()
        return "\"${link.title}\" ${link.url} · $tags · saved $day"
    }

    private fun count(n: Int) = "$n ${if (n == 1) "link" else "links"}"
}
