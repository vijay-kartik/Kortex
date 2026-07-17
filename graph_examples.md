# Knowledge Graph Example Scenarios

This document outlines how the AI agent interacts with the Knowledge Graph under the hood. It demonstrates what the agent extracts from your natural conversations, how it stores that data in the graph, and how it queries it back using real vector embeddings.

## Scenario 1: Building the Graph (Extracting Knowledge)

**User Input (in Chat):** 
> "I just started a new role as a Senior Engineer at Google. I'm going to be working on their Jetpack Compose team. My manager is Sarah Connor."

The agent determines this contains permanent, useful knowledge. It invokes the **`save_knowledge`** tool (`KnowledgeExtractionTool`) with a structured payload:

### `save_knowledge` Tool Input
```json
{
  "entities": [
    { "type": "PERSON", "id": "p_user", "name": "User" },
    { "type": "PERSON", "id": "p_sarah", "name": "Sarah Connor" },
    { "type": "ORGANIZATION", "id": "o_google", "name": "Google" },
    { "type": "TOPIC", "id": "t_compose", "name": "Jetpack Compose" }
  ],
  "assertions": [
    {
      "subjectId": "p_user",
      "predicate": "WORKS_AT",
      "objectId": "o_google",
      "details": "Senior Engineer",
      "confidence": 1.0
    },
    {
      "subjectId": "p_user",
      "predicate": "INTERESTED_IN",
      "objectId": "t_compose",
      "details": "Working on this team",
      "confidence": 0.9
    },
    {
      "subjectId": "p_user",
      "predicate": "MANAGED_BY",
      "objectId": "p_sarah",
      "details": "At Google",
      "confidence": 1.0
    }
  ]
}
```

### Under the Hood (Database & UI)
1. **Embeddings**: The system calls Ollama Cloud (`all-minilm`) to create a 384-dimensional vector for each node and each assertion based on their textual descriptions.
2. **Graph View**: If you open your **Graph Tab**, you will instantly see:
   - Three purple (`Synapse`) nodes: **User**, **Sarah Connor**, **Google**
   - One green (`Knowledge`) node: **Jetpack Compose**
   - Gray lines (springs) connecting the nodes together, dynamically settling on the screen.

---

## Scenario 2: Implicit Topic Bridging

**User Input (in Chat):**
> "I'm having a lot of trouble with state hoisting in Compose. Do you know any good patterns?"

The agent extracts this new struggle/topic and links it to what it already knows.

### `save_knowledge` Tool Input
```json
{
  "entities": [
    { "type": "PERSON", "id": "p_user", "name": "User" },
    { "type": "TOPIC", "id": "t_hoisting", "name": "State Hoisting" }
  ],
  "assertions": [
    {
      "subjectId": "p_user",
      "predicate": "STRUGGLING_WITH",
      "objectId": "t_hoisting",
      "details": "In Jetpack Compose context",
      "confidence": 0.85
    }
  ]
}
```

### Under the Hood (Database & UI)
- A new green node for **State Hoisting** appears on the graph.
- It pulls closely to the **User** node due to the new edge. Since "User" is also connected to "Jetpack Compose", the physics engine will visually group these topics together!

---

## Scenario 3: Querying the Graph (Memory Retrieval)

Days later, the user asks a vague question.

**User Input (in Chat):**
> "Who was that manager I mentioned when I joined the new team? I need to email her about my state management issues."

The agent does not have this in its immediate short-term context. It decides to search memory. It calls the **`memory_search`** tool (`MemoryTool`).

### `memory_search` Tool Input
```json
{
  "query": "manager new team state management issues",
  "limit": 5
}
```

### Under the Hood
1. The tool embeds the query string using Ollama Cloud (`all-minilm`) into a 384-dimensional vector.
2. It performs an incredibly fast **HNSW Nearest Neighbor search** directly inside ObjectBox.
3. It finds the `MANAGED_BY` assertion ("Sarah Connor") and the `STRUGGLING_WITH` assertion ("State Hoisting") because their vectors land very close to the query vector in 384D space.

### `memory_search` Tool Output
```json
{
  "results": [
    {
      "node": { "id": "edge_1", "type": "ASSERTION", "label": "MANAGED_BY" },
      "details": "User -> Sarah Connor (Details: At Google)",
      "neighbors": [
        { "id": "p_user", "type": "PERSON", "label": "User" },
        { "id": "p_sarah", "type": "PERSON", "label": "Sarah Connor" }
      ]
    },
    {
      "node": { "id": "edge_2", "type": "ASSERTION", "label": "STRUGGLING_WITH" },
      "details": "User -> State Hoisting (Details: In Jetpack Compose context)",
      "neighbors": [
        { "id": "t_hoisting", "type": "TOPIC", "label": "State Hoisting" }
      ]
    }
  ]
}
```

### Final Agent Response
> "Your manager at Google is **Sarah Connor**. Since you're working on the Jetpack Compose team and previously struggled with state hoisting, I can draft an email to her outlining your current state management approach. Would you like me to do that?"
