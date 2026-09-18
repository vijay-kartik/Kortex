package dev.kortex.myinfo.topics.domain.usecase

import dev.kortex.myinfo.topics.domain.model.AddItemResult
import dev.kortex.myinfo.topics.domain.model.CaptureDraft
import dev.kortex.myinfo.topics.domain.model.CaptureResult
import dev.kortex.myinfo.topics.domain.model.CaptureTarget
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.PickedFile
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicSaveResult
import dev.kortex.myinfo.topics.domain.port.LinkCatalog

/**
 * The types quick capture can save as. A picked file is a doc or an image, or the invoice behind
 * a bill; typed text is a note or a bill; and an address is a link, in one of its three shapes.
 * Order matters: the sheet lists them this way.
 */
fun captureTypes(detection: Detection, file: PickedFile? = null): List<ItemType> = when {
    file != null && file.isImage -> listOf(ItemType.Image, ItemType.Bill, ItemType.Doc)
    file != null -> listOf(ItemType.Doc, ItemType.Bill)
    detection.url != null -> listOf(ItemType.Link, ItemType.Article, ItemType.Video, ItemType.Note)
    else -> listOf(ItemType.Note, ItemType.Bill)
}

/** The type quick capture starts on: what the file or the text looks like, else a plain link. */
fun defaultCaptureType(detection: Detection, file: PickedFile? = null): ItemType = when {
    file != null -> if (file.isImage) ItemType.Image else ItemType.Doc
    else -> detection.type.takeIf { it in captureTypes(detection) } ?: ItemType.Link
}

/** Title and Links status of an address, for the quick-capture sheet to show before saving. */
class LookUpLink(private val linkCatalog: LinkCatalog) {
    suspend operator fun invoke(url: String): LinkLookup = linkCatalog.lookUp(url)
}

/**
 * Saves what quick capture collected into a topic (Figma: Topics 1d), creating the topic first
 * when [CaptureTarget.New]. The item is checked before any topic is created, so a rejected item
 * never leaves an empty topic behind.
 */
class CaptureItem(
    private val createTopic: CreateTopic,
    private val addItem: AddItem,
    private val detectItemType: DetectItemType,
) {
    suspend operator fun invoke(draft: CaptureDraft, target: CaptureTarget): CaptureResult {
        val types = captureTypes(detectItemType(draft.text), draft.file)
        if (draft.type !in types) return CaptureResult.Invalid
        val item = draft.toNewItem() ?: return draft.rejection()

        val topicId = when (target) {
            is CaptureTarget.Existing -> target.topicId
            is CaptureTarget.New -> when (val created = createTopic(TopicDraft(name = target.name, sections = ItemType.DefaultSections + draft.type))) {
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

    /** Null when the draft lacks what its type needs; [rejection] then says why. The type already suits it. */
    private fun CaptureDraft.toNewItem(): NewItem? {
        val detection = detectItemType(text)
        return when (type) {
            ItemType.Note -> if (text.isBlank()) null else NewItem.Note(text)
            ItemType.Link, ItemType.Article, ItemType.Video ->
                NewItem.Link(checkNotNull(detection.url), title.ifBlank { null }, type)
            ItemType.Doc -> NewItem.Doc(
                title = docTitle(),
                file = checkNotNull(file).file,
                pageCount = file.pageCount,
            )
            ItemType.Image -> NewItem.Image(checkNotNull(file).file, caption = title.ifBlank { null })
            ItemType.Bill -> {
                val fields = bill ?: return null
                val amount = MoneyAmount.parse(fields.amount, fields.currency) ?: return null
                NewItem.Bill(
                    title = billTitle() ?: return null,
                    amount = amount,
                    dueAtMillis = fields.dueAtMillis,
                    paid = fields.paid,
                    file = file?.file,
                )
            }
        }
    }

    /** The closest thing to a reason the sheet can show, worked out only once the draft is refused. */
    private fun CaptureDraft.rejection(): CaptureResult = when {
        type != ItemType.Bill -> CaptureResult.Invalid
        billTitle() == null -> CaptureResult.BillTitleBlank
        bill == null || MoneyAmount.parse(bill.amount, bill.currency) == null -> CaptureResult.BillAmountInvalid
        else -> CaptureResult.Invalid
    }

    /** The title field, then whatever the file was called. */
    private fun CaptureDraft.docTitle(): String = title.ifBlank { file?.name.orEmpty() }

    /** A bill typed out is titled by the text; one snapped from a file, by the title field or its name. */
    private fun CaptureDraft.billTitle(): String? =
        title.ifBlank { text }.ifBlank { file?.name.orEmpty() }.trim().ifEmpty { null }
}
