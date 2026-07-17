package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Node
import dev.kortex.core.graph.complete
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.log.d
import dev.kortex.core.log.i
import dev.kortex.core.prompt.ReflectPrompt
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message

/**
 * Pattern 4 (Reflection). A critic reviews the latest answer against the user's request.
 * If it's good enough it sets scratch["reflect_verdict"] = "ok" (graph routes to END);
 * otherwise it appends actionable feedback as a new user turn, sets the verdict to
 * "revise", and the graph loops back to the executing node to try again.
 *
 * [maxReflections] caps the loop so we can't revise forever (also guarded by the budget).
 *
 * [model] selects the reviewer model. T4.4 (docs/PROMPT_IMPROVEMENT_PLAN.md) evaluated
 * right-sizing this to [Models.FAST]; the default stays [Models.REASONING] until a LIVE
 * run of the reflect eval suite (PromptEvalTest's FAST-vs-REASONING comparison, see
 * docs/eval-baselines.md §T4.4) shows FAST matching REASONING on missed-REVISE (the
 * dangerous failure — a wrong answer reaching the user) and staying within one case on
 * overall accuracy. Decide with that data, not intuition.
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

        /** Marks the feedback turns this node appends, so later reviews can skip them. */
        private const val FEEDBACK_PREFIX = "Please revise your previous answer."
    }

    override suspend fun run(ctx: AgentContext, state: AgentState): AgentState {
        ctx.onProgress.report("Reviewing answer…")

        val answer = state.messages
            .lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }
            ?.content.orEmpty()
        // The review target is the user's actual request — skip the feedback turns we
        // appended ourselves, or attempt 2+ reviews the answer against our own critique.
        val requestMessage = state.messages.lastOrNull {
            it.role == Message.Role.USER && !it.content.startsWith(FEEDBACK_PREFIX)
        }
        val request = state.goal?.description ?: requestMessage?.content.orEmpty()
        val attachments = requestMessage?.attachments.orEmpty()
        val count = state.scratch[COUNT]?.toIntOrNull() ?: 0

        // Nothing to review, or we've revised enough — accept and finish.
        if (answer.isBlank() || count >= maxReflections) {
            ctx.logger.d(TAG, "skipping review (blank=${answer.isBlank()}, count=$count/$maxReflections)")
            return state.withVerdict(OK).trace("reflect", "verdict", "stop")
        }

        // Pattern 16 (Resource-Aware Optimization): trivial runs — at most one successful
        // tool call, short confident answer, no revision yet — skip the review and its
        // whole LLM call (see ReflectPolicy).
        if (ReflectPolicy.shouldSkip(state)) {
            ctx.logger.i(TAG, "verdict: SKIPPED (trivial run, review not worth an LLM call)")
            return state.withVerdict(OK).trace("reflect", "verdict", "skipped")
        }

        ctx.logger.d(TAG, "reviewing answer (attempt ${count + 1}/$maxReflections)")

        // The reviewer must share the agent's grounding. Without the conversation's SYSTEM
        // message it falls back to its training-cutoff worldview and rejects correct answers
        // as "impossible future information"; without the tool-call list it invents critiques
        // like "the assistant did not search the web" when it demonstrably did; and without
        // the request's attachments it rejects correct vision answers as "the assistant
        // cannot view images" — so the attachments ride along on the review request itself.
        val system = state.messages.firstOrNull { it.role == Message.Role.SYSTEM }?.content
        // T2.1: pair each tool call with its recorded result so the reviewer can verify
        // the answer's claims against what the tools actually returned (truncation and
        // total-cap live in ReflectPrompt).
        val toolExchanges = ReflectPrompt.pair(state.messages)
        val attachmentNote = if (attachments.isEmpty()) "" else """

            The user's request included ${attachments.size} attachment(s), included below
            exactly as the assistant received them. The assistant CAN see and read attached
            images and files — never claim it cannot, and never reject an answer for citing
            their contents. Judge the answer against the attachments themselves.
        """.trimIndent().let { "\n$it" }

        val prompt = ReflectPrompt.build(toolExchanges, request, answer, attachmentNote)

        val resp = ctx.complete(
            LlmRequest(
                model = model,
                messages = listOfNotNull(
                    system?.let { Message(Message.Role.SYSTEM, it) },
                    Message(Message.Role.USER, prompt, attachments = attachments),
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
                        "$FEEDBACK_PREFIX Reviewer feedback: $feedback",
                    )
                )
                .trace("reflect", "verdict", "revise")
        }
    }

    private fun AgentState.withVerdict(v: String): AgentState =
        copy(scratch = scratch + (VERDICT to v))
}
