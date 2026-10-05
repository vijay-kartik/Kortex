package dev.kortex.finance.ui.recurring

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.DeleteRecurring
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.RecurringSaveResult
import dev.kortex.finance.domain.usecase.SaveRecurring
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class RecurringFormViewModel @Inject constructor(
    private val observeFinance: ObserveFinance,
    private val saveRecurring: SaveRecurring,
    private val deleteRecurring: DeleteRecurring,
    private val notices: FinanceNotices,
    private val clock: Clock,
) : MviViewModel<RecurringFormState, RecurringFormIntent, RecurringFormEffect>(RecurringFormState()) {

    private var started = false

    /** The route calls this once: [editUid] to edit that payment, else a new one. */
    fun start(editUid: String?) {
        if (started) return
        started = true
        val today = clock.today()
        setState { copy(today = today, nextDueOn = today) }
        viewModelScope.launch {
            // Follows the data, so an account added from here shows up when it closes.
            observeFinance().collect { snapshot ->
                val (accounts, categories) = RecurringFormState.options(snapshot)
                if (currentState.loading) {
                    val existing = editUid?.let { uid -> snapshot.recurring.find { it.uid == uid } }
                    if (editUid != null && existing == null) {
                        sendEffect(RecurringFormEffect.Close)
                        return@collect
                    }
                    setState { existing?.let { RecurringFormState.editing(it, today) } ?: copy(loading = false, accountUid = accounts.firstOrNull()?.uid) }
                }
                setState {
                    copy(
                        accounts = accounts,
                        categories = categories,
                        accountUid = when {
                            accountUid != null && accounts.any { it.uid == accountUid } -> accountUid
                            // A new payment with no account yet takes the one just added.
                            accountUid == null && !editing -> accounts.lastOrNull()?.uid
                            // Its account was deleted: "needs an account" until one is picked.
                            else -> null
                        },
                        categoryUid = categoryUid?.takeIf { uid -> categories.any { it.uid == uid } },
                    )
                }
            }
        }
    }

    override fun handleIntent(intent: RecurringFormIntent) {
        when (intent) {
            is RecurringFormIntent.Kind -> setState { copy(kind = intent.kind, error = null) }
            is RecurringFormIntent.Amount -> setState { copy(amount = intent.text.filter { it.isDigit() || it == '.' || it == ',' }.take(15), error = null) }
            is RecurringFormIntent.Name -> setState { copy(name = intent.text.take(MAX_NAME), error = null) }
            is RecurringFormIntent.Repeats -> setState { copy(frequency = intent.frequency) }
            is RecurringFormIntent.NextDue -> setState { copy(nextDueOn = intent.date) }
            is RecurringFormIntent.Account -> setState { copy(accountUid = intent.uid, error = null) }
            is RecurringFormIntent.Category -> setState { copy(categoryUid = intent.uid) }
            is RecurringFormIntent.Remind -> setState { copy(remindDaysBefore = intent.days) }
            is RecurringFormIntent.AutoMarkPaid -> setState { copy(autoMarkPaid = intent.on) }
            is RecurringFormIntent.Paused -> setState { copy(paused = intent.on) }
            RecurringFormIntent.AddAccount -> sendEffect(RecurringFormEffect.Navigate(FinanceRoute.AddAccount(AccountKind.BANK)))
            RecurringFormIntent.Save -> save()
            RecurringFormIntent.AskDelete -> setState { copy(confirmDelete = true) }
            RecurringFormIntent.CancelDelete -> setState { copy(confirmDelete = false) }
            RecurringFormIntent.ConfirmDelete -> delete()
        }
    }

    private fun save() {
        val state = currentState
        if (state.saving || state.loading) return
        val (draft, problem) = state.toDraft()
        if (draft == null) {
            setState { copy(error = problem) }
            return
        }
        setState { copy(saving = true) }
        viewModelScope.launch {
            val result = saveRecurring(state.editUid, draft)
            if (result is RecurringSaveResult.Saved) {
                notices.post(FinanceNotice(if (state.editing) "Saved ${draft.name.trim()}" else "Added ${draft.name.trim()}"))
                if (draft.remindDaysBefore != Recurring.NO_REMINDER) sendEffect(RecurringFormEffect.AskNotificationPermission)
                sendEffect(RecurringFormEffect.Close)
            } else {
                setState { copy(saving = false, error = RecurringFormState.message(result)) }
            }
        }
    }

    private fun delete() {
        val uid = currentState.editUid ?: return
        viewModelScope.launch {
            deleteRecurring(uid)
            notices.post(FinanceNotice("Deleted ${currentState.name.trim()} · its past payments stay"))
            sendEffect(RecurringFormEffect.Close)
        }
    }

    private companion object {
        const val MAX_NAME = 40
    }
}
