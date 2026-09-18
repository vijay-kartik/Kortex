package dev.kortex.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.kortex.app.ui.screens.chat.ChatRequest

/** Full-screen layer drawn over the tabbed home UI. At most one is open at a time. */
sealed interface Overlay {
    data object None : Overlay
    data object Settings : Overlay

    /** New-link form; [url] pre-fills the address and is empty when opened from the Links tab. */
    data class CreateLink(val url: String = "") : Overlay

    /** New-topic form, opened from the Topics tab. */
    data object NewTopic : Overlay

    /** One topic's feed. */
    data class Topic(val topicId: Long) : Overlay

    /** Search across every topic, opened from the Topics tab. */
    data object TopicSearch : Overlay
}

/**
 * Tab and overlay state for [RootScreen]. The tab rules live here so the tab bar, onboarding
 * and entry requests all move between tabs the same way.
 */
@Stable
class RootState(
    selected: KortexTab = KortexTab.Links,
    lastAgentTab: KortexTab = KortexTab.Chat,
    lastMyInfoTab: KortexTab = KortexTab.Links,
    overlay: Overlay = Overlay.None,
    pendingChatRequest: ChatRequest? = null,
) {
    var selected by mutableStateOf(selected)
        private set

    // Coming back to Agent restores the leaf the user left, rather than resetting to Chat.
    private var lastAgentTab by mutableStateOf(lastAgentTab)

    // Same for My Info: Links or Topics, whichever the user was last on.
    private var lastMyInfoTab by mutableStateOf(lastMyInfoTab)

    var overlay by mutableStateOf(overlay)
        private set

    /** Session change waiting for ChatScreen to apply; kept until it does, even across an overlay. */
    var pendingChatRequest by mutableStateOf(pendingChatRequest)
        private set

    /** The category shown expanded in the tab bar: always the selected tab's category. */
    val expanded: TabCategory get() = selected.category

    fun openTab(tab: KortexTab) {
        selected = tab
        when (tab.category) {
            TabCategory.Agent -> lastAgentTab = tab
            TabCategory.MyInfo -> lastMyInfoTab = tab
        }
    }

    fun openCategory(category: TabCategory) = openTab(
        when (category) {
            TabCategory.Agent -> lastAgentTab
            TabCategory.MyInfo -> lastMyInfoTab
        }
    )

    /** Switches to the Chat tab and hands [request] to ChatScreen. */
    fun openChat(request: ChatRequest) {
        pendingChatRequest = request
        openTab(KortexTab.Chat)
    }

    fun onChatRequestHandled() {
        pendingChatRequest = null
    }

    fun openSettings() {
        overlay = Overlay.Settings
    }

    fun openCreateLink(url: String = "") {
        overlay = Overlay.CreateLink(url)
    }

    fun openNewTopic() {
        overlay = Overlay.NewTopic
    }

    fun openTopicSearch() {
        overlay = Overlay.TopicSearch
    }

    /** Replaces whatever overlay is open, so a topic just created opens in place of its form. */
    fun openTopic(topicId: Long) {
        overlay = Overlay.Topic(topicId)
    }

    fun closeOverlay() {
        overlay = Overlay.None
    }

    companion object {
        private const val SETTINGS = "settings"
        private const val CREATE_LINK = "create_link"
        private const val NEW_TOPIC = "new_topic"
        private const val TOPIC = "topic"
        private const val TOPIC_SEARCH = "topic_search"
        private const val LOAD_SESSION = "load_session"
        private const val NEW_SESSION = "new_session"
        private const val NEW_SESSION_WITH_DRAFT = "new_session_draft"

        /** Saved as [selected, lastAgentTab, overlay kind, overlay value, chat request kind, chat request value, lastMyInfoTab]. */
        val Saver: Saver<RootState, *> = listSaver(
            save = { state ->
                val overlay = state.overlay
                val chat = state.pendingChatRequest
                listOf(
                    state.selected.name,
                    state.lastAgentTab.name,
                    when (overlay) {
                        Overlay.None -> ""
                        Overlay.Settings -> SETTINGS
                        is Overlay.CreateLink -> CREATE_LINK
                        Overlay.NewTopic -> NEW_TOPIC
                        is Overlay.Topic -> TOPIC
                        Overlay.TopicSearch -> TOPIC_SEARCH
                    },
                    when (overlay) {
                        is Overlay.CreateLink -> overlay.url
                        is Overlay.Topic -> overlay.topicId.toString()
                        else -> ""
                    },
                    when (chat) {
                        null -> ""
                        is ChatRequest.LoadSession -> LOAD_SESSION
                        is ChatRequest.NewSession -> if (chat.draft == null) NEW_SESSION else NEW_SESSION_WITH_DRAFT
                    },
                    when (chat) {
                        null -> ""
                        is ChatRequest.LoadSession -> chat.sessionId
                        is ChatRequest.NewSession -> chat.draft.orEmpty()
                    },
                    state.lastMyInfoTab.name,
                )
            },
            restore = { saved ->
                RootState(
                    selected = KortexTab.valueOf(saved[0]),
                    lastAgentTab = KortexTab.valueOf(saved[1]),
                    lastMyInfoTab = KortexTab.valueOf(saved[6]),
                    overlay = when (saved[2]) {
                        SETTINGS -> Overlay.Settings
                        CREATE_LINK -> Overlay.CreateLink(saved[3])
                        NEW_TOPIC -> Overlay.NewTopic
                        TOPIC -> Overlay.Topic(saved[3].toLong())
                        TOPIC_SEARCH -> Overlay.TopicSearch
                        else -> Overlay.None
                    },
                    pendingChatRequest = when (saved[4]) {
                        LOAD_SESSION -> ChatRequest.LoadSession(saved[5])
                        NEW_SESSION -> ChatRequest.NewSession()
                        NEW_SESSION_WITH_DRAFT -> ChatRequest.NewSession(draft = saved[5])
                        else -> null
                    },
                )
            },
        )
    }
}

/** A [RootState] that survives configuration changes and process death. */
@Composable
fun rememberRootState(): RootState = rememberSaveable(saver = RootState.Saver) { RootState() }
