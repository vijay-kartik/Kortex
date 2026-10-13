package dev.kortex.links.images

import dev.kortex.links.domain.model.LinkImageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * Runs at most [permits] downloads at once. A download's [timeoutMillis] starts only once it has a
 * slot, so a burst of them (a first sync of a large library, say) doesn't time out waiting in line.
 */
internal class DownloadSlots(permits: Int, private val timeoutMillis: Long) {
    private val semaphore = Semaphore(permits)

    suspend fun <T> run(block: suspend CoroutineScope.() -> T): T =
        semaphore.withPermit { withTimeout(timeoutMillis, block) }
}

/** Whether a download that reached [state] should start again: it failed, or its file has since gone. */
internal fun needsRestart(state: LinkImageState, fileExists: (String) -> Boolean): Boolean = when (state) {
    is LinkImageState.Loading -> false
    is LinkImageState.Ready -> !fileExists(state.path)
    LinkImageState.Failed -> true
}
