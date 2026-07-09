package dev.kortex.core.eval

import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import kotlinx.coroutines.test.runTest
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

    private companion object {
        // Committed RECORDED baselines — keep in sync with docs/eval-baselines.md.
        // Fixtures are hand-written plausible responses, so all scored cases pass today.
        const val ROUTER_BASELINE = 1.0
        const val TRIAGE_BASELINE = 1.0
        const val MEMORY_BASELINE = 1.0
    }
}
