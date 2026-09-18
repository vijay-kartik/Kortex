package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.AddItemResult
import dev.kortex.myinfo.topics.domain.model.CaptureResult
import dev.kortex.myinfo.topics.domain.model.CaptureTarget
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicSaveResult
import dev.kortex.myinfo.topics.domain.port.LinkCatalog

/**
 * The types quick capture can save [detection] as. Docs, images and bills need a file or an
 * amount, which quick capture doesn't collect yet, so an address of a file saves as a link.
 */
fun captureTypes(detection: Detection): List<ItemType> =
    if (detection.url != null) listOf(ItemType.Link, ItemType.Article, ItemType.Video, ItemType.Note) else listOf(ItemType.Note)

/** The type quick capture starts on: the detected one when it can be captured, else a plain link. */
fun defaultCaptureType(detection: Detection): ItemType =
    detection.type.takeIf { it in captureTypes(detection) } ?: ItemType.Link

/** Title and Links status of an address, for the quick-capture sheet to show before saving. */
class LookUpLink(private val linkCatalog: LinkCatalog) {
    suspend operator fun invoke(url: String): LinkLookup = linkCatalog.lookUp(url)
}

/**
 * Saves pasted or typed [text] into a topic as [type] (Figma: Topics 1d), creating the topic
 * first when [target] is new. The item is checked before any topic is created, so a rejected
 * item never leaves an empty topic behind.
 */
class CaptureItem(
    private val createTopic: CreateTopic,
    private val addItem: AddItem,
    private val detectItemType: DetectItemType,
) {
    suspend operator fun invoke(text: String, type: ItemType, title: String?, target: CaptureTarget): CaptureResult {
        val detection = detectItemType(text)
        if (text.isBlank() || type !in captureTypes(detection)) return CaptureResult.Invalid
        val item = if (type == ItemType.Note) NewItem.Note(text) else NewItem.Link(checkNotNull(detection.url), title, type)

        val topicId = when (target) {
            is CaptureTarget.Existing -> target.topicId
            is CaptureTarget.New -> when (val created = createTopic(TopicDraft(name = target.name, sections = ItemType.DefaultSections + type))) {
                is TopicSaveResult.Saved -> created.topicId
                TopicSaveResult.BlankName -> return CaptureResult.NewTopicNameBlank
                TopicSaveResult.NameTaken -> return CaptureResult.NewTopicNameTaken
            }
        }
        return when (addItem(topicId, item)) {
            is AddItemResult.Added -> CaptureResult.Saved(topicId)
            AddItemResult.Invalid -> CaptureResult.Invalid
            AddItemResult.AlreadyInTopic -> CaptureResult.AlreadyInTopic
        }
    }
}
