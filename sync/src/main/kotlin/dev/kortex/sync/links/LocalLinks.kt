package dev.kortex.sync.links

import dev.kortex.links.data.LinkSyncDao
import dev.kortex.links.data.RemoteLink
import dev.kortex.links.domain.model.LinkImageSource
import dev.kortex.links.domain.port.LinkThumbnails

/** The phone's side of [LinkSync]'s pulls and discards: links.db rows, with their thumbnails kept in step. */
internal class LocalLinks(private val dao: LinkSyncDao, private val images: LinkThumbnails) {

    /** Applies pulled or heard links, and fetches or drops thumbnails to match. */
    suspend fun apply(links: List<RemoteLink>) {
        val result = dao.applyPulled(links)
        result.needsImage.forEach { images.attachWhenReady(it.id, it.url, LinkImageSource.Known(it.imageUrl)) }
        result.deleted.forEach { images.deleteImages(it) }
    }

    /** Thumbnails go with their links. */
    suspend fun discardAll() {
        dao.discardAll().forEach { images.deleteImages(it) }
    }
}
