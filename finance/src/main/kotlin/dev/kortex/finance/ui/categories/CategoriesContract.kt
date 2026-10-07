package dev.kortex.finance.ui.categories

import dev.kortex.finance.domain.calc.Budgets
import dev.kortex.finance.domain.calc.Spending
import dev.kortex.finance.domain.calc.roundDiv
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.usecase.BudgetSaveResult
import dev.kortex.finance.domain.usecase.CategorySaveResult
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.BudgetProgressUi
import dev.kortex.finance.ui.common.FinanceColors
import dev.kortex.finance.ui.common.FinanceFormat
import java.time.LocalDate
import java.time.YearMonth

/**
 * [budget] is set when the category has one; then [subtitle] is "₹5,400 of ₹8,000 this month".
 * [opens] is false only for built-in income, which has nothing to edit.
 */
data class CategoryRowUi(
    val uid: String,
    val name: String,
    val subtitle: String,
    val colorToken: String,
    val builtIn: Boolean,
    val opens: Boolean = !builtIn,
    val budget: BudgetProgressUi? = null,
)

/** Figma: Categories 01, with budgets: Budgets · 01. */
data class CategoriesState(
    val loading: Boolean = true,
    val expenseBuiltIn: List<CategoryRowUi> = emptyList(),
    val expenseYours: List<CategoryRowUi> = emptyList(),
    val income: List<CategoryRowUi> = emptyList(),
)

sealed interface CategoriesIntent {
    data object New : CategoriesIntent
    data class Open(val uid: String) : CategoriesIntent
}

sealed interface CategoriesEffect {
    data class Navigate(val route: FinanceRoute) : CategoriesEffect
}

object CategoriesUi {
    /** What each built-in category covers, under its name. */
    private val builtInSubtitles = mapOf(
        BuiltInCategories.Food.uid to "Groceries, eating out, delivery",
        BuiltInCategories.Travel.uid to "Cabs, fuel, trains, flights",
        BuiltInCategories.Utilities.uid to "Electricity, internet, phone, gas",
        BuiltInCategories.Salary.uid to "Built in",
    )

    /** [budgets] maps a category's uid to its monthly amount; spend is [today]'s month so far. */
    fun build(snapshot: FinanceSnapshot, budgets: Map<String, Long>, today: LocalDate): CategoriesState {
        val counts = Spending.entriesPerCategory(snapshot.transactions)
        val budgeted = Budgets.status(snapshot.transactions, snapshot.categories, budgets, YearMonth.from(today), today)
            .categories.associate { it.categoryUid to BudgetProgressUi(it.spentMinor, it.budgetMinor) }
        fun row(category: Category): CategoryRowUi {
            val budget = budgeted[category.uid]
            return CategoryRowUi(
                uid = category.uid,
                name = category.name,
                subtitle = when {
                    budget != null -> "${budget.spent} of ${budget.budget} this month"
                    category.builtIn -> builtInSubtitles[category.uid] ?: "Built in"
                    else -> "Added by you · ${entries(counts[category.uid] ?: 0)}"
                },
                colorToken = category.colorToken,
                builtIn = category.builtIn,
                opens = !category.builtIn || category.kind == CategoryKind.EXPENSE,
                budget = budget,
            )
        }
        val sorted = snapshot.categories.sortedWith(compareBy({ !it.builtIn }, { it.sortOrder }, { it.name.lowercase() }))
        val expense = sorted.filter { it.kind == CategoryKind.EXPENSE }
        return CategoriesState(
            loading = false,
            expenseBuiltIn = expense.filter { it.builtIn }.map(::row),
            expenseYours = expense.filterNot { it.builtIn }.map(::row),
            income = sorted.filter { it.kind == CategoryKind.INCOME }.map(::row),
        )
    }

    fun entries(count: Int): String = "$count ${if (count == 1) "entry" else "entries"}"
}

/** One choice in Delete category: where its entries go. A null [uid] is Uncategorised. */
data class MoveTarget(val uid: String?, val title: String, val subtitle: String)

data class DeleteCategoryUi(val entryCount: Int, val targets: List<MoveTarget>, val selected: String? = null)

/** The "This month" card under the budget field (Figma: Budgets · 02–04). */
data class BudgetPreviewUi(
    val progress: BudgetProgressUi,
    val percentUsed: Int,
    /** "₹2,600 left" or "₹480 over". */
    val left: String,
    /** "Month ends near ₹5,586" */
    val projection: String,
)

/**
 * Figma: Categories 02 (New category) and editing one of yours; Categories 03 is [deleting].
 * Editing an expense category adds the Monthly budget field (Budgets · 03); a built-in expense
 * category shows only that field (Budgets · 02), and an invalid amount is Budgets · 04.
 */
