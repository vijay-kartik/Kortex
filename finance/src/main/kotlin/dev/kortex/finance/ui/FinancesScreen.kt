package dev.kortex.finance.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.usecase.RunFinanceEngine
import dev.kortex.finance.ui.accounts.AccountFormRoute
import dev.kortex.finance.ui.accounts.AccountsRoute
import dev.kortex.finance.ui.accounts.CardsRoute
import dev.kortex.finance.ui.categories.CategoriesRoute
import dev.kortex.finance.ui.categories.CategoryFormRoute
import dev.kortex.finance.ui.common.FinanceBottomBar
import dev.kortex.finance.ui.common.FinanceNoticeHost
import dev.kortex.finance.ui.dashboard.DashboardRoute
import dev.kortex.finance.ui.entry.AddEntryRoute
import dev.kortex.finance.ui.expenses.ExpensesRoute
import dev.kortex.finance.ui.expenses.MonthlyReportRoute
import dev.kortex.finance.ui.pending.PendingRoute
import dev.kortex.finance.ui.read.ReceiptEntryRoute
import dev.kortex.finance.ui.read.SmsEntryRoute
import dev.kortex.finance.ui.recurring.MarkPaidRoute
import dev.kortex.finance.ui.recurring.PayBillRoute
import dev.kortex.finance.ui.recurring.RecurringFormRoute
import dev.kortex.finance.ui.recurring.RecurringListRoute
import dev.kortex.mvi.ScopedViewModelStore
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The Finances tab (Figma: Kortex - Finances). The home top bar shows [FinanceSection.title]; this
 * draws the open section, the floating bottom bar and the Undo snackbar. Sheets and pushed screens
 * open through [onNavigate], which the host shows with [FinanceOverlay].
 */
@Composable
fun FinancesScreen(
    section: FinanceSection,
    onSectionChange: (FinanceSection) -> Unit,
    onNavigate: (FinanceRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FinanceHomeViewModel = hiltViewModel(),
) {
    val scroll = remember { BarVisibility() }
    // Each time the tab opens: due statements and auto-debits are written before anything reads them.
    LaunchedEffect(Unit) { viewModel.runEngine() }
    Box(modifier.fillMaxSize().nestedScroll(scroll)) {
        // Keyed so each section starts at its top and the bar shows again.
        key(section) {
            LaunchedEffect(Unit) { scroll.visible = true }
            when (section) {
                FinanceSection.Dashboard -> DashboardRoute(onNavigate)
                FinanceSection.Expenses -> ExpensesRoute(onNavigate)
                FinanceSection.Accounts -> AccountsRoute(onNavigate)
                FinanceSection.Cards -> CardsRoute(onNavigate)
            }
        }
        FinanceNoticeHost(Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp, start = 16.dp, end = 16.dp))
        FinanceBottomBar(
            selected = section,
            onSelect = onSectionChange,
            visible = scroll.visible,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The stacked Finance screens over the tabs, top one showing. Screens underneath keep their
 * ViewModels (and so what was typed) while something opens over them.
 */
@Composable
fun FinanceOverlay(
    stack: List<FinanceRoute>,
    onNavigate: (FinanceRoute) -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        stack.forEachIndexed { index, route ->
            key(index, route.encode()) {
                ScopedViewModelStore(key = "finance/$index/${route.encode()}") {
                    if (index == stack.lastIndex) FinanceRouteContent(route, onNavigate, onBack)
                }
            }
        }
    }
}

@Composable
private fun FinanceRouteContent(route: FinanceRoute, onNavigate: (FinanceRoute) -> Unit, onBack: () -> Unit) {
    when (route) {
        is FinanceRoute.AddEntry -> AddEntryRoute(income = route.income, onNavigate = onNavigate, onClose = onBack)
        is FinanceRoute.AddAccount -> AccountFormRoute(editUid = null, initialKind = route.kind, onClose = onBack, prefill = route.prefill)
        is FinanceRoute.EditAccount -> AccountFormRoute(editUid = route.uid, initialKind = null, onClose = onBack)
        is FinanceRoute.MonthlyReport -> MonthlyReportRoute(month = route.month, onBack = onBack)
        FinanceRoute.Categories -> CategoriesRoute(onNavigate = onNavigate, onBack = onBack)
        is FinanceRoute.CategoryForm -> CategoryFormRoute(uid = route.uid, kind = route.kind, onClose = onBack)
        FinanceRoute.Pending -> PendingRoute(onNavigate = onNavigate, onBack = onBack)
        FinanceRoute.Recurring -> RecurringListRoute(onNavigate = onNavigate, onBack = onBack)
        is FinanceRoute.RecurringForm -> RecurringFormRoute(uid = route.uid, onNavigate = onNavigate, onClose = onBack)
        is FinanceRoute.MarkPaid -> MarkPaidRoute(recurringUid = route.recurringUid, dueOn = route.dueOn, onClose = onBack)
        is FinanceRoute.PayBill -> PayBillRoute(statementUid = route.statementUid, onClose = onBack)
        is FinanceRoute.PasteSms -> SmsEntryRoute(text = route.text, onNavigate = onNavigate, onClose = onBack)
        FinanceRoute.ScanReceipt -> ReceiptEntryRoute(onNavigate = onNavigate, onClose = onBack)
    }
}

@HiltViewModel
class FinanceHomeViewModel @Inject constructor(
    private val engine: RunFinanceEngine,
) : ViewModel() {
    fun runEngine() {
        viewModelScope.launch { engine() }
    }
}

/** Hides the bottom bar while content scrolls down and brings it back on any scroll up. */
private class BarVisibility : NestedScrollConnection {
    var visible by mutableStateOf(true)

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y < -1f) visible = false else if (consumed.y > 1f) visible = true
        return Offset.Zero
    }
}
