package dev.kortex.core

import dev.kortex.core.tool.builtin.calculatorTool
import dev.kortex.core.tool.string
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class CalculatorTest {
    private val calc = calculatorTool()

    private suspend fun eval(expr: String) =
        calc.execute(buildJsonObject { put("expression", expr) })

    @Test
    fun `respects precedence, parentheses, power, and unary minus`() = runTest {
        eval("2 + 3 * 4").content shouldBe "14"
        eval("(2 + 3) * 4").content shouldBe "20"
        eval("2 ^ 3 ^ 2").content shouldBe "512"   // right-associative
        eval("-5 + 2").content shouldBe "-3"
        eval("10 / 4").content shouldBe "2.5"
    }

    @Test
    fun `reports a clear error on bad input instead of throwing`() = runTest {
        val result = eval("2 +")
        result.ok shouldBe false
        result.content shouldContain "Could not evaluate"
    }

    @Test
    fun `schema exposes the expression parameter for function calling`() {
        val schema = calc.parameters.toJsonSchema().toString()
        schema shouldContain "expression"
    }
}
