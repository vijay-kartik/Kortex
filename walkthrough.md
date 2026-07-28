# Knowledge Graph Integration Walkthrough

## Goal
The goal was to fully integrate the `graph-core` (pure domain abstractions) and `graph-storage` (ObjectBox persistence layer) into the agent's workflow by building the repository layer, defining concrete business entities, and creating tools to query and write to the graph. Additionally, real embedding support was added via the Ollama Cloud API.

## Changes Made

### 1. Repository Layer (`GraphRepository`)
Created a bridge between the domain and storage layers. The repository leverages ObjectBox's high-performance native APIs to handle:
- **Node Registration:** Minting new `GraphId`s and linking them to internal `graphKey`s via `GraphRegistryEntity`.
- **Edge Traversal:** Navigating the graph based on `Direction` (`INCOMING`, `OUTGOING`, `BOTH`) and `EdgeType`.
- **Vector Search:** Integrated querying using ObjectBox's native HNSW index on the `EmbeddingEntity`.

### 2. Business Entities
Created the first vertical slice of typed storage entities to persist actual graph data:
- `PersonEntity`: Stores people profiles.
- `TopicEntity`: Stores abstract concepts/topics.
- `AssertionEntity`: Reifies extracted facts carrying validity intervals, predicates, and confidences.
Custom getters were used for interface overrides (`graphId`, `nodeType`) so that ObjectBox's reflection mechanism properly populates the fields before they are resolved.

### 3. Orchestration API (`GraphBuilder`)
Created a high-level API for the agent tools to interact with. `GraphBuilder`:
- Coordinates creating the domain structures and the corresponding storage box records in a clean transaction.
- Explicitly delegates schema validation to `GraphSchema.requireValid()` before attempting to connect any edges, blocking illegal ontology states before they reach the database.

### 4. Real Vector Embeddings (`EmbeddingProvider`)
Added full support for real embeddings to replace the previous fake-embedding stub:
- **`EmbeddingProvider`**: A new pure interface in `core-agent`.
- **`OpenAiEmbeddingProvider`**: Implements the standard `/embeddings` endpoint format, compatible with OpenAI, Ollama, and Ollama Cloud.
- **`DynamicEmbeddingProvider`**: Added to `KortexContainer` alongside the LLM provider. It dynamically routes embedding requests to the currently active model (e.g. `all-minilm` for 384 dimensions matching our graph).
- **`StubEmbeddingProvider`**: A local deterministic fallback if no real API key is configured.

### 5. Agent Tools (`MemoryTool`, `KnowledgeExtractionTool`)
Integrated the graph into the main `app` module via two agentic tools, now wired with real `EmbeddingProvider`s:
- **`MemoryTool`**: Accepts natural language queries, embeds them, handles vector search via the ObjectBox HNSW index, and fetches corresponding memory nodes and neighbors.
- **`KnowledgeExtractionTool`**: Exposes the `GraphBuilder` pipeline to the LLM, letting the agent store derived facts (e.g., `PERSON - WORKS_AT - TOPIC`) seamlessly while generating true high-dimensional embeddings for the nodes and assertions.

### 6. Interactive Graph UI (`GraphScreen`)
Added a dedicated "Graph" tab to the main application to visualize the agent's memory in real-time. The UI features:
- **Custom Compose Canvas:** Renders nodes and edges natively without relying on heavy web-views.
- **Force-Directed Physics Engine:** Runs a continuous physics loop (`LaunchedEffect`) applying repulsion between all nodes, spring attraction along edges, and center gravity, resulting in a smooth, self-organizing organic layout.
- **Node Coloring & Labelling:** Nodes are colored by ontology `NodeCategory` (Identity, Event, Knowledge) using the app's design system tokens (Synapse, Amber, Green). Labels are dynamically resolved by querying the respective business entity boxes (e.g. `PersonEntity`, `TopicEntity`).
- **Interactive Dragging:** Users can drag nodes around to explore clusters; the physics engine gracefully stabilizes the rest of the graph around the interaction.

### 7. Config Alignment
Aligned `minSdkVersion` in the `graph-storage` module to `26` to fix manifest merger failures during `app` compilation.

## Validation Results
- **Compilation:** Successfully compiled all modules (`core-agent`, `graph-storage`, `app`) using `./gradlew :app:compileDebugKotlin`.
- **Type Safety:** Correctly leveraged ObjectBox's generated `QueryBuilder` properties (e.g. `GraphRegistryEntity_.graphId` and `StringOrder.CASE_SENSITIVE`).
- **Architecture Integrity:** Kept `graph-core` entirely pristine as a pure JVM module. Tool implementations reside in `app/` to prevent polluting the generic core architecture with app-specific ObjectBox dependencies.
