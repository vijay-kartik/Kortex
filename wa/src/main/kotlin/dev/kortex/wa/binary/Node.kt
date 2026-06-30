package dev.kortex.wa.binary

/**
 * A node in WhatsApp's binary-XML. [attrs] values are String or [Jid]; [content] is null,
 * a `List<Node>`, a `ByteArray`, or a String.
 */
data class Node(
    val tag: String,
    val attrs: Map<String, Any?> = emptyMap(),
    val content: Any? = null,
) {
    @Suppress("UNCHECKED_CAST")
    fun children(): List<Node> = (content as? List<Node>) ?: emptyList()

    fun contentBytes(): ByteArray? = content as? ByteArray
    fun contentString(): String? = content as? String

    fun attr(key: String): String? = attrs[key]?.toString()
    fun jidAttr(key: String): Jid? = attrs[key] as? Jid

    fun child(tag: String): Node? = children().firstOrNull { it.tag == tag }
    fun childrenWithTag(tag: String): List<Node> = children().filter { it.tag == tag }
}
