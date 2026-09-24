package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.PickedFile
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.model.SummaryDigest
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.TopicSummary
import dev.kortex.myinfo.topics.domain.port.AttachmentCache
import dev.kortex.myinfo.topics.domain.port.AttachmentDownload
import dev.kortex.myinfo.topics.domain.port.EmailAttachment
import dev.kortex.myinfo.topics.domain.port.EmailDirectory
import dev.kortex.myinfo.topics.domain.port.EmailMessage
import dev.kortex.myinfo.topics.domain.port.EmailReadResult
import dev.kortex.myinfo.topics.domain.port.EmailSearchResult
import dev.kortex.myinfo.topics.domain.port.FileVault
import dev.kortex.myinfo.topics.domain.port.LinkCatalog
import dev.kortex.myinfo.topics.domain.port.TopicSummarizer
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Records writes; names are unique ignoring case and a topic holds an address once, as in Room.
 * Reads come from [observedTopics] and [observedItems], which tests set directly.
 */
class FakeTopicsRepository : TopicsRepository {
    val topics = mutableListOf<TopicDraft>()
    val items = mutableListOf<NewItem>()
    var moves = 0
    val moved = mutableListOf<Pair<Collection<Long>, Long>>()
    val pinned = mutableMapOf<Long, Boolean>()
    val itemPins = mutableMapOf<Long, Boolean>()
    val done = mutableMapOf<Long, Boolean>()
    val deleted = mutableListOf<Long>()
    val deletedItems = mutableListOf<Long>()

    val observedTopics = MutableStateFlow<List<Topic>>(emptyList())
    val observedItems = MutableStateFlow<List<TopicItem>>(emptyList())

    /** One summary per topic, as the table keeps them; saving replaces. */
    val summaries = MutableStateFlow<Map<Long, TopicSummary>>(emptyMap())

    override fun observeSummary(topicId: Long): Flow<TopicSummary?> = summaries.map { it[topicId] }

    override suspend fun saveSummary(summary: TopicSummary) {
        summaries.update { it + (summary.topicId to summary) }
    }

    override fun observeTopics(): Flow<List<Topic>> = observedTopics
    override fun observeTopic(id: Long): Flow<Topic?> = observedTopics.map { topics -> topics.firstOrNull { it.id == id } }
    override fun observeItems(): Flow<List<TopicItem>> = observedItems
    override fun observeItems(topicId: Long): Flow<List<TopicItem>> = observedItems.map { items -> items.filter { it.topicId == topicId } }

    override suspend fun createTopic(draft: TopicDraft, nowMillis: Long): Long? {
        if (topics.any { it.name.equals(draft.name, ignoreCase = true) }) return null
        topics += draft
        return topics.size.toLong()
    }

    override suspend fun updateTopic(id: Long, draft: TopicDraft, nowMillis: Long): Boolean {
        val index = (id - 1).toInt()
        if (topics.withIndex().any { (i, it) -> i != index && it.name.equals(draft.name, ignoreCase = true) }) return false
        topics[index] = draft
        return true
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        this.pinned[id] = pinned
    }

    override suspend fun deleteTopic(id: Long) {
        deleted += id
        observedTopics.update { topics -> topics.filterNot { it.id == id } }
    }

    override suspend fun addItem(topicId: Long, item: NewItem, nowMillis: Long): Long? {
        if (item is NewItem.Link && items.any { it is NewItem.Link && it.url == item.url }) return null
        items += item
        return items.size.toLong()
    }

    override suspend fun setItemDone(itemId: Long, done: Boolean, nowMillis: Long) {
        this.done[itemId] = done
    }

    override suspend fun setItemsPinned(itemIds: Collection<Long>, pinned: Boolean, nowMillis: Long) {
        itemIds.forEach { itemPins[it] = pinned }
    }

    override suspend fun moveItems(itemIds: Collection<Long>, toTopicId: Long, nowMillis: Long) {
        moves++
        moved += itemIds to toTopicId
    }

