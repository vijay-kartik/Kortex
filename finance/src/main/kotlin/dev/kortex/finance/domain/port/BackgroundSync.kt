package dev.kortex.finance.domain.port

/**
 * Asks for this phone's unpushed finance changes to reach the cloud, whether or not the app is on
 * screen (docs/SMS_AUTO_PLAN.md, phase 7): entries added from bank SMS while it's closed. Bound
 * by the app, which owns sync; [None] where there's no sync.
 */
fun interface BackgroundSync {
    fun requestPush()

    companion object {
        val None = BackgroundSync {}
    }
}
