package dev.kortex.core.llm

import dev.kortex.core.state.Attachment
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PdfPageCacheTest {
    private val rendered = mutableListOf<String>()

    private fun cache(maxEntries: Int = 4, fail: Boolean = false) = PdfPageCache(maxEntries) { pdf ->
        rendered += pdf.dataBase64
        if (fail) error("corrupt PDF")
        listOf(Attachment("image/jpeg", "page-of-${pdf.dataBase64}"))
    }

    private fun pdf(data: String) = Attachment("application/pdf", data, "doc.pdf")

    @Test
    fun `renders each PDF once across repeated requests`() {
        val cache = cache()
        val a = pdf("AAAA")

        repeat(5) { cache.pagesFor(a) shouldBe listOf(Attachment("image/jpeg", "page-of-AAAA")) }
        // An equal payload from a reloaded message also hits.
        cache.pagesFor(pdf(String("AAAA".toCharArray())))

        rendered shouldBe listOf("AAAA")
    }

    @Test
    fun `evicts the least recently used PDF beyond the cap`() {
        val cache = cache(maxEntries = 2)
        cache.pagesFor(pdf("A"))
        cache.pagesFor(pdf("B"))
        cache.pagesFor(pdf("A"))
        cache.pagesFor(pdf("C")) // evicts B
        cache.pagesFor(pdf("A"))
        cache.pagesFor(pdf("B"))

        rendered shouldBe listOf("A", "B", "C", "B")
    }

    @Test
    fun `does not cache a failed render`() {
        val cache = cache(fail = true)
        repeat(2) { assertThrows<IllegalStateException> { cache.pagesFor(pdf("BAD")) } }

        rendered shouldBe listOf("BAD", "BAD")
    }
}
