package dev.kortex.core.eval

import dev.kortex.core.ambient.TriageContext
import dev.kortex.core.ambient.TriageDecision
import dev.kortex.core.state.Message

/**
 * Typed eval cases for the parseable prompts (T0.3 of docs/PROMPT_IMPROVEMENT_PLAN.md).
 *
 * A case pairs a real prompt input with an expected, machine-checkable outcome. Suites
 * (see [EvalSuite]) run each case through the REAL prompt builders and the REAL parsing
 * logic (RouterNode, AmbientTriage, LlmMemoryWriter); only the LLM completion itself is
 * supplied by a pluggable [EvalCompleter].
 *
 * [knownFailure] marks a case that documents a bug in today's prompt (e.g. router
 * misrouting multi-turn follow-ups — finding F3). Known failures are reported but excluded
 * from the scored accuracy, so they track the problem without failing the build. When a
 * later phase fixes the behavior, re-record the fixture and drop the flag.
 */

/** Routing case: a conversation whose LAST user message should classify as [expectedRoute]. */
data class RouterEvalCase(
    val id: String,
    val conversation: List<Message>,
    val expectedRoute: String,
    val knownFailure: Boolean = false,
)

/** Ambient-triage case: signals + known context that should triage to [expected]. */
data class TriageEvalCase(
    val id: String,
    val context: TriageContext,
    val expected: TriageDecision,
    val knownFailure: Boolean = false,
)

/**
 * Memory-writer case: the model's JSON must parse into exactly [expectedCount] entries.
 * Covers both schema validity (fixture parses at all) and the dedup instruction
 * (expectedCount = 0 when everything in the activity is already known).
 */
data class MemoryEvalCase(
    val id: String,
    val context: TriageContext,
    val expectedCount: Int,
    val knownFailure: Boolean = false,
)

/**
 * One tool call the assistant made during a reflect case's run. [result] is the content
 * of the matching TOOL message; null means the result was never recorded, so the reviewer
 * sees ReflectPrompt's "(no result recorded)" placeholder.
 */
data class ReflectToolStep(
    val name: String,
    val argumentsJson: String,
    val result: String?,
)

/**
 * Reflect-reviewer case (T4.4): given a finished run — user [request], the [tools]
 * exchanges, and the final [answer] — the real ReflectNode's verdict should be
 * [expectedVerdict] (`ReflectNode.OK` or `ReflectNode.REVISE`).
 *
 * Cases must be non-trivial enough that ReflectPolicy does NOT fast-path them (two or
 * more tool calls, or a hedging answer); the suite fails a case that got policy-skipped
 * instead of reviewed. Case ids are stable so LIVE runs can compare reviewer models
 * (Models.FAST vs Models.REASONING) on identical inputs.
 */
data class ReflectEvalCase(
    val id: String,
    val request: String,
    val answer: String,
    val tools: List<ReflectToolStep>,
    val expectedVerdict: String,
    val knownFailure: Boolean = false,
)
