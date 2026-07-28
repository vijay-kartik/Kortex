package dev.kortex.core.pattern

import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message

/**
 * Pattern 16 (Resource-Aware Optimization): decides whether a run's answer is trivial
 * enough to skip the [ReflectNode] review entirely, saving a whole REASONING-model call.
 *
 * The review only earns its cost when something could plausibly have gone wrong:
 * multi-step tool chains, failed tools, long or hedging answers, or an answer that has
 * already been revised once. A short, confident answer built from at most one successful
 * tool call (or none — nothing to verify against) is accepted as-is.
 *
 * Tool success is read from the trace: [ReActNode] records one event per tool call as
 * `trace("react", "tool", "<name>-><ok>")`, so a `"->true"` suffix means the governor
 * reported success. Limitation: this relies on that detail format; a tool that "succeeds"
 * with a useless result still counts as successful — the hedging check on the answer text
 * is the backstop for that case.
 */
object ReflectPolicy {

    /** Answers at or above this length get reviewed — length correlates with complexity. */
    private const val MAX_SKIP_ANSWER_LENGTH = 600

    /** If the answer itself sounds unsure, it's exactly what the reviewer should see. */
    private val HEDGING_MARKERS = listOf(
        "i couldn't",
        "i could not",
        "not sure",
        "unable to",
        "no results",
    )

    /** Trace detail suffix [ReActNode] writes for a successful tool call. */
    private const val TOOL_OK_SUFFIX = "->true"

    /**
     * True when the answer should go through the reflection review; false when it is
     * safe to accept it without spending an LLM call. Pure — reads only [state].
     *
     * Skips (returns false) only when ALL of the following hold:
     * - at most one tool call was made this run, and every tool call succeeded
     *   (zero tool calls also skips: with nothing to verify the answer against,
     *   the reviewer can only re-judge fluency, which isn't worth a REASONING call);
     * - no revision has happened yet (`scratch[ReflectNode.COUNT]` absent or 0);
     * - the final assistant answer is non-blank, shorter than
     *   [MAX_SKIP_ANSWER_LENGTH] chars, and contains no [HEDGING_MARKERS].
     */
    fun shouldReflect(state: AgentState): Boolean = !shouldSkip(state)

    /** Inverse of [shouldReflect]; the positive form ReflectNode's fast path reads. */
    fun shouldSkip(state: AgentState): Boolean {
        // Once a revision loop has started, always let the reviewer close it out.
        val revisions = state.scratch[ReflectNode.COUNT]?.toIntOrNull() ?: 0
        if (revisions > 0) return false

        val toolEvents = state.trace.filter { it.node == "react" && it.kind == "tool" }
        if (toolEvents.size > 1) return false
        if (toolEvents.any { !it.detail.endsWith(TOOL_OK_SUFFIX) }) return false

        val answer = state.messages
            .lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }
            ?.content.orEmpty()
        if (answer.isBlank()) return false
        if (answer.length >= MAX_SKIP_ANSWER_LENGTH) return false

        val lower = answer.lowercase()
        return HEDGING_MARKERS.none { lower.contains(it) }
    }
}
