package dev.kortex.links.tagging

import dev.kortex.links.domain.model.PageMetadata
import dev.kortex.links.domain.model.linkDomain
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/**
 * YouTube's oEmbed endpoint. YouTube sometimes answers a page fetch with its cookie-consent page,
 * which has no og tags and is titled just "YouTube"; oEmbed gives the video's title and thumbnail.
 */
internal object YouTubeOEmbed {

    private val HOSTS = setOf("youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")

    /** The oEmbed address to ask about [url], or null when it isn't a YouTube address. */
    fun endpoint(url: String): String? {
        val host = linkDomain(url)?.lowercase()?.removePrefix("www.") ?: return null
        if (host !in HOSTS) return null
        return "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url.trim(), "UTF-8")
    }

    /** What an oEmbed [json] answer says about [url], or null when it names no title. */
    fun parse(url: String, json: String): PageMetadata? {
        val answer = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return null
        }
        val title = answer.text("title") ?: return null
        return PageMetadata(
            url = url,
            title = title,
            siteName = answer.text("provider_name"),
            imageUrl = answer.text("thumbnail_url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        )
    }

    // isNull covers both a missing key and a JSON null, which optString would turn into "null".
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().ifEmpty { null }
}
