package dev.kortex.core.llm

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PdfPageBitmapSizeTest {
    @Test
    fun `renders a small page at 2x`() {
        pdfPageBitmapSize(400, 300) shouldBe (800 to 600)
        pdfPageBitmapSize(800, 600) shouldBe (1600 to 1200)
    }

    @Test
    fun `caps the longest side of a large-format page`() {
        // A0 in points.
        val (w, h) = pdfPageBitmapSize(2384, 3370)
        h shouldBe 1600
        w shouldBe (2384 * (1600f / 3370)).toInt()
    }

    @Test
    fun `never yields an empty bitmap`() {
        pdfPageBitmapSize(0, 0) shouldBe (1 to 1)
        pdfPageBitmapSize(100_000, 1) shouldBe (1600 to 1)
    }
}
