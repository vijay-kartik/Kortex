package dev.kortex.core.ambient

import dev.kortex.core.store.CardDao
import dev.kortex.core.store.GraphEntityDao
import dev.kortex.core.store.MemoryDao
import dev.kortex.core.store.MentionDao
import dev.kortex.core.store.RelationDao
import dev.kortex.core.store.toEntity

/**
 * Persists an [AnalysisOutcome] across the storage layers: graph artifacts
 * (entities → mentions → relations), then memories (Layer 3), then cards. There are no FK
 * constraints, but writing the graph nodes before the edges/cards that reference them keeps
 * the store self-consistent at every step.
 */
class OutcomeWriter(
    private val cards: CardDao,
    private val memories: MemoryDao,
    private val entities: GraphEntityDao,
    private val mentions: MentionDao,
    private val relations: RelationDao,
) {
    suspend fun persist(outcome: AnalysisOutcome) {
        outcome.entities.forEach { entities.upsert(it.toEntity()) }
        outcome.mentions.forEach { mentions.upsert(it.toEntity()) }
        outcome.relations.forEach { relations.upsert(it.toEntity()) }
        outcome.memories.forEach { memories.upsert(it.toEntity()) }
        outcome.cards.forEach { cards.upsert(it.toEntity()) }
    }
}
