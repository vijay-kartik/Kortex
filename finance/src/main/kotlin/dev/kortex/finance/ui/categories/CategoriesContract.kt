package dev.kortex.finance.ui.categories

import dev.kortex.finance.domain.calc.Spending
import dev.kortex.finance.domain.model.BuiltInCategories
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.usecase.CategorySaveResult
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceColors

data class CategoryRowUi(val uid: String, val name: String, val subtitle: String, val colorToken: String, val builtIn: Boolean)

/** Figma: Categories 01. */
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

    fun build(snapshot: FinanceSnapshot): CategoriesState {
        val counts = Spending.entriesPerCategory(snapshot.transactions)
        fun row(category: Category) = CategoryRowUi(
            uid = category.uid,
            name = category.name,
            subtitle = if (category.builtIn) builtInSubtitles[category.uid] ?: "Built in" else "Added by you · ${entries(counts[category.uid] ?: 0)}",
            colorToken = category.colorToken,
            builtIn = category.builtIn,
        )
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

/** Figma: Categories 02 (New category) and editing one of yours; Categories 03 is [deleting]. */
data class CategoryFormState(
    val loading: Boolean = false,
    val editUid: String? = null,
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val name: String = "",
    val colorToken: String = FinanceColors.categoryChoices.first(),
    /** The other categories of this kind, for the chip preview. */
    val siblings: List<Category> = emptyList(),
    val deleting: DeleteCategoryUi? = null,
    val error: String? = null,
    val saving: Boolean = false,
) {
    val editing: Boolean get() = editUid != null
    val title: String get() = if (editing) "Edit category" else "New category"
    val saveLabel: String get() = if (editing) "Save category" else "Add category"
    val kindHint: String get() = if (kind == CategoryKind.EXPENSE) "Shows up when you add an expense." else "Shows up when you add income."

    companion object {
        fun message(result: CategorySaveResult): String? = when (result) {
            is CategorySaveResult.Saved -> null
            CategorySaveResult.BlankName -> "Give it a name."
            CategorySaveResult.NameTaken -> "You already have a category with this name."
            CategorySaveResult.BuiltIn -> "Built-in categories can’t be changed."
            CategorySaveResult.NotFound -> "This category was deleted."
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
    data object Save : CategoryFormIntent
    data object AskDelete : CategoryFormIntent
    data class PickTarget(val uid: String?) : CategoryFormIntent
    data object CancelDelete : CategoryFormIntent
    data object ConfirmDelete : CategoryFormIntent
}

sealed interface CategoryFormEffect {
    data object Close : CategoryFormEffect
}
