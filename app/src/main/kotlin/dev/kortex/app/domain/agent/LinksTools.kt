package dev.kortex.app.domain.agent

import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.int
import dev.kortex.core.tool.stringOrNull
import dev.kortex.core.tool.tool
import dev.kortex.links.domain.agent.LinksAgent
import dev.kortex.links.domain.agent.LinksAnswer
import kotlinx.serialization.json.JsonObject

/**
 * The agent's tools for the user's Links library (articles, videos and docs they saved to come
 * back to). Saving is MEDIUM risk, so the user confirms first; reads are LOW.
 */
fun linksTools(links: LinksAgent): List<Tool> = listOf(
    tool("find_links", "Search the links the user saved in Kortex Links by a word in the title, address or tag, optionally only those with given tags. Newest first.") {
        param("query", "string", "Text to look for, e.g. 'kotlin flows'", required = false)
        param("tag", "string", "Only links carrying all of these tags, comma-separated", required = false)
        param("limit", "integer", "Most links to list, 1–50; 10 when left out", required = false)
        promptHint("Look here first when the user asks for something they saved; it needs no web search.")
        execute { args ->
            links.findLinks(args.text("query"), args.list("tag"), args.int("limit", 10)).toResult()
        }
    },
    tool("list_link_tags", "Every tag in the user's Links library, with how many links carry it.") {
        execute { links.listTags().toResult() }
    },
    tool("save_link", "Save a web address to the user's Links library, titled with the page's own title unless one is given, and tagged.") {
        param("url", "string", "Full http(s) address")
        param("title", "string", "Title to save it under; the page's title when left out", required = false)
        param("tags", "string", "Tags, comma-separated; prefer existing ones from list_link_tags. New ones are created.", required = false)
        risk(RiskLevel.MEDIUM)
        execute { args ->
            val url = args.text("url") ?: return@execute ToolResult(false, "url is required.")
            links.saveLink(url, args.text("title"), args.list("tags")).toResult()
        }
    },
)

private fun LinksAnswer.toResult() = ToolResult(ok, text)

private fun JsonObject.text(key: String): String? = stringOrNull(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

private fun JsonObject.list(key: String): List<String> = text(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
