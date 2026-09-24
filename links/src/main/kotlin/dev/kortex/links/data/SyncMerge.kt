package dev.kortex.links.data

/** What the merge knows about a row here: its change time, and whether that change is still unpushed. */
internal data class LocalVersion(val updatedAtMillis: Long, val dirty: Boolean)

internal enum class RemoteAction { Apply, Delete, Skip }

/**
 * Last writer wins, on `updatedAt` (docs/CLOUD_SYNC_PLAN.md › Sync algorithm). [local] is null when
 * the row doesn't exist here; a delete not yet pushed counts as a dirty version stamped with when
 * it happened.
 *
 * A remote doc no newer than an unpushed local change is skipped: the next push overwrites it.
 * Ties go to the local change for the same reason.
 */
internal fun decideRemote(local: LocalVersion?, remoteUpdatedAtMillis: Long, remoteDeleted: Boolean): RemoteAction = when {
    local == null -> if (remoteDeleted) RemoteAction.Skip else RemoteAction.Apply
    local.dirty && remoteUpdatedAtMillis <= local.updatedAtMillis -> RemoteAction.Skip
    remoteDeleted -> RemoteAction.Delete
    else -> RemoteAction.Apply
}
