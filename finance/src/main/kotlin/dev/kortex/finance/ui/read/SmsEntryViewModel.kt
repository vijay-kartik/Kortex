package dev.kortex.finance.ui.read

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.calc.Statements
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.read.EntryMatching
import dev.kortex.finance.domain.read.Instrument
import dev.kortex.finance.domain.read.ParsedSms
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.PrepareSmsEntry
import dev.kortex.finance.domain.usecase.ReadSms
import dev.kortex.finance.domain.usecase.ResolveInboxSms
import dev.kortex.finance.domain.usecase.SmsEntryPlan
import dev.kortex.finance.domain.usecase.SmsEntryType
import dev.kortex.finance.domain.usecase.SmsResult
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
import dev.kortex.finance.ui.entry.CategoryOption
import dev.kortex.finance.ui.recurring.RecurringLabels
import dev.kortex.mvi.MviViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class SmsEntryViewModel @Inject constructor(
    private val observeFinance: ObserveFinance,
    private val readSms: ReadSms,
    private val prepareSmsEntry: PrepareSmsEntry,
    private val addTransaction: AddTransaction,
    private val resolveInboxSms: ResolveInboxSms,
    private val notices: FinanceNotices,
    private val clock: Clock,
) : MviViewModel<SmsEntryState, SmsEntryIntent, SmsEntryEffect>(SmsEntryState()) {

    private var started = false
    private var clipboardRead = false

    /** The received SMS this review came from (To review); saving it marks that SMS done. */
    private var inboxId: String? = null

    /**
     * [shared] is the text shared from Messages or opened from To review; without it the route
     * reads the clipboard once.
     */
    fun start(shared: String?, inboxId: String? = null) {
        if (started) return
        started = true
        this.inboxId = inboxId
        setState { copy(today = clock.today(), date = clock.today()) }
        // Keeps the account lists current, and picks up a card added from here (Paste SMS 09).
        viewModelScope.launch { observeFinance().collect(::onData) }
        shared?.let {
            clipboardRead = true
            read(it)
        }
    }

    override fun handleIntent(intent: SmsEntryIntent) {
        when (intent) {
            // Read once: coming back from Add card mustn't paste again.
            is SmsEntryIntent.Clipboard -> if (!clipboardRead) {
                clipboardRead = true
                intent.text?.takeIf { it.isNotBlank() }?.let(::read) ?: setState { copy(stage = SmsStage.Paste) }
            }
            is SmsEntryIntent.PasteBox -> setState { copy(pasteBox = intent.text, rejected = null) }
            SmsEntryIntent.ReadPasteBox -> currentState.pasteBox.takeIf { it.isNotBlank() }?.let(::read)
            SmsEntryIntent.EnterManually -> sendEffect(SmsEntryEffect.Replace(FinanceRoute.AddEntry(income = currentState.type == SmsEntryType.INCOME)))
            is SmsEntryIntent.Amount -> setState { copy(amount = intent.text.filter { it.isDigit() || it == '.' || it == ',' }.take(15), error = null) }
            is SmsEntryIntent.Merchant -> setState { copy(merchant = intent.text.take(60), error = null) }
            is SmsEntryIntent.Date -> setState { copy(date = minOf(intent.date, today)) }
            is SmsEntryIntent.Account -> setState { copy(accountUid = intent.uid, error = null) }
            is SmsEntryIntent.FromAccount -> setState { copy(fromAccountUid = intent.uid, error = null) }
            is SmsEntryIntent.Category -> setState { copy(categoryUid = intent.uid, categorySuggested = false) }
            is SmsEntryIntent.Note -> setState { copy(note = intent.text) }
            SmsEntryIntent.AddCard -> addCard()
            SmsEntryIntent.Save -> save(asNew = false)
            SmsEntryIntent.AddAnyway -> save(asNew = true)
            SmsEntryIntent.Skip -> sendEffect(SmsEntryEffect.Close)
        }
    }

    private fun read(text: String) {
        setState { copy(stage = SmsStage.Reading, text = text.trim(), rejected = null, error = null) }
        viewModelScope.launch {
            when (val result = readSms(text.trim())) {
                is SmsResult.NotAPayment -> setState { copy(stage = SmsStage.Paste, rejected = result.kind, pasteBox = "") }
                is SmsResult.Read -> {
                    val snapshot = observeFinance().first()
                    review(prepareSmsEntry(text.trim(), result.sms, snapshot), snapshot)
                }
            }
        }
    }

    private fun review(plan: SmsEntryPlan, snapshot: FinanceSnapshot) {
        val sms = plan.sms
        setState {
            copy(
                stage = SmsStage.Review,
                sms = sms,
                spans = sms.spans,
                header = header(sms, plan.date),
                type = plan.type,
                amount = FinanceFormat.amountInput(sms.amountMinor),
                merchant = plan.merchant.name ?: if (sms.payeeIsUpiId) "" else sms.payee.orEmpty(),
                needsName = plan.merchant.needsName,
                upiId = sms.payee?.takeIf { sms.payeeIsUpiId },
                date = plan.date,
                time = sms.time,
                accountUid = plan.account?.uid,
                unknownLast4 = plan.unknownLast4,
                categoryUid = plan.merchant.categoryUid,
                categorySuggested = plan.merchant.categoryUid != null,
                duplicate = plan.duplicate,
                duplicateAccount = plan.duplicate?.let { snapshot.accountsByUid[it.accountUid] }?.let(RecurringLabels::account),
            )
        }
        onData(snapshot)
    }

    /** Lists stay current; a card or account added for this SMS is picked as soon as it exists. */
    private fun onData(snapshot: FinanceSnapshot) {
        val accounts = snapshot.accounts.filterNot { it.archived }
        fun option(a: dev.kortex.finance.domain.model.Account) = AccountOption(a.uid, RecurringLabels.account(a), AccountsUi.subtitle(a, snapshot.accountsByUid))
        setState {
            val options = accounts.filter { type != SmsEntryType.INCOME || it.kind != AccountKind.CREDIT_CARD }.map(::option)
            val paying = accounts.filter { it.kind != AccountKind.CREDIT_CARD }.map(::option)
            val kind = if (type == SmsEntryType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
            val added = unknownLast4?.let { last4 -> EntryMatching.accountFor(last4, sms?.instrument ?: Instrument.UNKNOWN, accounts) }
            copy(
                accounts = options,
                payingAccounts = paying,
                categories = snapshot.categories.filter { it.kind == kind }.map { CategoryOption(it.uid, it.name, it.colorToken) },
                accountUid = added?.uid ?: accountUid?.takeIf { uid -> options.any { it.uid == uid } },
                unknownLast4 = if (added != null) null else unknownLast4,
                fromAccountUid = fromAccountUid?.takeIf { uid -> paying.any { it.uid == uid } }
                    ?: accounts.firstOrNull { it.kind == AccountKind.BANK }?.uid ?: paying.firstOrNull()?.uid,
            )
        }
    }

    private fun addCard() {
        val sms = currentState.sms ?: return
        val last4 = currentState.unknownLast4 ?: sms.last4
        val card = sms.isCreditCard
        val bankShort = sms.bank?.substringBefore(" ")
        sendEffect(
            SmsEntryEffect.Navigate(
                FinanceRoute.AddAccount(
                    kind = if (card) AccountKind.CREDIT_CARD else AccountKind.BANK,
                    prefill = AccountPrefill(
                        name = listOfNotNull(bankShort, last4?.let { "••$it" }).joinToString(" ").ifEmpty { null },
                        institution = sms.bank,
                        last4 = last4,
                        availableLimitMinor = sms.availableLimitMinor,
                    ),
                ),
            ),
        )
    }

    private fun save(asNew: Boolean) {
        val state = currentState
        val sms = state.sms ?: return
        if (state.saving) return
        if (state.type == SmsEntryType.CARD_REFUND) return
        val amount = FinanceFormat.parseAmount(state.amount) ?: return setState { copy(error = "Enter an amount above zero.") }
        val cardPayment = state.type == SmsEntryType.CARD_PAYMENT
        val account = (if (cardPayment) state.fromAccountUid else state.accountUid)
            ?: return setState { copy(error = if (cardPayment) "Pick the account the bill was paid from." else "Pick how you paid.") }
        if (cardPayment && state.accountUid == null) return setState { copy(error = "Pick the card that was paid.") }
        setState { copy(saving = true, error = null) }
        viewModelScope.launch {
            val snapshot = observeFinance().first()
            val name = state.merchant.trim().ifEmpty { state.upiId.orEmpty() }
            val result = addTransaction(
                TransactionDraft(
                    type = state.transactionType,
                    amountMinor = amount,
                    accountUid = account,
                    toAccountUid = state.accountUid.takeIf { cardPayment },
                    categoryUid = state.categoryUid.takeIf { !cardPayment },
                    merchant = name.takeIf { !cardPayment },
                    payeeKey = state.upiId,
                    note = state.note,
                    occurredAtMillis = clock.millisAt(state.date, state.time),
                    source = TransactionSource.SMS,
                    sourceRef = sms.ref,
                    statementUid = state.accountUid?.takeIf { cardPayment }?.let { Statements.latest(it, snapshot.statements)?.uid },
                    // The same SMS pasted twice, or on two phones, is one entry; "Add anyway" makes another.
                    uid = if (asNew) null else FinanceIds.smsTransaction("", state.text),
                ),
            )
            when (result) {
                is TransactionSaveResult.Saved -> {
                    inboxId?.let { resolveInboxSms.saved(it, result.uid) }
                    val where = name.takeIf { it.isNotEmpty() && !cardPayment }?.let { if (state.type == SmsEntryType.INCOME) " from $it" else " at $it" }.orEmpty()
                    notices.post(FinanceNotice("Saved ${FinanceFormat.rupees(amount)}$where", FinanceUndo.DeleteEntry(result.uid)))
                    sendEffect(SmsEntryEffect.Close)
                }
                is TransactionSaveResult.AlreadySaved -> {
                    // This SMS's own entry is already there: the received one is done with too.
                    inboxId?.let { resolveInboxSms.saved(it, result.uid) }
                    setState { copy(saving = false, duplicate = snapshot.transactions.find { it.uid == result.uid }, error = null) }
                }
                else -> setState { copy(saving = false, error = dev.kortex.finance.ui.entry.AddEntryState.message(result)) }
            }
        }
    }

    private fun header(sms: ParsedSms, date: LocalDate): String = listOfNotNull(
        (sms.bank ?: "Bank SMS").uppercase(),
        (FinanceFormat.day(date) + (sms.time?.let { ", " + it.format(Clock12) } ?: "")).uppercase(),
    ).joinToString(" · ")

    private companion object {
        val Clock12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}
