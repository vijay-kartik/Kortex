package dev.kortex.wa.binary

/**
 * WhatsApp binary-XML tag constants and token-index lookups (whatsmeow `binary/token`).
 * Token string tables live in the generated [WaTokens].
 */
internal object Tags {
    const val LIST_EMPTY = 0
    const val DICTIONARY_0 = 236
    const val DICTIONARY_1 = 237
    const val DICTIONARY_2 = 238
    const val DICTIONARY_3 = 239
    const val INTEROP_JID = 245
    const val FB_JID = 246
    const val AD_JID = 247
    const val LIST_8 = 248
    const val LIST_16 = 249
    const val JID_PAIR = 250
    const val HEX_8 = 251
    const val BINARY_8 = 252
    const val BINARY_20 = 253
    const val BINARY_32 = 254
    const val NIBBLE_8 = 255
    const val PACKED_MAX = 127

    private val singleIndex: Map<String, Int> = buildMap {
        WaTokens.SINGLE.forEachIndexed { i, t -> if (t.isNotEmpty()) put(t, i) }
    }

    /** token -> (dictionary, index). Later dictionaries win on collision, matching whatsmeow. */
    private val doubleIndex: Map<String, Pair<Int, Int>> = buildMap {
        WaTokens.DOUBLE.forEachIndexed { d, arr -> arr.forEachIndexed { i, t -> put(t, d to i) } }
    }

    fun singleTokenIndex(token: String): Int? = singleIndex[token]
    fun doubleTokenIndex(token: String): Pair<Int, Int>? = doubleIndex[token]
}
