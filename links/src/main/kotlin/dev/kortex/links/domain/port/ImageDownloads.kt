package dev.kortex.links.domain.port

import dev.kortex.links.domain.model.LinkImageState
import kotlinx.coroutines.flow.Flow

/** Page share images, downloaded once per address and shared by whoever asks. */
interface ImageDownloads {
    /** Starts downloading [imageUrl] unless it already is (or has). Never completes, so a retry shows up. */
    fun image(imageUrl: String): Flow<LinkImageState>

    /** Restarts a failed download. Does nothing while one is running or after one succeeded. */
    fun retry(imageUrl: String)
}
