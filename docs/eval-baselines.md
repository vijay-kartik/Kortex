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

| Suite   | Cases | Known failures | Recorded baseline (scored) | Live baseline |
|---------|-------|----------------|----------------------------|---------------|
| router  | 15    | 0              | 15/15 (1.00)               | TBD — no live run yet |
| triage  | 10    | 0              | 10/10 (1.00)               | TBD — no live run yet |
| memory  | 5     | 0              | 5/5 (1.00)                 | TBD — no live run yet |
| reflect | 10    | 0              | 10/10 (1.00)               | TBD — no live run yet (see §T4.4) |

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

### Router suite changes in T4.2 (`plan` route restored)

**T4.2** re-introduced `plan`, now backed by a real PlanNode (`docs/PLANNODE_DESIGN.md`).
New cases with `plan` fixtures:

- `plan_compare_recommend` — "Compare the iPhone 17 and the Pixel 11 on price, camera
  quality, and battery life, then recommend one": two named entities, each needing its own
  research thread, plus a dependent recommendation step.
- `plan_multi_topic` — "Find the weather in Tokyo this weekend, and also get the latest
  USD to JPY exchange rate": two unrelated sub-goals in one request.

**Boundary choice (deliberate, not a silent flip):** the `multi_step_*` cases stay
`tool_task`. The line we draw is *distinct research threads named upfront* vs *one research
chain*:

- `multi_step_trip` ("plan a 3-day trip to Jaipur…") is a single goal — one trip — whose
  sub-parts (budget, itinerary) all fall out of the same chained research; the word "plan"
  in the request does not make it a `plan` route.
- `multi_step_research_compare` ("research the top 5 mid-range phones this year, compare…,
  recommend one") looks like `plan_compare_recommend`, but the entities are **not named
  upfront** — a discovery search must produce the candidate list first, so the whole task is
  one research chain (find list → read comparisons → recommend). By contrast,
  `plan_compare_recommend` names both products, so each is an independently executable
  sub-goal the planner can decompose. This is the closest boundary pair in the suite; if a
  live run shows the FAST model can't hold this line, prefer moving
  `multi_step_research_compare` to `plan` (over-planning a research chain degrades gracefully
  via per-step ReAct loops; under-planning a decomposable request loses the benefit).

## T4.4 — Reflect-model right-sizing (reflect suite + FAST/REASONING comparison)

**T4.4** added a `reflect` eval suite that runs the REAL `ReflectNode` (real `ReflectPrompt`
with T2.1 tool-result grounding and the T2.2 rubric, real OK/REVISE parsing) over 10 cases with
hand-written run fixtures (request + final answer + tool exchanges). The 10 cases span the
rubric:

- **(a) factual consistency:** `revise_contradicts_tools`, `revise_fabricated_number`
- **(b) answers what was asked:** `revise_wrong_question`
- **(c) explicitly requested item missing:** `revise_missing_requested_item`
- **anti-style-revision rule:** `ok_correct_terse` (correct but terse → OK)
- **multi-tool synthesis:** `ok_synthesized_conversion` (search + calculator combined → OK)
- **per-result truncation:** `ok_truncated_multi_results` (>500-char results; claims appear
  before the cut → OK)
- **grounding preamble:** `ok_postdates_training` (tool results dated after any plausible
  training cutoff must not be rejected → OK)
- **unmatched tool call:** `ok_unmatched_tool_call` (one call renders "(no result recorded)";
  answer grounded in the other result → OK)
- **honest empty-handed answer:** `ok_honest_failure` (tools found nothing; answer says so
  plainly and matches them → OK)

Every case has ≥2 tool calls (or a hedging answer), so `ReflectPolicy` never fast-paths it;
the suite fails any case whose review got policy-skipped instead of reaching the reviewer LLM.
Case ids are stable so LIVE runs compare models on identical inputs.

### Model comparison (LIVE only)

`ReflectEvalSuite` takes the reviewer model as a constructor param. The live-only test
`reflect model comparison FAST vs REASONING (live only)` in `PromptEvalTest` runs the same 10
cases twice — reviewer on `Models.FAST` (`gpt-4o-mini` / `deepseek-v4-flash`) and on
`Models.REASONING` (`gpt-4o` / `deepseek-v4-pro`) — and prints per-model accuracy plus the two
costly failure modes: **false-REVISE** (expected OK, got REVISE — wastes a full ReAct revision
loop) and **missed-REVISE** (expected REVISE, got OK — a wrong answer reaches the user).

```powershell
$env:KORTEX_EVAL_LIVE = "1"
$env:OPENAI_API_KEY = "sk-..."   # or $env:DEEPSEEK_API_KEY = "..."
.\gradlew.bat :core-agent:testDebugUnitTest --tests "dev.kortex.core.eval.PromptEvalTest"
```

### Decision (2026-07-16): default stays `Models.REASONING` — live data pending

Decision rule (flip `ReflectNode`'s default to `Models.FAST` only if BOTH hold on a live run):

1. FAST **matches** REASONING on missed-REVISE — the dangerous failure mode; and
2. FAST is **within 1 case** of REASONING on overall accuracy (10 cases).

No API key was available when T4.4 landed, so no live run has happened yet and the default is
**unchanged** (`Models.REASONING`). Blocker: live data pending — run the command above and
record the score table here, then flip the default (and this section) only if the rule passes.
The RECORDED reflect baseline (10/10) asserts the harness plumbing and parsing and is
model-independent; it stays green in CI either way.
