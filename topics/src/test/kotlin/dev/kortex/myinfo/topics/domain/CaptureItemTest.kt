package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.BillFields
import dev.kortex.myinfo.topics.domain.model.CaptureDraft
import dev.kortex.myinfo.topics.domain.model.CaptureResult
import dev.kortex.myinfo.topics.domain.model.CaptureTarget
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.PickedFile
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.usecase.AddItem
import dev.kortex.myinfo.topics.domain.usecase.CaptureItem
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import dev.kortex.myinfo.topics.domain.usecase.captureTypes
import dev.kortex.myinfo.topics.domain.usecase.defaultCaptureType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureItemTest {
    private val repository = FakeTopicsRepository()
    private val detect = DetectItemType()
    private val clock = Clock { 0 }
    private val capture = CaptureItem(CreateTopic(repository, clock), AddItem(repository, clock), detect)

    private val pdf = PickedFile(StoredFile("/files/topic-files/1-visa.pdf", "application/pdf"), name = "visa.pdf", isImage = false, pageCount = 4)
    private val photo = PickedFile(StoredFile("/files/topic-files/2-bill.jpg", "image/jpeg"), name = "bill.jpg", isImage = true)

    @Test
    fun `an address is a link, article, video or note — text is a note or a bill`() {
        assertEquals(listOf(ItemType.Link, ItemType.Article, ItemType.Video, ItemType.Note), captureTypes(detect("gov.uk/visa")))
        assertEquals(listOf(ItemType.Note, ItemType.Bill), captureTypes(detect("pack adapters")))
    }

    @Test
    fun `a picked file is a doc or an image, and either way could be a bill`() {
        assertEquals(listOf(ItemType.Doc, ItemType.Bill), captureTypes(detect(""), pdf))
        assertEquals(listOf(ItemType.Image, ItemType.Bill, ItemType.Doc), captureTypes(detect(""), photo))
        assertEquals(ItemType.Doc, defaultCaptureType(detect(""), pdf))
        assertEquals(ItemType.Image, defaultCaptureType(detect(""), photo))
    }

    @Test
    fun `an address of a file is still a link — only a picked file is a doc`() {
        assertEquals(ItemType.Link, defaultCaptureType(detect("example.com/visa.pdf")))
        assertEquals(ItemType.Video, defaultCaptureType(detect("youtu.be/abc")))
    }

    @Test
    fun `a video goes into the chosen topic with its title`() = runTest {
        val draft = CaptureDraft(ItemType.Video, text = "youtube.com/watch?v=8xQ1n", title = "Dubai in 3 days")

        assertEquals(CaptureResult.Saved(4), capture(draft, CaptureTarget.Existing(4)))
        assertEquals(NewItem.Link("https://youtube.com/watch?v=8xQ1n", "Dubai in 3 days", ItemType.Video), repository.items.single())
    }

    @Test
    fun `an address can be kept as a note`() = runTest {
        capture(CaptureDraft(ItemType.Note, text = "gov.uk/check-sponsor", title = "ignored"), CaptureTarget.Existing(4))

        assertEquals(NewItem.Note("gov.uk/check-sponsor"), repository.items.single())
    }

    @Test
    fun `a doc keeps its file, its pages and the name it arrived with`() = runTest {
        capture(CaptureDraft(ItemType.Doc, file = pdf), CaptureTarget.Existing(4))

        assertEquals(NewItem.Doc("visa.pdf", pdf.file, pageCount = 4), repository.items.single())
    }

    @Test
    fun `a typed title wins over the file's name`() = runTest {
        capture(CaptureDraft(ItemType.Doc, title = "Visa checklist", file = pdf), CaptureTarget.Existing(4))

        assertEquals(NewItem.Doc("Visa checklist", pdf.file, pageCount = 4), repository.items.single())
    }

    @Test
    fun `an image keeps its caption, and no caption is no caption`() = runTest {
        capture(CaptureDraft(ItemType.Image, title = "  Meter reading ", file = photo), CaptureTarget.Existing(4))
        assertEquals(NewItem.Image(photo.file, caption = "Meter reading"), repository.items.single())

        capture(CaptureDraft(ItemType.Image, file = photo), CaptureTarget.Existing(4))
        assertEquals(NewItem.Image(photo.file, caption = null), repository.items.last())
    }

    @Test
    fun `a bill typed out is titled by its text and priced in the chosen currency`() = runTest {
        val draft = CaptureDraft(
            type = ItemType.Bill,
            text = "Electricity — February",
            bill = BillFields(amount = "4,280.50", currency = "AED", dueAtMillis = 1_700_000_000_000, paid = false),
        )

        assertEquals(CaptureResult.Saved(4), capture(draft, CaptureTarget.Existing(4)))
        assertEquals(
            NewItem.Bill(
                title = "Electricity — February",
                amount = Money(428_050, "AED"),
                dueAtMillis = 1_700_000_000_000,
                paid = false,
            ),
            repository.items.single(),
        )
    }

    @Test
    fun `a bill snapped from a photo keeps it as the invoice`() = runTest {
        val draft = CaptureDraft(ItemType.Bill, title = "Water", file = photo, bill = BillFields(amount = "88", currency = "AED", paid = true))

        capture(draft, CaptureTarget.Existing(4))

        assertEquals(NewItem.Bill("Water", Money(8_800, "AED"), paid = true, file = photo.file), repository.items.single())
    }

    @Test
    fun `a bill without a title or a number says which it is`() = runTest {
        val noTitle = CaptureDraft(ItemType.Bill, text = "  ", bill = BillFields(amount = "88", currency = "AED"))
        val noAmount = CaptureDraft(ItemType.Bill, text = "Electricity", bill = BillFields(amount = "later", currency = "AED"))

        assertEquals(CaptureResult.BillTitleBlank, capture(noTitle, CaptureTarget.Existing(4)))
        assertEquals(CaptureResult.BillAmountInvalid, capture(noAmount, CaptureTarget.Existing(4)))
        assertTrue(repository.items.isEmpty())
    }

    @Test
    fun `a picked email is saved as an email, and is the only thing it could be`() = runTest {
        val email = SavedEmail(
            messageId = "18c2a3f",
            threadId = "18c2a00",
            subject = "Your visa appointment",
            from = "Visa Centre <noreply@visa.example>",
            snippet = "Your appointment is confirmed for 14 March.",
            sentAtMillis = 1_700_000_000_000,
            rfc822MessageId = "abc@visa.example",
            accountEmail = "me@example.com",
        )

        assertEquals(listOf(ItemType.Email), captureTypes(detect(""), email = email))
        assertEquals(ItemType.Email, defaultCaptureType(detect(""), email = email))
        assertEquals(CaptureResult.Saved(4), capture(CaptureDraft(ItemType.Email, email = email), CaptureTarget.Existing(4)))
        assertEquals(NewItem.Email(email), repository.items.single())
    }

    @Test
    fun `a new topic is created with the item's section showing`() = runTest {
        val result = capture(CaptureDraft(ItemType.Note, text = "Metro closes 00:30"), CaptureTarget.New("Trip to Dubai"))

        assertEquals(CaptureResult.Saved(1), result)
        assertEquals(TopicDraft(name = "Trip to Dubai", sections = ItemType.DefaultSections + ItemType.Note), repository.topics.single())
    }

    @Test
    fun `nothing is created for an item that can't be saved`() = runTest {
        assertEquals(CaptureResult.Invalid, capture(CaptureDraft(ItemType.Note, text = "   "), CaptureTarget.New("Trip")))
        assertEquals(CaptureResult.Invalid, capture(CaptureDraft(ItemType.Video, text = "plain words"), CaptureTarget.New("Trip")))
        // A doc needs a file; without one the type doesn't suit the draft at all.
        assertEquals(CaptureResult.Invalid, capture(CaptureDraft(ItemType.Doc, text = "notes"), CaptureTarget.New("Trip")))
        assertTrue(repository.topics.isEmpty())
    }

    @Test
    fun `new topic names are checked`() = runTest {
        repository.createTopic(TopicDraft(name = "Trip"), nowMillis = 0)

        assertEquals(CaptureResult.NewTopicNameBlank, capture(CaptureDraft(ItemType.Note, text = "note"), CaptureTarget.New(" ")))
        assertEquals(CaptureResult.NewTopicNameTaken, capture(CaptureDraft(ItemType.Note, text = "note"), CaptureTarget.New("trip")))
    }

    @Test
    fun `a link the topic already holds is reported`() = runTest {
        capture(CaptureDraft(ItemType.Link, text = "gov.uk/visa"), CaptureTarget.Existing(4))

        assertEquals(
            CaptureResult.AlreadyInTopic,
            capture(CaptureDraft(ItemType.Link, text = "https://gov.uk/visa"), CaptureTarget.Existing(4)),
        )
    }
}
