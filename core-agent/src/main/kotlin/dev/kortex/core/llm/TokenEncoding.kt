package dev.kortex.core.llm

data class TokenEncoding(
    val ids: IntArray,
    val attentionMask: IntArray
)
