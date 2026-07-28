package dev.kortex.core.llm

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

class SpmBertTokenizer: BertTokenizer {
    private var vocabMap: Map<String, Int> = emptyMap()
    private var idToToken: Map<Int, String> = emptyMap()
    private var isInitialized = false

    private val padTokenId = 0
    private val bosTokenId = 1
    private val eosTokenId = 2
    private val unkTokenId = 3

    init {
        initialize()
    }

    private fun initialize() {
        try {
            val downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val vocabFile = File(downloadFolder, TOKENIZER_NAME)

            if (!vocabFile.exists()) {
                Log.e("SpmBertTokenizer", "Vocab file not found: ${vocabFile.absolutePath}")
                throw FileNotFoundException("Vocab file not found at path: ${vocabFile.absolutePath}")
            }

            vocabMap = loadVocabulary(vocabFile)
            idToToken = vocabMap.entries.associate { it.value to it.key }

            isInitialized = true
            Log.i("SpmBertTokenizer", "Tokenizer initialized with ${vocabMap.size} tokens")
        } catch (e: Exception) {
            Log.e("SpmBertTokenizer", "Failed to init tokenizer", e)
            throw IllegalStateException("Failed to init BertTokenizer: ${e.message}", e)
        }
    }

    private fun loadVocabulary(file: File): Map<String, Int> {
        val vocab = mutableMapOf<String, Int>()
        val inputStream = FileInputStream(file)

        vocab["[PAD]"] = padTokenId
        vocab["[BOS]"] = bosTokenId
        vocab["[EOS]"] = eosTokenId
        vocab["[UNK]"] = unkTokenId

        inputStream.close()
        return vocab
    }

    override fun tokenize(text: String): TokenEncoding {
        check(isInitialized) { "BertTokenizer is not initialized" }

        val tokens = mutableListOf<String>()
        val words = text.trim().split("\\s+".toRegex())

        for (word in words) {
            if (word.isBlank()) continue

            val lowerWord = word.lowercase()

            if (vocabMap.containsKey(lowerWord)) {
                tokens.add(lowerWord)
            } else {
                for (char in lowerWord) {
                    tokens.add(char.toString())
                }
            }
        }

        val ids = IntArray(tokens.size) { i ->
            vocabMap[tokens[i]] ?: unkTokenId
        }

        val attentionMask = IntArray(tokens.size) { 1 }

        return TokenEncoding(ids = ids, attentionMask = attentionMask)
    }

    override fun close() {
        vocabMap = emptyMap()
        idToToken = emptyMap()
        isInitialized = false
    }

    companion object {
        private const val TOKENIZER_NAME = "sentencepiece.model"
    }
}
