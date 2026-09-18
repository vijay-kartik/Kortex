package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.AddItemResult
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.repository.TopicsRepository

/**
 * Adds an item to a topic, trimmed. A link's address gets `https://` when it has no scheme; if
 * the Links library already has that address the topic points at the saved link, otherwise the
 * link is saved there too.
 */
class AddItem(
    private val repository: TopicsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(topicId: Long, item: NewItem): AddItemResult {
        val clean = item.cleaned() ?: return AddItemResult.Invalid
        val id = repository.addItem(topicId, clean, clock.nowMillis()) ?: return AddItemResult.AlreadyInTopic
        return AddItemResult.Added(id)
    }

    private fun NewItem.cleaned(): NewItem? = when (this) {
        is NewItem.Note -> text.trim().ifEmpty { null }?.let { copy(text = it) }
        is NewItem.Link -> WebAddress.parse(url)?.let { copy(url = it.toString(), title = title.trimToNull()) }
        is NewItem.Doc -> title.trim().ifEmpty { null }?.let { copy(title = it) }
        is NewItem.Image -> copy(caption = caption.trimToNull())
        is NewItem.Bill -> title.trim().ifEmpty { null }?.let { copy(title = it) }
    }

    private fun String?.trimToNull(): String? = this?.trim()?.ifEmpty { null }
}
