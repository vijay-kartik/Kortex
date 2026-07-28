package dev.kortex.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.kortex.app.store.ChatSessionDao
import dev.kortex.app.store.ChatSessionEntity
import dev.kortex.core.Agent
import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Approver
import dev.kortex.core.graph.LlmUsageListener
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.log.Logger
import dev.kortex.core.state.Attachment
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Runs the agent for share-intake requests after the floating composer is dismissed —
 * the user has moved on, so the run is app-scoped (survives the activity), the result
 * is persisted as a regular chat session, and a notification brings the user back to it.
 *
 * Runs headless: there is no UI to host the Human-in-the-Loop dialog, so the approver
 * declines MEDIUM/HIGH-risk tools; the agent is told so and works with LOW-risk ones.
 */
class ShareAgentRunner(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val llm: LlmProvider,
    private val tools: ToolRegistry,
    private val sessionDao: ChatSessionDao,
    private val runStore: dev.kortex.core.observability.AgentRunStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Fire-and-forget: starts the run and immediately returns the new session's id. */
    fun submit(query: String, attachment: Attachment): String {
        val sessionId = UUID.randomUUID().toString()
        val title = query.ifBlank { attachment.filename ?: "Shared ${kindOf(attachment)}" }.take(40)

        scope.launch {
            val reasoning = mutableListOf<ReasoningLine>()
            var tokens = 0
            var toolCalls = 0
            val startMs = System.currentTimeMillis()
            val recorder = dev.kortex.core.observability.AgentRunRecorder(
                query = query,
                sessionId = sessionId,
                delegate = Logger { level, tag, message, error ->
                    AndroidLogger.log(level, tag, message, error)
                    reasoning += ReasoningLine(level, tag, message)
                },
            )
            val ctx = AgentContext(
                llm = llm,
                tools = tools,
                governor = ToolGovernor(onAudit = { entry -> toolCalls++; recorder.audit(entry) }),
                // Headless: no one is there to approve, so risky tools are declined.
                approver = Approver { _, _ -> false },
                logger = recorder.logger,
                onLlmUsage = LlmUsageListener { usage ->
                    tokens += usage.inputTokens + usage.outputTokens
                    recorder.usage.report(usage)
                },
            )

            val userMessage = Message(Message.Role.USER, query, listOf(attachment))
            val turns = runCatching { Agent(ctx).ask(query, listOf(attachment)) }
                .also { outcome ->
                    val run = outcome.fold(
                        onSuccess = { recorder.finish(it, dev.kortex.core.observability.AgentRun.Status.COMPLETED) },
                        onFailure = { recorder.finish(dev.kortex.core.state.AgentState(), dev.kortex.core.observability.AgentRun.Status.FAILED) },
                    )
                    runCatching { runStore.save(run) }
                }
                .fold(
                    onSuccess = { result ->
                        val answer = result.messages
                            .lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }
                            ?: Message(Message.Role.ASSISTANT, "The agent finished without producing an answer.")
                        val stats = ReasoningStats(tokens, toolCalls, System.currentTimeMillis() - startMs)
                        listOf(ChatTurn(userMessage), ChatTurn(answer, reasoning.toList(), stats))
                    },
                    onFailure = { err ->
                        AndroidLogger.log(Logger.Level.ERROR, TAG, "share run failed: ${err.message}", err)
                        listOf(
                            ChatTurn(userMessage),
                            ChatTurn(Message(Message.Role.ASSISTANT, "Processing failed: ${err.message ?: "unknown error"}. Open the session and retry from the chat.")),
                        )
                    },
                )

            sessionDao.upsert(
                ChatSessionEntity(
                    id = sessionId,
                    title = title,
                    turnsJson = json.encodeToString(turns),
                    updatedAtMillis = System.currentTimeMillis(),
                )
            )
            notifyDone(sessionId, title, turns.last().message.content)
        }
        return sessionId
    }

    private fun notifyDone(sessionId: String, title: String, answer: String) {
        val manager = NotificationManagerCompat.from(appContext)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Posted when a shared file has been processed."
            }
        )

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_SESSION_ID, sessionId)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            sessionId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kortex_mark)
            .setContentTitle("Agent processing complete")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(answer.take(300)))
            .setSubText("Tap to view")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        // Pre-13 devices need no permission; on 13+ the composer asked for it. If the user
        // declined, the result still lands in History — only the ping is lost.
        runCatching { manager.notify(sessionId.hashCode(), notification) }
    }

    private fun kindOf(att: Attachment) = when {
        att.mimeType.startsWith("image/") -> "photo"
        att.mimeType == "application/pdf" -> "PDF"
        else -> "file"
    }

    companion object {
        private const val TAG = "ShareAgentRunner"
        private const val CHANNEL_ID = "share_agent_results"
    }
}
