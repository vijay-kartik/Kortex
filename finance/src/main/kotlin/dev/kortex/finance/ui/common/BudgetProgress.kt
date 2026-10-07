package dev.kortex.finance.ui.common

import androidx.compose.ui.graphics.Color
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Synapse

/** How far into its budget a category is (Figma: Budgets · 01). */
enum class BudgetLevel {
    /** Under 80 %: Synapse. */
    UNDER,

    /** 80–100 %: the bar and the spent amount turn Amber. */
    NEAR,

    /** Past 100 %: the bar fills and the bar and the spent amount turn Alarm. */
    OVER;

    val color: Color
        get() = when (this) {
            UNDER -> Synapse
            NEAR -> Amber
            OVER -> Alarm
        }

    companion object {
        fun of(spentMinor: Long, budgetMinor: Long): BudgetLevel = when {
            spentMinor > budgetMinor -> OVER
            spentMinor * 100 >= budgetMinor * 80 -> NEAR
            else -> UNDER
        }
    }
}

/** A budgeted category's month as a bar: "₹5,400 of ₹8,000". */
data class BudgetProgressUi(val spentMinor: Long, val budgetMinor: Long) {
    val level: BudgetLevel get() = BudgetLevel.of(spentMinor, budgetMinor)

    /** How much of the track to fill; full once over budget. */
    val fraction: Float get() = (spentMinor.toFloat() / budgetMinor).coerceIn(0f, 1f)

    val spent: String get() = FinanceFormat.rupees(spentMinor, paise = false)
    val budget: String get() = FinanceFormat.rupees(budgetMinor, paise = false)
}
