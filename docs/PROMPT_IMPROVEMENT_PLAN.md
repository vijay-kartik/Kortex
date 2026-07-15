# Kortex Prompt Architecture — Improvement Action Plan

**Status:** Draft for review · **Owner:** Kartik · **Created:** 2026-07-09
**Scope:** All LLM prompts in `core-agent` — main agent system prompt (`Agent.kt`), router (`RouterNode.kt`), reflect (`ReflectNode.kt`), and the ambient pipeline (`AmbientTriage.kt`, `LlmCardGenerator.kt`, `LlmMemoryWriter.kt`).

---

## 1. Assessment (verified against code
All feedback points were confirmed against the current source:

| # | Finding | Evidence | Severity |
|---|---------|----------|----------|
| F1 | Main system prompt is thin: no persona depth, no output-format rules, no multi-turn guidance, no error-handling guidance, no safety policy | `Agent.kt:66-75` — single paragraph | High |
| F2 | System prompt hardcodes tool names (`web_search`, `open_url`) that can drift from the registry | `Agent.kt:71-75`; tools live in `ToolRegistry` (`AgentContext.kt:16`) | Medium |
| F3 | Router classifies the latest user message **in isolation** — follow-ups ("yes do that") misroute | `RouterNode.kt:26` takes only `messages.lastOrNull { USER }` | High |
| F4 | `plan` route is a dead label — no PlanNode; falls through to `react` | `Agent.kt:38` edge comment "tool_task / plan (fallthrough)" | Medium |
| F5 | No `chitchat`/`no_action` route — "thanks"/"ok" trigger full LLM paths | `RouterNode.kt:21` routes list | Medium |
| F6 | Router has no tool-inventory awareness | `RouterNode.kt:28-36` prompt names no tools | Low |
| F7 | Reflect reviews tool **names** but not tool **results** — cannot verify grounding | `ReflectNode.kt:56-58` joins `toolCalls` only; TOOL messages (`ReActNode.kt:52`) are ignored | High |
| F8 | Reflect runs on every `react` turn, on the REASONING model — pure overhead for trivial single-tool answers | `Agent.kt:42` unconditional edge; `ReflectNode.kt:22` `Models.REASONING` | High (cost) |
| F9 | Reflect rubric is vague ("strict reviewer… fully and correctly") → stylistic REVISEs waste a full ReAct loop | `ReflectNode.kt:60-79` | Medium |
| F10 | Reflect doesn't see prior conversation turns (only goal + final answer) | `ReflectNode.kt:39-40,74-78` | Medium |
| F11 | `AmbientTriage` has no few-shot examples for the STORE_MEMORY vs GENERATE_CARD boundary | `AmbientTriage.kt:71-93` | Low |
| F12 | `LlmCardGenerator` asks for card + actions + entities + memories in one giant JSON prompt — high hallucination risk | `LlmCardGenerator.kt:63-96` | Medium |
| F13 | `LlmMemoryWriter` lacks few-shot examples of good vs skip-worthy memories | `LlmMemoryWriter.kt:64-83` | Low |

Two structural root causes underlie most of these:

1. **Prompts are string literals scattered across classes** — no shared building blocks, no way to inject the live tool inventory, no versioning, no way to A/B or regression-test a prompt change.
2. **No evaluation harness** — every prompt change today is verified by eyeball. Gradual improvement is impossible to do safely without a small eval set per prompt.

The plan therefore invests early in a tiny amount of infrastructure (Phase 0) that makes every later prompt change cheap, testable, and parallelizable.

---

## 2. Guiding principles

> **Design direction (decided 2026-07-09):** Kortex is a **tool-execution agent**, not a conversational companion. Priorities: expert multi-*step* tool work within a single run, token efficiency, and straightforward direct answers. Conversational features (chitchat handling, follow-up interpretation like "yes do that", conversation-aware routing/review) are explicitly **out of scope** — the feedback items F3, F5, and F10 are acknowledged but intentionally not addressed.

- **Prompts are code.** Centralize them, generate dynamic sections (tool list, date) from the source of truth, and cover behavior with tests where the behavior is parseable (routing labels, JSON schemas).
- **Measure before and after.** Each phase lands with a small eval set (10–30 cases) so regressions are caught, not felt.
- **Cheap paths stay cheap.** Router/triage improvements must not grow FAST-model prompts unboundedly; reflection changes must *reduce* net token spend.
- **Small, independent PRs.** Every task below is sized for one agent/PR and lists its dependencies explicitly.

