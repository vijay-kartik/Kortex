# Kortex

An on-device **agentic AI for Android**, inspired by Antonio Gulli's
*Agentic Design Patterns* (Springer, 2025). Takes any query, routes it through agentic
design patterns, and uses well-governed tools to act. See **[PLAN.md](PLAN.md)** for the
full architecture, the 21-pattern → component map, and the phased roadmap.

## Layout
- `core-agent/` — pure Kotlin/JVM brain: graph engine, state, Tool + LlmProvider
  interfaces, pattern nodes (Router, ReAct), the `tool { }` DSL and `ToolGovernor`.
  Unit-tested with no Android or network.
- `app/` — Android (Jetpack Compose) chat UI + trace inspector, ViewModel, the
  Human-in-the-Loop approval dialog, and a stub LLM provider for Phase 0.

## Status: Phase 1 (real LLM)
The graph runs end-to-end through the **OpenAI** provider (`OpenAiProvider`, Ktor + Chat
Completions function calling). Default models: `gpt-4o` (reasoning) / `gpt-4o-mini`
(routing). The provider is behind the `LlmProvider` interface, so Claude/Gemini/on-device
can be swapped in. Without a key, the app falls back to a stub so it still launches.

## Building
1. Add your key to `local.properties` (gitignored):
   ```
   OPENAI_API_KEY=sk-...
   ```
2. Open in Android Studio (Ladybug+), let it sync, run the `app` config on a device/emulator.
3. The Gradle wrapper is not committed yet — generate it once with a local Gradle:
   `gradle wrapper --gradle-version 8.11` (needs a JDK installed).

## Tests
`./gradlew :core-agent:test` — exercises Router → ReAct → tool → governor with a
scripted provider (no network).
