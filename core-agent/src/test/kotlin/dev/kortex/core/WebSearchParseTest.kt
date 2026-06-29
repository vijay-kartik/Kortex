package dev.kortex.core

import dev.kortex.core.tool.builtin.parseResults
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class WebSearchParseTest {
    // Trimmed-down shape of a real DuckDuckGo HTML result list.
    private val sampleHtml = """
        <div class="result results_links">
          <a class="result__a" rel="nofollow"
             href="//duckduckgo.com/l/?uddg=https%3A%2F%2Faside.app%2F&amp;rut=abc">
             Aside &mdash; the new <b>browser</b>
          </a>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Faside.app%2F">
             Aside is a newly released <b>web browser</b> focused on side-by-side tabs.
          </a>
        </div>
        <div class="result results_links">
          <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Faside">
             Aside review
          </a>
          <a class="result__snippet">First impressions of the Aside browser.</a>
        </div>
    """.trimIndent()

    @Test
    fun `extracts title, decoded url, and snippet from DDG html`() {
        val results = parseResults(sampleHtml)

        results shouldHaveSize 2
        results[0].title shouldContain "Aside"
        results[0].title shouldContain "browser"          // nested <b> stripped, text kept
        results[0].url shouldBe "https://aside.app/"       // uddg redirect decoded
        results[0].snippet shouldContain "newly released"
        results[1].url shouldBe "https://example.com/aside"
    }
}