---

## 3. Roadmap overview

| Phase | Theme | Outcome | Effort |
|-------|-------|---------|--------|
| 0 | Prompt infrastructure + evals | ✅ **Done (2026-07-09)** — prompts centralized, tool inventory injectable, eval harness exists | ~3 tasks |
| 1 | Quick wins (high value, low risk) | ✅ **Done (2026-07-10)** — enriched system prompt, conditional reflection, router cleanup + tool awareness | ~4 tasks |
| 2 | Grounded reflection & rubrics | ✅ **Done (2026-07-15)** — reflect sees tool results, explicit rubric | ~2 tasks |
| 3 | Ambient pipeline hardening | Few-shots for triage/memory, split card generation | ~3 tasks |
| 4 | Longer-term architecture | Real PlanNode (or drop route), safety policy layer, prompt versioning/telemetry | ~4 tasks |

Phases 1–3 can largely run in parallel once Phase 0 lands. Within each phase, tasks marked ∥ are independent of each other.

---

## Phase 0 — Prompt infrastructure & evaluation (prerequisite)

### T0.1 — Centralize prompts into a `prompts` package ✅ DONE (2026-07-09)
Create `core-agent/src/main/kotlin/dev/kortex/core/prompt/` with one object/file per prompt (e.g. `SystemPrompt.kt`, `RouterPrompt.kt`, `ReflectPrompt.kt`, `TriagePrompt.kt`, `CardPrompts.kt`, `MemoryPrompt.kt`). Each exposes a `build(...)` function taking typed inputs (query, history, tool descriptors, etc.). Move existing literals verbatim — **no behavior change** in this PR.
- *Files:* new `prompt/` package; `Agent.kt`, `RouterNode.kt`, `ReflectNode.kt`, `AmbientTriage.kt`, `LlmCardGenerator.kt`, `LlmMemoryWriter.kt` updated to call builders.
- *Acceptance:* byte-identical prompts (assert in a unit test against the old strings), all existing tests pass.
- *Depends on:* nothing. **Unblocks nearly everything else.**
- *Landed:* `prompt/` package with `SystemPrompt`, `RouterPrompt`, `ReflectPrompt`, `TriagePrompt`, `CardPrompts`, `MemoryPrompt`; `PromptSnapshotTest` (10 tests) pins byte-identical output. `Agent.DEFAULT_SYSTEM` kept as deprecated alias. Also fixed a pre-existing Gradle 9 break (missing JUnit Platform launcher dep in `core-agent/build.gradle.kts`).

### T0.2 — Dynamic tool inventory renderer (fixes F2, enables F1/F6) ✅ DONE (2026-07-09)
Add `ToolRegistry.describeForPrompt()` (or a `prompt/ToolInventory.kt` helper) that renders the registered tools as a short bulleted list (name + one-line description) from the live registry, including MCP-registered tools. The system prompt builder consumes it; hardcoded `web_search`/`open_url` sentences become a generic "prefer opening sources over telling the user to visit links" rule plus per-tool usage hints attached to the tools themselves (extend the `Tool` interface with an optional `promptHint: String?` if needed).
- *Acceptance:* renaming/adding a tool changes the system prompt with zero prompt-file edits; unit test covers rendering.
- *Depends on:* T0.1.
- *Landed:* `prompt/ToolInventory.kt` renders enabled tools (name + truncated description + optional hint); `Tool.promptHint` added with default getter (source-compatible); `web_search`/`open_url` guidance moved from the system prompt onto the web_search tool's hint; `SystemPrompt.grounded(system, now, tools)` overload wired into `Agent.ask()`; `ToolInventoryTest` + updated snapshots.

