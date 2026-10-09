package dev.kortex.app.domain.share

/** Tells the user a background share run has finished and where its session is. */
fun interface ShareResultNotifier {
    fun notifyDone(sessionId: String, title: String, answer: String)
}
