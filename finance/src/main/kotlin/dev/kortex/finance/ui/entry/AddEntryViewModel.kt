package dev.kortex.finance.ui.entry

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.SuggestCategory
import dev.kortex.finance.domain.usecase.TransactionDraft
import dev.kortex.finance.domain.usecase.TransactionSaveResult
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.finance.ui.common.FinanceUndo
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class AddEntryViewModel @Inject constructor(
    private val observeFinance: ObserveFinance,
    private val addTransaction: AddTransaction,
    private val suggestCategoryFor: SuggestCategory,
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
            observeFinance().map { snapshot ->
                val (accounts, categories) = AddEntryState.options(snapshot, income)
                // Looks through every entry, so it is worked out off Main with the snapshot.
                Triple(accounts, categories, AddEntryState.defaultAccount(snapshot, income, accounts))
            }.flowOn(observeFinance.dispatcher).collect { (accounts, categories, defaultAccount) ->
                setState {
                    copy(
                        loading = false,
                        accounts = accounts,
                        categories = categories,
                        accountUid = accountUid?.takeIf { uid -> accounts.any { it.uid == uid } } ?: defaultAccount,
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
            AddEntryIntent.PasteSms -> sendEffect(AddEntryEffect.Replace(FinanceRoute.PasteSms()))
            AddEntryIntent.ScanReceipt -> sendEffect(AddEntryEffect.Replace(FinanceRoute.ScanReceipt))
            AddEntryIntent.Save -> save()
        }
    }

    /**
     * Picks the category last used for this merchant, else the decision model's pick, unless the
     * user already chose one.
     */
    private fun suggestCategory(merchant: String) {
        suggestJob?.cancel()
        if (currentState.categoryPicked) return
        if (FinanceIds.payeeKey(merchant).isEmpty()) {
            setState { copy(categoryUid = if (suggestedFrom != null) null else categoryUid, suggestedFrom = null) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DELAY_MS)
            val kind = if (currentState.income) CategoryKind.INCOME else CategoryKind.EXPENSE
            val suggested = suggestCategoryFor(merchant, kind)?.categoryUid
            setState {
                if (categoryPicked) this
                else if (suggested != null && categories.any { it.uid == suggested }) copy(categoryUid = suggested, suggestedFrom = merchant.trim())
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
            val at = clock.millisOn(state.date)
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
                notices.post(FinanceNotice("Saved ${FinanceFormat.rupees(amount)}$where", undo = FinanceUndo.DeleteEntry(result.uid)))
                sendEffect(AddEntryEffect.Close)
            } else {
                setState { copy(saving = false, error = AddEntryState.message(result)) }
            }
        }
    }

    private companion object {
        /** Long enough that the decision model is asked about a word, not every keystroke. */
        const val SUGGEST_DELAY_MS = 500L
    }
}