### T0.3 — Minimal prompt eval harness ✅ DONE (2026-07-09)
A JUnit-based (or standalone `main`) harness in `core-agent/src/test/` that runs a list of `(input, expected)` cases against a real or recorded LLM for the *parseable* prompts: router labels, triage labels, memory/card JSON validity. Start with ~15 router cases (incl. multi-turn follow-ups), ~10 triage cases. Support a `RECORDED` mode (fixture responses) for CI and a `LIVE` mode for local runs.
- *Acceptance:* `./gradlew :core-agent:test` runs recorded evals; a documented command runs live evals; baseline scores are committed to `docs/eval-baselines.md`.
- *Depends on:* T0.1 (∥ with T0.2).
- *Landed:* `eval/` test package (EvalCase, EvalCompleter with RECORDED/LIVE modes, EvalSuite running the real RouterNode/AmbientTriage/LlmMemoryWriter, EvalSets, PromptEvalTest) + 30 recorded fixtures. Baselines: router 11/11 (4 known F3 misroute failures tracked for T1.2), triage 10/10, memory 5/5. Docs in `docs/eval-baselines.md`.

---

## Phase 1 — Quick wins

### T1.1 — Enrich the main system prompt (F1) ✅ DONE (2026-07-10)
Rewrite `DEFAULT_SYSTEM` (in the new `SystemPrompt.kt`) with clearly separated sections:
1. **Identity & tone** — Kortex, an on-device tool-execution agent; direct and matter-of-fact. No filler, no pleasantries, no restating the question. Straightforward answers are respected more.
2. **Output format** — concise by default; lead with the answer, not the process; markdown headers/lists only for genuinely long answers. Short factual results in one line.
3. **Multi-step execution** — you may need several tool calls to finish a task: chain them without stopping to narrate each step; report once, at the end, with the result. Don't re-fetch information already obtained earlier in the run.
4. **Tool use** — keep the existing tool-first rule; add the generic "open sources yourself" rule (from T0.2); insert the dynamic tool inventory.
5. **Error handling** — on tool failure/empty results: retry once with adjusted input if sensible, otherwise say plainly what failed and what you know without it. Never fabricate tool output.
6. **Grounding** — keep the dynamic date block appended by `Agent.ask`.
Keep it under ~40 lines — it rides along on every ReAct iteration, so every line costs tokens on every step.
- *Acceptance:* manual smoke on 5 representative queries; no eval regressions (T0.3).
- *Depends on:* T0.1, T0.2. ∥ with T1.3, T1.4.
- *Landed:* `SystemPrompt.DEFAULT` rewritten with all five sections plus the T4.1 safety/privacy block — 11 lines / ~1,540 chars. Budget enforced by a test that fails above 40 lines / 2,000 chars. `grounded()` overloads and time suffix unchanged. Manual smoke on-device still pending.

### ~~T1.2 — Router gets conversation context (F3)~~ ❌ REMOVED (2026-07-09)
Out of scope per the design direction: Kortex is not a conversational agent, so classifying follow-up phrasings ("yes do that") is not a goal. The 4 `knownFailure` router eval cases added in T0.3 for F3 should be retired in T1.3 rather than fixed.

### T1.3 — Router cleanup: drop dead `plan` label, retire conversational eval cases (F4) ✅ DONE (2026-07-10)
- Remove `plan` from the default routes (graph edge already treats it as fallthrough; re-add it in Phase 4 only if/when a real PlanNode lands). Update the router prompt so `tool_task` explicitly covers multi-step tool work.
- Retire the 4 F3 `knownFailure` follow-up cases from the router eval set (out of scope per design direction); update `docs/eval-baselines.md`.
- *Acceptance:* router evals green with the reduced route set; graph behavior unchanged for `simple_qa`/`tool_task`.
- *Files:* `RouterNode.kt`, `RouterPrompt.kt`, `Agent.kt` (edges), `eval/EvalSets.kt`, `docs/eval-baselines.md`.
- *Depends on:* T0.1, T0.3. ∥ with T1.1, T1.4.
- *Landed:* routes are now `simple_qa, tool_task`; `tool_task` explicitly covers chained multi-step tool work. 4 F3 follow-up cases retired; `plan_*` cases became `multi_step_*` expecting `tool_task`. Router eval: 13/13, zero known failures.