data class CategoryFormState(
    val loading: Boolean = false,
    val editUid: String? = null,
    val builtIn: Boolean = false,
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val name: String = "",
    val colorToken: String = FinanceColors.categoryChoices.first(),
    /** The other categories of this kind, for the chip preview. */
    val siblings: List<Category> = emptyList(),
    /** The Monthly budget field as typed; empty is no budget. */
    val budgetText: String = "",
    val savedBudgetMinor: Long? = null,
    /** This category's spend this month, and what the month ends near at this pace. */
    val spentMinor: Long = 0,
    val projectedMinor: Long = 0,
    val deleting: DeleteCategoryUi? = null,
    val error: String? = null,
    val saving: Boolean = false,
) {
    val editing: Boolean get() = editUid != null
    val title: String get() = if (builtIn) name else if (editing) "Edit category" else "New category"
    val saveLabel: String get() = if (editing) "Save" else "Add category"
    val kindHint: String get() = if (kind == CategoryKind.EXPENSE) "Shows up when you add an expense." else "Shows up when you add income."

    /** New categories get their budget once they exist; income never has one. */
    val budgetable: Boolean get() = editing && kind == CategoryKind.EXPENSE

    /** The typed budget in paise; null when the field is empty or [budgetInvalid]. */
    val typedBudgetMinor: Long? get() = FinanceFormat.parseAmount(budgetText)

    /** Zero, more than two decimals or not a number: the field turns Alarm and Save is off. */
    val budgetInvalid: Boolean get() = budgetable && budgetText.isNotBlank() && typedBudgetMinor == null

    val canSave: Boolean get() = !saving && !loading && !budgetInvalid

    val budgetHelper: String
        get() = when {
            budgetInvalid -> "Enter an amount above ₹0, with at most two decimals. To stop budgeting $name, use Remove budget."
            builtIn -> "Resets on the 1st of each month."
            else -> "Optional. Leave empty for no budget. Resets on the 1st of each month."
        }

    /** The typed budget while it's valid, else the saved one; null hides the card. */
    val preview: BudgetPreviewUi?
        get() {
            val budgetMinor = (if (budgetInvalid) savedBudgetMinor else typedBudgetMinor) ?: return null
            val left = budgetMinor - spentMinor
            return BudgetPreviewUi(
                progress = BudgetProgressUi(spentMinor, budgetMinor),
                percentUsed = roundDiv(spentMinor * 100, budgetMinor).toInt(),
                left = if (left >= 0) "${FinanceFormat.rupees(left, paise = false)} left" else "${FinanceFormat.rupees(-left, paise = false)} over",
                projection = "Month ends near ${FinanceFormat.rupees(projectedMinor, paise = false)}",
            )
        }

    /** Under the card while the field is invalid: Budgets · 04. */
    val previewNote: String?
        get() = savedBudgetMinor?.takeIf { budgetInvalid }
            ?.let { "Showing the saved budget (${FinanceFormat.rupees(it, paise = false)}) until the new amount is valid." }

    companion object {
        fun message(result: CategorySaveResult): String? = when (result) {
            is CategorySaveResult.Saved -> null
            CategorySaveResult.BlankName -> "Give it a name."
            CategorySaveResult.NameTaken -> "You already have a category with this name."
            CategorySaveResult.BuiltIn -> "Built-in categories can’t be changed."
            CategorySaveResult.NotFound -> "This category was deleted."
        }

        fun message(result: BudgetSaveResult): String? = when (result) {
            BudgetSaveResult.Saved -> null
            BudgetSaveResult.InvalidAmount -> "Enter an amount above ₹0."
            BudgetSaveResult.NotExpense -> "Only expense categories can have a budget."
            BudgetSaveResult.NotFound -> "This category was deleted."
        }

        /** [category]'s spend this month and what it ends near; neither depends on the budget amount. */
        fun thisMonth(snapshot: FinanceSnapshot, category: Category, today: LocalDate): Pair<Long, Long> {
            val month = YearMonth.from(today)
            val row = Budgets.status(snapshot.transactions, listOf(category), mapOf(category.uid to 1L), month, today).categories.firstOrNull()
            return (row?.spentMinor ?: 0L) to (row?.projectedMinor ?: 0L)
        }

        /** Uncategorised first, then built-in, then yours, all of the same kind and never [uid] itself. */
        fun moveTargets(snapshot: FinanceSnapshot, uid: String, kind: CategoryKind): List<MoveTarget> {
            val counts = Spending.entriesPerCategory(snapshot.transactions)
            val others = snapshot.categories
                .filter { it.kind == kind && it.uid != uid }
                .sortedWith(compareBy({ !it.builtIn }, { it.sortOrder }, { it.name.lowercase() }))
                .map { MoveTarget(it.uid, it.name, if (it.builtIn) "Built in" else CategoriesUi.entries(counts[it.uid] ?: 0)) }
            return listOf(MoveTarget(null, "Uncategorised", "Sort them later from Daily view")) + others
        }
    }
}

sealed interface CategoryFormIntent {
    data class SelectKind(val kind: CategoryKind) : CategoryFormIntent
    data class Name(val text: String) : CategoryFormIntent
    data class Color(val token: String) : CategoryFormIntent
    data class Budget(val text: String) : CategoryFormIntent
    data object Save : CategoryFormIntent

    /** Saves with no budget, the same as saving an empty field. */
    data object RemoveBudget : CategoryFormIntent
    data object AskDelete : CategoryFormIntent
    data class PickTarget(val uid: String?) : CategoryFormIntent
    data object CancelDelete : CategoryFormIntent
    data object ConfirmDelete : CategoryFormIntent
}

sealed interface CategoryFormEffect {
    data object Close : CategoryFormEffect
}