    override suspend fun deleteItems(itemIds: Collection<Long>, nowMillis: Long) {
        deletedItems += itemIds
    }
}

/** Hands out a stored file per address, and remembers what was thrown away. */
class FakeFileVault : FileVault {
    /** Addresses that can't be read; [store] answers null for these. */
    val unreadable = mutableSetOf<String>()
    val deleted = mutableListOf<String>()
    private var next = 1

    override suspend fun store(uri: String): PickedFile? {
        if (uri in unreadable) return null
        val name = uri.substringAfterLast('/')
        val isImage = name.endsWith(".jpg") || name.endsWith(".png")
        return PickedFile(
            file = StoredFile("/files/topic-files/${next++}-$name", if (isImage) "image/jpeg" else "application/pdf"),
            name = name,
            isImage = isImage,
        )
    }

    override suspend fun delete(paths: Collection<String>) {
        deleted += paths
    }
}

/** Nothing saved; [findOrSave] hands out fresh ids and [lookUp] answers from [lookups]. */
class FakeLinkCatalog : LinkCatalog {
    private var nextId = 1L
    val lookups = mutableMapOf<String, LinkLookup>()

    override fun observeLinks(): Flow<Map<Long, SavedLink>> = emptyFlow()

    override suspend fun findOrSave(url: String, title: String?): Long = nextId++

    override suspend fun lookUp(url: String): LinkLookup = lookups[url] ?: LinkLookup(title = null, inLinks = false)
}

/** Answers with [reply], or throws [failure] when one is set; remembers every digest it read. */
class FakeTopicSummarizer(var reply: String = "You're planning a trip.") : TopicSummarizer {
    var failure: Exception? = null
    val read = mutableListOf<SummaryDigest>()

    override suspend fun summarize(digest: SummaryDigest): String {
        read += digest
        failure?.let { throw it }
        return reply
    }
}

/**
 * A mailbox in a map: [emails] are searched by a plain substring of subject or sender, and
 * [result] overrides the answer when a test wants a failure instead. Reading a kept email answers
 * [readResult] when set, and otherwise gives it a body from its snippet.
 */
class FakeEmailDirectory(var emails: List<SavedEmail> = emptyList()) : EmailDirectory {
    var result: EmailSearchResult? = null
    val queries = mutableListOf<String>()

    override suspend fun search(query: String, limit: Int): EmailSearchResult {
        queries += query
        result?.let { return it }
        val matches = emails.filter {
            it.subject.contains(query, ignoreCase = true) || it.from.contains(query, ignoreCase = true)
        }
        return EmailSearchResult.Found(matches.take(limit))
    }

    var readResult: EmailReadResult? = null
    val reads = mutableListOf<SavedEmail>()
    var downloadResult: AttachmentDownload = AttachmentDownload.Downloaded(byteArrayOf(1, 2, 3))
    val downloads = mutableListOf<EmailAttachment>()

    override suspend fun download(email: SavedEmail, attachment: EmailAttachment): AttachmentDownload {
        downloads += attachment
        return downloadResult
    }

    override suspend fun read(email: SavedEmail): EmailReadResult {
        reads += email
        readResult?.let { return it }
        return EmailReadResult.Read(
            EmailMessage(
                subject = email.subject,
                from = email.from,
                to = email.accountEmail.orEmpty(),
                cc = "",
                sentAtMillis = email.sentAtMillis,
                bodyText = email.snippet,
                bodyHtml = null,
                attachments = emptyList(),
            ),
        )
    }
}

/** Keeps what it is given in a map; [failing] makes every write fail. */
class FakeAttachmentCache : AttachmentCache {
    var failing = false
    val written = mutableMapOf<String, ByteArray>()

    override suspend fun put(name: String, mimeType: String, bytes: ByteArray): StoredFile? {
        if (failing) return null
        written[name] = bytes
        return StoredFile("/cache/email-attachments/$name", mimeType)
    }
}
