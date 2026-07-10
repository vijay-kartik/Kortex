# Prompt Eval Baselines

Harness introduced by **T0.3** of [PROMPT_IMPROVEMENT_PLAN.md](PROMPT_IMPROVEMENT_PLAN.md).
Code lives in `core-agent/src/test/kotlin/dev/kortex/core/eval/`; recorded model responses in
`core-agent/src/test/resources/eval/<suite>/<caseId>.txt`.

Each eval case runs through the **real** prompt builders and the **real** parsing code
(`RouterNode`, `AmbientTriage`, `LlmMemoryWriter`) — only the LLM completion is swapped in via
`EvalCompleter`. Cases marked `knownFailure = true` document bugs in today's prompts; they are
printed but excluded from the scored accuracy so they track the problem without failing the
build. (There are currently none: the 4 F3 follow-up cases were retired in T1.3 — see below.)

The router suite runs `RouterNode` with a `defaultTools()` registry, because since **T1.5** the
router prompt embeds a compact tool inventory and routes to `tool_task` only when a registered
tool plausibly helps (finding F6).

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
| router | 13    | 0              | 13/13 (1.00)               | TBD — no live run yet |
| triage | 10    | 0              | 10/10 (1.00)               | TBD — no live run yet |
| memory | 5     | 0              | 5/5 (1.00)                 | TBD — no live run yet |

Baseline constants asserted in tests: `PromptEvalTest` (`ROUTER_BASELINE` etc.). Keep this table
in sync when adding cases or re-recording.

### Known-failure cases

None. The 4 F3 follow-up cases (`followup_yes_do_that`, `followup_what_about_other`,
`followup_again_for_mumbai`, `followup_second_option`) were **retired in T1.3**, not fixed:
per the design direction (PROMPT_IMPROVEMENT_PLAN.md §2), Kortex is a tool-execution agent,
not a conversational one, so interpreting conversational follow-ups is out of scope.

### Router suite changes in T1.3 / T1.5

- **T1.3** dropped the dead `plan` route (routes are now `simple_qa`, `tool_task`; `tool_task`
  explicitly covers multi-step, chained tool work). The former `plan_trip` /
  `plan_research_compare` cases became `multi_step_trip` / `multi_step_research_compare` and
  now expect `tool_task`.
- `followup_thanks` / `followup_ok_cool` stay as plain `simple_qa` cases — no `chitchat` route
  is planned (F5 is out of scope per the design direction), and `simple_qa` remains the
  cheapest sensible home for pleasantries.
- **T1.5** made the router tool-aware. New/changed cases: `simple_qa_unavailable_lights`
  ("turn on my living room lights" — no such tool → `simple_qa`),
  `simple_qa_no_messaging_tool` (formerly `tool_task_send_message` — the eval registry has no
  messaging tool, so a tool-aware router must not pick `tool_task`), and
  `tool_task_chained_currency` (web_search + calculator chained in one run → `tool_task`).
