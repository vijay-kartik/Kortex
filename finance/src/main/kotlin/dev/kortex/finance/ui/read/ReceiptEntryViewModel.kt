package dev.kortex.finance.ui.read

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Receipt
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.port.ReceiptImageReader
import dev.kortex.finance.domain.port.ReceiptPhotoStore
import dev.kortex.finance.domain.read.EntryMatching
import dev.kortex.finance.domain.read.Instrument
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.AttachReceipt
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.ReadReceipt
import dev.kortex.finance.domain.usecase.SuggestMerchant
import dev.kortex.finance.domain.usecase.TransactionDraft
import dev.kortex.finance.domain.usecase.TransactionSaveResult
import dev.kortex.finance.ui.AccountPrefill
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.accounts.AccountsUi
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.finance.ui.common.FinanceUndo
import dev.kortex.finance.ui.entry.AccountOption
import dev.kortex.finance.ui.entry.AddEntryState
import dev.kortex.finance.ui.entry.CategoryOption
import dev.kortex.finance.ui.recurring.RecurringLabels
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class ReceiptEntryViewModel @Inject constructor(
    private val imageReader: ReceiptImageReader,
    private val photoStore: ReceiptPhotoStore,
    private val observeFinance: ObserveFinance,
    private val readReceipt: ReadReceipt,
    private val suggestMerchant: SuggestMerchant,
    private val addTransaction: AddTransaction,
    private val attachReceipt: AttachReceipt,
    private val notices: FinanceNotices,
    private val clock: Clock,
) : MviViewModel<ReceiptEntryState, ReceiptEntryIntent, ReceiptEntryEffect>(ReceiptEntryState()) {

    private var started = false

    /** Where a retake started from, to go back to if the scanner is closed without a photo. */
    private var beforeRetake = ReceiptStage.Review

    fun start() {
        if (started) return
        started = true
        setState { copy(today = clock.today(), date = clock.today()) }
        viewModelScope.launch { observeFinance().collect(::onData) }
        sendEffect(ReceiptEntryEffect.LaunchScanner)
    }

    override fun handleIntent(intent: ReceiptEntryIntent) {
        when (intent) {
            is ReceiptEntryIntent.Scanned -> read(intent.pages)
            // Backing out of the first scan closes Scan receipt; backing out of a retake goes back.
            ReceiptEntryIntent.Cancelled -> if (currentState.pages.isEmpty()) sendEffect(ReceiptEntryEffect.Close) else setState { copy(stage = beforeRetake) }
            ReceiptEntryIntent.ScannerUnavailable -> setState { copy(stage = ReceiptStage.NoScanner) }
            ReceiptEntryIntent.Retake -> {
                beforeRetake = currentState.stage
                setState { copy(stage = ReceiptStage.Scanning) }
                sendEffect(ReceiptEntryEffect.LaunchScanner)
            }
            ReceiptEntryIntent.EnterManually -> sendEffect(ReceiptEntryEffect.Replace(FinanceRoute.AddEntry(income = false)))
            is ReceiptEntryIntent.PickTotal -> setState { copy(pickedTotal = intent.index, error = null) }
            is ReceiptEntryIntent.OtherTotal -> setState { copy(otherTotal = intent.text.filter { it.isDigit() || it == '.' || it == ',' }.take(15), pickedTotal = -1) }
            ReceiptEntryIntent.UseTotal -> currentState.pickedTotalMinor?.let(::useTotal)
            is ReceiptEntryIntent.Amount -> setState { copy(amount = intent.text.filter { it.isDigit() || it == '.' || it == ',' }.take(15), error = null) }
            is ReceiptEntryIntent.Merchant -> setState { copy(merchant = intent.text.take(60)) }
            is ReceiptEntryIntent.Date -> setState { copy(date = minOf(intent.date, today)) }
            is ReceiptEntryIntent.Account -> {
                setState { copy(accountUid = intent.uid, error = null) }
                checkDuplicate()
            }
            is ReceiptEntryIntent.Category -> setState { copy(categoryUid = intent.uid, categorySuggested = false) }
            is ReceiptEntryIntent.Note -> setState { copy(note = intent.text) }
            is ReceiptEntryIntent.KeepPhoto -> setState { copy(keepPhoto = intent.keep) }
            is ReceiptEntryIntent.ViewPhoto -> setState { copy(viewingPhoto = intent.open) }
            ReceiptEntryIntent.AddCard -> sendEffect(
                ReceiptEntryEffect.Navigate(
                    FinanceRoute.AddAccount(
                        AccountKind.CREDIT_CARD,
                        AccountPrefill(name = currentState.unknownLast4?.let { "Card ••$it" }, last4 = currentState.unknownLast4),
                    ),
                ),
            )
            ReceiptEntryIntent.Save -> save()
            ReceiptEntryIntent.Attach -> attach()
            ReceiptEntryIntent.AddAsNew -> setState { copy(stage = ReceiptStage.Review, duplicate = null) }
        }
    }

    private fun read(pages: List<String>) {
        setState { copy(stage = ReceiptStage.Reading, pages = pages, error = null) }
        viewModelScope.launch {
            val text = imageReader.text(pages)
            val receipt = readReceipt(text)
            if (receipt.unreadable) {
                setState { copy(stage = ReceiptStage.Unreadable, receipt = receipt) }
                return@launch
            }
            val snapshot = observeFinance().first()
            val account = EntryMatching.accountFor(receipt.last4, Instrument.CARD, snapshot.accounts)
            val guess = suggestMerchant(receipt.merchant, CategoryKind.EXPENSE)
            setState {
                copy(
                    receipt = receipt,
                    merchant = guess.name ?: receipt.merchant.orEmpty(),
                    date = receipt.date?.let { minOf(it, today) } ?: today,
                    time = receipt.time,
                    accountUid = account?.uid ?: accountUid,
                    unknownLast4 = if (account == null) receipt.last4 else null,
                    categoryUid = guess.categoryUid,
                    categorySuggested = guess.categoryUid != null,
                    pickedTotal = 0,
                )
            }
            onData(snapshot)
            val total = receipt.total
            if (total == null) setState { copy(stage = ReceiptStage.PickTotal) } else useTotal(total)
        }
    }

    private fun useTotal(totalMinor: Long) {
        setState { copy(amount = FinanceFormat.amountInput(totalMinor), stage = ReceiptStage.Review) }
        checkDuplicate()
    }

    /** Scan receipt 05: the same amount on the same card within an hour, SMS-added or typed in. */
    private fun checkDuplicate() {
        val state = currentState
        val amount = FinanceFormat.parseAmount(state.amount) ?: return
        val account = state.accountUid ?: return
        if (state.stage != ReceiptStage.Review) return
        viewModelScope.launch {
            val snapshot = observeFinance().first()
            val existing = EntryMatching.duplicateOf(
                amount, account, clock.millisAt(state.date, state.time), state.time != null, null,
                snapshot.transactions.filter { it.type == TransactionType.EXPENSE && it.receipt == null },
                EntryMatching.RECEIPT_DUPLICATE_MINUTES, clock::dayOf,
            ) ?: return@launch
            setState {
                copy(
                    stage = ReceiptStage.Duplicate,
                    duplicate = existing,
                    duplicateAccount = snapshot.accountsByUid[existing.accountUid]?.let(RecurringLabels::account),
                    duplicateCategory = existing.categoryUid?.let(snapshot.categoriesByUid::get)?.name,
                )
            }
        }
    }

    private fun onData(snapshot: FinanceSnapshot) {
        val accounts = snapshot.accounts.filterNot { it.archived }
        setState {
            val options = accounts.map { AccountOption(it.uid, RecurringLabels.account(it), AccountsUi.subtitle(it, snapshot.accountsByUid)) }
            val added = unknownLast4?.let { EntryMatching.accountFor(it, Instrument.CARD, accounts) }
            copy(
                accounts = options,
                categories = snapshot.categories.filter { it.kind == CategoryKind.EXPENSE }.map { CategoryOption(it.uid, it.name, it.colorToken) },
                accountUid = added?.uid ?: accountUid?.takeIf { uid -> options.any { it.uid == uid } },
                unknownLast4 = if (added != null) null else unknownLast4,
            )
        }
    }

    private suspend fun receiptFor(transactionUid: String): Receipt {
        val state = currentState
        val photo = state.pages.firstOrNull()?.takeIf { state.keepPhoto }
            ?.let { photoStore.keep(it, transactionUid) }
        return Receipt(
            itemCount = state.receipt.items.sumOf { it.quantity },
            items = state.receipt.items,
            taxMinor = state.receipt.taxMinor,
            photo = photo,
        )
    }

    private fun save() {
        val state = currentState
        if (state.saving) return
        val amount = FinanceFormat.parseAmount(state.amount) ?: return setState { copy(error = "Enter an amount above zero.") }
        val account = state.accountUid ?: return setState { copy(error = "Pick how you paid.") }
        setState { copy(saving = true, error = null) }
        viewModelScope.launch {
            val uid = FinanceIds.random()
            val result = addTransaction(
                TransactionDraft(
                    type = TransactionType.EXPENSE,
                    amountMinor = amount,
                    accountUid = account,
                    categoryUid = state.categoryUid,
                    merchant = state.merchant,
                    note = state.note,
                    occurredAtMillis = clock.millisAt(state.date, state.time),
                    source = TransactionSource.RECEIPT,
                    receipt = receiptFor(uid),
                    uid = uid,
                ),
            )
            if (result is TransactionSaveResult.Saved) {
                val where = state.merchant.trim().takeIf { it.isNotEmpty() }?.let { " at $it" }.orEmpty()
                notices.post(FinanceNotice("Saved ${FinanceFormat.rupees(amount)}$where", FinanceUndo.DeleteEntry(result.uid)))
                sendEffect(ReceiptEntryEffect.Close)
            } else {
                setState { copy(saving = false, error = AddEntryState.message(result)) }
            }
        }
    }

    private fun attach() {
        val existing = currentState.duplicate ?: return
        if (currentState.saving) return
        setState { copy(saving = true) }
        viewModelScope.launch {
            if (attachReceipt(existing.uid, receiptFor(existing.uid))) {
                notices.post(FinanceNotice("Receipt added to ${existing.merchant ?: "the expense"}"))
                sendEffect(ReceiptEntryEffect.Close)
            } else {
                setState { copy(saving = false, stage = ReceiptStage.Review, duplicate = null) }
            }
        }
    }
}
