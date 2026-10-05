package dev.kortex.finance.ui

import androidx.annotation.DrawableRes
import dev.kortex.finance.R

/**
 * The four Finance tabs on the floating bottom bar (Figma: Finances/BottomBar). [title] is what the
 * home top bar shows while the tab is open.
 */
enum class FinanceSection(val label: String, val title: String, @DrawableRes val icon: Int) {
    Dashboard("Dashboard", "Finances", R.drawable.ic_fin_dashboard),
    Expenses("Expenses", "Expenses", R.drawable.ic_fin_expenses),
    Accounts("Accounts", "Accounts", R.drawable.ic_fin_wallet),
    Cards("Cards", "Credit Cards", R.drawable.ic_fin_card),
}
