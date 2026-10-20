package dev.kortex.links.domain.port

import dev.kortex.links.domain.model.LinkImageSource

/** The thumbnails stored for saved links: fetched after a save or pull, deleted with their links. */
interface LinkThumbnails {
    /**
     * Sweeps up thumbnails whose link is gone and attaches the missing ones. Call once at app
     * startup; nothing happens before it.
     */
    fun start()

    /**
     * Gives a just-saved link its thumbnail once one is available. A download that failed earlier is
     * tried again; a failure now leaves the link icon until [attachMissing] next runs.
     */
    fun attachWhenReady(linkId: Long, pageUrl: String, source: LinkImageSource)

    /**
     * Attaches a thumbnail to every link that should show one but has none stored, e.g. because its
     * download failed while offline. Call after a sync pull.
     */
    fun attachMissing()

    /** Deletes every stored thumbnail of [linkId] from app storage. Call once its row is deleted. */
    suspend fun deleteImages(linkId: Long)
}
