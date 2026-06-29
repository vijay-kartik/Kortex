# Kortex — An On-Device Agentic AI for Android

> Kotlin + *cortex* (the reasoning core). An Android app that takes **any** query,
> routes it through the **agentic design patterns** from Antonio Gulli's
> *Agentic Design Patterns* (Springer, 2025), and uses **well-governed tools** to
> get things done. Name is a placeholder — rename freely.

---

## 1. Vision

A chat-style Android app where the user types or speaks a goal ("organize my week",
"summarize this PDF and email me the gist", "what's the cheapest way to get to X").
The app is a real **agent**, not a thin chat wrapper. Following the book's five-step
loop — *Get the mission → Scan the scene → Think it through → Take action → Learn and
get better* — Kortex:

1. **Understands & routes** the query to the right strategy (simple Q&A vs. multi-step plan).
2. **Plans** when needed, decomposing into steps.
3. **Uses tools wisely** — function-calling with a typed tool registry, gated by
   guardrails, permissions, and (for risky actions) human approval.
4. **Reflects** on intermediate results and retries/repairs.
5. **Remembers** across turns and sessions, and **learns** from feedback.

The book's key architectural lesson (Appendix C): linear chains (LangChain/LCEL) are
fine for predictable flows, but real agents need **cyclic, stateful graphs**
(LangGraph-style) so they can loop, reflect, retry, and call tools in a flexible order.
**Kortex's heart is a small graph-orchestration engine** built on Kotlin coroutines.

---

## 2. The 21 patterns → concrete components

Every chapter pattern maps to a buildable piece. This is the architecture spine.

| # | Book pattern | Kortex component |
|---|--------------|------------------|
| 1 | Prompt Chaining | Sequential `Node` composition in the graph |
| 2 | Routing | `RouterNode` — classifies the query, picks a sub-graph / strategy |
| 3 | Parallelization | Fan-out/fan-in nodes using `coroutineScope { async {} }` |
| 4 | Reflection | `ReflectNode` — critic→revise loop (a cycle in the graph) |
| 5 | Tool Use | `Tool` interface + `ToolRegistry` + function-calling protocol |
| 6 | Planning | `PlannerNode` — produces a `Plan`; plan-and-execute loop |
| 7 | Multi-Agent Collaboration | Sub-agents as nodes; `SupervisorAgent` |
| 8 | Memory Management | `ShortTermMemory` (state) + `LongTermMemory` (Room/vector) |
| 9 | Learning & Adaptation | Feedback capture + few-shot example store |
| 10 | Model Context Protocol (MCP) | `McpClient` — consume external tools/resources |
| 11 | Goal Setting & Monitoring | `Goal` object + termination/`isSatisfied` checks |
| 12 | Exception Handling & Recovery | Retry/fallback policies, error edges |
| 13 | Human-in-the-Loop | Graph interrupt/resume → Compose approval dialogs |
| 14 | Knowledge Retrieval (RAG) | `Retriever` + vector store over local docs |
| 15 | Inter-Agent Comms (A2A) | Typed message bus between agents |
| 16 | Resource-Aware Optimization | Model picker: on-device (Gemini Nano) vs cloud by battery/network/cost |
| 17 | Reasoning Techniques | Pluggable strategies: ReAct, CoT, Tree-of-Thoughts |
| 18 | Guardrails / Safety | Input/output validators, policy filters, permission gates |
| 19 | Evaluation & Monitoring | Tracing + in-app trace viewer + offline eval harness |
| 20 | Prioritization | Priority task queue for multi-step plans |
| 21 | Exploration & Discovery | Exploration strategy for open-ended goals |

---

## 3. Why Android changes the design

- **UI:** Jetpack Compose + Material 3, chat surface + a **trace/inspector** screen
  (pattern 19) so you can *see* the agent reason.
- **Human-in-the-Loop (13)** becomes Compose approval sheets before risky tool calls
  (send SMS, place call, spend money).
- **Guardrails (18)** ride on Android **runtime permissions** + a policy layer.
- **Resource-Aware Optimization (16)** is real here: route cheap/offline queries to an
  **on-device model** (Gemini Nano / MediaPipe LLM Inference) and escalate hard ones to
  cloud Claude — decided by battery, network, and a token budget.
- **Tools** include device capabilities: contacts, calendar, location, web search,
  notifications, share-sheet — each behind a permission + audit log.
- **Background work:** long agent runs via `WorkManager`; streaming via `Flow`.

---

## 4. Module structure (Gradle multi-module)

Keep the brain pure-Kotlin and testable on the JVM; keep Android at the edges.

