package dev.kortex.core.observability

import dev.kortex.core.store.RunTraceDao
import dev.kortex.core.store.RunTraceEntity
import dev.kortex.core.store.RunTraceSummaryRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Room-backed [AgentRunStore]. Serializes the full [AgentRun] into one row and
 * keeps summary columns alongside for the list query. Trims to [maxRuns] on save.
 */
class RoomAgentRunStore(
    private val dao: RunTraceDao,
    private val maxRuns: Int = 200,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AgentRunStore {

    override suspend fun save(run: AgentRun) {
        dao.upsert(
            RunTraceEntity(
                id = run.id,
                sessionId = run.sessionId,
                query = run.query,
                startedAt = run.startedAt,
                status = run.status.name,
                durationMs = run.stats.durationMs,
                totalTokens = run.stats.totalTokens,
                toolCalls = run.stats.toolCalls,
                steps = run.stats.steps,
                json = json.encodeToString(AgentRun.serializer(), run),
            )
        )
        dao.trim(maxRuns)
    }

    override fun observeSummaries(limit: Int): Flow<List<AgentRunSummary>> =
        dao.observeSummaries(limit).map { rows -> rows.map { it.toSummary() } }

    override suspend fun get(id: String): AgentRun? =
        dao.getJson(id)?.let { json.decodeFromString(AgentRun.serializer(), it) }

    override suspend fun clear() = dao.clear()

    private fun RunTraceSummaryRow.toSummary() = AgentRunSummary(
        id = id,
        query = query,
        startedAt = startedAt,
        status = runCatching { AgentRun.Status.valueOf(status) }
            .getOrDefault(AgentRun.Status.COMPLETED),
        durationMs = durationMs,
        totalTokens = totalTokens,
        toolCalls = toolCalls,
        steps = steps,
    )
}
