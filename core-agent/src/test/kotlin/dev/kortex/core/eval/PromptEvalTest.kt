package dev.kortex.core.eval

import dev.kortex.core.llm.Models
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Prompt eval harness entry point (T0.3 of docs/PROMPT_IMPROVEMENT_PLAN.md).
 *
 * RECORDED mode (default, CI): completions come from fixtures in
 * `src/test/resources/eval/`, and each suite's accuracy must not drop below the committed
 * baseline. Known-failure cases are printed but never scored.
 *
 * LIVE mode (`KORTEX_EVAL_LIVE=1` plus `OPENAI_API_KEY` or `DEEPSEEK_API_KEY`): the same
 * cases run against a real provider; scores are printed but NOT asserted (live baselines
 * are tracked in docs/eval-baselines.md). See that doc for the exact commands.
 */
class PromptEvalTest {

    private suspend fun check(report: EvalReport, recordedBaseline: Double) {
        report.print()
        if (!EvalMode.isLive) {
            report.accuracy shouldBeGreaterThanOrEqual recordedBaseline
        }
    }

    @Test
    fun `router eval meets baseline`() = runTest {
        check(RouterEvalSuite(EvalSets.router).run(EvalMode.resolveCompleter()), ROUTER_BASELINE)
    }

    @Test
    fun `triage eval meets baseline`() = runTest {
        check(TriageEvalSuite(EvalSets.triage).run(EvalMode.resolveCompleter()), TRIAGE_BASELINE)
    }

    @Test
    fun `memory writer eval meets baseline`() = runTest {
        check(MemoryEvalSuite(EvalSets.memory).run(EvalMode.resolveCompleter()), MEMORY_BASELINE)
    }

    @Test
    fun `reflect eval meets baseline`() = runTest {
        val scores = ReflectEvalSuite(EvalSets.reflect).run(EvalMode.resolveCompleter())
        check(scores.report, REFLECT_BASELINE)
    }

    /**
     * T4.4 reflect-model right-sizing: runs the SAME reflect cases twice — reviewer on
     * [Models.FAST] and on [Models.REASONING] — and prints a per-model score table.
     * Live-only (skipped otherwise): in RECORDED mode both runs would read the same
     * fixtures and the comparison would be meaningless. Scores are printed, not asserted;
     * the decision rule lives in docs/eval-baselines.md §T4.4 (flip the ReflectNode
     * default to FAST only if FAST matches REASONING on missed-REVISE and stays within
     * one case on overall accuracy).
     */
    @Test
    fun `reflect model comparison FAST vs REASONING (live only)`() = runTest {
        Assumptions.assumeTrue(
            EvalMode.isLive,
            "Live-only: set KORTEX_EVAL_LIVE=1 and OPENAI_API_KEY or DEEPSEEK_API_KEY",
        )
        val completer = EvalMode.resolveCompleter()
        val runs = listOf(
            ReflectEvalSuite(EvalSets.reflect, model = Models.FAST).run(completer),
            ReflectEvalSuite(EvalSets.reflect, model = Models.REASONING).run(completer),
        )
        println("=== T4.4 reflect model comparison (live) ===")
        println("%-24s %-10s %-13s %-13s".format("model", "accuracy", "false-REVISE", "missed-REVISE"))
        runs.forEach { s ->
            val acc = "${s.report.passedCount}/${s.report.scored.size}"
            println("%-24s %-10s %-13d %-13d".format(s.model, acc, s.falseRevise, s.missedRevise))
        }
        runs.forEach { it.report.print() }
    }

    private companion object {
        // Committed RECORDED baselines — keep in sync with docs/eval-baselines.md.
        // Fixtures are hand-written plausible responses, so all scored cases pass today.
        const val ROUTER_BASELINE = 1.0
        const val TRIAGE_BASELINE = 1.0
        const val MEMORY_BASELINE = 1.0
        const val REFLECT_BASELINE = 1.0
    }
}
