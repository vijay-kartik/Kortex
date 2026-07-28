# PlanNode Design (T4.2)

**Status:** Approved for implementation · **Created:** 2026-07-15
**Task:** T4.2 in `PROMPT_IMPROVEMENT_PLAN.md` — re-introduce the `plan` route with a real planning pattern (pattern 6: Planning), serving the multi-step-expertise goal.

## 1. Problem

Today every tool-needing request runs through a single ReAct loop (`react`, max 8 iterations). That works for one-goal chains ("what's 5% of Tesla's stock price" → search → calculate), but degrades on requests with **multiple distinct sub-goals**: the model interleaves sub-goals in one message context, loses track of what's done, re-fetches, and produces unbalanced answers. The `plan` router label existed for this but was dropped in T1.3 because no PlanNode backed it.

## 2. Graph shape

```
router ──simple_qa──▶ direct ─────────────────────────────▶ END
router ──tool_task──▶ react ──▶ reflect ──ok──▶ END   (unchanged)
router ──plan───────▶ plan ──▶ execute ─┐
                        │        ▲      │ (steps remain)
                        │        └──────┘
                        │(plan failed → fallthrough)
                        │               │ (all steps done)
                        └──▶ react      ▼
                                    synthesize ──▶ reflect ──ok──▶ END
                                        ▲                │
                                        └────revise──────┘
```

- `plan` decomposes; `execute` self-loops one step per pass; `synthesize` composes the final answer; the existing `reflect` reviews it (rubric + tool grounding from Phase 2 apply as-is).
- **Reflect's revise edge for the plan path targets `synthesize`, not `execute`** — re-answering from already-collected evidence is cheap; re-running tools is not. Reflect's existing feedback-as-user-message mechanism works unchanged. (Edge selection: `reflect → synthesize` when a plan exists in state and verdict is revise, else the existing `reflect → react` revise edge.)
- **Degradation:** if planning fails (unparseable output) or produces < 2 steps, PlanNode marks the plan unusable and an edge falls through to plain `react`. A broken planner must never make the agent worse than today.

## 3. State

Typed, not scratch-JSON. Add to `AgentState` (all optional/defaulted — serialization-compatible with existing checkpoints):

```kotlin
@Serializable
data class PlanStep(
    val description: String,
    val status: Status = Status.PENDING,   // PENDING, DONE, FAILED
    val result: String = "",               // step outcome summary fed to later steps + synthesis
) { enum class Status { PENDING, DONE, FAILED } }

@Serializable
data class Plan(val steps: List<PlanStep>) {
    val nextPending: Int?  // index of first PENDING step, null when none
}

// AgentState gains: val plan: Plan? = null
```

Rationale: scratch is `Map<String,String>` and fine for labels, but a plan is structured, mutated across nodes, and worth tracing/checkpointing first-class. `Plan` is immutable like the rest of the state; nodes copy-on-write.

## 4. Nodes

### PlanNode (`pattern/PlanNode.kt`)
- Model: `Models.REASONING`, temperature 0.
- Prompt (`prompt/PlanPrompt.kt`): decompose the request into **2–5 sequential steps**, each a concrete, independently executable instruction; steps may depend on earlier results; include the compact tool inventory (`ToolInventory.renderCompact`) so steps are grounded in what tools exist; forbid steps requiring unavailable capabilities. Output: JSON array of strings, nothing else (reuse the fence-stripping/first-bracket parse idiom from `LlmMemoryWriter`).
- On success: `state.copy(plan = Plan(steps))`, trace `plan/created/<n> steps`, progress "Planning…".
- On failure (parse error, < 2 steps): no plan set, trace `plan/degraded/<reason>` — the graph falls through to `react`.

