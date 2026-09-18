package dev.kortex.myinfo.topics.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kortex.myinfo.topics.ui.list.TopicsListRoute

/**
 * My Info › Topics tab content. The new-topic form, the topic detail and search are full-screen,
 * so the host shows them over the tabbed UI: [onCreateTopic] should open
 * [dev.kortex.myinfo.topics.ui.create.NewTopicRoute], [onOpenTopic] the topic's detail, and
 * [onSearch] [dev.kortex.myinfo.topics.ui.search.TopicSearchRoute].
 */
@Composable
fun TopicsScreen(
    onCreateTopic: () -> Unit,
    onOpenTopic: (Long) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopicsListRoute(
        onOpenTopic = onOpenTopic,
        onCreateTopic = onCreateTopic,
        onSearch = onSearch,
        modifier = modifier,
    )
}
