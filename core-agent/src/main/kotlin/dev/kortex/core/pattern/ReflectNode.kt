package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Node
import dev.kortex.core.graph.complete
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.log.d
import dev.kortex.core.log.i
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message

/**
 * Pattern 4 (Reflection). A critic reviews the latest answer against the user's request.
 * If it's good enough it sets scratch["reflect_verdict"] = "ok" (graph routes to END);
 * otherwise it appends actionable feedback as a new user turn, sets the verdict to
 * "revise", and the graph loops back to the executing node to try again.
 *
 * [maxReflections] caps the loop so we can't revise forever (also guarded by the budget).
 */
class ReflectNode(
    private val model: String = Models.REASONING,
    private val maxReflections: Int = 2,
) : Node {
    companion object {
        const val VERDICT = "reflect_verdict"
        const val COUNT = "reflections"
        const val OK = "ok"
        const val REVISE = "revise"
        private const val TAG = "ReflectNode"
    }

    override suspend fun run(ctx: AgentContext, state: AgentState): AgentState {
        ctx.onProgress.report("Reviewing answer…")

        val answer = state.messages
            .lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }
            ?.content.orEmpty()
        val request = state.messages.firstOrNull { it.role == Message.Role.USER }?.content
            ?: state.goal?.description.orEmpty()
        val count = state.scratch[COUNT]?.toIntOrNull() ?: 0

        // Nothing to review, or we've revised enough — accept and finish.
        if (answer.isBlank() || count >= maxReflections) {
            ctx.logger.d(TAG, "skipping review (blank=${answer.isBlank()}, count=$count/$maxReflections)")
            return state.withVerdict(OK).trace("reflect", "verdict", "stop")
        }

        ctx.logger.d(TAG, "reviewing answer (attempt ${count + 1}/$maxReflections)")

        // The reviewer must share the agent's grounding. Without the conversation's SYSTEM
        // message it falls back to its training-cutoff worldview and rejects correct answers
        // as "impossible future information"; without the tool-call list it invents critiques
        // like "the assistant did not search the web" when it demonstrably did.
        val system = state.messages.firstOrNull { it.role == Message.Role.SYSTEM }?.content
        val toolsUsed = state.messages
            .flatMap { it.toolCalls }
            .joinToString("\n") { "- ${it.name}(${it.argumentsJson})" }

        val prompt = """
            You are a strict reviewer. Decide whether the assistant's answer fully and
            correctly addresses the user's request.
            The assistant has real, live tools (web search, opening URLs, the device clock);
            facts in its answer may come from those tool results, which are current and
            trustworthy even when they postdate your training data. Never reject an answer
            because its dates are later than what you know, and never claim the assistant
            cannot search the web or access real-time information — it can.
            - If the answer is good, reply with exactly: OK
            - Otherwise reply: REVISE: <specific, actionable feedback>

            Tool calls the assistant already made during this run:
            ${toolsUsed.ifBlank { "(none)" }}

            User request:
            $request

            Assistant answer:
            $answer
        """.trimIndent()

        val resp = ctx.complete(
            LlmRequest(
                model = model,
                messages = listOfNotNull(
                    system?.let { Message(Message.Role.SYSTEM, it) },
                    Message(Message.Role.USER, prompt),
                ),
                temperature = 0.0,
            )
        )
        val verdict = resp.message.content.trim()
        val budget = state.budget.copy(
            tokensUsed = state.budget.tokensUsed + resp.inputTokens + resp.outputTokens,
        )

        // Default to OK on anything ambiguous, so we never loop on a malformed critique.
        val needsRevision = verdict.contains("REVISE", ignoreCase = true) &&
            !verdict.equals("OK", ignoreCase = true)
        ctx.logger.i(TAG, "verdict: ${if (needsRevision) "REVISE" else "OK"}")

        return if (!needsRevision) {
            state.copy(budget = budget).withVerdict(OK).trace("reflect", "verdict", "ok")
        } else {
            val feedback = verdict.substringAfter("REVISE:", verdict).trim()
            state.copy(
                budget = budget,
                scratch = state.scratch + (VERDICT to REVISE) + (COUNT to (count + 1).toString()),
            )
                .withMessage(
                    Message(
                        Message.Role.USER,
                        "Please revise your previous answer. Reviewer feedback: $feedback",
                    )
                )
                .trace("reflect", "verdict", "revise")
        }
    }

    private fun AgentState.withVerdict(v: String): AgentState =
        copy(scratch = scratch + (VERDICT to v))
}
