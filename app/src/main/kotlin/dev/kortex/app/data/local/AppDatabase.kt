package dev.kortex.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val turnsJson: String,
    val updatedAtMillis: Long,
)

/** A session row without `turnsJson`, which holds every attachment's bytes and can be megabytes long. */
data class ChatSessionHeader(val id: String, val title: String, val updatedAtMillis: Long)

@Dao
interface ChatSessionDao {
    @Query("SELECT id, title, updatedAtMillis FROM chat_sessions ORDER BY updatedAtMillis DESC")
    fun observeHeaders(): Flow<List<ChatSessionHeader>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChatSessionEntity?

    @Query("SELECT title FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getTitle(id: String): String?

    @Upsert
    suspend fun upsert(session: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [ChatSessionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
}
