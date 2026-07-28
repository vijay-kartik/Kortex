package dev.kortex.core.llm

import android.os.Environment
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class EmbeddingGemmaProvider: EmbeddingProvider {
    private var interpreter: Interpreter? = null
    private var tokenizer: SpmBertTokenizer? = null

    private val maxSequenceLength = 1024

    /**
     * Loads the model+tokenizer on first use rather than in the constructor.
     *
     * Constructing this provider must never throw: it is created eagerly while
     * the DI container / ViewModels are built, so a load failure here (missing
     * file, no storage access) would crash the whole app at startup. Instead we
     * defer loading to the first [embed] call, where the failure is a catchable
     * exception the callers (MemoryTool / KnowledgeExtractionTool) already
     * handle. A failed attempt leaves [interpreter] null, so a later call retries
     * — the user can drop the model in and embed again without restarting.
     */
    @Synchronized
    private fun ensureReady() {
        if (interpreter != null && tokenizer != null) return
        initializeModelFromDownloads()
    }

    fun initializeModelFromDownloads() {
        Log.i("EmbeddingGemma", "init model from downloads")
        val downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val modelFile = File(downloadFolder, MODEL_NAME)

        if (!modelFile.exists()) {
            Log.e("EmbeddingGemma", "model file does not exist")
            throw FileNotFoundException("Gemma model file is missing in Downloads directory")
        }

        val options = Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors())
        }

        val modelBuffer = loadExternalModelFile(modelFile)
        interpreter = Interpreter(modelBuffer, options)

        tokenizer = SpmBertTokenizer()
    }

    private fun loadExternalModelFile(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    override suspend fun embed(text: String): FloatArray {
        ensureReady()
        val encoding = tokenizer?.tokenize(text) ?: throw IllegalStateException("TOkenizer not ready")

        val inputIds = encoding.ids

        val length = minOf(inputIds.size, maxSequenceLength)

        val finalInputIds = IntArray(maxSequenceLength) { 0 }
        System.arraycopy(inputIds, 0, finalInputIds, 0, length)
        val inputBuffer = Array(1) { finalInputIds }

        val outputMap = HashMap<Int, Any>()
        val outputEmbedding = Array(1) { FloatArray(768) }
        outputMap[0] = outputEmbedding

        interpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        return truncateMatryoshka(outputEmbedding[0])
    }

    fun truncateMatryoshka(vector768: FloatArray): FloatArray {
        val truncated = vector768.copyOfRange(0, 384)

        val magnitude = sqrt(truncated.map { it * it }.sum())

        if (magnitude > 0) {
            for (i in truncated.indices) {
                truncated[i] /= magnitude
            }
        }
        return truncated
    }

    override fun close() {
        interpreter?.close()
        tokenizer?.close()
    }
    companion object {
        private const val MODEL_NAME = "embeddinggemma-300M_seq1024_mixed-precision.tflite"
    }
}