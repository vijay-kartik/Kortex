package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlinx.coroutines.flow.first

sealed interface CategorySaveResult {
    data class Saved(val uid: String) : CategorySaveResult
    data object BlankName : CategorySaveResult

    /** Another category of the same kind already has this name, ignoring case. */
    data object NameTaken : CategorySaveResult

    /** Built-in categories can't be renamed, recoloured or deleted. */
    data object BuiltIn : CategorySaveResult
    data object NotFound : CategorySaveResult
}

/** Figma: Categories 02 (New category). */
class AddCategory(private val repository: FinanceRepository) {
    suspend operator fun invoke(name: String, kind: CategoryKind, colorToken: String): CategorySaveResult {
        val clean = name.trim().ifEmpty { return CategorySaveResult.BlankName }
        val existing = repository.observeCategories().first()
        if (existing.any { it.kind == kind && it.name.equals(clean, ignoreCase = true) }) return CategorySaveResult.NameTaken
        val category = Category(
            uid = FinanceIds.random(),
            name = clean,
            kind = kind,
            colorToken = colorToken,
            sortOrder = (existing.filter { it.kind == kind }.maxOfOrNull { it.sortOrder } ?: 0) + 1,
        )
        repository.addCategory(category)
        return CategorySaveResult.Saved(category.uid)
    }
}

/** Renames or recolours one of your categories; past entries follow, as they hold its uid. */
class UpdateCategory(private val repository: FinanceRepository) {
    suspend operator fun invoke(uid: String, name: String, colorToken: String): CategorySaveResult {
        val category = repository.getCategory(uid) ?: return CategorySaveResult.NotFound
        if (category.builtIn) return CategorySaveResult.BuiltIn
        val clean = name.trim().ifEmpty { return CategorySaveResult.BlankName }
        val taken = repository.observeCategories().first()
            .any { it.uid != uid && it.kind == category.kind && it.name.equals(clean, ignoreCase = true) }
        if (taken) return CategorySaveResult.NameTaken
        repository.updateCategory(category.copy(name = clean, colorToken = colorToken))
        return CategorySaveResult.Saved(uid)
    }
}

sealed interface CategoryDeleteResult {
    data object Deleted : CategoryDeleteResult
    data object BuiltIn : CategoryDeleteResult
    data object NotFound : CategoryDeleteResult

    /** Entries can only move to a different category of the same kind. */
    data object InvalidTarget : CategoryDeleteResult
}

/**
 * Figma: Categories 03. Deleting never deletes entries: they move to [moveTo], or to
 * Uncategorised when it's null.
 */
class DeleteCategory(private val repository: FinanceRepository) {
    suspend operator fun invoke(uid: String, moveTo: String?): CategoryDeleteResult {
        val category = repository.getCategory(uid) ?: return CategoryDeleteResult.NotFound
        if (category.builtIn) return CategoryDeleteResult.BuiltIn
        if (moveTo != null) {
            val target = repository.getCategory(moveTo)
            if (moveTo == uid || target == null || target.kind != category.kind) return CategoryDeleteResult.InvalidTarget
        }
        repository.deleteCategory(uid, moveTo)
        return CategoryDeleteResult.Deleted
    }
}
