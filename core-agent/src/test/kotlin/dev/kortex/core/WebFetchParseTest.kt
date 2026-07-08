package dev.kortex.core

import dev.kortex.core.tool.builtin.extractReadableText
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class WebFetchParseTest {
    @Test
    fun `strips scripts, styles, and tags, keeping visible text`() {
        val html = """
            <html><head><style>.a{color:red}</style><script>alert(1)</script></head>
            <body>
              <h1>Richest People</h1>
              <p>Elon Musk is the wealthiest person, with a net worth of $223.8 billion.</p>
              <!-- a comment -->
              <div>See the <b>full list</b> below.</div>
            </body></html>
        """.trimIndent()

        val text = extractReadableText(html)

        text shouldContain "Richest People"
        text shouldContain "Elon Musk is the wealthiest person"
        text shouldContain "full list"
        text shouldNotContain "alert(1)"
        text shouldNotContain "color:red"
        text shouldNotContain "a comment"
        text shouldNotContain "<"
    }

    @Test
    fun `falls back to whole document when there is no body tag`() {
        val text = extractReadableText("<div>Just a fragment</div>")
        text shouldBe "Just a fragment"
    }
}
