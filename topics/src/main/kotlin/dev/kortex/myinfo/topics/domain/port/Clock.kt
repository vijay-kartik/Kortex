package dev.kortex.myinfo.topics.domain.port

/** Wall-clock time, so use cases can be tested at a fixed instant. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
