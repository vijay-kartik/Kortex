package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.FakeEmailDirectory
import dev.kortex.myinfo.topics.domain.FakeFileVault
import dev.kortex.myinfo.topics.domain.FakeLinkCatalog
import dev.kortex.myinfo.topics.domain.FakeTopicsRepository
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.LinkLookup
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.port.EmailSearchResult
import dev.kortex.myinfo.topics.domain.usecase.AddItem
import dev.kortex.myinfo.topics.domain.usecase.CaptureItem
import dev.kortex.myinfo.topics.domain.usecase.CreateTopic
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import dev.kortex.myinfo.topics.domain.usecase.DiscardPickedFile
import dev.kortex.myinfo.topics.domain.usecase.KeepPickedFile
import dev.kortex.myinfo.topics.domain.usecase.LookUpLink
import dev.kortex.myinfo.topics.domain.usecase.ObserveLinkTags
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopics
import dev.kortex.myinfo.topics.domain.usecase.SearchEmails
import dev.kortex.myinfo.topics.ui.capture.CaptureError
import dev.kortex.myinfo.topics.ui.capture.QuickCaptureEffect
import dev.kortex.myinfo.topics.ui.capture.QuickCaptureIntent
import dev.kortex.myinfo.topics.ui.capture.QuickCaptureViewModel
import dev.kortex.myinfo.topics.ui.common.TopicChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickCaptureViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeTopicsRepository()
    private val catalog = FakeLinkCatalog()
    private val vault = FakeFileVault()
    private val mailbox = FakeEmailDirectory()
    private lateinit var viewModel: QuickCaptureViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository.observedTopics.value = listOf(topic(1, "Trip to Dubai", updatedAt = 10), topic(2, "Job switch prep", updatedAt = 20))
        viewModel = viewModelFor(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `topics come most recent first, with the opening topic selected`() {
        val state = viewModel.state.value

        assertEquals(listOf(TopicChoice(2, "Job switch prep"), TopicChoice(1, "Trip to Dubai")), state.topics)
        assertEquals(1L, state.selectedTopicId)
        assertFalse(state.canSave)
    }

    @Test
    fun `a pasted video address is detected, and a change sticks only while it suits`() {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("youtube.com/watch?v=8xQ1n"))
        assertEquals(ItemType.Video, viewModel.state.value.type)
        assertTrue(viewModel.state.value.typeIsDetected)

        viewModel.onIntent(QuickCaptureIntent.ChooseType(ItemType.Article))
        assertEquals(ItemType.Article, viewModel.state.value.type)
        assertFalse(viewModel.state.value.typeIsDetected)

        viewModel.onIntent(QuickCaptureIntent.TextChanged("just words"))
        assertEquals(ItemType.Note, viewModel.state.value.type)
    }

    @Test
    fun `the address is looked up once typing pauses`() = runTest(dispatcher) {
        catalog.lookups["https://gov.uk/visa"] = LinkLookup(title = "Check if you need a visa", inLinks = true)

        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        assertTrue(viewModel.state.value.lookingUp)
        assertNull(viewModel.state.value.lookup)

        advanceUntilIdle()
        assertEquals(LinkLookup("Check if you need a visa", inLinks = true), viewModel.state.value.lookup)
        assertFalse(viewModel.state.value.lookingUp)
    }

    // ── A link's tags ─────────────────────────────────────────────

    @Test
    fun `the page's suggested tags start picked and are saved with the link`() = runTest(dispatcher) {
        catalog.tagNames.value = listOf("fashion", "travel", "work")
        catalog.lookups["https://salty.co.in/caps"] = LinkLookup(title = "Caps", inLinks = false, tags = listOf("fashion"))

        viewModel.onIntent(QuickCaptureIntent.TextChanged("salty.co.in/caps"))
        advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue(state.showTagFields)
        assertEquals(listOf("fashion"), state.pickedTags)
        // Suggestions lead; the rest of the user's tags follow.
        assertEquals(listOf("fashion", "travel", "work"), state.tagsOnOffer)

        viewModel.onIntent(QuickCaptureIntent.ToggleTag("travel"))
        viewModel.onIntent(save(text = "salty.co.in/caps"))
        viewModel.effects.first()

        assertEquals(listOf("fashion", "travel"), (repository.items.single() as NewItem.Link).tags)
    }

    @Test
    fun `a new tag takes an existing tag's spelling, and a blank one just closes the field`() {
        catalog.tagNames.value = listOf("Travel")
        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))

        typeTag(" travel ")
        viewModel.onIntent(QuickCaptureIntent.AddTag)
        assertEquals(listOf("Travel"), viewModel.state.value.pickedTags)
        assertFalse(viewModel.state.value.addingTag)

        typeTag("visas")
        viewModel.onIntent(QuickCaptureIntent.AddTag)
        typeTag("  ")
        viewModel.onIntent(QuickCaptureIntent.AddTag)
        assertEquals(listOf("Travel", "visas"), viewModel.state.value.pickedTags)
        // A tag named here is listed last, where it was typed.
        assertEquals(listOf("Travel", "visas"), viewModel.state.value.tagsOnOffer)
    }

    @Test
    fun `tapping on to something else adds the tag being typed`() {
        catalog.tagNames.value = listOf("travel")
        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))

        typeTag("visas")
        viewModel.onIntent(QuickCaptureIntent.ToggleTag("travel"))

        assertEquals(listOf("visas", "travel"), viewModel.state.value.pickedTags)
        assertFalse(viewModel.state.value.addingTag)
        assertEquals("", viewModel.state.value.newTagName)
    }

    @Test
    fun `a tag still being typed is saved with the link`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        typeTag("visas")
        viewModel.onIntent(save(text = "gov.uk/visa"))
        viewModel.effects.first()

        assertEquals(listOf("visas"), (repository.items.single() as NewItem.Link).tags)
    }

    private fun typeTag(name: String) {
        viewModel.onIntent(QuickCaptureIntent.StartNewTag)
        viewModel.onIntent(QuickCaptureIntent.NewTagNameChanged(name))
    }

    @Test
    fun `a lookup that lands late doesn't undo the user's picks`() = runTest(dispatcher) {
        catalog.lookups["https://gov.uk/visa"] = LinkLookup(title = null, inLinks = false, tags = listOf("travel"))

        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        viewModel.onIntent(QuickCaptureIntent.ToggleTag("work"))
        advanceUntilIdle()

        assertEquals(listOf("work"), viewModel.state.value.pickedTags)
    }

    @Test
    fun `a link saved before its lookup is in leaves its tags alone`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        viewModel.onIntent(save(text = "gov.uk/visa"))
        viewModel.effects.first()

        assertNull((repository.items.single() as NewItem.Link).tags)
    }

    @Test
    fun `only the first few tags show until asked for the rest`() {
        catalog.tagNames.value = (1..12).map { "tag$it" }
        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        assertEquals(8, viewModel.state.value.tagsOnOffer.size)
        assertEquals(4, viewModel.state.value.hiddenTagCount)

        viewModel.onIntent(QuickCaptureIntent.ShowAllTags)
        assertEquals(12, viewModel.state.value.tagsOnOffer.size)
        assertEquals(0, viewModel.state.value.hiddenTagCount)
    }

    @Test
    fun `saving reports the topic by name`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("Metro closes 00:30"))
        viewModel.onIntent(QuickCaptureIntent.SelectTopic(2))
        viewModel.onIntent(save(text = "Metro closes 00:30"))

        assertEquals(QuickCaptureEffect.Saved(2, "Job switch prep"), viewModel.effects.first())
    }

    @Test
    fun `saving into a new topic uses the typed name`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("Metro closes 00:30"))
        viewModel.onIntent(QuickCaptureIntent.StartNewTopic)
        viewModel.onIntent(save(text = "Metro closes 00:30", newTopicName = " Dubai notes "))

        assertEquals(QuickCaptureEffect.Saved(1, "Dubai notes"), viewModel.effects.first())
    }

    @Test
    fun `a duplicate link is reported until the text changes`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        viewModel.onIntent(save(text = "gov.uk/visa"))
        viewModel.effects.first()

        val again = viewModelFor(repository)
        again.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa"))
        again.onIntent(save(text = "gov.uk/visa"))
        assertEquals(CaptureError.AlreadyInTopic, again.state.value.error)

        again.onIntent(QuickCaptureIntent.TextChanged("gov.uk/visa/apply"))
        assertNull(again.state.value.error)
    }

    // ── Files ─────────────────────────────────────────────────────

    @Test
    fun `attaching a picture switches the sheet to it, as an image`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.AttachFile("content://media/42/meter.jpg"))

        val state = viewModel.state.value
        assertTrue(state.fileMode)
        assertEquals("meter.jpg", state.file?.name)
        assertEquals(ItemType.Image, state.type)
        assertEquals(listOf(ItemType.Image, ItemType.Bill, ItemType.Doc), state.types)
        assertTrue(state.canSave)
    }

    @Test
    fun `swapping the attachment throws the old one away and forgets the chosen type`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.AttachFile("content://docs/visa.pdf"))
        viewModel.onIntent(QuickCaptureIntent.ChooseType(ItemType.Bill))
        val first = viewModel.state.value.file!!.file.path

        viewModel.onIntent(QuickCaptureIntent.AttachFile("content://media/42/meter.jpg"))

        assertEquals(listOf(first), vault.deleted)
        assertEquals(ItemType.Image, viewModel.state.value.type)
    }

    @Test
    fun `removing the attachment gives the sheet back its text field`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.AttachFile("content://docs/visa.pdf"))
        val path = viewModel.state.value.file!!.file.path

        viewModel.onIntent(QuickCaptureIntent.RemoveFile)

        assertFalse(viewModel.state.value.fileMode)
        assertEquals(listOf(path), vault.deleted)
    }

    @Test
    fun `a file that can't be read is reported and nothing is attached`() = runTest(dispatcher) {
        vault.unreadable += "content://docs/gone.pdf"

        viewModel.onIntent(QuickCaptureIntent.AttachFile("content://docs/gone.pdf"))

        assertNull(viewModel.state.value.file)
        assertEquals(CaptureError.FileUnreadable, viewModel.state.value.error)
    }

    @Test
    fun `a doc is saved with the file the sheet kept`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.AttachFile("content://docs/visa.pdf"))
        val file = viewModel.state.value.file!!.file
        viewModel.onIntent(save(title = "Visa checklist"))

        assertEquals(QuickCaptureEffect.Saved(1, "Trip to Dubai"), viewModel.effects.first())
        assertEquals(NewItem.Doc("Visa checklist", file), repository.items.single())
        // The topic owns it now, so the sheet leaves it alone.
        assertTrue(vault.deleted.isEmpty())
    }

    // ── Emails ────────────────────────────────────────────────────

    private val visaEmail = SavedEmail(
        messageId = "18c2a3f",
        threadId = "18c2a00",
        subject = "Your visa appointment",
        from = "Visa Centre <noreply@visa.example>",
        snippet = "Confirmed for 14 March.",
        sentAtMillis = 1_700_000_000_000,
        rfc822MessageId = "abc@visa.example",
        accountEmail = "me@example.com",
    )

    @Test
    fun `the mailbox is searched once typing pauses, not per keystroke`() = runTest(dispatcher) {
        mailbox.emails = listOf(visaEmail)
        viewModel.onIntent(QuickCaptureIntent.StartPickingEmail)
        assertTrue(viewModel.state.value.pickingEmail)

        viewModel.onIntent(QuickCaptureIntent.EmailQueryChanged("vi"))
        viewModel.onIntent(QuickCaptureIntent.EmailQueryChanged("visa"))
        assertTrue(viewModel.state.value.emailSearching)
        assertTrue(mailbox.queries.isEmpty())

        advanceUntilIdle()

        assertEquals(listOf("visa"), mailbox.queries)
        assertEquals(listOf(visaEmail), viewModel.state.value.emailResults)
        assertFalse(viewModel.state.value.emailSearching)
    }

    @Test
    fun `picking an email closes the picker and makes the item an email`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.StartPickingEmail)

        viewModel.onIntent(QuickCaptureIntent.PickEmail(visaEmail))

        val state = viewModel.state.value
        assertFalse(state.pickingEmail)
        assertTrue(state.emailMode)
        assertEquals(ItemType.Email, state.type)
        assertEquals(listOf(ItemType.Email), state.types)
        assertTrue(state.canSave)
    }

    @Test
    fun `a picked email is what gets saved`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.PickEmail(visaEmail))
        viewModel.onIntent(save())

        assertEquals(QuickCaptureEffect.Saved(1, "Trip to Dubai"), viewModel.effects.first())
        assertEquals(NewItem.Email(visaEmail), repository.items.single())
    }

    @Test
    fun `removing the picked email gives the sheet back its text field`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.PickEmail(visaEmail))

        viewModel.onIntent(QuickCaptureIntent.RemoveEmail)

        assertFalse(viewModel.state.value.emailMode)
        assertFalse(viewModel.state.value.canSave)
    }

    @Test
    fun `a mailbox that isn't connected says so instead of showing nothing`() = runTest(dispatcher) {
        mailbox.result = EmailSearchResult.NotConnected

        viewModel.onIntent(QuickCaptureIntent.EmailQueryChanged("visa"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.emailNotConnected)
        assertTrue(viewModel.state.value.emailResults.isEmpty())
    }

    @Test
    fun `a mailbox that fails reports why`() = runTest(dispatcher) {
        mailbox.result = EmailSearchResult.Failed("The Gmail sign-in has expired.")

        viewModel.onIntent(QuickCaptureIntent.EmailQueryChanged("visa"))
        advanceUntilIdle()

        assertEquals("The Gmail sign-in has expired.", viewModel.state.value.emailError)
    }

    // ── Bills ─────────────────────────────────────────────────────

    @Test
    fun `a bill is saved with its amount, currency, due date and paid flag`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("Electricity — February"))
        viewModel.onIntent(QuickCaptureIntent.ChooseType(ItemType.Bill))
        viewModel.onIntent(QuickCaptureIntent.ChooseCurrency("AED"))
        viewModel.onIntent(QuickCaptureIntent.SetDueDate(1_700_000_000_000))
        viewModel.onIntent(QuickCaptureIntent.SetPaid(true))
        viewModel.onIntent(save(text = "Electricity — February", billAmount = "4,280.50"))

        viewModel.effects.first()
        assertEquals(
            NewItem.Bill("Electricity — February", Money(428_050, "AED"), dueAtMillis = 1_700_000_000_000, paid = true),
            repository.items.single(),
        )
    }

    @Test
    fun `an amount that isn't a number is reported until it is edited`() = runTest(dispatcher) {
        viewModel.onIntent(QuickCaptureIntent.TextChanged("Electricity"))
        viewModel.onIntent(QuickCaptureIntent.ChooseType(ItemType.Bill))
        viewModel.onIntent(save(text = "Electricity", billAmount = "about eighty"))

        assertEquals(CaptureError.BillAmountInvalid, viewModel.state.value.error)
        assertTrue(repository.items.isEmpty())

        viewModel.onIntent(QuickCaptureIntent.BillAmountEdited)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `the due-date picker opens and closes`() {
        viewModel.onIntent(QuickCaptureIntent.OpenDueDate)
        assertTrue(viewModel.state.value.pickingDueDate)

        viewModel.onIntent(QuickCaptureIntent.SetDueDate(1_700_000_000_000))
        assertFalse(viewModel.state.value.pickingDueDate)
        assertEquals(1_700_000_000_000, viewModel.state.value.billDueAtMillis)

        viewModel.onIntent(QuickCaptureIntent.OpenDueDate)
        viewModel.onIntent(QuickCaptureIntent.CloseDueDate)
        assertFalse(viewModel.state.value.pickingDueDate)
        assertEquals(1_700_000_000_000, viewModel.state.value.billDueAtMillis)
    }

    private fun save(
        text: String = "",
        title: String = "",
        newTopicName: String = "",
        billAmount: String = "",
    ) = QuickCaptureIntent.Save(text, title, newTopicName, billAmount)

    private fun viewModelFor(repository: FakeTopicsRepository): QuickCaptureViewModel {
        val clock = Clock { 0 }
        val detect = DetectItemType()
        return QuickCaptureViewModel(
            initialTopicId = 1,
            appScope = CoroutineScope(dispatcher),
            observeTopics = ObserveTopics(repository),
            observeLinkTags = ObserveLinkTags(catalog),
            detectItemType = detect,
            lookUpLink = LookUpLink(catalog),
            keepPickedFile = KeepPickedFile(vault),
            searchEmails = SearchEmails(mailbox),
            discardPickedFile = DiscardPickedFile(vault),
            captureItem = CaptureItem(CreateTopic(repository, clock), AddItem(repository, clock), detect),
        )
    }

    private fun topic(id: Long, name: String, updatedAt: Long) =
        Topic(id, name, purpose = null, pinned = false, sections = emptySet(), createdAtMillis = 0, updatedAtMillis = updatedAt)
}