```
kortex/
├── core-agent/        Pure Kotlin/JVM. No Android deps. The graph engine, state,
│                      Tool + LlmProvider interfaces, pattern nodes. Unit-tested.
├── llm-claude/        Ktor client for Anthropic Claude (function calling, streaming).
│                      (Folded into core-agent initially; split out later.)
├── memory/            Memory + RAG; pluggable storage SPI (in-mem now, Room later).
└── app/               Android application. Compose UI, Hilt DI, ViewModels,
                       Android-specific tools, secure key storage, on-device model.
```

**Default LLM provider: OpenAI** — `gpt-4o` for hard reasoning, `gpt-4o-mini` for
routing/cheap steps (ties into pattern 16). Provider is an interface (`LlmProvider`),
so Claude/Gemini/on-device can drop in.

---

## 5. Core abstractions (scaffolded in `core-agent/`)

```kotlin
// State flows through the graph and is the single source of truth.
data class AgentState(
    val messages: List<Message>,
    val goal: Goal?,
    val scratch: Map<String, Any?> = emptyMap(),
    val budget: Budget = Budget(),          // tokens / cost / wall-clock — pattern 16
    val trace: List<TraceEvent> = emptyList() // pattern 19
)

// A unit of work. Pattern implementations ARE nodes.
fun interface Node { suspend fun run(ctx: AgentContext, state: AgentState): AgentState }

// Conditional, cyclic wiring — the LangGraph idea.
class AgentGraph(entry, nodes, edges) { suspend fun invoke(state): AgentState }

// Tools: typed, self-describing, governed.
interface Tool {
    val name: String
    val description: String
    val parameters: ToolSchema           // → JSON schema for function calling
    val risk: RiskLevel                  // LOW auto-runs; HIGH needs approval (pattern 13)
    suspend fun execute(args: JsonObject): ToolResult
}

interface LlmProvider {
    suspend fun complete(req: LlmRequest): LlmResponse   // tool-calls supported
    fun stream(req: LlmRequest): Flow<LlmChunk>
}
```

**"Define tools and let them be used wisely"** is delivered by:
- a **`tool { }` DSL** to declare a tool + auto-derive its JSON schema;
- a **`ToolRegistry`** with per-agent allow-lists;
- a **`ToolGovernor`** that enforces: permission present? within budget? risk requires
  approval? args validated? — *before* execution, plus a tamper-evident **audit log**.

---

## 6. Phased roadmap

**Phase 0 — Scaffold** *(this commit)*
Gradle multi-module, version catalog, `core-agent` interfaces, Android `app` skeleton
with a Compose chat screen wired to a stub agent. *(Builds once a JDK + Android SDK are present.)*

**Phase 1 — Single agent that works** *(in progress)*
OpenAI provider (Ktor, Chat Completions) + function calling — done. Next: 1–2 real tools
(calculator, web search) wired into the ReAct loop (pattern 17). End-to-end: type a
query → see tool calls → answer.

**Phase 2 — Graph engine + core patterns**
`AgentGraph` with cycles. Implement Chaining (1), Routing (2), Parallelization (3),
Reflection (4). Trace every node for the inspector screen.

**Phase 3 — Tools, done right**
`tool { }` DSL, schema generation, `ToolGovernor`, permission mapping, Compose approval
sheets (pattern 13), audit log. Add Android tools (contacts/calendar/location).

**Phase 4 — Planning & multi-agent**
`PlannerNode` (6), plan-and-execute loop with Prioritization (20), `SupervisorAgent` (7),
A2A message bus (15).

**Phase 5 — Memory, RAG, learning**
Room-backed memory (8), local-doc RAG with vector store (14), feedback → few-shot (9).

**Phase 6 — Robustness & smarts**
Exception/recovery (12), Goal monitoring (11), Resource-aware on-device routing (16),
Exploration (21), more reasoning strategies (17: CoT, ToT).

**Phase 7 — Safety, eval, extensibility**
Guardrails (18), Evaluation harness + metrics (19), MCP client (10).

**Phase 8 — Polish**
Voice input, share-sheet entry ("process this with Kortex"), settings, onboarding.

---

## 7. Tech stack

Kotlin · Gradle (Kotlin DSL + version catalog) · Coroutines/Flow · kotlinx.serialization ·
Ktor (OkHttp engine) · Jetpack Compose + Material 3 · Hilt · Room · DataStore +
EncryptedSharedPreferences (API keys in Keystore) · WorkManager · JUnit5 + Kotest +
Turbine (Flow tests) · optional MediaPipe LLM Inference / Gemini Nano for on-device.

---

## 8. Open decisions (defaults chosen, change anytime)

- **Project name:** "Kortex" (placeholder).
- **Primary model:** OpenAI (`gpt-4o` reasoning / `gpt-4o-mini` cheap) — user has an
  OpenAI key. On-device model is Phase 6, not blocking.
- **min SDK 26 / target latest.** Compose-only UI.
- **Where keys live:** Android Keystore via EncryptedSharedPreferences; never in code.
```
