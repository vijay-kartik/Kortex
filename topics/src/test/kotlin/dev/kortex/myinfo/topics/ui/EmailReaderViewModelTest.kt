package dev.kortex.myinfo.topics.ui

import dev.kortex.myinfo.topics.domain.FakeAttachmentCache
import dev.kortex.myinfo.topics.domain.FakeEmailDirectory
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.port.AttachmentDownload
import dev.kortex.myinfo.topics.domain.port.EmailAttachment
import dev.kortex.myinfo.topics.domain.port.EmailMessage
import dev.kortex.myinfo.topics.domain.port.EmailReadResult
import dev.kortex.myinfo.topics.domain.usecase.FetchEmailAttachment
import dev.kortex.myinfo.topics.domain.usecase.ReadEmail
import dev.kortex.myinfo.topics.ui.email.EmailProblem
import dev.kortex.myinfo.topics.ui.email.EmailReaderEffect
import dev.kortex.myinfo.topics.ui.email.EmailReaderIntent
import dev.kortex.myinfo.topics.ui.email.EmailReaderState
import dev.kortex.myinfo.topics.ui.email.EmailReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class EmailReaderViewModelTest {
    private val mailbox = FakeEmailDirectory()
    private val cache = FakeAttachmentCache()
    private val invoice = EmailAttachment(id = "att-1", name = "invoice.pdf", mimeType = "application/pdf", sizeBytes = 48_213)
    private val email = SavedEmail(
        messageId = "18c2a3f",
        threadId = null,
        subject = "Your visa appointment",
        from = "Visa Centre <noreply@visa.example>",
        snippet = "Confirmed for 14 March.",
        sentAtMillis = 1_700_000_000_000,
        rfc822MessageId = "abc@visa.example",
        accountEmail = "me@example.com",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening reads the email from the mailbox`() {
        val full = message(subject = "Your visa appointment — confirmed", body = "Bring your passport.")
        mailbox.readResult = EmailReadResult.Read(full)

        val viewModel = viewModel()

        assertEquals(listOf(email), mailbox.reads)
        val state = viewModel.state.value
        assertFalse(state.loading)
        assertNull(state.problem)
        assertEquals(full, state.message)
        assertEquals("the mailbox's subject wins over the kept one", "Your visa appointment — confirmed", state.subject)
    }

    @Test
    fun `what the topic kept shows while the mailbox hasn't answered`() {
        val state = EmailReaderState(email)

        assertTrue(state.loading)
        assertEquals("Your visa appointment", state.subject)
        assertEquals("Visa Centre <noreply@visa.example>", state.from)
        assertEquals(1_700_000_000_000, state.sentAtMillis)
    }

    @Test
    fun `a blank subject still reads as something`() {
        mailbox.readResult = EmailReadResult.Read(message(subject = "", body = "Hi"))

        assertEquals("(no subject)", viewModel().state.value.subject)
    }

    @Test
    fun `no mailbox connected, or a mail that has gone, can't be retried`() {
        mailbox.readResult = EmailReadResult.NotConnected
        val notConnected = viewModel()
        assertEquals(EmailProblem.NotConnected, notConnected.state.value.problem)
        assertFalse(notConnected.state.value.canRetry)

        mailbox.readResult = EmailReadResult.Gone
        val gone = viewModel()
        assertEquals(EmailProblem.Gone, gone.state.value.problem)
        assertFalse(gone.state.value.canRetry)

        gone.onIntent(EmailReaderIntent.Retry)
        assertEquals("retry does nothing when there is nothing to retry", 2, mailbox.reads.size)
    }

    @Test
    fun `a failed read says why, and trying again reads it`() {
        mailbox.readResult = EmailReadResult.Failed("The Gmail sign-in has expired. Reconnect the account in Settings.")
        val viewModel = viewModel()
        assertEquals(
            EmailProblem.Failed("The Gmail sign-in has expired. Reconnect the account in Settings."),
            viewModel.state.value.problem,
        )
        assertTrue(viewModel.state.value.canRetry)

        mailbox.readResult = null
        viewModel.onIntent(EmailReaderIntent.Retry)

        assertNull(viewModel.state.value.problem)
        assertEquals("Confirmed for 14 March.", viewModel.state.value.message?.bodyText)
        assertEquals(2, mailbox.reads.size)
    }

    @Test
    fun `open Gmail leaves for the mail app`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(EmailReaderIntent.OpenMailApp)

        assertEquals(EmailReaderEffect.OpenMailApp, viewModel.effects.first())
    }

    @Test
    fun `tapping an attachment downloads it and hands the file out`() = runTest {
        val viewModel = viewModel()

        viewModel.onIntent(EmailReaderIntent.OpenAttachment(invoice))

        assertEquals(listOf(invoice), mailbox.downloads)
        assertEquals(setOf("invoice.pdf"), cache.written.keys)
        assertEquals(
            EmailReaderEffect.OpenFile(StoredFile("/cache/email-attachments/invoice.pdf", "application/pdf")),
            viewModel.effects.first(),
        )
        assertNull("done opening", viewModel.state.value.opening)
    }

    @Test
    fun `an attachment that can't be downloaded says why`() = runTest {
        mailbox.downloadResult = AttachmentDownload.Failed("invoice.pdf is no longer in your mailbox.")
        val viewModel = viewModel()

        viewModel.onIntent(EmailReaderIntent.OpenAttachment(invoice))

        assertEquals(EmailReaderEffect.ShowMessage("invoice.pdf is no longer in your mailbox."), viewModel.effects.first())
        assertTrue("nothing written", cache.written.isEmpty())
    }

    @Test
    fun `an attachment that can't be saved on the phone says so`() = runTest {
        cache.failing = true
        val viewModel = viewModel()

        viewModel.onIntent(EmailReaderIntent.OpenAttachment(invoice))

        assertEquals(EmailReaderEffect.ShowMessage("Couldn't save invoice.pdf on this phone."), viewModel.effects.first())
    }

    private fun viewModel() = EmailReaderViewModel(email, ReadEmail(mailbox), FetchEmailAttachment(mailbox, cache))

    private fun message(subject: String, body: String) = EmailMessage(
        subject = subject,
        from = email.from,
        to = "me@example.com",
        cc = "",
        sentAtMillis = email.sentAtMillis,
        bodyText = body,
        bodyHtml = null,
        attachments = emptyList(),
    )
}
