package dev.kortex.app.domain.gmail

import dev.kortex.core.gmail.GmailApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailAccessTest {

    private val denied: (GmailToken.Denied) -> String = { "denied: $it" }

    @Test
    fun `a granted token reaches the call`() = runTest {
        val gmail = FakeGmailAccess(GmailToken.Granted("me@gmail.com", "t1"))

        val result = gmail.withToken(denied) { "${it.account} ${it.token}" }

        assertEquals("me@gmail.com t1", result)
        assertTrue(gmail.invalidated.isEmpty())
    }

    @Test
    fun `no connected account goes to denied without calling`() = runTest {
        val gmail = FakeGmailAccess(GmailToken.NotConnected)

        val result = gmail.withToken(denied) { error("called") }

        assertEquals("denied: NotConnected", result)
    }

    @Test
    fun `a 401 drops the token and retries once with a fresh one`() = runTest {
        val gmail = FakeGmailAccess(
            GmailToken.Granted("me@gmail.com", "expired"),
            GmailToken.Granted("me@gmail.com", "fresh"),
        )
        val used = mutableListOf<String>()

        val result = gmail.withToken(denied) {
            used += it.token
            if (it.token == "expired") throw GmailApiException(401, "Unauthorized")
            "read"
        }

        assertEquals("read", result)
        assertEquals(listOf("expired", "fresh"), used)
        assertEquals(listOf("expired"), gmail.invalidated)
    }

    @Test(expected = GmailApiException::class)
    fun `a second 401 is not retried again`() = runTest {
        val gmail = FakeGmailAccess(
            GmailToken.Granted("me@gmail.com", "t1"),
            GmailToken.Granted("me@gmail.com", "t2"),
        )

        gmail.withToken(denied) { throw GmailApiException(401, "Unauthorized") }
    }

    @Test(expected = GmailApiException::class)
    fun `other Gmail errors are not retried`() = runTest {
        val gmail = FakeGmailAccess(GmailToken.Granted("me@gmail.com", "t1"))

        try {
            gmail.withToken(denied) { throw GmailApiException(403, "Forbidden") }
        } finally {
            assertTrue(gmail.invalidated.isEmpty())
        }
    }
}
