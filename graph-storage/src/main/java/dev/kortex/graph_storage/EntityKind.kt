package dev.kortex.graph_storage

import dev.kortex.graph_core.NodeType


/**
 * Persistence type: WHICH storage table/box owns a node's business data.
 *
 * Distinct from [NodeType] (ontology) by design. The graph sees MESSAGE;
 * storage sees SMS_MESSAGE vs WHATSAPP_MESSAGE vs EMAIL_MESSAGE. This
 * separation lets the storage model change (split/merge tables) without
 * touching graph semantics.
 *
 * The registry stores [id]; repositories use EntityKind to dispatch
 * hydration ("businessEntityId 42 of kind SMS_MESSAGE → SmsMessageBox").
 *
 * ## ID contract (permanent)
 * Same rules as the core enums: stable, additive-only, never renumbered or
 * reused, never ordinal. Ranges: Identity 1-9, Event storage 10-29,
 * Knowledge 30-49, Assets 50-59.
 */
enum class EntityKind(val id: Int) {

    // ----- Identity (1-9) -----
    PERSON(1),
    ORGANIZATION(2),
    LOCATION(3),
    PHONE_NUMBER(4),
    EMAIL_ADDRESS(5),

    // ----- Event storage (10-29): one kind per source table -----
    SMS_MESSAGE(10),
    WHATSAPP_MESSAGE(11),
    EMAIL_MESSAGE(12),
    CALL_LOG(13),
    CALENDAR_EVENT(14),
    CONVERSATION(15),

    // ----- Knowledge (30-49) -----
    TOPIC(30),
    TASK(31),
    PROJECT(32),
    DECISION(33),
    COMMITMENT(34),
    MEMORY(35),
    ASSERTION(36),

    // ----- Assets (50-59) -----
    DOCUMENT(50),
    IMAGE(51),
    AUDIO(52),
    ;

    /**
     * The ontology type nodes of this persistence kind map to. Many-to-one:
     * SMS_MESSAGE, WHATSAPP_MESSAGE → MESSAGE; CALENDAR_EVENT → MEETING.
     */
    fun nodeType(): NodeType = when (this) {
        PERSON -> NodeType.PERSON
        ORGANIZATION -> NodeType.ORGANIZATION
        LOCATION -> NodeType.LOCATION
        PHONE_NUMBER -> NodeType.PHONE_NUMBER
        EMAIL_ADDRESS -> NodeType.EMAIL_ADDRESS
        SMS_MESSAGE, WHATSAPP_MESSAGE -> NodeType.MESSAGE
        EMAIL_MESSAGE -> NodeType.EMAIL
        CALL_LOG -> NodeType.CALL
        CALENDAR_EVENT -> NodeType.MEETING
        CONVERSATION -> NodeType.CONVERSATION
        TOPIC -> NodeType.TOPIC
        TASK -> NodeType.TASK
        PROJECT -> NodeType.PROJECT
        DECISION -> NodeType.DECISION
        COMMITMENT -> NodeType.COMMITMENT
        MEMORY -> NodeType.MEMORY
        ASSERTION -> NodeType.ASSERTION
        DOCUMENT -> NodeType.DOCUMENT
        IMAGE -> NodeType.IMAGE
        AUDIO -> NodeType.AUDIO
    }

    companion object {
        private val byId: Map<Int, EntityKind> = buildMap {
            for (kind in EntityKind.entries) {
                val previous = put(kind.id, kind)
                check(previous == null) {
                    "Duplicate EntityKind id ${kind.id}: $previous and $kind"
                }
            }
        }

        /** Resolves a persisted id; null for unknown ids (skip-and-log on read). */
        fun fromId(id: Int): EntityKind? = byId[id]
    }
}
