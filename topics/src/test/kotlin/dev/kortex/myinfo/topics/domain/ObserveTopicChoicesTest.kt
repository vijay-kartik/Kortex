package dev.kortex.myinfo.topics.domain

import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopicChoices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveTopicChoicesTest {
    private val repository = FakeTopicsRepository()

    @Test
    fun `pinned topics come first, then the most recently changed`() = runTest {
        repository.observedTopics.value = listOf(
            topic(1, pinned = false, updatedAt = 300),
            topic(2, pinned = true, updatedAt = 100),
            topic(3, pinned = false, updatedAt = 200),
            topic(4, pinned = true, updatedAt = 400),
        )

        assertEquals(listOf(4L, 2L, 1L, 3L), ObserveTopicChoices(repository)().first().map { it.id })
    }

    @Test
    fun `a change to the items doesn't re-emit`() = runTest {
        repository.observedTopics.value = listOf(topic(1, pinned = false, updatedAt = 0))
        val emissions = mutableListOf<List<Topic>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ObserveTopicChoices(repository)().toList(emissions) }

        repository.observedItems.value = listOf(TopicItem.Note(1, topicId = 1, addedAtMillis = 0, text = "note"))

        assertEquals(1, emissions.size)
    }

    private fun topic(id: Long, pinned: Boolean, updatedAt: Long) =
        Topic(id, "Topic $id", purpose = null, pinned, sections = emptySet(), createdAtMillis = 0, updatedAtMillis = updatedAt)
}
