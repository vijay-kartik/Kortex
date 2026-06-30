package dev.kortex.app.tools

import android.app.appsearch.GenericDocument
import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.string
import dev.kortex.core.tool.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * A tool that uses Android 16 Platform App Functions to send WhatsApp messages.
 * Uses Reflection to interact with the AppFunctionManager available on Android 16+ devices.
 */
@Suppress("NewApi")
fun whatsappTool(context: Context): Tool = tool(
    name = "whatsapp_send_message",
    description = "Sends a real WhatsApp message to a specific contact using Android App Functions. " +
        "Use this for any request to send a WhatsApp message. Do NOT ask for permission first.",
) {
    param("contact_name", "string", "The name of the contact as it appears in WhatsApp.")
    param("message", "string", "The text content of the message to send.")
    
    risk(RiskLevel.HIGH) 
    
    execute { args ->
        val contactName = args.string("contact_name")
        val message = args.string("message")

        // Only attempt App Functions on Android 16 (API 36) or higher.
        if (Build.VERSION.SDK_INT < 36 && Build.VERSION.CODENAME != "Baklava") {
            return@execute ToolResult(false, "App Functions require Android 16+.")
        }

        runCatching {
            val manager = context.getSystemService("app_function") 
                ?: return@runCatching ToolResult(false, "AppFunctionManager not found.")

            // 1. Build the parameters as a GenericDocument
            val parameters = GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                .setPropertyString("recipientName", contactName)
                .setPropertyString("text", message)
                .build()

            // 2. Reflectively build the ExecuteAppFunctionRequest
            val requestBuilderClass = Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest\$Builder")
            val requestBuilder = requestBuilderClass.getConstructor(String::class.java, String::class.java)
                .newInstance("com.whatsapp", "sendMessage")
            
            requestBuilderClass.getMethod("setParameters", GenericDocument::class.java)
                .invoke(requestBuilder, parameters)
            
            val request = requestBuilderClass.getMethod("build").invoke(requestBuilder)

            // 3. Call executeAppFunction
            suspendCancellableCoroutine<ToolResult> { continuation ->
                val outcomeReceiver = object : OutcomeReceiver<Any, Exception> {
                    override fun onResult(result: Any) {
                        val isSuccess = result.javaClass.getMethod("isSuccess").invoke(result) as Boolean
                        if (isSuccess) {
                            continuation.resume(ToolResult(true, "WhatsApp message sent to $contactName."))
                        } else {
                            val error = result.javaClass.getMethod("getErrorMessage").invoke(result) as? String
                            continuation.resume(ToolResult(false, "WhatsApp failed: ${error ?: "Unknown error"}"))
                        }
                    }

                    override fun onError(error: Exception) {
                        continuation.resume(ToolResult(false, "AppFunction error: ${error.message}"))
                    }
                }

                manager.javaClass.getMethod(
                    "executeAppFunction",
                    Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest"),
                    Executor::class.java,
                    OutcomeReceiver::class.java
                ).invoke(manager, request, Dispatchers.Default.asExecutor(), outcomeReceiver)
            }
        }.getOrElse { 
            ToolResult(false, "Error executing App Function: ${it.message}")
        }
    }
}
