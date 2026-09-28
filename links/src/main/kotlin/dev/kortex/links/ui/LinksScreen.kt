package dev.kortex.links.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kortex.design.TopBarSearch
import dev.kortex.links.ui.list.LinksRoute

/**
 * The Links tab, as the host shows it. Its search field lives in the home top bar; [search] brings
 * the query here and tells the screen when the field is open.
 */
@Composable
fun LinksScreen(
    search: TopBarSearch,
    modifier: Modifier = Modifier,
    onCreateLink: () -> Unit = {},
) {
    LinksRoute(search = search, onCreateLink = onCreateLink, modifier = modifier)
}
