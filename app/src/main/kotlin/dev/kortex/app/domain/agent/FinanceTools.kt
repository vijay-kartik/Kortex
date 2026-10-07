package dev.kortex.app.domain.agent

import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.stringOrNull
import dev.kortex.core.tool.tool
import dev.kortex.finance.agent.AgentAnswer
import dev.kortex.finance.agent.FinanceAgent
import java.time.LocalDate
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The agent's finance tools (docs/FINANCE_PLAN.md › Agent tools). Writes are MEDIUM risk, so the
 * user confirms before anything is saved; reads are LOW. Amounts are rupees, as people say them.
 */
fun financeTools(finance: FinanceAgent): List<Tool> = listOf(
    tool("add_expense", "Record an expense in Kortex Finances: money spent from one of the user's accounts or cards.") {
        param("amount", "number", "Amount in rupees, e.g. 42.5")
        param("account", "string", "Account or card name, or its last 4 digits. Leave out only when the user has a single account.", required = false)
        param("merchant", "string", "Who was paid, e.g. 'Whole Foods Market'", required = false)
        param("category", "string", "Expense category name, e.g. Food, Travel, Utilities", required = false)
        param("date", "string", "Day it was spent, yyyy-MM-dd; today when left out", required = false)
        param("note", "string", "A short note", required = false)
        risk(RiskLevel.MEDIUM)
        promptHint("Ask which account if it isn't clear; never guess an amount.")
        execute { args ->
            val amount = args.minor("amount") ?: return@execute ToolResult(false, "amount must be a number of rupees above zero.")
            finance.addEntry(false, amount, args.text("account"), args.text("category"), args.text("merchant"), args.text("note"), args.date("date")).toResult()
        }
    },
    tool("add_income", "Record income in Kortex Finances: money received into one of the user's accounts (not a credit card).") {
        param("amount", "number", "Amount in rupees")
        param("account", "string", "Account name or last 4 digits it was received into", required = false)
        param("from", "string", "Who paid, e.g. 'Acme Technologies'", required = false)
        param("category", "string", "Income category name, e.g. Salary", required = false)
        param("date", "string", "Day it was received, yyyy-MM-dd; today when left out", required = false)
        param("note", "string", "A short note", required = false)
        risk(RiskLevel.MEDIUM)
        execute { args ->
            val amount = args.minor("amount") ?: return@execute ToolResult(false, "amount must be a number of rupees above zero.")
            finance.addEntry(true, amount, args.text("account"), args.text("category"), args.text("from"), args.text("note"), args.date("date")).toResult()
        }
    },
    tool("mark_paid", "Mark the next occurrence of a recurring payment (a subscription or fixed expense) as paid, recording the expense.") {
        param("name", "string", "The recurring payment's name, e.g. Netflix")
        param("amount", "number", "Amount paid in rupees, if different from usual", required = false)
        param("account", "string", "Account or card it was paid from, if not its usual one", required = false)
        risk(RiskLevel.MEDIUM)
        execute { args ->
            val name = args.text("name") ?: return@execute ToolResult(false, "name is required.")
            finance.markPaid(name, args.minor("amount"), args.text("account")).toResult()
        }
    },
    tool("spending_summary", "How much the user spent and earned over a period, and where the money went by category.") {
        param("period", "string", "today, this_week, this_month, last_month, this_year, or a month as yyyy-MM. Defaults to this_month.", required = false)
        param("category", "string", "Only this category's spending", required = false)
        execute { args -> finance.spendingSummary(args.text("period"), args.text("category")).toResult() }
    },
    tool("budget_status", "This month's budget: how much of each budgeted category is spent and left, and where it's heading by month-end.") {
        param("category", "string", "Only this category's budget, e.g. Food", required = false)
        execute { args -> finance.budgetStatus(args.text("category")).toResult() }
    },
    tool("find_transactions", "Search the user's finance entries by merchant, note or category, optionally between two dates.") {
        param("query", "string", "Text to look for, e.g. 'swiggy'", required = false)
        param("from", "string", "First day, yyyy-MM-dd", required = false)
        param("to", "string", "Last day, yyyy-MM-dd", required = false)
        execute { args -> finance.findTransactions(args.text("query"), args.date("from"), args.date("to")).toResult() }
    },
    tool("list_pending", "Card bills and recurring payments due in the next 30 days, with amounts and due dates.") {
        execute { finance.listPending().toResult() }
    },
)

private fun AgentAnswer.toResult() = ToolResult(ok, text)

private fun JsonObject.text(key: String): String? = stringOrNull(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

private fun JsonObject.date(key: String): LocalDate? = text(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Rupees, as a number or "1,239.50", to paise; null when missing or not above zero. */
private fun JsonObject.minor(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    val rupees = primitive.doubleOrNull ?: primitive.content.replace(",", "").replace("₹", "").trim().toDoubleOrNull() ?: return null
    return (rupees * 100).roundToLong().takeIf { it > 0 }
}
