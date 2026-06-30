package dev.kortex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.core.ambient.ActionCard
import dev.kortex.core.ambient.CardAction
import dev.kortex.core.ambient.CardStatus
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
        _ui.update { it.copy(cards = container.cardDao.feed().map { e -> e.toDomain() }) }
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

    /** Dev/testing: push a sample incoming message for the first saved contact through the pipeline. */
    fun injectTestSignal() = viewModelScope.launch {
        val contact = container.contactDao.all().firstOrNull()
        if (contact == null) {
            _ui.update { it.copy(status = "Sync contacts first") }
            return@launch
        }
        val handle = contact.toDomain().handles.firstOrNull()
        if (handle == null) {
            _ui.update { it.copy(status = "Contact has no phone/email handle") }
            return@launch
        }
        _ui.update { it.copy(busy = true, status = "Analyzing test signal…") }
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
