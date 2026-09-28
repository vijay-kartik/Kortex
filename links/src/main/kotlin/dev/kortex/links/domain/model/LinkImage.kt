package dev.kortex.links.domain.model

sealed interface LinkImageState {
    /** [fraction] is null while the server hasn't said how big the image is. */
    data class Loading(val fraction: Float?) : LinkImageState

    /** [width] × [height] are the original image's, before it was shrunk to a thumbnail. */
    data class Ready(val path: String, val width: Int, val height: Int) : LinkImageState

    data object Failed : LinkImageState
}

/** What the page said about its share image when the link was saved. */
sealed interface LinkImageSource {
    data class Known(val imageUrl: String) : LinkImageSource

    /** The page was read and names no image. */
    data object None : LinkImageSource

    /** Saved before the page was read; the image is looked up afterwards. */
    data object Unknown : LinkImageSource
}