package dev.kortex.finance.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import dev.kortex.finance.domain.model.InboxSms
import dev.kortex.finance.domain.model.InboxStatus
import dev.kortex.finance.domain.model.ReviewReason
import kotlinx.coroutines.flow.Flow

/**
 * Received bank SMS (docs/SMS_AUTO_PLAN.md › Data). On this phone only: the table has no sync
 * triggers and isn't in [FinanceSyncSchema], so raw SMS text never leaves the device.
 */
@Entity(tableName = "sms_inbox", indices = [Index("status")])
data class SmsInboxEntity(
    @PrimaryKey val id: String,
    val sender: String,
    val body: String?,
    val receivedAtMillis: Long,
    /** [InboxStatus] name. */
    val status: String,
    /** [ReviewReason] name. */
    val reason: String?,
    val transactionUid: String?,
    @ColumnInfo(defaultValue = "0") val imported: Boolean,
)

@Dao
interface SmsInboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SmsInboxEntity): Long

    @Query("SELECT * FROM sms_inbox WHERE id = :id")
    suspend fun get(id: String): SmsInboxEntity?

    @Query("SELECT * FROM sms_inbox WHERE status = :status AND imported = :imported ORDER BY receivedAtMillis ASC LIMIT :limit")
    suspend fun withStatus(status: String, imported: Boolean, limit: Int): List<SmsInboxEntity>

    @Query("SELECT * FROM sms_inbox WHERE status = :status ORDER BY receivedAtMillis DESC")
    fun observeWithStatus(status: String): Flow<List<SmsInboxEntity>>

    @Update
    suspend fun update(row: SmsInboxEntity)

    @Query("DELETE FROM sms_inbox WHERE receivedAtMillis < :millis AND status IN (:statuses)")
    suspend fun deleteBefore(millis: Long, statuses: List<String>)

    @Query("SELECT COUNT(*) FROM sms_inbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM sms_inbox")
    suspend fun deleteAll()
}

internal fun SmsInboxEntity.toDomain() = InboxSms(
    id = id,
    sender = sender,
    body = body,
    receivedAtMillis = receivedAtMillis,
    status = InboxStatus.valueOf(status),
    reason = reason?.let { name -> ReviewReason.entries.firstOrNull { it.name == name } },
    transactionUid = transactionUid,
    imported = imported,
)

internal fun InboxSms.toEntity() = SmsInboxEntity(
    id = id,
    sender = sender,
    body = body,
    receivedAtMillis = receivedAtMillis,
    status = status.name,
    reason = reason?.name,
    transactionUid = transactionUid,
    imported = imported,
)
