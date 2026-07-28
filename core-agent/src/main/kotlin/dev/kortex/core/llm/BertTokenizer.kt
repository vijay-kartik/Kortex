package dev.kortex.core.llm

import java.io.Closeable

interface BertTokenizer: Closeable {
    fun tokenize(text: String): TokenEncoding
    override fun close()
}