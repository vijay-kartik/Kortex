package dev.kortex.core.tool.builtin

import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.tool
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An internal tool to handle financial statement analysis. 
 * Since the LLM natively receives attachments (images, PDFs) in its context, 
 * this tool relies on the LLM's visual/document parsing capabilities to extract 
 * the raw data, and then this tool rigorously formats it into a Markdown table.
 */
fun analyzeStatementTool(): Tool = tool(
    name = "analyze_statement",
    description = "Analyzes a financial bank statement or credit card statement from the attachments, extracts transaction details, and formats them into a Markdown table.",
) {
    param(
        name = "transactions",
        type = "array",
        description = "A JSON array containing a list of transactions extracted from the attachment. Each object MUST have 'date', 'description', and 'amount' fields. Optional: 'category'.",
    )
    
    risk(RiskLevel.LOW)
    execute { args ->
        val jsonElements = args["transactions"]?.jsonArray
        
        if (jsonElements == null || jsonElements.isEmpty()) {
            return@execute ToolResult(false, "No transactions provided or extraction failed. Ensure you pass a valid JSON array of objects.")
        }

        try {

            val sb = StringBuilder()
            sb.append("Here is the extracted statement data:\n\n")
            sb.append("| Date | Description | Category | Amount |\n")
            sb.append("|---|---|---|---|\n")

            for (element in jsonElements) {
                val obj = element.jsonObject
                val date = obj["date"]?.jsonPrimitive?.content ?: "-"
                val desc = obj["description"]?.jsonPrimitive?.content ?: "-"
                val category = obj["category"]?.jsonPrimitive?.content ?: "-"
                val amount = obj["amount"]?.jsonPrimitive?.content ?: "-"
                
                sb.append("| $date | $desc | $category | $amount |\n")
            }

            ToolResult(true, "Successfully analyzed and formatted. Output this exact Markdown table to the user:\n\n$sb")
        } catch (e: Exception) {
            ToolResult(false, "Failed to parse transactions JSON: ${e.message}")
        }
    }
}
