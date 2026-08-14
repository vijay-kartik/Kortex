# Graph Traversal & Fact Extraction Plan

## Goal Description

Today the graph can only be read one hop at a time, one edge type at a time, and can only be
entered through fuzzy vector search. This plan adds a traversal layer that answers the question
*"given a person or a topic, give me every fact connected to it"* — including facts that are two
or more hops away — and rewires `memory_search` to use it.

Nothing in the ObjectBox schema changes. No migration, no re-embedding. `EdgeEntity` already
indexes `sourceKey`, `targetKey` and `relationshipId`, and `GraphRegistryEntity` already mints
graph-wide unique keys; the storage model is correct for this. What is missing is a query layer
on top of it.

**Concrete case this must solve:** `Kartik —TRAVELING_TO→ Crowne Plaza —HAS_PHONE→ 011...`
Asking about Kartik today never reaches the hotel's phone number, because
`getConnectedAssertions` stops at facts where the seed node is *directly* the subject or object.

---

## Decisions already made

| Decision | Choice |
|---|---|
| Agent surface | **Extend `memory_search` in place.** No new tool. One entry point for the LLM. |
| Scope | **Storage layer + agent tool.** `GraphScreen` is out of scope. |
| Verification | **Manual only.** No test suite in this handoff — see [Risks](#risks). |

---

## Current state (what exists, what's missing)

### Building blocks that work

| Piece | Location |
|---|---|
| 1-hop neighbors, one edge type, one direction | `GraphRepository.getNeighbors` — `GraphRepository.kt:89` |
| 1-hop expansion into assertions | `GraphRepository.getConnectedAssertions` — `GraphRepository.kt:143` |
| Rendering a node/fact as prose | `GraphBuilder.getNodeSummary` — `GraphBuilder.kt:29` |
| Private label helper | `GraphBuilder.nodeLabel` — `GraphBuilder.kt:99` |
| Fuzzy entry via HNSW | `GraphRepository.searchSimilar` — `GraphRepository.kt:176` |
| Current end-to-end path | `MemoryTool.kt:43-67` |

### Gaps this plan closes

1. **No name → node lookup.** `getGraphKey` needs a UUID you don't have. `findEntityType`
   (`GraphBuilder.kt:129`) finds the entity then throws it away, returning only the type.
   `getOrCreatePerson` **creates on miss** — unusable on a read path.
2. **`getNeighbors` can't fetch all connections.** It requires one explicit `EdgeType`; getting
   every edge means 20 types × 2 directions = 40 queries. `sourceKey == x` alone is an indexed
   query, but the code always ANDs `relationshipId`, so that form is never used.
3. **Edge type is discarded.** `getNeighbors` returns `List<NodeHandle>` — you learn *who* is
   connected but not *how*. `Relationship` (`graph-core/Relationship.kt`) exists for exactly this
   and is referenced nowhere. Same for `GraphConstants.MAX_TRAVERSAL_DEPTH` and
   `DEFAULT_NEIGHBOR_LIMIT` — declared, never used.
4. **No multi-hop traversal.** No BFS, no visited set, no depth cap, no cycle guard.
5. **N+1 registry reads.** `getNeighbors` calls `registryBox.get()` once per edge
   (`GraphRepository.kt:101`, `:120`).

---

## Proposed Changes

### Phase 1 — Domain result types (`graph-core`)

Pure Kotlin, no ObjectBox. These are what crosses the API boundary.

#### [NEW] `graph-core/src/main/java/dev/kortex/graph_core/GraphFact.kt`

```kotlin
/** One endpoint of a fact, with a display label already resolved. */
data class FactEndpoint(
    val reference: GraphReference,
    val label: String,
)

/**
 * A reified fact, flattened for consumption. This is what traversal returns instead of
 * the pre-formatted strings getNodeSummary produces — callers need the parts, not prose.
 */
data class GraphFact(
    val assertion: GraphReference,
    val subject: FactEndpoint?,
    val obj: FactEndpoint?,
    /** Display form: the extractor's phrase for OTHER, the enum name otherwise. */
    val predicate: String,
    val canonicalPredicate: AssertionPredicate,
    val confidence: Float,
    /** Epoch millis, 0 = unbounded. */
    val validFrom: Long,
    val validTo: Long,
    /** Distance in hops from the traversal seed. 1 = directly about the seed. */
    val hops: Int,
)
```

`subject` and `obj` are nullable: date-valued predicates (`BIRTHDAY_ON`) have no object node
by design (see `AssertionPredicate.objectIsDate`), and a malformed assertion may be missing an
endpoint.

#### [NEW] `graph-core/src/main/java/dev/kortex/graph_core/TraversalResult.kt`

```kotlin
data class TraversalNode(
    val reference: GraphReference,
    val label: String,
    val hops: Int,
)

data class TraversalResult(
    val seed: GraphReference,
    /** Entity nodes reached, excluding ASSERTION nodes. Ordered by hops ASC. */
    val nodes: List<TraversalNode>,
    /** Every edge walked, with its type preserved. */
    val relationships: List<Relationship>,
    /** Facts collected, ordered per the rule in Phase 3. */
    val facts: List<GraphFact>,
    /** True when a budget or fan-out cap stopped the walk early. */
    val truncated: Boolean,
)

data class TraversalOptions(
    /** 1 = facts directly about the seed. 2 = also facts about those facts' endpoints. */
    val maxDepth: Int = 2,
    /** Hard ceiling on distinct nodes visited. Guards against hub explosion. */
    val maxNodes: Int = 150,
    /** Max neighbors expanded from any single node before truncating. */
    val maxFanOut: Int = 50,
    /** Null = follow every edge type. */
    val edgeTypes: Set<EdgeType>? = null,
    val minConfidence: Float = 0f,
) {
    init {
        require(maxDepth in 1..GraphConstants.MAX_TRAVERSAL_DEPTH) {
            "maxDepth must be 1..${GraphConstants.MAX_TRAVERSAL_DEPTH}, got $maxDepth"
        }
    }
}
```

This is where `GraphConstants.MAX_TRAVERSAL_DEPTH` finally gets used. Clamp rather than throw at
the tool boundary — the LLM will pass silly values.

---

### Phase 2 — Repository primitives (`graph-storage`)

#### [MODIFY] `graph-storage/src/main/java/dev/kortex/graph_storage/GraphRepository.kt`

Add, do not remove anything:

```kotlin
/**
 * Every edge touching [nodeKey], type preserved. One indexed query per direction —
 * the relationshipId filter is deliberately absent, which is what makes "all
 * connections" a 1-2 query operation instead of 40.
 */
fun getEdges(nodeKey: Long, direction: Direction): List<EdgeEntity>

/** Batched registry hydration. One box.get(Iterable) instead of N gets. */
fun getRegistryEntities(graphKeys: Collection<Long>): Map<Long, GraphRegistryEntity>

/** Batched graphKey -> GraphReference. Skips rows with unknown nodeTypeId (skip-and-log). */
fun getReferences(graphKeys: Collection<Long>): Map<Long, GraphReference>

/**
 * Edges touching [nodeKey] as domain Relationships, endpoints hydrated in one
 * batched registry read. Puts the unused Relationship type to work.
 */
fun getRelationships(nodeKey: Long, direction: Direction): List<Relationship>

/** Reverse lookup: business row -> registry row. businessEntityId is already @Index'd. */
fun findRegistryByBusinessEntity(
    entityKind: EntityKind,
    businessEntityId: Long,
): GraphRegistryEntity?

/**
 * SUBJECT/OBJECT edges for many assertions in ONE query. The batching primitive
 * that keeps fact hydration off the N+1 path.
 */
fun getAssertionEndpoints(assertionKeys: Collection<Long>): List<EdgeEntity>
```

Implementation notes:

- `getEdges` — `edgeBox.query().equal(EdgeEntity_.sourceKey, nodeKey).build().find()` for
  OUTGOING, `targetKey` for INCOMING, both for BOTH. No `relationshipId` term.
- `getRegistryEntities` — `registryBox.get(graphKeys)` (ObjectBox 4.0.3 supports
  `get(Iterable<Long>)`). It returns nulls for missing ids; filter them.
- `getAssertionEndpoints` — `edgeBox.query().oneOf(EdgeEntity_.sourceKey, keys.toLongArray())
  .and().oneOf(EdgeEntity_.relationshipId, longArrayOf(SUBJECT.id, OBJECT.id))`. Note
  `relationshipId` is an `Int` property but ObjectBox's `oneOf` for integer properties takes
  `int[]` — check the generated `EdgeEntity_` signature and match it.
- **Reimplement `getConnectedAssertions` on top of `getEdges`** — it currently runs two
  `getNeighbors` calls (four queries with the N+1 registry reads); it should be one `getEdges`
  call plus one batched hydration. Keep the method and its signature: it is a legitimate depth-1
  shortcut and the doc comment on it is good.

#### [MODIFY] `graph-storage/src/main/java/dev/kortex/graph_storage/GraphBuilder.kt`

`GraphBuilder` owns the business boxes, so name lookup and labelling belong here.

```kotlin
/**
 * Resolves a name to an existing PERSON or TOPIC without creating anything.
 * The read-path counterpart to getOrCreateEntity. PERSON is checked first, for the
 * same reason findEntityType checks it first.
 */
fun findEntity(name: String): GraphReference?

/** Promoted from the existing private `nodeLabel`. Traversal needs labels. */
fun labelOf(graphKey: Long): String?

/** Batched labels — one registry read plus one read per business box. */
fun labelsOf(graphKeys: Collection<Long>): Map<Long, String>
```

- `findEntity` reuses the existing indexed queries from `findEntityType`
  (`PersonEntity_.name` / `TopicEntity_.label`, `StringOrder.CASE_INSENSITIVE`) but returns a
  `GraphReference` built from the found entity's `graphIdStr`.
- `labelOf` is the existing private `nodeLabel` (`GraphBuilder.kt:99`) made public and renamed.
  Update its one internal caller in `getNodeSummary`.
- `labelsOf` is what traversal actually calls; `labelOf` stays for single lookups.

---

### Phase 3 — The traversal engine

#### [NEW] `graph-storage/src/main/java/dev/kortex/graph_storage/GraphTraversal.kt`

```kotlin
class GraphTraversal(
    private val repository: GraphRepository,
    private val builder: GraphBuilder,
    private val boxStore: BoxStore,
) {
    fun traverse(seedKey: Long, options: TraversalOptions = TraversalOptions()): TraversalResult
    fun collectFacts(seedKey: Long, options: TraversalOptions = TraversalOptions()): List<GraphFact>
}
```

`collectFacts` is `traverse(...).facts` — provided as the ergonomic entry point, since it is what
the tool actually wants.

#### The algorithm — read this section carefully

This is the part that is easy to get subtly wrong, and (per the verification decision) there are
no tests guarding it.

**The central rule: never BFS blindly through ASSERTION nodes.** An assertion is a hub. A topic
like `Crowne Plaza Okhla, Delhi` can be the object of assertions belonging to twenty unrelated
people. A naive walk of *node → assertion → other endpoint → onward* pulls in half the graph by
depth 3. So the frontier holds **entity nodes only**, and assertions are *collected* at each
level rather than *traversed as frontier members*.

```
visited: MutableSet<Long>          // entity keys already expanded
facts:   MutableMap<Long, GraphFact>   // keyed on assertion graphKey — dedup
frontier: List<Long> = [seedKey]
truncated = false

for depth in 1..options.maxDepth:
    if frontier is empty: break

    # ---- one batched edge read for the whole frontier level ----
    edges = repository.getEdges(frontier, Direction.BOTH)     # see note below
    apply options.edgeTypes filter if non-null

    # ---- collect assertions at this level ----
    # Edges run ASSERTION -> endpoint, so from an entity we follow
    # SUBJECT/OBJECT INCOMING to reach the assertions about it.
    assertionKeys = edges
        .filter { it.relationshipId in [SUBJECT, OBJECT] && it.targetKey in frontier }
        .map { it.sourceKey }
        .distinct()

    hydrateFacts(assertionKeys, hops = depth)   # -> facts, see hydration below

    # ---- build the next frontier ----
    next = []

    # (a) the OTHER endpoints of the assertions just collected
    next += endpoints(assertionKeys) - visited

    # (b) direct entity->entity structural edges (RELATED_TO, HAS_PHONE,
    #     WORKS_AT, LIVES_AT, PART_OF, ...) — anything not SUBJECT/OBJECT/DERIVED_FROM
    next += structuralNeighbours(edges) - visited

    # ---- budgets ----
    if next.size > options.maxFanOut:
        next = next.take(options.maxFanOut); truncated = true
    if visited.size + next.size > options.maxNodes:
        next = next.take(options.maxNodes - visited.size); truncated = true

    visited += next
    frontier = next
```

Notes on the sketch:

- **`getEdges` for a whole frontier.** Either loop `getEdges` per node, or add a
  `getEdges(nodeKeys: Collection<Long>, direction)` overload using `oneOf`. Prefer the overload —
  one query per level instead of one per node.
- **Cycle safety** comes from `visited`; a node is never expanded twice. `facts` is keyed on
  assertion graphKey so the same fact reached by two paths appears once, at its **shortest** hop
  distance (only write to the map if absent — do not overwrite with a longer distance).
- **The seed itself** goes into `visited` before the loop starts, so a fact pointing back at it
  does not re-expand it.
- **`DERIVED_FROM`** edges point at source events. There are no event entities in storage today
  (only PERSON/TOPIC/ASSERTION), so they will be inert — but exclude them from frontier expansion
  explicitly rather than by accident, so this stays correct when event nodes land.
- **Default `maxDepth = 2`** is the meaningful default: depth 1 = facts directly about the seed,
  depth 2 = facts about the entities named in those facts. That is the Crowne Plaza case. Depth 3+
  should be opt-in.

#### Fact hydration (the N+1 killer)

For a set of assertion graphKeys, resolve everything in **four batched reads**, not four per
assertion:

1. `repository.getRegistryEntities(assertionKeys)` → `businessEntityId` for each.
2. `assertionBox.get(businessEntityIds)` → predicate, confidence, validFrom, validTo.
3. `repository.getAssertionEndpoints(assertionKeys)` → all SUBJECT/OBJECT edges in one query.
4. `builder.labelsOf(endpointKeys)` → labels for every endpoint at once.

Then assemble `GraphFact`s in memory. Use `AssertionEntity.displayPredicate` for the `predicate`
field — it already handles the OTHER-with-raw-phrase case correctly (`AssertionEntity.kt:60`).

Filter out facts below `options.minConfidence` *after* hydration (confidence lives on the
business row, not the edge).

#### Fact ordering

Deterministic, in this order:

1. `hops` ASC — closest facts first, so a truncated render keeps the most relevant.
2. `confidence` DESC.
3. `validFrom` DESC, treating `0` as lowest — recent facts before undated ones.
4. `assertion.graphId` ASC as a final tiebreak, so output is stable across runs.

---

### Phase 4 — Agent integration

#### [MODIFY] `app/src/main/kotlin/dev/kortex/app/tools/MemoryTool.kt`

Extend in place. The tool keeps its name (`memory_search`) and its existing behaviour as the
fallback path.

**Constructor** gains `private val traversal: GraphTraversal`.

**Parameters** — add one, keep both existing:

```kotlin
ToolParam("query", "string", "The search query (e.g., a person's name or a topic)"),
ToolParam("maxResults", "integer", "Maximum number of results to return", required = false),
ToolParam("maxHops", "integer",
    "How far to traverse from each match. 1 = only direct facts, 2 (default) = also " +
    "facts about the entities those facts mention. Raise to 3 for deep context.",
    required = false),
```

**Seeding — hybrid, and this is the part that fixes gap 1.** "Extend in place" does not mean
keeping the non-deterministic entry path; it means one tool that seeds two ways:

1. `graphBuilder.findEntity(query)` — exact, case-insensitive, index-backed. If the user asks
   about "Kartik" and a `PersonEntity` named Kartik exists, that node is found **deterministically**,
   not hoped for in a top-K.
2. `repository.searchSimilar(embedder.embed(query), maxResults)` — as today, for semantic matches
   and for queries that aren't entity names.
3. Union the seeds, dedupe on `graphKey`, **exact match ordered first**.

**Per seed:**

- Entity seed (PERSON/TOPIC) → `traversal.collectFacts(key, TraversalOptions(maxDepth = maxHops))`.
- ASSERTION seed returned directly by vector search → render via the existing
  `graphBuilder.getNodeSummary`, unchanged. Do not traverse from it.

**Render budget.** Cap total rendered facts at ~40 across all seeds and note the truncation in the
output. A depth-3 walk on a well-connected person can produce hundreds of facts, and dumping them
all into the tool result blows the context window the tool exists to serve. Facts are already
ordered closest-first, so truncation drops the least relevant.

**Suggested output shape** (the LLM reads this; indirect facts must be visibly marked):

```
Found memories for "Crowne Plaza":

Crowne Plaza Okhla, Delhi (TOPIC)
  • Kartik traveling to Crowne Plaza Okhla, Delhi (16 Jul 2025 15:00 - 17 Jul 2025 12:00)
  • Crowne Plaza Okhla, Delhi has phone 01146462000
  • Kartik booked through MakeMyTrip
  related, 2 hops away:
  • Kartik works at Samsung

(showing 4 of 12 facts)
```

#### [MODIFY] `app/src/main/kotlin/dev/kortex/app/GraphManager.kt`

Add to `GraphEnvironment`, matching the existing lazy style:

```kotlin
val graphTraversal by lazy { GraphTraversal(graphRepository, graphBuilder, boxStore) }
```

#### [MODIFY] `app/src/main/kotlin/dev/kortex/app/KortexContainer.kt`

Expose it alongside `graphRepository` (`KortexContainer.kt:60`) and pass it into `MemoryTool`
(`KortexContainer.kt:73`).

---

## Commit breakdown

Branch: `claude/dazzling-euler-n6ydda`.

| # | Commit | Files |
|---|---|---|
| 1 | Domain result types for traversal | `GraphFact.kt`, `TraversalResult.kt` (both new, `graph-core`) |
| 2 | Batched repository primitives | `GraphRepository.kt`, `GraphBuilder.kt` |
| 3 | Traversal engine | `GraphTraversal.kt` (new) |
| 4 | Multi-hop memory search | `MemoryTool.kt`, `GraphManager.kt`, `KortexContainer.kt` |

Commits 1–3 are additive and leave existing behaviour untouched; the app still compiles and runs
exactly as before until commit 4 lands. That is deliberate — it keeps the risky commit small.

---

## Verification Plan

Manual only, per the scoping decision. `./gradlew :graph-storage:assembleDebug :app:assembleDebug`
must pass after each commit.

### Seed data

Feed this through the agent in one message so `save_knowledge` batches it:

> Kartik works at Samsung. Kartik is traveling to Crowne Plaza Okhla, Delhi from 16 July 2025 to
> 17 July 2025. He booked through MakeMyTrip. The Crowne Plaza Okhla's phone number is 01146462000.
> Kartik's manager is Priya. Priya studied at IIT Delhi.

### Checks

| # | Ask the agent | Expect |
|---|---|---|
| 1 | "What do you know about Kartik?" | Employer, trip, booking channel, manager — all depth-1 facts. |
| 2 | "What's the phone number of the hotel Kartik is staying at?" | `01146462000`. **This is the headline case — it fails on `main`.** |
| 3 | "Tell me about Crowne Plaza" | Exact-name seeding hits the TOPIC directly; phone number and the trip both appear. |
| 4 | "Where did Kartik's manager study?" | IIT Delhi, via Kartik → Priya → STUDIED_AT (2 hops). |
| 5 | Ask #1 again with `maxHops: 1` | Priya's education is **absent** — proves the depth bound is actually applied. |
| 6 | "What do you know about someone who does not exist?" | Clean "no memories found", no crash, no created nodes. |

### Non-obvious things to verify by hand

- **No node creation on the read path.** Open the Graph screen, note the node count, run several
  `memory_search` queries, reload. The count must be identical. A `getOrCreate*` accidentally left
  on the read path silently pollutes the graph — this is the single most likely regression.
- **Cycle safety.** Assert `Kartik FRIEND_OF Priya` *and* `Priya FRIEND_OF Kartik`, then query
  with `maxHops: 3`. Must terminate promptly and not repeat facts.
- **Truncation is honest.** Force it with a low `maxNodes`, confirm the output says so rather than
  silently returning a partial answer.
- **Existing behaviour intact.** A vague semantic query ("travel plans") that matches no entity
  name must still work through the vector path.

---

## Risks

- **No automated tests.** Traversal carries cycle guards, depth bounds, budget arithmetic and
  dedup-at-shortest-distance — all classic off-by-one territory, and all currently unguarded.
  The manual checks above are the only gate. Strongly recommend a follow-up adding JVM tests with
  `io.objectbox:objectbox-linux` against an in-memory store; `plan_for_graph.md` already calls for
  exactly this and it was never done.
- **Assertion hub explosion.** Mitigated by `maxNodes`, `maxFanOut` and the entity-only frontier,
  but a very well-connected topic at depth 3 will still hit the caps. Acceptable — it truncates
  rather than hangs — but do not raise the defaults without testing on a real graph.
- **`searchSimilar` has no distance threshold** (`GraphRepository.kt:176`). HNSW returns the
  nearest K regardless of how far they are, so an unrelated query still yields seeds, and
  traversal now amplifies that into a larger wrong answer. Out of scope here; the fix is
  `findWithScores()` (available in ObjectBox 4.0.3) plus a cutoff. **Worth doing soon.**
- **Context budget.** Multi-hop retrieval returns far more text than the current 1-hop expansion.
  The 40-fact render cap is a guess — tune it against real conversations.

## Out of scope (noted, not fixed)

- `GraphScreen.kt:118` does `registryBox.all.find { it.graphKey == nodeId }` — a full table scan
  where `registryBox.get(nodeId)` would do. One-line fix, unrelated to this work.
- `GraphScreen` focus/subgraph rendering on top of `GraphTraversal`.
- Deleting the `ExampleUnitTest` / `ExampleInstrumentedTest` stubs still carrying the
  `com.samsung.android.graph_storage` package name.
