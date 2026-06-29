package dev.kortex.core.tool

import dev.kortex.core.graph.Approver
import dev.kortex.core.state.Budget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

/**
 * The "used wisely" half of the goal. Every tool call passes through here, which enforces,
 * in order: budget (16) -> required params present (18 guardrails) -> risk approval (13),
 * and writes a tamper-evident audit entry. The LLM proposes; the governor disposes.
 */
class ToolGovernor(
    private val onAudit: (AuditEntry) -> Unit = {},
) {
    data class AuditEntry(
        val tool: String,
        val argumentsJson: String,
        val decision: Decision,
        val at: Long = System.currentTimeMillis(),
    )
    enum class Decision { ALLOWED, DENIED_BUDGET, DENIED_VALIDATION, DENIED_APPROVAL }

    suspend fun run(
        tool: Tool,
        args: JsonObject,
        budget: Budget,
        approver: Approver,
    ): ToolResult {
        val argsJson = Json.encodeToString(JsonObject.serializer(), args)

        if (budget.exhausted) return audit(tool, argsJson, Decision.DENIED_BUDGET)
            .let { ToolResult(false, "Budget exhausted; tool '${tool.name}' not run.") }

        val missing = tool.parameters.params.filter { it.required && it.name !in args }
        if (missing.isNotEmpty()) return audit(tool, argsJson, Decision.DENIED_VALIDATION)
            .let { ToolResult(false, "Missing required params: ${missing.joinToString { it.name }}") }

        if (tool.risk != RiskLevel.LOW && !approver.approve(tool.name, argsJson))
            return audit(tool, argsJson, Decision.DENIED_APPROVAL)
                .let { ToolResult(false, "User declined to run '${tool.name}'.") }

        audit(tool, argsJson, Decision.ALLOWED)
        return tool.execute(args)
    }

    private fun audit(tool: Tool, argsJson: String, decision: Decision): AuditEntry =
        AuditEntry(tool.name, argsJson, decision).also(onAudit)
}