### ExecuteStepNode (`pattern/ExecuteStepNode.kt`)
- Runs ONE pending step per graph pass (the graph's self-loop drives iteration; keeps each pass small, budget-checked by the runner between passes).
- Builds a **scoped, ephemeral message list** — NOT `state.messages`: the run's SYSTEM message; one USER message containing the overall goal, results of completed steps ("Step 1 — done: <result>"), and the current step instruction. This is the point of planning: each step gets a small focused context instead of the ever-growing transcript.
- Inner tool loop: same Think→Act→Observe shape as ReActNode but capped at `maxIterationsPerStep = 4`; tool calls go through `ctx.governor` exactly like ReActNode (approval flow intact). Tool budget is shared via `state.budget` updates.
- Step outcome: final assistant content becomes `PlanStep.result` (truncated ~800 chars, word boundary); empty/failed → status FAILED with the failure reason as result. **A FAILED step does not abort the plan** — later steps and synthesis see the failure and work around it.
- Tool-result TOOL/ASSISTANT messages from the scoped loop are **not** appended to `state.messages` (context bloat); only the step results live on, inside the plan. Trace each step: `execute/step/<i>:<status>`.

### SynthesizeNode (`pattern/SynthesizeNode.kt`)
- Model: `Models.REASONING` (answer quality; T4.4 may revisit).
- Prompt (`prompt/SynthesizePrompt.kt`): the goal + every step (description, status, result) → compose the single final answer; answer-first, concise, note explicitly anything a FAILED step leaves unknown; never invent data missing from step results.
- Appends the answer as an ASSISTANT message to `state.messages` (so reflect and the app see it exactly like a react answer), sets `done = true` semantics same as react's final answer.
- On revise loops, the reviewer feedback USER message is already in `state.messages`; SynthesizeNode includes the previous answer + feedback in its prompt and re-answers from the same step results.

### Reflect integration
- No ReflectNode changes. `ReflectPrompt.pair(state.messages)` finds no tool exchanges for plan runs (scoped loops don't write TOOL messages to state) — instead, ExecuteStepNode records per-step tool exchanges into the plan step results, which reach the reviewer through the answer's provenance. To keep the reviewer grounded, SynthesizeNode also stores a compact "evidence" digest (step results) in `scratch["plan_evidence"]`, and ReflectNode's prompt already receives the request+answer; **v1 accepts vibes-level review for plan runs** — grounding the plan-path reviewer in step evidence is a noted follow-up, not in this task.
- `ReflectPolicy`: plan runs always reflect (they made >1 tool call → predicate already says reflect; no change needed).

## 5. Router changes

- Routes: `simple_qa, tool_task, plan` (restore). `plan` criteria in `RouterPrompt`: *multiple distinct sub-goals, or steps that depend on earlier results across different topics* (e.g. "compare X and Y on price, reviews, and availability, then recommend one"); single-goal chained tool use stays `tool_task`.
- Edges in `Agent.kt`:
  - `router → plan` on `scratch["route"] == "plan"`; `router → react` fallthrough unchanged.
  - `plan → execute` when `state.plan != null`; `plan → react` fallthrough (degradation).
  - `execute → execute` while `plan.nextPending != null`; `execute → synthesize` fallthrough.
  - `synthesize → reflect`.
  - `reflect → synthesize` when verdict revise AND `state.plan != null`; keep existing `reflect → react` revise edge after it; `reflect → END` fallthrough.
  (Edge order matters: the graph takes the first matching edge.)

## 6. Budgets

- Plan-path additions ride the existing `Budget` (tokens/tool-calls/steps); `maxSteps=40` accommodates 5 plan steps × (1 pass + inner iterations counted as LLM calls, not graph steps). Per-step inner loop cap 4; plan cap 5 steps. No new budget fields.

## 7. Evals & tests

- Router eval: re-add `plan` cases — `plan_compare_recommend` ("compare A vs B on three criteria and recommend"), `plan_multi_topic` (two unrelated sub-goals in one request); assert `multi_step_trip`-style single-goal chains still classify `tool_task` (boundary cases). Fixtures re-recorded.
- Unit tests: PlanPrompt/SynthesizePrompt snapshots; plan JSON parsing (clean/fenced/garbage → degrade); `Plan.nextPending`; ExecuteStepNode with canned LLM+tools (step success, step failure continues, budget respected, scoped messages don't leak into state.messages); SynthesizeNode (includes FAILED step gaps; revise pass includes feedback); graph-level test: plan route end-to-end with canned completions; degradation path (garbage plan → react still answers).
- `docs/eval-baselines.md` updated.

## 8. Out of scope (follow-ups)

- Grounding the reflect reviewer in plan-step evidence (noted above).
- Parallel step execution (steps are sequential v1; the graph loop makes parallelism a later, isolated change).
- Re-planning mid-run (plan is fixed after PlanNode; failures are handled by synthesis, not re-decomposition).
- T4.4 model right-sizing for synthesize/plan calls.
