package dev.kortex.core.eval

import dev.kortex.core.graph.AgentContext
import dev.kortex.core.llm.Models
import dev.kortex.core.pattern.ReflectNode
import dev.kortex.core.pattern.RouterNode
import dev.kortex.core.state.AgentState
import dev.kortex.core.state.Message
import dev.kortex.core.state.ToolCall
import dev.kortex.core.state.TraceEvent
import dev.kortex.core.tool.builtin.defaultTools
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry

/**
 * Eval suites: each runs its cases through the REAL component (prompt builder + parsing),
 * with the completion supplied by an [EvalCompleter], and returns an [EvalReport].
 */

/** One case's outcome. [detail] is a human-readable expected-vs-actual line. */
data class CaseResult(
    val id: String,
    val passed: Boolean,
    val knownFailure: Boolean,
    val detail: String,
)

/** Per-suite score. Known-failure cases are reported but excluded from [accuracy]. */
data class EvalReport(val suite: String, val results: List<CaseResult>) {
    val scored: List<CaseResult> get() = results.filterNot { it.knownFailure }
    val passedCount: Int get() = scored.count { it.passed }
    val accuracy: Double get() = if (scored.isEmpty()) 1.0 else passedCount.toDouble() / scored.size

    /** Prints per-case results and the suite score to stdout (visible with `gradlew --info` or on failure). */
    fun print() {
        println("=== eval suite '$suite': $passedCount/${scored.size} scored cases passed (accuracy ${"%.2f".format(accuracy)}) ===")
        results.forEach { r ->
            val mark = when {
                r.knownFailure && r.passed -> "KNOWN-FAILURE NOW PASSING?"
                r.knownFailure -> "known-failure"
                r.passed -> "pass"
                else -> "FAIL"
            }
            println("  [$mark] ${r.id}: ${r.detail}")
        }
    }
}

/** Runs [RouterEvalCase]s through the real [RouterNode] (real RouterPrompt + label matching). */
class RouterEvalSuite(private val cases: List<RouterEvalCase>) {
    suspend fun run(completer: EvalCompleter): EvalReport {
        val results = cases.map { case ->
            val ctx = AgentContext(
                llm = EvalLlmProvider(completer, NAME, case.id),
                // Representative registry: the router prompt embeds the tool inventory
                // (T1.5), so eval cases must see the same tools production starts with.
                tools = ToolRegistry(defaultTools()),
                governor = ToolGovernor(),
            )
            val out = RouterNode().run(ctx, AgentState(messages = case.conversation))
            val actual = out.scratch["route"]
            CaseResult(
                id = case.id,
                passed = actual == case.expectedRoute,
                knownFailure = case.knownFailure,
                detail = "expected=${case.expectedRoute} actual=$actual",
            )
        }
        return EvalReport(NAME, results)
    }

    companion object {
        const val NAME = "router"
    }
}

/**
 * Runs [ReflectEvalCase]s through the REAL [ReflectNode] (real ReflectPrompt with
 * tool-result grounding and the T2.2 rubric, real OK/REVISE parsing) — T4.4.
 *
 * The reviewer [model] is a constructor param so LIVE mode can run the same cases twice
 * — once on [Models.FAST] and once on [Models.REASONING] — and compare per-model scores
 * on identical inputs (see PromptEvalTest's live-only comparison test). In RECORDED mode
 * the model id is irrelevant: fixtures assert the harness plumbing and the parsing,
 * not model choice.
 */
class ReflectEvalSuite(
    private val cases: List<ReflectEvalCase>,
    private val model: String = Models.REASONING,
) {
    /**
     * [report] plus the two costly failure modes over scored cases:
     * [falseRevise] — expected OK but got REVISE (wastes a full ReAct revision loop);
     * [missedRevise] — expected REVISE but got OK (a wrong answer reaches the user).
     */
    data class Scores(
        val model: String,
        val report: EvalReport,
        val falseRevise: Int,
        val missedRevise: Int,
    )

    suspend fun run(completer: EvalCompleter): Scores {
        var falseRevise = 0
        var missedRevise = 0
        val results = cases.map { case ->
            val ctx = AgentContext(
                llm = EvalLlmProvider(completer, NAME, case.id),
                tools = ToolRegistry(),
                governor = ToolGovernor(),
            )
            val out = ReflectNode(model = model).run(ctx, stateFor(case))
            // The fast paths ("skipped"/"stop") mean the reviewer LLM was never consulted;
            // a case that hits them is malformed (too trivial) and must fail loudly rather
            // than pass as a fake OK.
            val reviewed = out.trace.lastOrNull { it.node == "reflect" }?.detail in REVIEWED_DETAILS
            val actual = out.scratch[ReflectNode.VERDICT]
            val passed = reviewed && actual == case.expectedVerdict
            if (reviewed && !case.knownFailure && actual != case.expectedVerdict) {
                if (actual == ReflectNode.REVISE) falseRevise++ else missedRevise++
            }
            CaseResult(
                id = case.id,
                passed = passed,
                knownFailure = case.knownFailure,
                detail = "expected=${case.expectedVerdict} actual=$actual" +
                    if (reviewed) "" else " (review was policy-skipped — make the case non-trivial)",
            )
        }
        return Scores(model, EvalReport(NAME, results), falseRevise, missedRevise)
    }

    /**
     * Rebuilds the end-of-run [AgentState] ReflectNode sees: SYSTEM grounding, the user
     * request, one ASSISTANT(toolCalls)+TOOL pair per step (TOOL omitted when the step's
     * result is null, exercising the "(no result recorded)" path), the final answer, and
     * the react trace events ReflectPolicy reads.
     */
    private fun stateFor(case: ReflectEvalCase): AgentState {
        val messages = buildList {
            add(Message(Message.Role.SYSTEM, "You are Kortex, an on-device assistant with live tools."))
            add(Message(Message.Role.USER, case.request))
            case.tools.forEachIndexed { i, step ->
                add(
                    Message(
                        Message.Role.ASSISTANT, "",
                        toolCalls = listOf(ToolCall("c$i", step.name, step.argumentsJson)),
                    )
                )
                step.result?.let { add(Message(Message.Role.TOOL, it, toolCallId = "c$i")) }
            }
            add(Message(Message.Role.ASSISTANT, case.answer))
        }
        val trace = case.tools.map { TraceEvent("react", "tool", "${it.name}->true", 0L) }
        return AgentState(messages = messages, trace = trace)
    }

    companion object {
        const val NAME = "reflect"
        private val REVIEWED_DETAILS = setOf("ok", "revise")
    }
}
