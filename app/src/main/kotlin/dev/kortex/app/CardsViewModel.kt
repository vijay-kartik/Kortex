package dev.kortex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.ambient.ActionCard
import dev.kortex.core.ambient.CardAction
import dev.kortex.core.ambient.CardStatus
import dev.kortex.core.ambient.ContactRef
import dev.kortex.core.ambient.CoordinationResult
import dev.kortex.core.ambient.Direction
import dev.kortex.core.ambient.Signal
import dev.kortex.core.ambient.SignalKind
import dev.kortex.core.ambient.SignalSource
import dev.kortex.core.store.toDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class CardsUi(
    val cards: List<ActionCard> = emptyList(),
    val busy: Boolean = false,
    val status: String? = null,
    val testSignalText: String = "Hey! Are we still on for dinner Friday at 7? Rahul might join too.",
    val contacts: List<ContactRef> = emptyList(),
    val selectedContactId: String? = null,
    val contactQuery: String = "",
)

/**
 * Drives the card feed (`CardDao.feed()`), contact seeding, card status transitions, and a
 * dev hook to inject a test signal through the live pipeline. Actually performing a card
 * action (sending a reply, sharing location) is the deferred CardActionExecutor — for now an
 * action just marks the card ACTED.
 */
class CardsViewModel(app: Application) : AndroidViewModel(app) {
    private val container get() = getApplication<KortexApp>().container

    private val _ui = MutableStateFlow(CardsUi())
    val ui: StateFlow<CardsUi> = _ui.asStateFlow()

    fun refresh() = viewModelScope.launch {
        val cards = container.cardDao.feed().map { e -> e.toDomain() }
        val contacts = container.contactDao.all().map { it.toDomain() }
        _ui.update {
            it.copy(
                cards = cards,
                contacts = contacts,
                selectedContactId = it.selectedContactId ?: contacts.firstOrNull()?.id
            )
        }
    }

    fun onContactSelected(contactId: String) {
        _ui.update { it.copy(selectedContactId = contactId, contactQuery = "") }
        // Optionally reload all contacts so the list is fresh next time
        viewModelScope.launch {
            val contacts = container.contactDao.all().map { it.toDomain() }
            _ui.update { it.copy(contacts = contacts) }
        }
    }

    fun onContactQueryChanged(query: String) = viewModelScope.launch {
        _ui.update { it.copy(contactQuery = query) }
        val results = if (query.isBlank()) {
            container.contactDao.all()
        } else {
            container.contactDao.search(query)
        }
        _ui.update { it.copy(contacts = results.map { it.toDomain() }) }
    }

    fun onTestSignalTextChanged(text: String) {
        _ui.update { it.copy(testSignalText = text) }
    }

    fun syncContacts() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, status = "Syncing contacts…") }
        val count = container.contactSeeder.seedFromDevice()
        _ui.update { it.copy(busy = false, status = "Synced $count contacts") }
    }

    fun dismiss(card: ActionCard) = viewModelScope.launch {
        container.cardDao.setStatus(card.id, CardStatus.DISMISSED.name)
        refresh()
    }

    /** Confirmed execution. TODO: route through CardActionExecutor + ToolGovernor for real side effects. */
    fun act(card: ActionCard, action: CardAction) = viewModelScope.launch {
        _ui.update { it.copy(status = "Would: ${action.label}") }
        container.cardDao.setStatus(card.id, CardStatus.ACTED.name)
        refresh()
    }

    /** Dev/testing: push a sample incoming message for a specific contact through the pipeline. */
    fun injectTestSignal() = viewModelScope.launch {
        val contactId = _ui.value.selectedContactId
        if (contactId == null) {
            _ui.update { it.copy(status = "Sync contacts first") }
            return@launch
        }
        val contact = container.contactDao.byId(contactId)?.toDomain()
        if (contact == null) {
            _ui.update { it.copy(status = "Contact not found") }
            return@launch
        }
        val handle = contact.handles.firstOrNull()
        if (handle == null) {
            _ui.update { it.copy(status = "Contact has no phone/email handle") }
            return@launch
        }
        _ui.update { it.copy(busy = true, status = "Analyzing test signal from ${contact.displayName}…") }
        val signal = Signal(
            id = UUID.randomUUID().toString(),
            source = SignalSource(appId = "com.kortex.test", appLabel = "Test"),
            kind = SignalKind.MESSAGE,
            direction = Direction.INCOMING,
            senderHandle = handle,
            content = _ui.value.testSignalText,
            timestampMillis = System.currentTimeMillis(),
        )
        val outcome = when (val r = container.coordinator.onSignal(signal)) {
            is CoordinationResult.Dropped -> "Dropped: ${r.reason}"
            is CoordinationResult.Analyzed -> r.result::class.simpleName ?: "Analyzed"
        }
        _ui.update { it.copy(busy = false, status = outcome) }
        refresh()
    }
}
