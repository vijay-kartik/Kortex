package dev.kortex.finance.ui.accounts

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.calc.Balances
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.usecase.AccountSaveResult
import dev.kortex.finance.domain.usecase.AddAccount
import dev.kortex.finance.domain.usecase.DeleteAccount
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.RevealNumber
import dev.kortex.finance.domain.usecase.UpdateAccount
import dev.kortex.finance.ui.common.ScreenLock
import kotlinx.coroutines.delay
import dev.kortex.finance.ui.AccountPrefill
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class AccountFormViewModel @Inject constructor(
    private val observeFinance: ObserveFinance,
    private val addAccount: AddAccount,
    private val updateAccount: UpdateAccount,
    private val deleteAccount: DeleteAccount,
    private val revealNumber: RevealNumber,
    private val notices: FinanceNotices,
) : MviViewModel<AccountFormState, AccountFormIntent, AccountFormEffect>(AccountFormState()) {

    private var started = false

    /** The route calls this once: [editUid] to edit that account, else a new one of [kind]. */
    fun start(editUid: String?, kind: AccountKind?, prefill: AccountPrefill? = null) {
        if (started) return
        started = true
        if (editUid == null) {
            setState { prefill?.let { AccountFormState.prefilled(kind ?: AccountKind.CREDIT_CARD, it) } ?: copy(kind = kind ?: AccountKind.BANK) }
            return
        }
        setState { copy(loading = true) }
        viewModelScope.launch {
            val snapshot = observeFinance().first()
            val account = snapshot.accountsByUid[editUid]
            if (account == null) {
                sendEffect(AccountFormEffect.Close)
                return@launch
            }
            setState { AccountFormState.editing(account, Balances.balanceMinor(account, snapshot.transactions, snapshot.accountsByUid)) }
        }
    }

    override fun handleIntent(intent: AccountFormIntent) {
        when (intent) {
            is AccountFormIntent.SelectKind -> if (!currentState.editing) setState { copy(kind = intent.kind, error = null) }
            is AccountFormIntent.Edit -> setState { intent.change(this).copy(error = null) }
            AccountFormIntent.Save -> save()
            AccountFormIntent.AskDelete -> askDelete()
            AccountFormIntent.CancelDelete -> setState { copy(deleteSummary = null) }
            AccountFormIntent.ConfirmDelete -> confirmDelete()
            AccountFormIntent.Reveal -> reveal()
            AccountFormIntent.Hide -> setState { copy(revealedNumber = null) }
        }
    }

    private fun save() {
        if (currentState.saving) return
        val (draft, problem) = currentState.toDraft()
        if (draft == null) {
            setState { copy(error = problem) }
            return
        }
        setState { copy(saving = true) }
        viewModelScope.launch {
            val uid = currentState.editUid
            val result = if (uid == null) addAccount(draft) else updateAccount(uid, draft)
            if (result is AccountSaveResult.Saved) {
                val saved = if (uid == null) "Added ${draft.name.trim()}" else "Saved ${draft.name.trim()}"
                notices.post(
                    FinanceNotice(
                        if (result.numberKept == false) "$saved · the full number wasn’t kept: sign in to Kortex, online, to keep it encrypted" else saved,
                    ),
                )
                sendEffect(AccountFormEffect.Close)
            } else {
                setState { copy(saving = false, error = AccountFormState.message(result)) }
            }
        }
    }

    private fun reveal() {
        val uid = currentState.editUid ?: return
        viewModelScope.launch {
            val number = revealNumber(uid) ?: return@launch setState { copy(error = "Couldn’t show the full number. Check you’re signed in and online.") }
            setState { copy(revealedNumber = number, error = null) }
            delay(ScreenLock.SHOW_MILLIS)
            setState { copy(revealedNumber = null) }
        }
    }

    private fun askDelete() {
        val uid = currentState.editUid ?: return
        viewModelScope.launch {
            val snapshot = observeFinance().first()
            val account = snapshot.accountsByUid[uid] ?: return@launch
            setState {
                copy(
                    deleteSummary = DeleteSummary(
                        balanceMinor = Balances.balanceMinor(account, snapshot.transactions, snapshot.accountsByUid),
                        entryCount = snapshot.transactions.count { it.accountUid == uid || it.toAccountUid == uid },
                        recurringNames = snapshot.recurring.filter { it.accountUid == uid }.map { it.name },
                    ),
                )
            }
        }
    }

    private fun confirmDelete() {
        val uid = currentState.editUid ?: return
        viewModelScope.launch {
            deleteAccount(uid)
            notices.post(FinanceNotice("Deleted ${currentState.name.trim()}"))
            sendEffect(AccountFormEffect.Close)
        }
    }
}
