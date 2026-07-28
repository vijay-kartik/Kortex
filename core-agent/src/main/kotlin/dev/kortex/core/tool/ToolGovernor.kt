package dev.kortex.core.tool

import dev.kortex.core.graph.Approver
import dev.kortex.core.log.Logger
import dev.kortex.core.log.d
import dev.kortex.core.log.e
import dev.kortex.core.log.w
import dev.kortex.core.state.Budget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

/**
 * The "used wisely" half of the goal. Every tool call passes through here, which enforces,
 * in order: budget (16) -> required params present (18 guardrails) -> risk approval (13),
 * and writes a tamper-evident audit entry. The LLM proposes; the governor disposes.
 *
 * Every tool — builtin (calculator, web_search, current_time) or host-provided (WhatsApp) —
 * runs through [run], so logging here gives full visibility into tool traffic in one place.
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

    /** [logger] comes from the caller's [dev.kortex.core.graph.AgentContext] rather than being
     *  fixed at construction, so each turn/run can route its logs somewhere different (e.g. a
     *  UI-observing logger scoped to one chat turn) without needing a new governor instance. */
    suspend fun run(
        tool: Tool,
        args: JsonObject,
        budget: Budget,
        approver: Approver,
        logger: Logger = Logger.CONSOLE,
    ): ToolResult {
        val argsJson = Json.encodeToString(JsonObject.serializer(), args)
        logger.d(TAG, "-> ${tool.name}($argsJson)")

        if (budget.exhausted) {
            logger.w(TAG, "${tool.name} denied: budget exhausted")
            return audit(tool, argsJson, Decision.DENIED_BUDGET)
                .let { ToolResult(false, "Budget exhausted; tool '${tool.name}' not run.") }
        }

        val missing = tool.parameters.params.filter { it.required && it.name !in args }
        if (missing.isNotEmpty()) {
            logger.w(TAG, "${tool.name} denied: missing params ${missing.joinToString { it.name }}")
            return audit(tool, argsJson, Decision.DENIED_VALIDATION)
                .let { ToolResult(false, "Missing required params: ${missing.joinToString { it.name }}") }
        }

        if (tool.risk != RiskLevel.LOW && !approver.approve(tool.name, argsJson)) {
            logger.w(TAG, "${tool.name} denied: user declined approval")
            return audit(tool, argsJson, Decision.DENIED_APPROVAL)
                .let { ToolResult(false, "User declined to run '${tool.name}'.") }
        }

        audit(tool, argsJson, Decision.ALLOWED)
        val start = System.currentTimeMillis()
        val result = runCatching { tool.execute(args) }
            .getOrElse { err ->
                logger.e(TAG, "${tool.name} threw", err)
                ToolResult(false, "Tool '${tool.name}' failed: ${err.message}")
            }
        val elapsedMs = System.currentTimeMillis() - start
        logger.d(TAG, "<- ${tool.name} ok=${result.ok} (${elapsedMs}ms) ${result.content.take(200)}")
        return result
    }

    private fun audit(tool: Tool, argsJson: String, decision: Decision): AuditEntry =
        AuditEntry(tool.name, argsJson, decision).also(onAudit)

    companion object {
        private const val TAG = "ToolGovernor"
    }
}
