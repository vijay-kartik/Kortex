package dev.kortex.myinfo.topics.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kortex.myinfo.topics.ui.list.TopicsListRoute

/**
 * My Info › Topics tab content. The new-topic form and topic detail are full-screen, so the host
 * shows them over the tabbed UI: [onCreateTopic] should open [dev.kortex.myinfo.topics.ui.create.NewTopicRoute],
 * and [onOpenTopic] the topic's detail.
 */
@Composable
fun TopicsScreen(
    onCreateTopic: () -> Unit,
    onOpenTopic: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    TopicsListRoute(onOpenTopic = onOpenTopic, onCreateTopic = onCreateTopic, modifier = modifier)
}
