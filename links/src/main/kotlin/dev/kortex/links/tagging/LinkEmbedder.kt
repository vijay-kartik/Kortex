package dev.kortex.links.tagging

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Turns text into a vector. Swap the implementation to change models; cached vectors are keyed by [modelId]. */
interface LinkEmbedder {
    /** Changes whenever vectors from this embedder stop being comparable with earlier ones. */
    val modelId: String

    suspend fun embed(text: String): FloatArray
}

/**
 * Universal Sentence Encoder through MediaPipe's TextEmbedder, which handles tokenization
 * itself. The ~6MB model ships in `links/src/main/assets`.
 */
@Singleton
class MediaPipeLinkEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) : LinkEmbedder {
    override val modelId: String = MODEL_ASSET

    private val lock = Mutex()
    private var textEmbedder: TextEmbedder? = null

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        // TextEmbedder isn't safe for concurrent use; it's also created lazily so a
        // missing model only fails the call, not app startup.
        lock.withLock {
            val embedder = textEmbedder ?: createEmbedder().also { textEmbedder = it }
            embedder.embed(text).embeddingResult().embeddings().first().floatEmbedding()
        }
    }

    private fun createEmbedder(): TextEmbedder = TextEmbedder.createFromOptions(
        context,
        TextEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setL2Normalize(true)
            .build(),
    )

    private companion object {
        const val MODEL_ASSET = "universal_sentence_encoder.tflite"
    }
}
