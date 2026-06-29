package dev.kortex.core.tool.builtin

import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.string
import dev.kortex.core.tool.tool

/**
 * A real, side-effect-free calculator (pattern 5: Tool Use). LLMs are unreliable at
 * arithmetic, so we give them a deterministic tool instead. Pure Kotlin — no Android,
 * no network — so it lives in core-agent and is unit-testable.
 */
fun calculatorTool(): Tool = tool(
    name = "calculator",
    description = "Evaluate an arithmetic expression and return the numeric result. " +
        "Supports + - * / %, ^ (power), parentheses, unary minus, and decimals. " +
        "Example expression: '2 * (3 + 4) ^ 2'.",
) {
    param("expression", "string", "The arithmetic expression to evaluate.")
    risk(RiskLevel.LOW)
    execute { args ->
        runCatching { ExpressionEvaluator.eval(args.string("expression")) }
            .fold(
                onSuccess = { ToolResult(true, formatResult(it)) },
                onFailure = { ToolResult(false, "Could not evaluate: ${it.message}") },
            )
    }
}

private fun formatResult(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

/**
 * Recursive-descent evaluator. Grammar (lowest to highest precedence):
 *   expr   := term  (('+' | '-') term)*
 *   term   := power (('*' | '/' | '%') power)*
 *   power  := unary ('^' power)?            // right-associative
 *   unary  := ('+' | '-') unary | primary
 *   primary:= number | '(' expr ')'
 */
internal object ExpressionEvaluator {
    fun eval(input: String): Double {
        val p = Parser(input)
        val v = p.parseExpr()
        p.expectEnd()
        return v
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseExpr(): Double {
            var acc = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { next(); acc += parseTerm() }
                    '-' -> { next(); acc -= parseTerm() }
                    else -> return acc
                }
            }
        }

        private fun parseTerm(): Double {
            var acc = parsePower()
            while (true) {
                when (peek()) {
                    '*' -> { next(); acc *= parsePower() }
                    '/' -> { next(); acc /= parsePower() }
                    '%' -> { next(); acc %= parsePower() }
                    else -> return acc
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            return if (peek() == '^') { next(); Math.pow(base, parsePower()) } else base
        }

        private fun parseUnary(): Double = when (peek()) {
            '+' -> { next(); parseUnary() }
            '-' -> { next(); -parseUnary() }
            else -> parsePrimary()
        }

        private fun parsePrimary(): Double {
            if (peek() == '(') {
                next()
                val v = parseExpr()
                require(peek() == ')') { "expected ')' at position $pos" }
                next()
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            require(pos > start) { "expected a number at position $pos" }
            return s.substring(start, pos).toDouble()
        }

        private fun peek(): Char? {
            while (pos < s.length && s[pos] == ' ') pos++
            return if (pos < s.length) s[pos] else null
        }

        private fun next() { pos++ }

        fun expectEnd() = require(peek() == null) { "unexpected trailing input at position $pos" }
    }
}
