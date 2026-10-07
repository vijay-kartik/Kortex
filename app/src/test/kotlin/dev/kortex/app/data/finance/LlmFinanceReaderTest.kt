package dev.kortex.app.data.finance

import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.log.Logger
import dev.kortex.core.state.Message
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.ReceiptItem
import dev.kortex.finance.domain.read.Instrument
import dev.kortex.finance.domain.read.MerchantSuggestion
import dev.kortex.finance.domain.read.MoneyDirection
import dev.kortex.finance.domain.read.SmsReading
import dev.kortex.finance.domain.read.TotalCandidate
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [LlmFinanceReader] on a scripted model: which replies become money, and which are thrown away. */
class LlmFinanceReaderTest {
    private val today = LocalDate.of(2026, 10, 7)

    private class FakeLlm(private val answer: suspend () -> String) : LlmProvider {
        val requests = mutableListOf<LlmRequest>()

        override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
            requests += req
            return LlmResponse(Message(Message.Role.ASSISTANT, answer()))
        }

        override fun stream(req: LlmRequest): Flow<LlmChunk> = emptyFlow()
    }

    private fun reader(reply: String) = LlmFinanceReader(FakeLlm { reply }, model = { "test-model" })

    private fun sms(reply: String): SmsReading? = runBlocking { reader(reply).readSms("masked", today) }

    private fun amountOf(value: String): Long? = sms("""{"direction": "debit", "amount": $value}""")?.amountMinor

    // ── Finding the JSON ──────────────────────────────────────────

    @Test
    fun `a plain object is read`() {
        assertEquals(1, LlmFinanceReader.jsonIn("""{"a": 1}""")?.getInt("a"))
    }

    @Test
    fun `an object inside a code fence is read`() {
        assertEquals("x", LlmFinanceReader.jsonIn("Here you go:\n```json\n{\"a\": \"x\"}\n```")?.getString("a"))
    }

    @Test
    fun `a reasoning model's thinking is skipped, whatever its case, even when it has braces`() {
        assertEquals(2, LlmFinanceReader.jsonIn("<think>maybe {\"a\": 1}?</think>\n{\"a\": 2}")?.getInt("a"))
        assertEquals(2, LlmFinanceReader.jsonIn("<THINK>\n{oops}\n</Think>{\"a\": 2}")?.getInt("a"))
    }

    @Test
    fun `prose with no object is null`() {
        assertNull(LlmFinanceReader.jsonIn("Sorry, I can't read that SMS."))
        assertNull(LlmFinanceReader.jsonIn("} backwards {"))
    }

    @Test
    fun `text after the object that has a closing brace doesn't hide the object`() {
        assertEquals(1, LlmFinanceReader.jsonIn("""{"a": 1} (amounts are in {rupees})""")?.getInt("a"))
    }

    @Test
    fun `malformed JSON is null`() {
        assertNull(LlmFinanceReader.jsonIn("""{"amount": , "direction": debit"""))
        assertNull(LlmFinanceReader.jsonIn("""{"amount": }"""))
    }

    // ── Amounts ───────────────────────────────────────────────────

    @Test
    fun `a number of rupees becomes paise`() {
        assertEquals(123_900L, amountOf("1239"))
        assertEquals(123_950L, amountOf("1239.5"))
    }

    @Test
    fun `a string of rupees with commas and a rupee sign becomes paise`() {
        assertEquals(123_900L, amountOf("\"1,239.00\""))
        assertEquals(123_950L, amountOf("\"₹ 1,239.50\""))
    }

    @Test
    fun `fractions of a paisa round to the nearest paisa`() {
        assertEquals(1_000L, amountOf("9.999"))
    }

    @Test
    fun `zero, negative, null, missing and unparsable amounts give no reading`() {
        assertNull(amountOf("0"))
        assertNull(amountOf("-500"))
        assertNull(amountOf("null"))
        assertNull(amountOf("\"\""))
        assertNull(amountOf("true"))
        assertNull(sms("""{"direction": "debit"}"""))
    }

    @Test
    fun `an amount written with Rs is not read`() {
        // Only "," and "₹" are stripped. Pinned so a change here is a choice, not an accident.
        assertNull(amountOf("\"Rs. 500\""))
    }

    // ── SMS ───────────────────────────────────────────────────────

    @Test
    fun `a full SMS reading maps every field`() {
        val reading = sms(
            """
            {"direction": "debit", "amount": 1239, "last4": "1234", "instrument": "card",
             "date": "2026-10-06", "time": "14:05", "payee": "SWIGGY", "ref": "412345678901"}
            """.trimIndent(),
        )

        assertEquals(
            SmsReading(
                direction = MoneyDirection.DEBIT,
                amountMinor = 123_900L,
                last4 = "1234",
                instrument = Instrument.CARD,
                date = LocalDate.of(2026, 10, 6),
                time = LocalTime.of(14, 5),
                payee = "SWIGGY",
                ref = "412345678901",
            ),
            reading,
        )
    }

    @Test
    fun `direction is read whatever its case`() {
        assertEquals(MoneyDirection.DEBIT, sms("""{"direction": "DEBIT", "amount": 1}""")?.direction)
        assertEquals(MoneyDirection.CREDIT, sms("""{"direction": "Credit", "amount": 1}""")?.direction)
    }

    @Test
    fun `an unknown or missing direction gives no reading`() {
        assertNull(sms("""{"direction": "refund", "amount": 100}"""))
        assertNull(sms("""{"amount": 100}"""))
    }

    @Test
    fun `last4 keeps only the last four digits, and fewer than four is none`() {
        fun last4(value: String) = sms("""{"direction": "debit", "amount": 1, "last4": $value}""")?.last4

        assertEquals("1234", last4("\"XX1234\""))
        assertEquals("5678", last4("\"1234 5678\""))
        assertEquals("1234", last4("1234"))
        assertNull(last4("\"12\""))
        assertNull(last4("\"XXXX\""))
        assertNull(last4("null"))
    }

    @Test
    fun `instrument is read whatever its case and falls back to unknown`() {
        fun instrument(value: String) = sms("""{"direction": "debit", "amount": 1, "instrument": $value}""")?.instrument

        assertEquals(Instrument.CARD, instrument("\"Card\""))
        assertEquals(Instrument.ACCOUNT, instrument("\"ACCOUNT\""))
        assertEquals(Instrument.UNKNOWN, instrument("\"upi\""))
        assertEquals(Instrument.UNKNOWN, instrument("null"))
    }

    @Test
    fun `a bad date or time is dropped, not the whole reading`() {
        val reading = sms("""{"direction": "credit", "amount": 50, "date": "06/10/2026", "time": "2pm"}""")

        assertEquals(SmsReading(MoneyDirection.CREDIT, 5_000L), reading)
    }

    @Test
    fun `blank and literal null strings are no value`() {
        val reading = sms("""{"direction": "debit", "amount": 1, "payee": "  ", "ref": "null"}""")

        assertNull(reading?.payee)
        assertNull(reading?.ref)
        assertEquals(100L, reading?.amountMinor)
    }

    @Test
    fun `an SMS that isn't a transaction gives no reading`() {
        assertNull(sms("""{"amount": null}"""))
    }

    @Test
    fun `the SMS goes to the chosen model, fenced off as data`() {
        val llm = FakeLlm { """{"amount": null}""" }
        runBlocking { LlmFinanceReader(llm, model = { "chosen" }).readSms("Rs 500 debited", today) }

        val request = llm.requests.single()
        assertEquals("chosen", request.model)
        assertEquals(0.0, request.temperature, 0.0)
        assertEquals(LlmFinanceReader.SMS_PROMPT, request.messages[0].content)
        assertEquals("Today is 2026-10-07.\nBEGIN SMS\nRs 500 debited\nEND SMS", request.messages[1].content)
    }

    // ── Receipts ──────────────────────────────────────────────────

    private fun receipt(reply: String) = runBlocking { reader(reply).readReceipt("masked", today) }

    @Test
    fun `a receipt keeps its fields, totals and items`() {
        val reading = receipt(
            """
            {"merchant": "DMart", "date": "2026-10-05", "time": "19:30",
             "totals": [{"amount": 1180, "label": "Total incl. GST"}, {"amount": "1,000", "label": "Subtotal"}],
             "tax": 180, "items": [{"name": "Rice", "qty": 2, "amount": 500}], "last4": "XX9876"}
            """.trimIndent(),
        )!!

        assertEquals("DMart", reading.merchant)
        assertEquals(LocalDate.of(2026, 10, 5), reading.date)
        assertEquals(LocalTime.of(19, 30), reading.time)
        assertEquals(listOf(TotalCandidate(118_000L, "Total incl. GST"), TotalCandidate(100_000L, "Subtotal")), reading.totals)
        assertEquals(18_000L, reading.taxMinor)
        assertEquals(listOf(ReceiptItem("Rice", 2, 50_000L)), reading.items)
        assertEquals("9876", reading.last4)
    }

    @Test
    fun `items with no name or no amount are dropped`() {
        val reading = receipt(
            """
            {"items": [{"qty": 1, "amount": 10}, {"name": "Milk", "qty": 1}, {"name": " ", "amount": 5},
                       {"name": "Bread", "amount": "0"}, {"name": "Eggs", "qty": 1, "amount": 72}, "junk"]}
            """.trimIndent(),
        )!!

        assertEquals(listOf(ReceiptItem("Eggs", 1, 7_200L)), reading.items)
    }

    @Test
    fun `a missing, zero or negative quantity counts as one`() {
        val reading = receipt(
            """{"items": [{"name": "A", "amount": 1}, {"name": "B", "qty": 0, "amount": 1}, {"name": "C", "qty": -3, "amount": 1}]}""",
        )!!

        assertEquals(listOf(1, 1, 1), reading.items.map { it.quantity })
    }

    @Test
    fun `a total with no label is called Total, and one with no amount is dropped`() {
        val reading = receipt("""{"totals": [{"amount": 99}, {"label": "Grand total"}, {"amount": 0, "label": "Due"}]}""")!!

        assertEquals(listOf(TotalCandidate(9_900L, "Total")), reading.totals)
    }

    @Test
    fun `a receipt with nothing readable is an empty reading, not null`() {
        val reading = receipt("""{"merchant": null, "date": "yesterday", "totals": null, "items": "none", "tax": "n/a"}""")!!

        assertNull(reading.merchant)
        assertNull(reading.date)
        assertEquals(emptyList<TotalCandidate>(), reading.totals)
        assertEquals(emptyList<ReceiptItem>(), reading.items)
        assertNull(reading.taxMinor)
    }

    // ── Merchants ─────────────────────────────────────────────────

    private val categories = listOf(
        Category("food", "Food", CategoryKind.EXPENSE, "Amber"),
        Category("travel", "Travel", CategoryKind.EXPENSE, "Sky"),
    )

    private fun merchant(reply: String) = runBlocking { reader(reply).suggestMerchant("WHOLE FOODS MKT MUMBAI", categories) }

    @Test
    fun `a merchant keeps its name and a listed category`() {
        assertEquals(MerchantSuggestion("Whole Foods", "food"), merchant("""{"name": "Whole Foods", "category": "food"}"""))
    }

    @Test
    fun `a category the model made up is dropped and the name kept`() {
        assertEquals(MerchantSuggestion("Whole Foods", null), merchant("""{"name": "Whole Foods", "category": "groceries"}"""))
        assertEquals(MerchantSuggestion("Whole Foods", null), merchant("""{"name": "Whole Foods", "category": null}"""))
    }

    @Test
    fun `a merchant with no name is no suggestion`() {
        assertNull(merchant("""{"category": "food"}"""))
        assertNull(merchant("""{"name": "", "category": "food"}"""))
    }

    // ── Failures ──────────────────────────────────────────────────

    @Test
    fun `a model that throws gives null`() {
        val reader = LlmFinanceReader(FakeLlm { throw IOException("offline") }, model = { "m" })

        runBlocking {
            assertNull(reader.readSms("masked", today))
            assertNull(reader.readReceipt("masked", today))
            assertNull(reader.suggestMerchant("X", categories))
        }
    }

    @Test
    fun `no model set up gives null`() {
        val reader = LlmFinanceReader(FakeLlm { """{"amount": 1}""" }, model = { throw IllegalStateException("no key") })

        assertNull(runBlocking { reader.readSms("masked", today) })
    }

    @Test
    fun `a model that never answers times out to null instead of throwing`() {
        val reader = LlmFinanceReader(FakeLlm { awaitCancellation() }, model = { "m" }, timeoutMs = 50)

        assertNull(runBlocking { reader.readSms("masked", today) })
    }

    @Test
    fun `a reply that isn't JSON gives null`() {
        assertNull(sms("The amount debited is ₹500."))
    }
}
