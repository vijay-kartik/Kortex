package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Node
import dev.kortex.core.graph.complete
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.log.d
import dev.kortex.core.prompt.SynthesizePrompt
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message
import dev.kortex.core.state.PlanStep

/**
 * Pattern 6 (Planning), T4.2. Composes the single final answer from the goal plus every
 * plan step's description/status/result, and appends it as an ASSISTANT message to
 * `state.messages` — so ReflectNode and the app see it exactly like a react answer.
 *
 * On revise passes (reflect → synthesize) the reviewer's feedback USER turn is already
 * in `state.messages`; the previous answer and that feedback ride into the prompt so the
 * model re-answers from the same step results — re-answering from already-collected
 * evidence is cheap; re-running tools is not.
 *
 * Also stores a compact per-step evidence digest in `scratch["plan_evidence"]` for the
 * reviewer / telemetry (grounding the plan-path reviewer in it is a noted follow-up).
 */
class SynthesizeNode(
    private val model: String = Models.REASONING,
) : Node {
    override suspend fun run(ctx: AgentContext, state: AgentState): AgentState {
        val plan = state.plan ?: return state
        ctx.onProgress.report("Composing answer…")
        val goal = state.goal?.description
            ?: state.messages.firstOrNull { it.role == Message.Role.USER }?.content.orEmpty()

        // Revise pass: reflect stored the REVISE verdict and appended its feedback as the
        // latest USER turn; the answer under review is the latest non-blank ASSISTANT turn.
        val revising = state.scratch[ReflectNode.VERDICT] == ReflectNode.REVISE
        val priorAnswer = if (revising) {
            state.messages.lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }?.content
        } else null
        val feedback = if (revising) {
            state.messages.lastOrNull { it.role == Message.Role.USER }?.content
        } else null
        ctx.logger.d(TAG, "synthesizing from ${plan.steps.size} steps (revising=$revising)")

        val prompt = SynthesizePrompt.build(goal, plan.steps, priorAnswer, feedback)
        val resp = ctx.complete(
            LlmRequest(
                model = model,
                messages = listOfNotNull(
                    state.messages.firstOrNull { it.role == Message.Role.SYSTEM },
                    Message(Message.Role.USER, prompt),
                ),
            ),
        )

        val failed = plan.steps.count { it.status == PlanStep.Status.FAILED }
        val evidence = plan.steps.mapIndexed { i, step ->
            "${i + 1}. [${step.status.name}] ${step.description}: " +
                ExecuteStepNode.truncateAtWord(step.result, EVIDENCE_RESULT_CHARS)
        }.joinToString("\n")

        return state.withMessage(resp.message)
            .copy(
                done = true,
                budget = state.budget.copy(
                    tokensUsed = state.budget.tokensUsed + resp.inputTokens + resp.outputTokens,
                ),
                scratch = state.scratch + (EVIDENCE_KEY to evidence),
            )
            .trace("synthesize", "llm", "answer (${plan.steps.size} steps, $failed failed)")
    }

    companion object {
        private const val TAG = "SynthesizeNode"

        /** Compact per-step evidence digest for the reviewer / telemetry. */
        const val EVIDENCE_KEY = "plan_evidence"
        private const val EVIDENCE_RESULT_CHARS = 200
    }
}
