package dev.kortex.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Full-screen layer drawn over the tabbed home UI. At most one is open at a time. */
sealed interface Overlay {
    data object None : Overlay
    data object Settings : Overlay

    /** New-link form; [url] pre-fills the address and is empty when opened from the Links tab. */
    data class CreateLink(val url: String = "") : Overlay
}

/**
 * Tab and overlay state for [RootScreen]. The tab rules live here so the tab bar, onboarding
 * and entry requests all move between tabs the same way.
 */
@Stable
class RootState(
    selected: KortexTab = KortexTab.Links,
    lastAgentTab: KortexTab = KortexTab.Chat,
    overlay: Overlay = Overlay.None,
) {
    var selected by mutableStateOf(selected)
        private set

    // Coming back to Agent restores the leaf the user left, rather than resetting to Chat.
    private var lastAgentTab by mutableStateOf(lastAgentTab)

    var overlay by mutableStateOf(overlay)
        private set

    /** The category shown expanded in the tab bar: always the selected tab's category. */
    val expanded: TabCategory get() = selected.category

    fun openTab(tab: KortexTab) {
        selected = tab
        if (tab.category == TabCategory.Agent) lastAgentTab = tab
    }

    fun openCategory(category: TabCategory) = openTab(
        when (category) {
            TabCategory.Agent -> lastAgentTab
            TabCategory.MyInfo -> KortexTab.Links
        }
    )

    fun openSettings() {
        overlay = Overlay.Settings
    }

    fun openCreateLink(url: String = "") {
        overlay = Overlay.CreateLink(url)
    }

    fun closeOverlay() {
        overlay = Overlay.None
    }

    companion object {
        private const val SETTINGS = "settings"
        private const val CREATE_LINK = "create_link"

        /** Saved as [selected, lastAgentTab, overlay kind, create-link url]. */
        val Saver: Saver<RootState, *> = listSaver(
            save = { state ->
                val overlay = state.overlay
                listOf(
                    state.selected.name,
                    state.lastAgentTab.name,
                    when (overlay) {
                        Overlay.None -> ""
                        Overlay.Settings -> SETTINGS
                        is Overlay.CreateLink -> CREATE_LINK
                    },
                    (overlay as? Overlay.CreateLink)?.url.orEmpty(),
                )
            },
            restore = { saved ->
                RootState(
                    selected = KortexTab.valueOf(saved[0]),
                    lastAgentTab = KortexTab.valueOf(saved[1]),
                    overlay = when (saved[2]) {
                        SETTINGS -> Overlay.Settings
                        CREATE_LINK -> Overlay.CreateLink(saved[3])
                        else -> Overlay.None
                    },
                )
            },
        )
    }
}

/** A [RootState] that survives configuration changes and process death. */
@Composable
fun rememberRootState(): RootState = rememberSaveable(saver = RootState.Saver) { RootState() }
