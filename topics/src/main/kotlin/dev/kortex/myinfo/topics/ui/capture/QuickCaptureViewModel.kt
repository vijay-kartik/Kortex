package dev.kortex.myinfo.topics.ui.capture

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.mvi.MviViewModel
import dev.kortex.myinfo.topics.di.TopicsScope
import dev.kortex.myinfo.topics.domain.model.BillFields
import dev.kortex.myinfo.topics.domain.model.CaptureDraft
import dev.kortex.myinfo.topics.domain.model.CaptureResult
import dev.kortex.myinfo.topics.domain.model.CaptureTarget
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.PickedFile
import dev.kortex.myinfo.topics.domain.model.TopicSort
import dev.kortex.myinfo.topics.domain.model.sortedFor
import dev.kortex.myinfo.topics.domain.port.EmailSearchResult
import dev.kortex.myinfo.topics.domain.usecase.CaptureItem
import dev.kortex.myinfo.topics.domain.usecase.DetectItemType
import dev.kortex.myinfo.topics.domain.usecase.DiscardPickedFile
import dev.kortex.myinfo.topics.domain.usecase.KeepPickedFile
import dev.kortex.myinfo.topics.domain.usecase.LookUpLink
import dev.kortex.myinfo.topics.domain.usecase.MoneyAmount
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopics
import dev.kortex.myinfo.topics.domain.usecase.SearchEmails
import dev.kortex.myinfo.topics.ui.common.TopicChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = QuickCaptureViewModel.Factory::class)
class QuickCaptureViewModel @AssistedInject constructor(
    @Assisted initialTopicId: Long,
    @TopicsScope private val appScope: CoroutineScope,
    observeTopics: ObserveTopics,
    private val detectItemType: DetectItemType,
    private val lookUpLink: LookUpLink,
    private val keepPickedFile: KeepPickedFile,
    private val searchEmails: SearchEmails,
    private val discardPickedFile: DiscardPickedFile,
    private val captureItem: CaptureItem,
) : MviViewModel<QuickCaptureState, QuickCaptureIntent, QuickCaptureEffect>(QuickCaptureState(selectedTopicId = initialTopicId)) {

    @AssistedFactory
    interface Factory {
        fun create(initialTopicId: Long): QuickCaptureViewModel
    }

    private var lookupJob: Job? = null
    private var emailSearchJob: Job? = null

    /** A kept file belongs to the topic once it is saved; until then this sheet has to clean it up. */
    private var savedFile: PickedFile? = null

    init {
        // Same order as the topics list, so the topic the user works in most is near the front.
        observeTopics().reduceInto { overviews ->
            copy(
                topics = overviews.sortedFor(TopicSort.Recent).map { TopicChoice(it.topic.id, it.topic.name) },
                selectedTopicId = selectedTopicId?.takeIf { id -> overviews.any { it.topic.id == id } },
            )
        }
    }

    override fun handleIntent(intent: QuickCaptureIntent) {
        when (intent) {
            is QuickCaptureIntent.TextChanged -> onTextChanged(intent.text)
            is QuickCaptureIntent.ChooseType -> setState { copy(chosenType = intent.type, error = null) }
            is QuickCaptureIntent.AttachFile -> attach(intent.uri)
            QuickCaptureIntent.RemoveFile -> removeFile()
            QuickCaptureIntent.StartPickingEmail -> setState {
                copy(pickingEmail = true, emailError = null, emailNotConnected = false)
            }
            QuickCaptureIntent.CancelPickingEmail -> setState { copy(pickingEmail = false) }
            is QuickCaptureIntent.EmailQueryChanged -> onEmailQueryChanged(intent.query)
            is QuickCaptureIntent.PickEmail -> setState {
                // An email replaces whatever was typed: it is the whole item.
                copy(email = intent.email, pickingEmail = false, chosenType = null, error = null)
            }
            QuickCaptureIntent.RemoveEmail -> setState { copy(email = null, chosenType = null, error = null) }
            QuickCaptureIntent.BillAmountEdited -> setState {
                copy(error = error?.takeUnless { it == CaptureError.BillAmountInvalid || it == CaptureError.BillTitleBlank })
            }
            is QuickCaptureIntent.ChooseCurrency -> setState { copy(billCurrency = intent.currency, error = null) }
            QuickCaptureIntent.OpenDueDate -> setState { copy(pickingDueDate = true) }
            QuickCaptureIntent.CloseDueDate -> setState { copy(pickingDueDate = false) }
            is QuickCaptureIntent.SetDueDate -> setState { copy(billDueAtMillis = intent.atMillis, pickingDueDate = false) }
            is QuickCaptureIntent.SetPaid -> setState { copy(billPaid = intent.paid) }
            // Every error is about the old target: the item already in it, or the new topic's name.
            is QuickCaptureIntent.SelectTopic -> setState { copy(selectedTopicId = intent.topicId, creatingTopic = false, error = null) }
            QuickCaptureIntent.StartNewTopic -> setState { copy(creatingTopic = true, error = null) }
            QuickCaptureIntent.NewTopicNameEdited -> setState {
                copy(error = error?.takeUnless { it == CaptureError.NewTopicNameBlank || it == CaptureError.NewTopicNameTaken })
            }
            is QuickCaptureIntent.Save -> save(intent)
        }
    }

    /** The sheet went away without saving, so the file it kept has nobody to belong to. */
    override fun onCleared() {
        super.onCleared()
        val orphan = currentState.file?.takeIf { it != savedFile } ?: return
        // Not viewModelScope: that is already cancelled by the time this runs.
        appScope.launch { discardPickedFile(orphan) }
    }

    private fun onTextChanged(text: String) {
        val detection = detectItemType(text)
        val addressChanged = detection.url != currentState.detection.url
        setState {
            copy(
                detection = detection,
                blank = text.isBlank(),
                error = error?.takeUnless { it == CaptureError.AlreadyInTopic || it == CaptureError.BillTitleBlank },
                lookup = if (addressChanged) null else lookup,
                lookingUp = if (addressChanged) detection.url != null else lookingUp,
            )
        }
        if (!addressChanged) return
        lookupJob?.cancel()
        val url = detection.url ?: return
        lookupJob = viewModelScope.launch {
            // Typing an address shouldn't read a page per keystroke.
            delay(LOOKUP_DEBOUNCE_MS)
            val lookup = lookUpLink(url)
            setState { if (detection.url == url) copy(lookup = lookup, lookingUp = false) else this }
        }
    }

    /** Copies the picked file in, replacing whatever was attached before. */
    private fun attach(uri: String) {
        val previous = currentState.file
        setState { copy(attaching = true, error = null) }
        viewModelScope.launch {
            val kept = keepPickedFile(uri)
            previous?.let { discardPickedFile(it) }
            setState {
                copy(
                    file = kept,
                    attaching = false,
                    // The old file's type no longer applies to the new one.
                    chosenType = null,
                    error = if (kept == null) CaptureError.FileUnreadable else null,
                )
            }
        }
    }

    /** Asks the mailbox once typing pauses, so a query isn't sent per keystroke. */
    private fun onEmailQueryChanged(query: String) {
        emailSearchJob?.cancel()
        if (query.isBlank()) {
            setState { copy(emailResults = emptyList(), emailSearching = false, emailError = null) }
            return
        }
        setState { copy(emailSearching = true, emailError = null, emailNotConnected = false) }
        emailSearchJob = viewModelScope.launch {
            delay(EMAIL_DEBOUNCE_MS)
            when (val result = searchEmails(query)) {
                is EmailSearchResult.Found -> setState { copy(emailResults = result.emails, emailSearching = false) }
                EmailSearchResult.NotConnected -> setState {
                    copy(emailResults = emptyList(), emailSearching = false, emailNotConnected = true)
                }
                is EmailSearchResult.Failed -> setState {
                    copy(emailResults = emptyList(), emailSearching = false, emailError = result.message)
                }
            }
        }
    }

    private fun removeFile() {
        val file = currentState.file ?: return
        setState { copy(file = null, chosenType = null, error = null) }
        viewModelScope.launch { discardPickedFile(file) }
    }

    private fun save(intent: QuickCaptureIntent.Save) {
        val state = currentState
        if (!state.canSave) return
        val newName = intent.newTopicName.trim()
        val target = if (state.creatingTopic) CaptureTarget.New(newName) else CaptureTarget.Existing(checkNotNull(state.selectedTopicId))
        val draft = CaptureDraft(
            type = state.type,
            text = intent.text,
            title = intent.title.trim(),
            file = state.file,
            email = state.email,
            bill = if (state.type == ItemType.Bill) {
                BillFields(
                    amount = intent.billAmount,
                    currency = state.billCurrency,
                    dueAtMillis = state.billDueAtMillis,
                    paid = state.billPaid,
                )
            } else {
                null
            },
        )
        setState { copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = captureItem(draft, target)) {
                // Stays saving: the sheet is on its way out, and a second tap mustn't save twice.
                is CaptureResult.Saved -> {
                    // The topic owns the file now, so the sheet mustn't delete it on the way out.
                    savedFile = state.file
                    val name = if (state.creatingTopic) newName else state.topics.firstOrNull { it.id == result.topicId }?.name.orEmpty()
                    sendEffect(QuickCaptureEffect.Saved(result.topicId, name))
                }
                CaptureResult.Invalid -> setState { copy(saving = false) }
                CaptureResult.AlreadyInTopic -> setState { copy(saving = false, error = CaptureError.AlreadyInTopic) }
                CaptureResult.BillTitleBlank -> setState { copy(saving = false, error = CaptureError.BillTitleBlank) }
                CaptureResult.BillAmountInvalid -> setState { copy(saving = false, error = CaptureError.BillAmountInvalid) }
                CaptureResult.NewTopicNameBlank -> setState { copy(saving = false, error = CaptureError.NewTopicNameBlank) }
                CaptureResult.NewTopicNameTaken -> setState { copy(saving = false, error = CaptureError.NewTopicNameTaken) }
            }
        }
    }

    companion object {
        private const val LOOKUP_DEBOUNCE_MS = 400L

        /** A mailbox search is a network round-trip, so it waits a little longer. */
        private const val EMAIL_DEBOUNCE_MS = 450L

        /** Offered next to the amount, on top of whatever this device's region uses. */
        val CommonCurrencies: List<String> = listOf("USD", "EUR", "GBP", "INR", "AED")

        /** The device's currency first, then the common ones it isn't already. */
        fun currencyChoices(current: String): List<String> =
            (listOf(current) + MoneyAmount.defaultCurrency() + CommonCurrencies).distinct()
    }
}
