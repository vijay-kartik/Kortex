# Prompt Eval Baselines

Harness introduced by **T0.3** of [PROMPT_IMPROVEMENT_PLAN.md](PROMPT_IMPROVEMENT_PLAN.md).
Code lives in `core-agent/src/test/kotlin/dev/kortex/core/eval/`; recorded model responses in
`core-agent/src/test/resources/eval/<suite>/<caseId>.txt`.

Each eval case runs through the **real** prompt builders and the **real** parsing code
(`RouterNode`, `AmbientTriage`, `LlmMemoryWriter`) — only the LLM completion is swapped in via
`EvalCompleter`. Cases marked `knownFailure = true` document bugs in today's prompts (currently:
router misrouting multi-turn follow-ups, finding F3); they are printed but excluded from the
scored accuracy so they track the problem without failing the build.

## Running recorded evals (default, CI)

```powershell
.\gradlew.bat :core-agent:testDebugUnitTest --tests "dev.kortex.core.eval.PromptEvalTest"
```

(Any `:core-agent:test*UnitTest` run includes them.) Completions come from the checked-in
fixtures; each suite's accuracy is asserted against the baseline below. Add `--info` (or look at
the HTML test report) to see the per-case pass/fail lines.

## Running live evals

Set `KORTEX_EVAL_LIVE=1` plus an API key (OpenAI is preferred if both are set, mirroring
`KortexContainer`), then run the same test class:

```powershell
$env:KORTEX_EVAL_LIVE = "1"
$env:OPENAI_API_KEY = "sk-..."   # or $env:DEEPSEEK_API_KEY = "..."
.\gradlew.bat :core-agent:testDebugUnitTest --tests "dev.kortex.core.eval.PromptEvalTest"
```

In live mode the same cases hit the real provider (real model ids, temperatures, prompts); scores
are printed to the test output but **not** asserted, since live baselines are still TBD. Use live
runs to (a) measure the true baseline and (b) re-record fixtures after a prompt change.

## Baseline scores

Scored cases exclude known failures. Recorded fixtures are hand-written plausible model responses
until the first live re-recording, so the recorded baseline is by construction 100%; its job is
regression detection on the prompt + parse pipeline.

| Suite  | Cases | Known failures | Recorded baseline (scored) | Live baseline |
|--------|-------|----------------|----------------------------|---------------|
| router | 15    | 4              | 11/11 (1.00)               | TBD — no live run yet |
| triage | 10    | 0              | 10/10 (1.00)               | TBD — no live run yet |
| memory | 5     | 0              | 5/5 (1.00)                 | TBD — no live run yet |

Baseline constants asserted in tests: `PromptEvalTest` (`ROUTER_BASELINE` etc.). Keep this table
in sync when adding cases or re-recording.

### Known-failure cases (tracked for Phase 1)

All in the router suite; all document **F3** (the router classifies the last user message in
isolation, so follow-ups lose their context) and are expected to flip after **T1.2** (router gets
conversation context):

| Case id | Follow-up message | Expected | Today (recorded) |
|---|---|---|---|
| `followup_yes_do_that`      | "yes do that" after offering a weather lookup      | `tool_task` | `simple_qa` |
| `followup_what_about_other` | "what about the other one?" after a review search  | `tool_task` | `simple_qa` |
| `followup_again_for_mumbai` | "can you do that again but for Mumbai?"            | `tool_task` | `simple_qa` |
| `followup_second_option`    | "let's go with the second option" (send a message) | `tool_task` | `simple_qa` |

Related but not flagged: `followup_thanks` / `followup_ok_cool` expect `simple_qa` because no
`chitchat` route exists yet (finding F5, fixed by T1.3) — update their expectations when the
route lands.
