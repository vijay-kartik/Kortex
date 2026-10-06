package dev.kortex.finance.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.usecase.DeleteTransaction
import dev.kortex.finance.domain.usecase.ResolveInboxSms
import dev.kortex.finance.domain.usecase.UndoOccurrence
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Shows the latest [FinanceNotice] with its Undo. Each Finance screen a sheet can close onto has
 * one, so the notice appears wherever the person lands (Figma: Recurring 05 on Pending payments).
 */
@Composable
fun FinanceNoticeHost(modifier: Modifier = Modifier, viewModel: FinanceNoticeViewModel = hiltViewModel()) {
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.notices.notices.collect { notice ->
            viewModel.notices.consumed()
            val result = snackbars.showSnackbar(
                message = notice.message,
                actionLabel = notice.undo?.let { "UNDO" },
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) notice.undo?.let(viewModel::undo)
        }
    }
    SnackbarHost(snackbars, modifier)
}

@HiltViewModel
class FinanceNoticeViewModel @Inject constructor(
    val notices: FinanceNotices,
    private val deleteTransaction: DeleteTransaction,
    private val undoOccurrence: UndoOccurrence,
    private val resolveInboxSms: ResolveInboxSms,
) : ViewModel() {
    fun undo(undo: FinanceUndo) {
        viewModelScope.launch {
            when (undo) {
                is FinanceUndo.DeleteEntry -> deleteTransaction(undo.transactionUid)
                is FinanceUndo.RestoreRecurring -> undoOccurrence(undo.previous, undo.paymentUid)
                is FinanceUndo.RestoreInboxSms -> resolveInboxSms.restore(undo.previous)
            }
        }
    }
}
