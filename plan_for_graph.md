# Graph Integration Plan

## Goal Description
The `graph-core` and `graph-storage` modules establish a solid, type-safe foundation for Kortex's knowledge graph using ObjectBox. Currently, the domain models (Ontology, Nodes, Edges) and storage tables (Registry, Edges, Embeddings) are defined. The goal of the next steps is to bring this graph to life by building the repository layer, defining the first set of business entities, and integrating the graph into the agent's reasoning loop (Memory/RAG).

## User Review Required
> [!IMPORTANT]
> **ObjectBox usage vs Room:** `PLAN.md` initially mentioned Room-backed memory, but the implementation correctly pivots to ObjectBox for its native HNSW vector index and zero-copy performance. We should update `PLAN.md` to reflect this architectural decision.
> **Embedding Dimensions:** The plan assumes `384` dimensions as configured in `GraphStorageConfig.kt`. If the target on-device model will output `768`, this must be updated before any data is written.

## Open Questions
> [!WARNING]
> - Do you want to implement the vector embedding generation locally (using MediaPipe/Gemini Nano) or via the cloud LLM provider (OpenAI/Deepseek) for this phase?
> - What should be the very first business entities we model to test this out? (e.g., `PersonEntity` and `TopicEntity`?)

## Proposed Changes

---

### Phase 1: Repository Layer (The Engine)
We need a `GraphRepository` that coordinates between the pure domain `graph-core` and the `graph-storage` entities.

#### [NEW] `graph-storage/src/main/java/dev/kortex/graph_storage/GraphRepository.kt`
Define and implement the main repository interface.
- **Node Management**: Functions to mint new `GraphId`s, resolve `GraphId` to `graphKey` (via `GraphRegistryEntity`), and the cascade delete logic described in `GraphRegistryEntity`.
- **Traversal**: Functions to traverse the graph (e.g., `fun getNeighbors(node: GraphReference, type: EdgeType, dir: Direction): List<Relationship>`).
- **Vector Search**: Functions to query the HNSW index on `EmbeddingEntity` to find similar nodes.

---

### Phase 2: Business Entities (The Data)
Create the actual storage boxes for the `EntityKind`s we want to support first.

#### [NEW] `graph-storage/src/main/java/dev/kortex/graph_storage/business/...`
We should start with a small vertical slice, for example:
- `PersonEntity`: Representing a `PERSON`.
- `AssertionEntity`: Representing an `ASSERTION` (extracted facts).
- `TopicEntity`: Representing a `TOPIC`.
These entities will implement `GraphNode` from `graph-core` and carry domain-specific properties (e.g., `name`, `content`, `confidence`).

---

### Phase 3: Graph Building API
A high-level service to orchestrate inserting complex structures, ensuring `GraphSchema` validity.

#### [NEW] `graph-core/src/main/java/dev/kortex/graph_core/GraphBuilder.kt`
- Enforces ontology rules before insert (e.g., a `PERSON` cannot be connected to a `PHONE_NUMBER` via a `RELATED_TO` edge; it must be `HAS_PHONE`).
- Coordinates creating the business entity, minting the registry entry, and writing the embedding.

---

### Phase 4: Agent Integration & Tools
Expose the graph to the agentic loop.

#### [MODIFY] `core-agent/...`
- **MemoryTool**: A new tool the agent can call to search its memory (triggering HNSW vector search + 1-hop neighbor retrieval).
- **KnowledgeExtractionNode**: A new node or tool that takes raw conversation text, extracts facts (Assertions, Persons, Topics) and inserts them into the graph.

## Verification Plan

### Automated Tests
- Build comprehensive unit/integration tests for `GraphRepository` utilizing ObjectBox's in-memory test database.
- Verify cascade deletion works correctly (deleting a business node drops its registry entry, edges, and embeddings).
- Verify vector search returns expected similarities.

### Manual Verification
- The user will interact with the agent via the app, feeding it facts (e.g., "I work at Google"), and later asking questions ("Where do I work?") to verify the memory retrieval loop works correctly through the new tool.
