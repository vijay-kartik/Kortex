package dev.kortex.finance.ui.common

import androidx.compose.ui.graphics.Color
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.design.Teal

/** Money in, savings, gains (Theme › Data colours: Growth). */
val Growth = Color(0xFF4ADE80)

/**
 * Data colours (Figma: Theme › Data colours) by token name, as categories store them. Only for
 * charts, categories and income; anything unknown reads as Muted.
 */
object FinanceColors {
    private val byToken = mapOf(
        "Synapse" to Synapse,
        "Teal" to Teal,
        "Amber" to Amber,
        "Growth" to Growth,
        "Lilac" to Color(0xFFC792EA),
        "Rose" to Color(0xFFF28FB3),
        "Mint" to Color(0xFF8FD18F),
        "Sky" to Color(0xFF5EC2E8),
        "Alarm" to Alarm,
    )

    fun of(token: String?): Color = token?.let(byToken::get) ?: Muted

    /** The swatches New category offers, in order. */
    val categoryChoices: List<String> = listOf("Synapse", "Teal", "Amber", "Lilac", "Rose", "Mint", "Sky")
}
