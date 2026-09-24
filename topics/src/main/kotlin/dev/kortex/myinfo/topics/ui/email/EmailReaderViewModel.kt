package dev.kortex.myinfo.topics.ui.email

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.mvi.MviViewModel
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.port.EmailAttachment
import dev.kortex.myinfo.topics.domain.port.EmailReadResult
import dev.kortex.myinfo.topics.domain.usecase.FetchEmailAttachment
import dev.kortex.myinfo.topics.domain.usecase.FetchedAttachment
import dev.kortex.myinfo.topics.domain.usecase.ReadEmail
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = EmailReaderViewModel.Factory::class)
class EmailReaderViewModel @AssistedInject constructor(
    @Assisted email: SavedEmail,
    private val readEmail: ReadEmail,
    private val fetchAttachment: FetchEmailAttachment,
) : MviViewModel<EmailReaderState, EmailReaderIntent, EmailReaderEffect>(EmailReaderState(email)) {

    @AssistedFactory
    interface Factory {
        fun create(email: SavedEmail): EmailReaderViewModel
    }

    private var reading: Job? = null

    init {
        read()
    }

    override fun handleIntent(intent: EmailReaderIntent) {
        when (intent) {
            EmailReaderIntent.Retry -> if (currentState.canRetry) read()
            EmailReaderIntent.OpenMailApp -> sendEffect(EmailReaderEffect.OpenMailApp)
            is EmailReaderIntent.OpenAttachment -> if (currentState.opening == null) open(intent.attachment)
        }
    }

    private fun open(attachment: EmailAttachment) {
        setState { copy(opening = attachment.id) }
        viewModelScope.launch {
            val fetched = fetchAttachment(currentState.email, attachment)
            setState { copy(opening = null) }
            when (fetched) {
                is FetchedAttachment.Ready -> sendEffect(EmailReaderEffect.OpenFile(fetched.file))
                is FetchedAttachment.Failed -> sendEffect(EmailReaderEffect.ShowMessage(fetched.message))
            }
        }
    }

    private fun read() {
        reading?.cancel()
        setState { copy(loading = true, problem = null) }
        reading = viewModelScope.launch {
            val result = readEmail(currentState.email)
            setState {
                when (result) {
                    is EmailReadResult.Read -> copy(message = result.message, loading = false)
                    EmailReadResult.NotConnected -> copy(loading = false, problem = EmailProblem.NotConnected)
                    EmailReadResult.Gone -> copy(loading = false, problem = EmailProblem.Gone)
                    is EmailReadResult.Failed -> copy(loading = false, problem = EmailProblem.Failed(result.message))
                }
            }
        }
    }
}
