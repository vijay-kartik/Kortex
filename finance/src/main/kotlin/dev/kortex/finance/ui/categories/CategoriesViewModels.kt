package dev.kortex.finance.ui.categories

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.AddCategory
import dev.kortex.finance.domain.usecase.CategoryDeleteResult
import dev.kortex.finance.domain.usecase.CategorySaveResult
import dev.kortex.finance.domain.usecase.ClearBudget
import dev.kortex.finance.domain.usecase.DeleteCategory
import dev.kortex.finance.domain.usecase.ObserveBudgets
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.SetBudget
import dev.kortex.finance.domain.usecase.UpdateCategory
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    observeFinance: ObserveFinance,
    observeBudgets: ObserveBudgets,
    clock: Clock,
) : MviViewModel<CategoriesState, CategoriesIntent, CategoriesEffect>(CategoriesState()) {

    init {
        combine(observeFinance(), observeBudgets()) { snapshot, budgets -> CategoriesUi.build(snapshot, budgets, clock.today()) }
            .reduceInto { it }
    }

    override fun handleIntent(intent: CategoriesIntent) {
        val route = when (intent) {
            CategoriesIntent.New -> FinanceRoute.CategoryForm()
            is CategoriesIntent.Open -> {
                val row = (currentState.expenseBuiltIn + currentState.expenseYours + currentState.income)
                    .find { it.uid == intent.uid && it.opens } ?: return
                FinanceRoute.CategoryForm(uid = row.uid)
            }
        }
        sendEffect(CategoriesEffect.Navigate(route))
    }
}

@HiltViewModel
class CategoryFormViewModel @Inject constructor(
    private val observeFinance: ObserveFinance,
    private val addCategory: AddCategory,
    private val updateCategory: UpdateCategory,
    private val deleteCategory: DeleteCategory,
    private val observeBudgets: ObserveBudgets,
    private val setBudget: SetBudget,
    private val clearBudget: ClearBudget,
    private val clock: Clock,
    private val notices: FinanceNotices,
) : MviViewModel<CategoryFormState, CategoryFormIntent, CategoryFormEffect>(CategoryFormState()) {

    private var started = false

    /**
     * The route calls this once: [editUid] to edit one of yours or budget a built-in expense
     * category, else a new category of [kind].
     */
    fun start(editUid: String?, kind: CategoryKind) {
        if (started) return
        started = true
        setState { copy(loading = true, kind = kind) }
        viewModelScope.launch {
            combine(observeFinance(), observeBudgets(), ::Pair).collect { (snapshot, budgets) ->
                if (editUid == null) {
                    setState { copy(loading = false, siblings = snapshot.categories.filter { it.kind == this.kind }) }
                    return@collect
                }
                val category = snapshot.categoriesByUid[editUid]
                if (category == null || (category.builtIn && category.kind != CategoryKind.EXPENSE)) {
                    // Deleted (here or on another device), or not editable: nothing to show.
                    if (!currentState.saving) sendEffect(CategoryFormEffect.Close)
                    return@collect
                }
                val saved = budgets[editUid]
                val (spent, projected) = CategoryFormState.thisMonth(snapshot, category, clock.today())
                setState {
                    if (loading) {
                        copy(
                            loading = false,
                            editUid = editUid,
                            builtIn = category.builtIn,
                            kind = category.kind,
                            name = category.name,
                            colorToken = category.colorToken,
                            budgetText = saved?.let(FinanceFormat::amountInput).orEmpty(),
                        )
                    } else {
                        this
                    }.copy(
                        siblings = snapshot.categories.filter { it.kind == category.kind && it.uid != editUid },
                        savedBudgetMinor = saved,
                        spentMinor = spent,
                        projectedMinor = projected,
                    )
                }
            }
        }
    }

    override fun handleIntent(intent: CategoryFormIntent) {
        when (intent) {
            is CategoryFormIntent.SelectKind -> if (!currentState.editing && currentState.kind != intent.kind) {
                setState { copy(kind = intent.kind, error = null) }
                viewModelScope.launch {
                    val categories = observeFinance().first().categories
                    setState { copy(siblings = categories.filter { it.kind == kind }) }
                }
            }
            is CategoryFormIntent.Name -> setState { copy(name = intent.text.take(MAX_NAME), error = null) }
            is CategoryFormIntent.Color -> setState { copy(colorToken = intent.token) }
            is CategoryFormIntent.Budget -> setState { copy(budgetText = intent.text.take(MAX_AMOUNT), error = null) }
            CategoryFormIntent.Save -> save()
            CategoryFormIntent.RemoveBudget -> if (!currentState.saving) {
                setState { copy(budgetText = "") }
                save()
            }
            CategoryFormIntent.AskDelete -> askDelete()
            is CategoryFormIntent.PickTarget -> setState { copy(deleting = deleting?.copy(selected = intent.uid)) }
            CategoryFormIntent.CancelDelete -> setState { copy(deleting = null) }
            CategoryFormIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private fun save() {
        val state = currentState
        if (!state.canSave) return
        setState { copy(saving = true) }
        viewModelScope.launch {
            val uid = state.editUid
            if (!state.builtIn) {
                val result = if (uid == null) addCategory(state.name, state.kind, state.colorToken) else updateCategory(uid, state.name, state.colorToken)
                if (result !is CategorySaveResult.Saved) {
                    setState { copy(saving = false, error = CategoryFormState.message(result)) }
                    return@launch
                }
            }
            if (uid != null && state.budgetable) {
                val budget = state.typedBudgetMinor
                when {
                    budget == state.savedBudgetMinor -> Unit
                    budget == null -> clearBudget(uid)
                    else -> CategoryFormState.message(setBudget(uid, budget))?.let { error ->
                        setState { copy(saving = false, error = error) }
                        return@launch
                    }
                }
            }
            val notice = when {
                uid == null -> "Added ${state.name.trim()}"
                state.builtIn && state.typedBudgetMinor == null -> "Removed the budget for ${state.name}"
                state.builtIn -> "Saved the budget for ${state.name}"
                else -> "Saved ${state.name.trim()}"
            }
            notices.post(FinanceNotice(notice))
            sendEffect(CategoryFormEffect.Close)
        }
    }

    private fun askDelete() {
        val uid = currentState.editUid ?: return
        viewModelScope.launch {
            val snapshot = observeFinance().first()
            val count = snapshot.transactions.count { it.categoryUid == uid }
            setState { copy(deleting = DeleteCategoryUi(count, CategoryFormState.moveTargets(snapshot, uid, kind))) }
        }
    }

    private fun confirmDelete() {
        val state = currentState
        val uid = state.editUid ?: return
        val deleting = state.deleting ?: return
        if (state.saving) return
        setState { copy(saving = true) }
        viewModelScope.launch {
            when (deleteCategory(uid, deleting.selected)) {
                CategoryDeleteResult.Deleted, CategoryDeleteResult.NotFound -> {
                    notices.post(FinanceNotice("Deleted ${state.name.trim()}"))
                    sendEffect(CategoryFormEffect.Close)
                }
                CategoryDeleteResult.InvalidTarget -> setState {
                    copy(saving = false, deleting = deleting.copy(selected = null), error = "That category is gone; pick another.")
                }
                CategoryDeleteResult.BuiltIn -> sendEffect(CategoryFormEffect.Close)
            }
        }
    }

    private companion object {
        const val MAX_NAME = 32
        const val MAX_AMOUNT = 16
    }
}
