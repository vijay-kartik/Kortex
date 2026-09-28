package dev.kortex.links.domain.port

import dev.kortex.links.domain.model.PageMetadata

/** Reads what a web page says about itself. */
interface PageReader {
    /** An unreachable page isn't an error: every field except the url is then empty. */
    suspend fun read(url: String): PageMetadata
}
