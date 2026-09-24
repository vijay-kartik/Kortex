package dev.kortex.app.ui.screens.chat

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.app.ui.util.conversationAsText
import dev.kortex.design.CircleButton
import dev.kortex.design.R

/** Top-bar action for the Chat tab: shares the conversation as text. Hidden while it's empty. */
@Composable
fun ChatShareAction(vm: ChatViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (ui.turns.isEmpty()) return
    val context = LocalContext.current
    CircleButton(
        icon = R.drawable.ic_share,
        contentDescription = "Share conversation",
        onClick = { shareConversation(context, ui.turns) },
    )
}

private fun shareConversation(context: Context, turns: List<ChatTurn>) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, conversationAsText(turns))
    }
    context.startActivity(Intent.createChooser(send, "Share conversation"))
}