### T1.4 — Conditional reflection (F8) — biggest cost win ✅ DONE (2026-07-10)
Skip `ReflectNode` when the answer is low-risk. Concretely, in the `react→reflect` edge (or a fast path inside ReflectNode), go straight to END when **all** hold:
- ≤1 tool call was made in the run, and it succeeded;
- no revision has happened yet;
- the answer is short (< ~600 chars) and contains no hedging markers ("I couldn't", "not sure").
Make the predicate a small pure function with unit tests. Log a `reflect_skipped` trace so we can measure skip rate and complaint rate.
- *Acceptance:* "what time is it" path makes zero REASONING review calls; multi-tool research queries still reflect; unit tests on the predicate.
- *Files:* `Agent.kt` (edge condition), `ReflectNode.kt` or new `ReflectPolicy.kt`.
- *Depends on:* T0.1. ∥ with T1.1, T1.3.
- *Landed:* `pattern/ReflectPolicy.kt` (pure predicate; tool success detected from ReAct's `react/tool` trace events) + fast path in `ReflectNode.run()` emitting `trace("reflect","verdict","skipped")` with zero LLM calls. 0 successful tool calls also skips (nothing to verify against). 9 unit tests in `ReflectPolicyTest`.

### T1.5 — Router knows the tool inventory (F6) ✅ DONE (2026-07-10)
Add a one-line-per-tool list (names only, or name + 5-word description) to the router prompt via T0.2's renderer, with the instruction: "tool_task only if one of these tools plausibly helps; otherwise simple_qa."
- *Acceptance:* eval case "turn on my smart lights" (no such tool) routes to `simple_qa` (agent then explains it can't); token growth of router prompt < 200 tokens.
- *Depends on:* T0.2, T1.3 (routes list settled).
- *Landed:* `ToolInventory.renderCompact()` (60-char truncation, no hints) inserted via `RouterPrompt.build(routes, query, tools)`; RouterNode passes `ctx.tools.all()`; ~100–120 added tokens. New eval cases incl. unavailable-tool → `simple_qa`; eval suite now uses a production-representative registry.

---

## Phase 2 — Grounded, rubric-based reflection

### T2.1 — Reflect sees tool results, not just names (F7) ✅ DONE (2026-07-15)
Include TOOL-role message contents in the reflect prompt, truncated (e.g. 500 chars per result, newest-first, total cap ~3k chars) to bound cost. Rephrase the task as verification: "Check the answer's claims against these tool results."
- *Acceptance:* fixture test where the answer contradicts a tool result → REVISE; where it matches → OK.
- *Files:* `ReflectNode.kt` / `ReflectPrompt.kt`.
- *Depends on:* T0.1, best after T1.4 (so added tokens only hit non-trivial runs). ∥ with T2.2.
- *Landed:* `ReflectPrompt.build` now takes typed `ToolExchange` pairs (tool call matched to its TOOL-message result by toolCallId; unmatched → "(no result recorded)"). Per-result 500-char word-boundary truncation; 3,000-char total cap dropping oldest first with an "(N older tool call(s) omitted)" note. Helpers unit-tested in `ReflectPromptTest`; `ReflectNodeTest` covers contradicting-answer → REVISE and consistent-answer → OK with canned reviewers.

### T2.2 — Explicit review rubric (F9) ✅ DONE (2026-07-15)
Replace "fully and correctly" with pass/fail criteria: (a) factually consistent with tool results, (b) actually answers what was asked, (c) no critical omission the user explicitly requested. Add: "Do NOT request revision for style, tone, length, or formatting." Require the REVISE reason to cite which criterion failed.
- *Acceptance:* recorded eval: a correct-but-terse answer gets OK; a factually wrong one gets REVISE.
- *Depends on:* T0.1. ∥ with T2.1.
- *Landed:* reviewer reframed as verification against the tool results with explicit criteria (a) factually consistent, (b) answers what was asked, (c) nothing explicitly requested missing; REVISE feedback must name the failed criterion; "do NOT revise for style, tone, length, or formatting — concise answers are preferred". Reply format unchanged (OK / REVISE: …), instruction block grew ~7 lines. Grounding preamble kept.

### ~~T2.3 — Conversation-aware review (F10)~~ ❌ REMOVED (2026-07-09)
Out of scope per the design direction: the reviewer judges a single run's answer against the request and tool results (T2.1/T2.2), not conversational continuity.

---

## Phase 3 — Ambient pipeline hardening

### T3.1 — Few-shot examples for AmbientTriage (F11)
Add 3–4 compact labeled examples pinning the STORE_MEMORY/GENERATE_CARD boundary (e.g. "Flight lands at 6, can you pick me up?" → GENERATE_CARD; "I got the new job!" with no ask → STORE_MEMORY; "👍" → IGNORE).
- *Acceptance:* triage eval set (T0.3) score improves or holds; prompt stays < ~700 tokens before context.
- *Depends on:* T0.1, T0.3. ∥ with T3.2, T3.3.

### T3.2 — Split card generation into two calls (F12)
Split `LlmCardGenerator.generatePrompt` into (a) card + actions, (b) entities + memories. Call (b) with the accepted card as extra context; keep reflection on call (a) only (that's the user-visible artifact). Gate behind a constructor flag (`splitGeneration: Boolean = true`) so it's easy to A/B and revert.
- *Acceptance:* recorded eval comparing hallucinated-entity rate and JSON-parse failure rate single vs split; latency/token delta documented. Ship only if quality improves.
- *Depends on:* T0.1, T0.3. ∥ with T3.1, T3.3.

### T3.3 — Few-shot examples for LlmMemoryWriter (F13)
Add 2 positive examples (good durable memory entries) and 2 negative ("do not store" one-off chit-chat / duplicates of known facts).
- *Depends on:* T0.1. ∥ with T3.1, T3.2.

---

## Phase 4 — Longer-term architecture

### T4.1 — Safety & privacy policy section (F1) ✅ DONE (2026-07-10, landed with T1.1)
Add a concise policy block to the system prompt: decline clearly harmful requests; treat contact/message content as private — never include one contact's private information in messages drafted to another without the user asking; sensitive actions rely on the existing tool-approval flow (`Approver`). Coordinate with `ToolGovernor`/`CardGuardrails` so policy lives in *one* place per concern (prompt = model behavior; governor = enforcement).
- *Depends on:* T1.1.

### T4.2 — Real PlanNode (re-introduce the `plan` route)
Design and implement a PlanNode (decompose → execute steps via ReAct → synthesize), then re-add `plan` to the router with clear criteria ("multiple distinct sub-goals or dependencies between steps"). Directly serves the multi-step-expertise goal; this is its own mini-project — write a short design doc first.
- *Depends on:* T1.3 (route removed until this lands).

### T4.3 — Prompt versioning + telemetry
Tag each prompt builder with a version constant; include it in the `trace(...)` calls / `onLlmUsage` path so logs attribute outcomes (route distribution, reflect skip/REVISE rates, JSON parse failures) to prompt versions. This turns future prompt work into a measurable loop.
- *Depends on:* T0.1.

### T4.4 — Reflect-model right-sizing
With the rubric (T2.2) and grounding (T2.1) in place, evaluate running reflection on `Models.FAST` instead of REASONING. Decide with eval data, not intuition.
- *Depends on:* T2.1, T2.2, T0.3.

---

## 4. Suggested agent assignment (parallel waves)

- **Wave 1 (sequential, 1 agent):** T0.1 → then T0.2 and T0.3 in parallel (2 agents). ✅ Done.
- **Wave 2 (3 agents in parallel):** T1.1+T4.1 · T1.3+T1.5 (one agent — same RouterNode files) · T1.4. ✅ Done.
- **Wave 3 (1 agent):** T2.1+T2.2 (same file). ✅ Done.
- **Wave 4 (3 agents in parallel):** T3.1 · T3.2 · T3.3.
- **Wave 5:** T4.2, T4.3, T4.4 as capacity allows.

Merge-conflict note: RouterNode tasks (T1.3, T1.5) and ReflectNode tasks (T1.4, T2.x) each touch the same files — batch them per agent or serialize.

## 5. Success metrics

- **Cost:** ≥40% reduction in REASONING-model tokens per agent run (driven by T1.4).
- **Routing accuracy:** ≥90% on the router eval set (single-request classification; conversational follow-up cases retired per design direction).
- **Reflection quality:** REVISE-for-style rate ~0 on eval set; factual-error catch rate measured before/after T2.1.
- **Ambient reliability:** JSON parse failure and hallucinated-entity rates tracked before/after T3.2.

## 6. Risks

- **Prompt bloat:** every addition rides on every call. Mitigation: token budgets per prompt (noted in tasks), enforced by a unit test that fails if a built prompt exceeds its cap.
- **Small-model sensitivity:** FAST-model prompts (router, triage, memory) can regress from wording tweaks that look harmless. Mitigation: recorded evals (T0.3) are the merge gate.
- **Reflection skip false-negatives:** T1.4 may skip review on a wrong answer. Mitigation: conservative predicate + `reflect_skipped` telemetry to tune thresholds.
