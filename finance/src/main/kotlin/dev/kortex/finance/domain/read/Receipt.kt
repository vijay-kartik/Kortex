package dev.kortex.finance.domain.read

import dev.kortex.finance.domain.model.ReceiptItem
import java.time.LocalDate
import java.time.LocalTime

/** One amount that could be what was paid (Figma: Scan receipt 04). */
data class TotalCandidate(val amountMinor: Long, val label: String)

/** A receipt, read from the text recognised in its photo. */
data class ParsedReceipt(
    val merchant: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    /** Most likely first. More than one, and [total] null, means the person has to pick. */
    val candidates: List<TotalCandidate> = emptyList(),
    val total: Long? = null,
    val taxMinor: Long? = null,
    val items: List<ReceiptItem> = emptyList(),
    val last4: String? = null,
) {
    /** Nothing that looks like money: too blurry, or not a receipt (Scan receipt 06). */
    val unreadable: Boolean get() = candidates.isEmpty() && total == null
}

/**
 * Reads the text ML Kit recognised on a receipt. Patterns find the total, tax, items, date and
 * card; the LLM, when one is set up, fills in what they missed.
 */
object ReceiptParser {

    fun parse(text: String, today: LocalDate): ParsedReceipt {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParsedReceipt()
        val priced = lines.mapNotNull { line ->
            val m = TextReading.BareAmount.findAll(line).lastOrNull() ?: return@mapNotNull null
            PricedLine(line, line.substring(0, m.range.first).trim(), TextReading.minorOf(m.value) ?: return@mapNotNull null)
        }

        val total = priced.lastOrNull { Total.containsMatchIn(it.label) && !Subtotal.containsMatchIn(it.label) }
        val subtotal = priced.lastOrNull { Subtotal.containsMatchIn(it.label) }
        val tax = priced.filter { Tax.containsMatchIn(it.label) && !Total.containsMatchIn(it.label) }.sumOf { it.amountMinor }.takeIf { it > 0 }
        val paid = priced.lastOrNull { Paid.containsMatchIn(it.line) }

        val candidates = buildList {
            total?.let { add(TotalCandidate(it.amountMinor, if (tax != null) "Total, incl. ${rupees(tax)} GST" else "Total")) }
            paid?.takeIf { it.amountMinor != total?.amountMinor }?.let { add(TotalCandidate(it.amountMinor, "Paid by card")) }
            if (total == null && subtotal != null && tax != null) add(TotalCandidate(subtotal.amountMinor + tax, "Subtotal plus tax"))
            subtotal?.let { add(TotalCandidate(it.amountMinor, "Subtotal, before tax")) }
            if (isEmpty()) priced.maxByOrNull { it.amountMinor }?.let { add(TotalCandidate(it.amountMinor, "Largest amount")) }
        }.distinctBy { it.amountMinor }

        // Sure when the total line is there and the card payment, if printed, agrees with it.
        val sure = total != null && (paid == null || paid.amountMinor == total.amountMinor)

        val itemEnd = priced.indexOfFirst { Subtotal.containsMatchIn(it.label) || Total.containsMatchIn(it.label) || Tax.containsMatchIn(it.label) }
        val items = priced.take(if (itemEnd < 0) priced.size else itemEnd)
            .filter { it.label.any(Char::isLetter) && !NotAnItem.containsMatchIn(it.line) }
            .map { line ->
                val qty = Quantity.find(line.label)
                val name = (qty?.let { line.label.removeRange(it.range) } ?: line.label).trim().trimEnd('-', ':', '.').trim()
                ReceiptItem(name, qty?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt() ?: 1, line.amountMinor)
            }
            .filter { it.name.length >= 2 }

        return ParsedReceipt(
            merchant = merchant(lines),
            date = TextReading.date(text, today)?.value,
            time = TextReading.time(text)?.value,
            candidates = candidates,
            total = if (sure) total!!.amountMinor else candidates.singleOrNull()?.amountMinor,
            taxMinor = tax,
            items = items,
            last4 = Card.find(text)?.groupValues?.get(1),
        )
    }

    /** The first line that reads like a name: letters, not an address, tax id, date or heading. */
    private fun merchant(lines: List<String>): String? = lines.take(6).firstOrNull { line ->
        line.count(Char::isLetter) >= 3 && !NotAName.containsMatchIn(line) && line.count(Char::isDigit) <= 2
    }?.let(TextReading::tidyName)

    private class PricedLine(val line: String, val label: String, val amountMinor: Long)

    private fun rupees(minor: Long) = "₹" + ((minor + 50) / 100)

    private val Total = Regex("""\b(grand\s+total|total|net\s+amount|amount\s+due|bill\s+amount|amount\s+payable|net\s+payable)\b""", RegexOption.IGNORE_CASE)
    private val Subtotal = Regex("""\bsub\s*-?\s*total\b""", RegexOption.IGNORE_CASE)
    private val Tax = Regex("""\b(cgst|sgst|igst|gst|vat|tax|cess|service\s+charge)\b""", RegexOption.IGNORE_CASE)
    private val Paid = Regex("""\b(paid|visa|mastercard|master\s+card|rupay|amex|card|upi)\b""", RegexOption.IGNORE_CASE)
    private val Card = Regex("""[*xX•]{2,}\s*(\d{4})\b""")
    private val Quantity = Regex("""(?:\bx\s?(\d{1,3})\b|\b(\d{1,3})\s?x\b|\bqty[:\s]*(\d{1,3})\b)""", RegexOption.IGNORE_CASE)
    private val NotAnItem = Regex("""\b(change|cash|tender|round(ing)?\s*off|discount|balance|paid|visa|card|upi)\b""", RegexOption.IGNORE_CASE)
    private val NotAName = Regex(
        """\b(tax\s+invoice|invoice|receipt|bill\s*(no|#)|gstin|fssai|phone|tel|ph[:.]|road|rd\b|street|st\b|nagar|floor|main|cross|sector|date|time|table|cashier|welcome)\b""",
        RegexOption.IGNORE_CASE,
    )
}
