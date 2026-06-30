package dev.kortex.core.ambient

import kotlinx.serialization.Serializable

/**
 * The ingestion model for Kortex's ambient pipeline.
 *
 * A [Signal] is one raw communication event captured from the device (a received message,
 * a notification, a voicemail/call transcript, etc.). The Android layer (written later)
 * produces these; the agent reasons over them. Everything here is pure-Kotlin and
 * @Serializable so it can be persisted (Room/JSON) and handed to the LLM.
 *
 * All times are epoch milliseconds (UTC) — dependency-free and trivially serializable.
 */
@Serializable
data class Signal(
    val id: String,
    val source: SignalSource,
    val kind: SignalKind,
    val direction: Direction,
    /** Raw handle of the other party as seen in [source] (e.g. "+919812345678", "@neha"). */
    val senderHandle: Handle,
    /** Canonical contact this signal was resolved to, once identity resolution has run. */
    val contactId: String? = null,
    val content: String,
    val timestampMillis: Long,
    val attachments: List<Attachment> = emptyList(),
    /** The app's own thread id, if any. NOT used for cross-app grouping (see Conversation). */
    val nativeThreadId: String? = null,
    /** Escape hatch for source-specific extras (notification category, importance, etc.). */
    val metadata: Map<String, String> = emptyMap(),
)

/** Which app/medium a signal came from, e.g. ("com.whatsapp", "WhatsApp"). */
@Serializable
data class SignalSource(val appId: String, val appLabel: String)

@Serializable
enum class SignalKind { MESSAGE, NOTIFICATION, VOICEMAIL_TRANSCRIPT, CALL_TRANSCRIPT, CALL_LOG, EMAIL, OTHER }

@Serializable
enum class Direction { INCOMING, OUTGOING }

/**
 * A medium-specific address for a person. The same human has many handles across apps
 * (phone number for SMS, a username on WhatsApp, an email) — these are what identity
 * resolution maps onto a single [ContactRef] so conversations can span mediums.
 */
@Serializable
data class Handle(val type: HandleType, val value: String, val appId: String? = null)

@Serializable
enum class HandleType { PHONE, EMAIL, USERNAME, OTHER }

/** A resolved person. [handles] is what lets cross-app signals collapse onto one identity. */
@Serializable
data class ContactRef(
    val id: String,
    val displayName: String,
    val handles: List<Handle> = emptyList(),
    val affinity: ContactAffinity = ContactAffinity.OTHER,
    /** Optional 0..1 frequency/closeness score for ranking and prioritization (pattern 20). */
    val score: Double? = null,
)

@Serializable
enum class ContactAffinity { FAVORITE, FREQUENT, OTHER }

@Serializable
data class Attachment(
    val type: AttachmentType,
    val uri: String? = null,
    val mimeType: String? = null,
    val description: String? = null,
)

@Serializable
enum class AttachmentType { IMAGE, VIDEO, AUDIO, FILE, LOCATION, CONTACT, OTHER }
