package dev.kortex.finance.domain.read

/**
 * Which received SMS the automatic path may read at all (docs/SMS_AUTO_PLAN.md › Rules). Banks in
 * India send from registered DLT headers: an operator and circle prefix, a 6-character id, and a
 * category suffix on newer ones — `AX-HDFCBK`, `VM-ICICIT`, `JD-SBIINB-S`. Some phones show the id
 * alone (`HDFCBK`). Anything from a phone number or a numeric short code is a person or a service,
 * and is dropped unread. Passing says only "could be a bank": the patterns decide the rest.
 */
object SenderGate {
    fun mayRead(sender: String): Boolean {
        val match = Header.matchEntire(sender.trim()) ?: return false
        return match.groupValues[1].any(Char::isLetter)
    }

    private val Header = Regex("""(?:[A-Z]{2}-)?([A-Z0-9]{6})(?:-[STPG])?""", RegexOption.IGNORE_CASE)
}
