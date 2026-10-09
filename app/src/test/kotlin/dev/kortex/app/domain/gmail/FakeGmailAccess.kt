package dev.kortex.app.domain.gmail

import android.content.Intent

/** Hands out the queued [tokens] in turn and records what it was asked. */
class FakeGmailAccess(vararg tokens: GmailToken) : GmailAccess {
    private val queue = ArrayDeque(tokens.toList())
    val connected = mutableListOf<String>()
    val invalidated = mutableListOf<String>()
    val pickIntent = Intent()

    override suspend fun token(): GmailToken = queue.removeFirst()

    override suspend fun connect(account: String): GmailToken =
        queue.removeFirst().also { if (it is GmailToken.Granted) connected += account }

    override fun invalidate(token: String) {
        invalidated += token
    }

    override fun pickAccountIntent(): Intent = pickIntent
}
