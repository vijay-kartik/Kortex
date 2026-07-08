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
    fun `strips page chrome so menus don't crowd out the article`() {
        val html = """
            <body>
              <header><a href="/">Site Logo</a> Subscribe</header>
              <nav><a>Home</a><a>Politics</a><a>Sports</a><a>ePaper</a></nav>
              <article><p>Elon Musk became the first trillionaire in June 2026.</p></article>
              <aside>Trending: celebrity gossip</aside>
              <footer>Copyright 2026 · Privacy Policy</footer>
            </body>
        """.trimIndent()

        val text = extractReadableText(html)

        text shouldContain "first trillionaire in June 2026"
        text shouldNotContain "Subscribe"
        text shouldNotContain "ePaper"
        text shouldNotContain "celebrity gossip"
        text shouldNotContain "Privacy Policy"
    }

    @Test
    fun `falls back to whole document when there is no body tag`() {
        val text = extractReadableText("<div>Just a fragment</div>")
        text shouldBe "Just a fragment"
    }
}
