package dev.kortex.finance.data

import dev.kortex.finance.data.local.SmsInboxDao
import dev.kortex.finance.data.local.toDomain
import dev.kortex.finance.data.local.toEntity
import dev.kortex.finance.domain.model.InboxSms
import dev.kortex.finance.domain.model.InboxStatus
import dev.kortex.finance.domain.repository.SmsInboxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSmsInboxRepository(private val dao: SmsInboxDao) : SmsInboxRepository {

    override suspend fun add(sms: InboxSms): Boolean = dao.insert(sms.toEntity()) != -1L

    override suspend fun get(id: String): InboxSms? = dao.get(id)?.toDomain()

    override suspend fun pending(imported: Boolean, limit: Int): List<InboxSms> =
        dao.withStatus(InboxStatus.PENDING.name, imported, limit).map { it.toDomain() }

    override fun observeToReview(): Flow<List<InboxSms>> =
        dao.observeWithStatus(InboxStatus.REVIEW.name).map { rows -> rows.map { it.toDomain() } }

    override suspend fun update(sms: InboxSms) = dao.update(sms.toEntity())

    override suspend fun deleteHandledBefore(millis: Long) =
        dao.deleteBefore(millis, InboxStatus.entries.filter { it.handled }.map { it.name })

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override suspend fun clear() = dao.deleteAll()
}
