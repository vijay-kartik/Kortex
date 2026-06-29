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

## Status: Phase 0 (scaffold)
The graph runs end-to-end against a stub provider. **Phase 1** adds the real Claude
client (Ktor, function calling). The build targets Android Studio / a JDK 17 + Android
SDK environment.

## Building
Open in Android Studio (Ladybug+), let it sync, run the `app` config on a device/emulator.
The Gradle wrapper is not committed yet — generate it once with a local Gradle:
`gradle wrapper --gradle-version 8.11` (needs a JDK installed).

## Tests
`./gradlew :core-agent:test` — exercises Router → ReAct → tool → governor with a
scripted provider (no network).
