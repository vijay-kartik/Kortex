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

/** Largest file attached whole (base64) to the conversation. PDFs and text are read
 *  incrementally and bounded by page/char caps instead, so they aren't held to this. */
internal const val MAX_ATTACHED_FILE_BYTES = 10L * 1024 * 1024

private const val MAX_TEXT_CHARS = 100_000

fun readFileTool(): Tool = tool(
    name = "read_file",
    description = "Reads a local file from the device (like downloaded PDFs, text, or images) and attaches its content to the conversation so you can analyze it. Images and other non-text files over 10 MB are refused.",
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
                // Read only the first MAX_TEXT_CHARS rather than the whole file.
                val text = file.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(MAX_TEXT_CHARS)
                    var n = 0
                    while (n < buf.size) {
                        val read = reader.read(buf, n, buf.size - n)
                        if (read < 0) break
                        n += read
                    }
                    String(buf, 0, n)
                }
                return@execute ToolResult(true, "File contents of $filePath:\n$text")
            }

            val size = file.length()
            if (size > MAX_ATTACHED_FILE_BYTES) {
                return@execute ToolResult(
                    false,
                    "File is too large to attach: ${file.name} is ${size / (1024 * 1024)} MB, the limit is ${MAX_ATTACHED_FILE_BYTES / (1024 * 1024)} MB.",
                )
            }

            val base64 = Base64.getEncoder().encodeToString(file.readBytes())
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
