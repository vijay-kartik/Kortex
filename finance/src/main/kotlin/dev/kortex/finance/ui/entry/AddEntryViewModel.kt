package dev.kortex.finance.ui.entry

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.FinanceRepository
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.TransactionDraft
import dev.kortex.finance.domain.usecase.TransactionSaveResult
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel
class AddEntryViewModel @Inject constructor(
    private val observeFinance: ObserveFinance,
    private val repository: FinanceRepository,
    private val addTransaction: AddTransaction,
    private val notices: FinanceNotices,
    private val clock: Clock,
) : MviViewModel<AddEntryState, AddEntryIntent, AddEntryEffect>(AddEntryState()) {

    private var started = false
    private var suggestJob: Job? = null

    /** The route calls this once with the kind of entry it opened for. */
    fun start(income: Boolean) {
        if (started) return
        started = true
        setState { copy(income = income, date = clock.today()) }
        // Follows the data, so an account or category added from here shows up when it closes.
        viewModelScope.launch {
            observeFinance().collect { snapshot ->
                val (accounts, categories) = AddEntryState.options(snapshot, income)
                setState {
                    copy(
                        loading = false,
                        accounts = accounts,
                        categories = categories,
                        accountUid = accountUid?.takeIf { uid -> accounts.any { it.uid == uid } } ?: AddEntryState.defaultAccount(snapshot, income, accounts),
                        categoryUid = categoryUid?.takeIf { uid -> categories.any { it.uid == uid } },
                    )
                }
            }
        }
    }

    override fun handleIntent(intent: AddEntryIntent) {
        when (intent) {
            is AddEntryIntent.Amount -> setState { copy(amount = intent.text.filter { it.isDigit() || it == '.' || it == ',' }.take(15), error = null) }
            is AddEntryIntent.Merchant -> {
                setState { copy(merchant = intent.text, error = null) }
                suggestCategory(intent.text)
            }
            is AddEntryIntent.Note -> setState { copy(note = intent.text) }
            is AddEntryIntent.ShowDatePicker -> setState { copy(datePickerOpen = intent.open) }
            is AddEntryIntent.PickDate -> setState { copy(date = minOf(intent.date, clock.today()), datePickerOpen = false) }
            is AddEntryIntent.PickAccount -> setState { copy(accountUid = intent.uid, error = null) }
            is AddEntryIntent.PickCategory -> setState {
                copy(categoryUid = if (categoryUid == intent.uid) null else intent.uid, categoryPicked = true, suggestedFrom = null)
            }
            AddEntryIntent.NewCategory -> sendEffect(
                AddEntryEffect.Navigate(FinanceRoute.CategoryForm(kind = if (currentState.income) CategoryKind.INCOME else CategoryKind.EXPENSE)),
            )
            AddEntryIntent.ManageCategories -> sendEffect(AddEntryEffect.Navigate(FinanceRoute.Categories))
            AddEntryIntent.AddAccount -> sendEffect(AddEntryEffect.Navigate(FinanceRoute.AddAccount(AccountKind.BANK)))
            AddEntryIntent.Save -> save()
        }
    }

    /** Picks the category last used for this merchant, unless the user already chose one. */
    private fun suggestCategory(merchant: String) {
        suggestJob?.cancel()
        if (currentState.categoryPicked) return
        val key = FinanceIds.payeeKey(merchant)
        if (key.isEmpty()) {
            setState { copy(categoryUid = if (suggestedFrom != null) null else categoryUid, suggestedFrom = null) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DELAY_MS)
            val known = repository.findMerchant(key)?.categoryUid
            setState {
                if (categoryPicked) this
                else if (known != null && categories.any { it.uid == known }) copy(categoryUid = known, suggestedFrom = merchant.trim())
                else copy(categoryUid = if (suggestedFrom != null) null else categoryUid, suggestedFrom = null)
            }
        }
    }

    private fun save() {
        val state = currentState
        if (state.saving) return
        val amount = FinanceFormat.parseAmount(state.amount)
        val account = state.accountUid
        when {
            amount == null -> return setState { copy(error = "Enter an amount above zero.") }
            account == null -> return setState { copy(error = "Pick an account.") }
        }
        setState { copy(saving = true) }
        viewModelScope.launch {
            val now = clock.nowMillis()
            // Today keeps the time it was saved; another day is noon, so the day can't slip across zones.
            val at = if (state.date == clock.today()) now else state.date.atTime(12, 0).atZone(clock.zone()).toInstant().toEpochMilli()
            val result = addTransaction(
                TransactionDraft(
                    type = if (state.income) TransactionType.INCOME else TransactionType.EXPENSE,
                    amountMinor = amount!!,
                    accountUid = account!!,
                    categoryUid = state.categoryUid,
                    merchant = state.merchant,
                    note = state.note,
                    occurredAtMillis = at,
                ),
            )
            if (result is TransactionSaveResult.Saved) {
                val where = state.merchant.trim().takeIf { it.isNotEmpty() }?.let { if (state.income) " from $it" else " at $it" }.orEmpty()
                notices.post(FinanceNotice("Saved ${FinanceFormat.rupees(amount)}$where", undoTransactionUid = result.uid))
                sendEffect(AddEntryEffect.Close)
            } else {
                setState { copy(saving = false, error = AddEntryState.message(result)) }
            }
        }
    }

    private companion object {
        const val SUGGEST_DELAY_MS = 300L
    }
}
