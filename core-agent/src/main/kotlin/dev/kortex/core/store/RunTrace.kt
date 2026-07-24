package dev.kortex.core.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One persisted agent run. Summary columns back the cheap list query; the full
 * [dev.kortex.core.observability.AgentRun] (spans + logs) lives serialized in
 * [json], following this store's flat "nested types as JSON" convention.
 */
@Entity(tableName = "run_traces", indices = [Index("startedAt")])
data class RunTraceEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val query: String,
    val startedAt: Long,
    val status: String,
    val durationMs: Long,
    val totalTokens: Int,
    val toolCalls: Int,
    val steps: Int,
    val json: String,
)

/** List-screen projection — avoids loading the [RunTraceEntity.json] blob. */
data class RunTraceSummaryRow(
    val id: String,
    val query: String,
    val startedAt: Long,
    val status: String,
    val durationMs: Long,
    val totalTokens: Int,
    val toolCalls: Int,
    val steps: Int,
)

@Dao
interface RunTraceDao {
    @Upsert
    suspend fun upsert(entity: RunTraceEntity)

    @Query(
        "SELECT id, query, startedAt, status, durationMs, totalTokens, toolCalls, steps " +
            "FROM run_traces ORDER BY startedAt DESC LIMIT :limit"
    )
    fun observeSummaries(limit: Int): Flow<List<RunTraceSummaryRow>>

    @Query("SELECT json FROM run_traces WHERE id = :id")
    suspend fun getJson(id: String): String?

    @Query("DELETE FROM run_traces")
    suspend fun clear()

    /** Retention: delete everything except the newest [keep] runs. */
    @Query(
        "DELETE FROM run_traces WHERE id NOT IN " +
            "(SELECT id FROM run_traces ORDER BY startedAt DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)
}
