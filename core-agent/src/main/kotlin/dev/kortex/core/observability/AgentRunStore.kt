package dev.kortex.core.observability

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for [AgentRun] records. Kept as an interface in core-agent
 * so the Run-trace screen depends only on this + the model, and the Room-backed
 * implementation stays swappable (e.g. an in-memory fake in tests).
 */
interface AgentRunStore {

    /** Persists a completed run, then trims to the retention cap. */
    suspend fun save(run: AgentRun)

    /** Newest-first summaries for the list screen; emits on every change. */
    fun observeSummaries(limit: Int = 200): Flow<List<AgentRunSummary>>

    /** Full run (spans + logs) for the detail screen, or null if not found. */
    suspend fun get(id: String): AgentRun?

    /** Drops all stored runs. */
    suspend fun clear()
}
