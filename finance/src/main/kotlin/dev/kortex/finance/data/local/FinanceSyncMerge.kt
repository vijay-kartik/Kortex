package dev.kortex.finance.data.local

/**
 * A pulled document, ready to apply: the row it describes, or, for a delete, only its uid ([row]
 * null). A document that can't be read is never made into one.
 */
data class RemoteRow<T>(
    val uid: String,
    val updatedAtMillis: Long,
    val deleted: Boolean,
    val row: T?,
)

/** What a pull changed, for the caller's progress and logs. */
data class FinancePullResult(val applied: Int, val deleted: Int, val skipped: Int)

/** A local row's version: when it last changed and whether that change is still unpushed. */
data class RowVersion(val updatedAtMillis: Long, val dirty: Int)

internal enum class MergeAction { Apply, Delete, Skip }

/**
 * Last writer wins on `updatedAt`, as for links and topics (docs/CLOUD_SYNC_PLAN.md › Sync
 * algorithm). [local] is null when the row isn't here; an unpushed delete counts as a dirty
 * version stamped with when it happened. A remote change no newer than an unpushed local one is
 * skipped, ties included: the next push overwrites it.
 */
internal fun decideMerge(local: RowVersion?, remoteUpdatedAtMillis: Long, remoteDeleted: Boolean): MergeAction = when {
    local == null -> if (remoteDeleted) MergeAction.Skip else MergeAction.Apply
    local.dirty > 0 && remoteUpdatedAtMillis <= local.updatedAtMillis -> MergeAction.Skip
    remoteDeleted -> MergeAction.Delete
    else -> MergeAction.Apply
}

/** [name] if no other category of its kind has it, otherwise "Name (2)", "Name (3)"…, ignoring case. */
internal fun uniqueCategoryName(name: String, taken: Collection<String>): String {
    fun free(candidate: String) = taken.none { it.equals(candidate, ignoreCase = true) }
    if (free(name)) return name
    return generateSequence(2) { it + 1 }.map { "$name ($it)" }.first(::free)
}
