package dev.kortex.core.eval

import dev.kortex.core.ambient.AmbientTriage
import dev.kortex.core.ambient.LlmMemoryWriter
import dev.kortex.core.graph.AgentContext
import dev.kortex.core.pattern.RouterNode
import dev.kortex.core.state.AgentState
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
                tools = ToolRegistry(),
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

/** Runs [TriageEvalCase]s through the real [AmbientTriage] (real TriagePrompt + parse()). */
class TriageEvalSuite(private val cases: List<TriageEvalCase>) {
    suspend fun run(completer: EvalCompleter): EvalReport {
        val results = cases.map { case ->
            val triage = AmbientTriage(EvalLlmProvider(completer, NAME, case.id))
            val result = triage.triage(case.context)
            CaseResult(
                id = case.id,
                passed = result.decision == case.expected,
                knownFailure = case.knownFailure,
                detail = "expected=${case.expected} actual=${result.decision} (${result.rationale.take(80)})",
            )
        }
        return EvalReport(NAME, results)
    }

    companion object {
        const val NAME = "triage"
    }
}

/** Runs [MemoryEvalCase]s through the real [LlmMemoryWriter] (real MemoryPrompt + parseDrafts()). */
class MemoryEvalSuite(private val cases: List<MemoryEvalCase>) {
    suspend fun run(completer: EvalCompleter): EvalReport {
        val results = cases.map { case ->
            val writer = LlmMemoryWriter(EvalLlmProvider(completer, NAME, case.id))
            val entries = writer.write(case.context)
            CaseResult(
                id = case.id,
                passed = entries.size == case.expectedCount,
                knownFailure = case.knownFailure,
                detail = "expected=${case.expectedCount} entries actual=${entries.size}",
            )
        }
        return EvalReport(NAME, results)
    }

    companion object {
        const val NAME = "memory"
    }
}
