package dev.kortex.core.tool.builtin

import dev.kortex.core.state.Attachment
import dev.kortex.core.llm.renderPdfPagesAsImages
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.string
import dev.kortex.core.tool.tool
import java.io.File
import java.util.Base64

fun readFileTool(): Tool = tool(
    name = "read_file",
    description = "Reads a local file from the device (like downloaded PDFs, text, or images) and attaches its content to the conversation so you can analyze it.",
) {
    param("file_path", "string", "The absolute path to the local file to read.")
    risk(RiskLevel.LOW)
    
    execute { args ->
        val filePath = args.string("file_path")
        val file = File(filePath)
        
        if (!file.exists()) {
            return@execute ToolResult(false, "File does not exist: $filePath")
        }
        
        try {
            val bytes = file.readBytes()
            val ext = file.extension.lowercase()
            val mimeType = when (ext) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "txt", "md", "csv", "json" -> "text/plain"
                else -> "application/octet-stream"
            }
            
            // If it's a PDF, render each page as an image for the Vision model
            if (ext == "pdf") {
                return@execute try {
                    val attachments = renderPdfPagesAsImages(file, file.name)
                    ToolResult(
                        ok = true,
                        content = "Converted PDF into ${attachments.size} image pages. The pages are attached to this message. Analyze the attached images to extract the required details.",
                        attachments = attachments
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Failed to render PDF: ${e.message}")
                }
            }

            // If it's text, we can just return it in content to save attachment overhead
            if (mimeType == "text/plain") {
                val text = file.readText(Charsets.UTF_8).take(100_000)
                return@execute ToolResult(true, "File contents of $filePath:\n$text")
            }
            
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val attachment = Attachment(
                mimeType = mimeType,
                dataBase64 = base64,
                filename = file.name
            )
            
            ToolResult(
                ok = true, 
                content = "Successfully loaded file '${file.name}'. The file has been attached to the conversation. Analyze the attached document and extract the required details.",
                attachments = listOf(attachment)
            )
        } catch (e: Exception) {
            ToolResult(false, "Failed to read file: ${e.message}")
        }
    }
}
