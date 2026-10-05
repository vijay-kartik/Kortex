package dev.kortex.finance.domain.calc

/** [numerator] ÷ [denominator] rounded half away from zero. */
internal fun roundDiv(numerator: Long, denominator: Long): Long {
    require(denominator > 0) { "denominator must be positive" }
    return if (numerator >= 0) (numerator + denominator / 2) / denominator else -((-numerator + denominator / 2) / denominator)
}

/**
 * Whole percentages of [amounts] that add up to exactly 100 (largest remainder), so a split bar's
 * labels never read 99% or 101%. All zeros when the amounts sum to zero.
 */
fun roundedPercents(amounts: List<Long>): List<Int> {
    val total = amounts.sum()
    if (total <= 0) return amounts.map { 0 }
    val exact = amounts.map { it * 100.0 / total }
    val floors = exact.map { it.toInt() }.toMutableList()
    val short = 100 - floors.sum()
    exact.indices
        .sortedByDescending { exact[it] - floors[it] }
        .take(short)
        .forEach { floors[it] += 1 }
    return floors
}
