package dev.kortex.core.pattern

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Node
import dev.kortex.core.graph.complete
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.Models
import dev.kortex.core.log.d
import dev.kortex.core.log.i
import dev.kortex.core.log.w
import dev.kortex.core.prompt.PlanPrompt
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message
import dev.kortex.core.state.Plan
import dev.kortex.core.state.PlanStep
import kotlinx.serialization.json.Json

/**
 * Pattern 6 (Planning), T4.2. Decomposes a multi-goal request into 2–[maxSteps]
 * sequential [PlanStep]s that ExecuteStepNode runs one per graph pass.
 *
 * Degradation is first-class: on unparseable output or fewer than [minSteps] steps no
 * plan is set and the graph's fallthrough edge sends the run to plain react — a broken
 * planner must never make the agent worse than today. More than [maxSteps] steps are
 * clamped rather than rejected.
 */
class PlanNode(
    private val model: String = Models.REASONING,
    private val minSteps: Int = 2,
    private val maxSteps: Int = 5,
) : Node {
    override suspend fun run(ctx: AgentContext, state: AgentState): AgentState {
        ctx.onProgress.report("Planning…")
        val goal = state.goal?.description
            ?: state.messages.lastOrNull { it.role == Message.Role.USER }?.content.orEmpty()
        ctx.logger.d(TAG, "decomposing: \"$goal\"")

        val resp = ctx.complete(
            LlmRequest(
                model = model,
                messages = listOf(Message(Message.Role.USER, PlanPrompt.build(goal, ctx.tools.all()))),
                temperature = 0.0,
            )
        )
        val s = state.copy(
            budget = state.budget.copy(
                tokensUsed = state.budget.tokensUsed + resp.inputTokens + resp.outputTokens,
            ),
        )

        val steps = parseSteps(resp.message.content)
        return when {
            steps == null -> {
                ctx.logger.w(TAG, "unparseable plan output — degrading to react")
                s.trace("plan", "degraded", "unparseable")
            }
            steps.size < minSteps -> {
                ctx.logger.w(TAG, "only ${steps.size} step(s) — degrading to react")
                s.trace("plan", "degraded", "too_few_steps")
            }
            else -> {
                val clamped = steps.take(maxSteps)
                ctx.logger.i(TAG, "plan created: ${clamped.size} steps")
                s.copy(plan = Plan(clamped.map { PlanStep(it) }))
                    .trace("plan", "created", "${clamped.size} steps")
            }
        }
    }

    /**
     * Same fence-stripping / first-bracket parse idiom as LlmMemoryWriter: strip code
     * fences, take the outermost `[...]`, decode as a JSON array of strings. Null (not
     * empty) on any parse failure so the caller can distinguish "garbage" from "[]".
     */
    private fun parseSteps(content: String): List<String>? {
        val text = content.trim().removeCodeFences()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start == -1 || end == -1 || end < start) return null
        return runCatching {
            json.decodeFromString<List<String>>(text.substring(start, end + 1))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun String.removeCodeFences(): String =
        replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "").trim()

    private companion object {
        const val TAG = "PlanNode"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
