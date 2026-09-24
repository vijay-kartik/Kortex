package dev.kortex.myinfo.topics.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kortex.design.TopBarSearch
import dev.kortex.myinfo.topics.ui.list.TopicsListRoute

/**
 * My Info › Topics tab content. The new-topic form and the topic detail are full-screen, so the
 * host shows them over the tabbed UI: [onCreateTopic] should open
 * [dev.kortex.myinfo.topics.ui.create.NewTopicRoute] and [onOpenTopic] the topic's detail.
 * The search field is drawn by the home top bar; [search] brings its query here.
 */
@Composable
fun TopicsScreen(
    search: TopBarSearch,
    onCreateTopic: () -> Unit,
    onOpenTopic: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    TopicsListRoute(
        searchField = search,
        onOpenTopic = onOpenTopic,
        onCreateTopic = onCreateTopic,
        modifier = modifier,
    )
}
