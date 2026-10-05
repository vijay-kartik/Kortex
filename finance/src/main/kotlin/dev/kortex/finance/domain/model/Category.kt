package dev.kortex.finance.domain.model

enum class CategoryKind { EXPENSE, INCOME }

/** Figma: Categories 01–03. [colorToken] names a Theme colour (Synapse, Teal, Amber, Growth, Lilac, Rose, Mint, Sky). */
data class Category(
    val uid: String,
    val name: String,
    val kind: CategoryKind,
    val colorToken: String,
    /** Can't be renamed, recoloured or deleted. */
    val builtIn: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * The four categories every account starts with. Their uids are fixed, so every device seeds the
 * same rows and none of them is ever synced.
 */
object BuiltInCategories {
    val Food = Category("food", "Food", CategoryKind.EXPENSE, "Synapse", builtIn = true, sortOrder = 0)
    val Travel = Category("travel", "Travel", CategoryKind.EXPENSE, "Teal", builtIn = true, sortOrder = 1)
    val Utilities = Category("utilities", "Utilities", CategoryKind.EXPENSE, "Amber", builtIn = true, sortOrder = 2)
    val Salary = Category("salary", "Salary", CategoryKind.INCOME, "Growth", builtIn = true, sortOrder = 0)

    val all: List<Category> = listOf(Food, Travel, Utilities, Salary)

    fun isBuiltIn(uid: String): Boolean = all.any { it.uid == uid }
}
