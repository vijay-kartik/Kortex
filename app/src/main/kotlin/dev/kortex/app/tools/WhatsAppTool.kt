package dev.kortex.app.tools

import android.app.appsearch.GenericDocument
import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.util.Log
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.string
import dev.kortex.core.tool.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A tool that uses Android 16 Platform App Functions to send WhatsApp messages.
 * Uses robust Reflection to discover and interact with the AppFunctionManager.
 */
@Suppress("NewApi")
fun whatsappTool(context: Context): Tool = tool(
    name = "whatsapp_send_message",
    description = "CRITICAL: Call this tool immediately to send a WhatsApp message. " +
        "DO NOT talk to the user or ask for permission first. Just provide the contact name and the message.",
) {
    param("contact_name", "string", "The name of the contact as it appears in WhatsApp.")
    param("message", "string", "The text content of the message to send.")
    
    risk(RiskLevel.HIGH) 
    
    execute { args ->
        val contactName = args.string("contact_name")
        val message = args.string("message")
        Log.d("WhatsAppTool", "Executing for $contactName: $message")

        // Only attempt App Functions on Android 16 (API 36) or higher.
        if (Build.VERSION.SDK_INT < 36 && Build.VERSION.CODENAME != "Baklava") {
            return@execute ToolResult(false, "App Functions require Android 16+.")
        }

        runCatching {
            val manager = context.getSystemService("app_function") 
                ?: return@runCatching ToolResult(false, "AppFunctionManager not found.")

            // 1. Log all methods for debugging
            manager.javaClass.methods.forEach { m ->
                Log.d("WhatsAppTool", "Available method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }

            // 2. Build the parameters as a GenericDocument
            val parameters = GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                .setPropertyString("recipientName", contactName)
                .setPropertyString("recipient", contactName)
                .setPropertyString("text", message)
                .build()

            // 3. Reflectively build the ExecuteAppFunctionRequest
            val requestBuilderClass = Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest\$Builder")
            val requestBuilder = requestBuilderClass.getConstructor(String::class.java, String::class.java)
                .newInstance("com.whatsapp", "sendMessage")
            
            requestBuilderClass.getMethod("setParameters", GenericDocument::class.java)
                .invoke(requestBuilder, parameters)
            
            val request = requestBuilderClass.getMethod("build").invoke(requestBuilder)

            // 4. Discover the execute method dynamically
            val executeMethod = manager.javaClass.methods.find { 
                it.name.contains("execute", ignoreCase = true) && it.parameterCount >= 3 
            } ?: throw NoSuchMethodException("No execute method found on AppFunctionManager")

            Log.d("WhatsAppTool", "Selected method for execution: ${executeMethod.name}")

            // 5. ATTEMPT 1: Broadcast Intent (Alternative execution path)
            runCatching {
                val intent = android.content.Intent("android.app.appfunctions.intent.action.EXECUTE_APP_FUNCTION")
                intent.putExtra("android.app.appfunctions.extra.PACKAGE_NAME", "com.whatsapp")
                intent.putExtra("android.app.appfunctions.extra.FUNCTION_ID", "sendMessage")
                
                val paramsBundle = android.os.Bundle()
                paramsBundle.putString("recipientName", contactName)
                paramsBundle.putString("text", message)
                intent.putExtra("android.app.appfunctions.extra.PARAMETERS", paramsBundle)
                
                context.sendBroadcast(intent)
                Log.d("WhatsAppTool", "Broadcast intent sent: ${intent.action}")
            }.onFailure { Log.e("WhatsAppTool", "Failed to send broadcast", it) }

            // 6. ATTEMPT 2: Call execute (Original method)
            suspendCancellableCoroutine<ToolResult> { continuation ->
                val outcomeReceiver = object : OutcomeReceiver<Any, Exception> {
                    override fun onResult(result: Any) {
                        Log.d("WhatsAppTool", "Result received: $result")
                        runCatching {
                            val isSuccess = result.javaClass.getMethod("isSuccess").invoke(result) as Boolean
                            if (isSuccess) {
                                continuation.resume(ToolResult(true, "WhatsApp message sent to $contactName."))
                            } else {
                                val error = result.javaClass.getMethod("getErrorMessage").invoke(result) as? String
                                continuation.resume(ToolResult(false, "WhatsApp failed: $error. (Attempted broadcast bypass as well)"))
                            }
                        }.onFailure { e ->
                            continuation.resume(ToolResult(false, "Error parsing result: ${e.message}"))
                        }
                    }

                    override fun onError(error: Exception) {
                        Log.e("WhatsAppTool", "Error from system", error)
                        continuation.resume(ToolResult(false, "AppFunction error: ${error.message}"))
                    }
                }

                // Handle variants with 3 or 4 parameters (request, executor, callback, [cancellationSignal])
                val invokeArgs = when (executeMethod.parameterCount) {
                    3 -> arrayOf(request, Dispatchers.Default.asExecutor(), outcomeReceiver)
                    4 -> arrayOf(request, Dispatchers.Default.asExecutor(), android.os.CancellationSignal(), outcomeReceiver)
                    else -> throw IllegalStateException("Unexpected parameter count: ${executeMethod.parameterCount}")
                }
                
                try {
                    executeMethod.invoke(manager, *invokeArgs)
                } catch (e: Exception) {
                    val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                    Log.e("WhatsAppTool", "Method invocation failed", cause)
                    continuation.resume(ToolResult(false, "Method invocation failed: ${cause.message}"))
                }
            }
        }.getOrElse { 
            val cause = (it as? java.lang.reflect.InvocationTargetException)?.cause ?: it
            Log.e("WhatsAppTool", "Execution failure", cause)
            ToolResult(false, "AppFunction failure: ${cause.message}")
        }
    }
}
