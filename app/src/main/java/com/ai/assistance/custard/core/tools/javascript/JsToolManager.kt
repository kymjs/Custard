package com.ai.assistance.custard.core.tools.javascript

import android.content.Context
import com.ai.assistance.custard.util.AppLogger
import com.ai.assistance.custard.util.LocaleUtils
import com.ai.assistance.custard.core.tools.StringResultData
import com.ai.assistance.custard.core.tools.packTool.PackageManager
import com.ai.assistance.custard.data.model.AITool
import com.ai.assistance.custard.data.model.ToolResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Manages JavaScript tool execution using JsEngine This class handles the execution of JavaScript
 * code in package tools and coordinates tool calls from JavaScript back to native Android code.
 */
class JsToolManager
private constructor(private val context: Context, private val packageManager: PackageManager) {
    companion object {
        private const val TAG = "JsToolManager"
        private const val MAX_CONCURRENT_ENGINES = 4

        @Volatile private var INSTANCE: JsToolManager? = null

        fun getInstance(context: Context, packageManager: PackageManager): JsToolManager {
            return INSTANCE
                    ?: synchronized(this) {
                        INSTANCE
                                ?: JsToolManager(context.applicationContext, packageManager).also {
                                    INSTANCE = it
                                }
                    }
        }
    }

    private val enginePool = Channel<JsEngine>(capacity = MAX_CONCURRENT_ENGINES)
    private val allEngines = ConcurrentHashMap.newKeySet<JsEngine>()

    init {
        repeat(MAX_CONCURRENT_ENGINES) {
            val engine = JsEngine(context)
            allEngines.add(engine)
            enginePool.trySend(engine)
        }
    }

    private suspend fun acquireEngine(): JsEngine = enginePool.receive()

    private fun acquireEngineBlocking(): JsEngine = runBlocking { acquireEngine() }

    private fun releaseEngine(engine: JsEngine) {
        enginePool.trySend(engine)
    }

    /**
     * Execute a specific JavaScript tool
     * @param toolName The name of the tool to execute (format: packageName.functionName)
     * @param params Parameters to pass to the tool function
     * @return The result of tool execution
     */
    fun executeScript(toolName: String, params: Map<String, String>): String {
        val engine = acquireEngineBlocking()
        try {
            // Split the tool name to get package and function names
            val parts = toolName.split(".")
            if (parts.size < 2) {
                return "Invalid tool name format: $toolName. Expected format: packageName.functionName"
            }

            val packageName = parts[0]
            val functionName = parts[1]

            // Get the package script
            val script =
                    packageManager.getPackageScript(packageName)
                            ?: return "Package not found: $packageName"

            AppLogger.d(TAG, "Executing function $functionName in package $packageName")

            // Execute the function in the script
            val stateId = packageManager.getActivePackageStateId(packageName)
            val injectedParams = params.toMutableMap()
            if (stateId != null) {
                injectedParams["__custard_package_state"] = stateId
            } else {
                injectedParams.remove("__custard_package_state")
            }
            val language = LocaleUtils.getCurrentLanguage(context)
            if (language.isNotBlank()) {
                injectedParams["__custard_package_lang"] = language
            } else {
                injectedParams.remove("__custard_package_lang")
            }
            if (params["__custard_package_caller_name"] != null) {
                injectedParams["__custard_package_caller_name"] = params["__custard_package_caller_name"].toString()
            } else {
                injectedParams.remove("__custard_package_caller_name")
            }
            if (params["__custard_package_chat_id"] != null) {
                injectedParams["__custard_package_chat_id"] = params["__custard_package_chat_id"].toString()
            } else {
                injectedParams.remove("__custard_package_chat_id")
            }
            if (params["__custard_package_caller_card_id"] != null) {
                injectedParams["__custard_package_caller_card_id"] = params["__custard_package_caller_card_id"].toString()
            } else {
                injectedParams.remove("__custard_package_caller_card_id")
            }
            val result = engine.executeScriptFunction(script, functionName, injectedParams)

            return result?.toString() ?: "null"
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing script: ${e.message}", e)
            return "Error: ${e.message}"
        } finally {
            releaseEngine(engine)
        }
    }

    /**
     * Execute a JavaScript script with the given tool parameters
     * @param script The JavaScript code to execute
     * @param tool The tool being executed (provides parameters)
     * @return The result of script execution
     */
    fun executeScript(script: String, tool: AITool): Flow<ToolResult> = channelFlow {
        var engine: JsEngine? = null
        try {
            AppLogger.d(TAG, "Executing script for tool: ${tool.name}")

            engine = acquireEngine()

            // Extract the function name from the tool name (packageName:toolName)
            val parts = tool.name.split(":")
            if (parts.size != 2) {
                send(
                        ToolResult(
                                toolName = tool.name,
                                success = false,
                                result = StringResultData(""),
                                error = "Invalid tool name format. Expected 'packageName:toolName'"
                        )
                )
                return@channelFlow
            }

            val packageName = parts[0]
            val functionName = parts[1]

            val stateId = packageManager.getActivePackageStateId(packageName)

            // Get tool definition from PackageManager to access parameter types
            val toolDefinition = packageManager.getPackageTools(packageName)?.tools?.find { it.name == functionName }

            // Convert tool parameters to map for the script, with type conversion
            val params: Map<String, Any?> = tool.parameters.associate { param ->
                val paramDefinition = toolDefinition?.parameters?.find { it.name == param.name }
                // default to string if not found in metadata
                val paramType = paramDefinition?.type ?: "string"

                val convertedValue: Any? = try {
                    when (paramType.lowercase()) {
                        "number" -> param.value.toDoubleOrNull() ?: param.value.toLongOrNull() ?: param.value
                        "boolean" -> param.value.toBoolean()
                        "integer" -> param.value.toLongOrNull() ?: param.value
                        else -> param.value // string and other types
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to convert parameter '${param.name}' with value '${param.value}' to type '$paramType'. Using string value. Error: ${e.message}")
                    param.value // Fallback to string
                }
                param.name to convertedValue
            }

            val injectedParams = params.toMutableMap()
            if (stateId != null) {
                injectedParams["__custard_package_state"] = stateId
            } else {
                injectedParams.remove("__custard_package_state")
            }
            val language = LocaleUtils.getCurrentLanguage(context)
            if (language.isNotBlank()) {
                injectedParams["__custard_package_lang"] = language
            } else {
                injectedParams.remove("__custard_package_lang")
            }
            if (params["__custard_package_caller_name"] != null) {
                injectedParams["__custard_package_caller_name"] = params["__custard_package_caller_name"].toString()
            } else {
                injectedParams.remove("__custard_package_caller_name")
            }
            if (params["__custard_package_chat_id"] != null) {
                injectedParams["__custard_package_chat_id"] = params["__custard_package_chat_id"].toString()
            } else {
                injectedParams.remove("__custard_package_chat_id")
            }
            if (params["__custard_package_caller_card_id"] != null) {
                injectedParams["__custard_package_caller_card_id"] = params["__custard_package_caller_card_id"].toString()
            } else {
                injectedParams.remove("__custard_package_caller_card_id")
            }

            // Execute the script with timeout
            try {
                withTimeout(JsTimeoutConfig.SCRIPT_TIMEOUT_MS) {
                    AppLogger.d(TAG, "Starting script execution for function: $functionName")

                    val startTime = System.currentTimeMillis()
                    val scriptResult =
                            engine!!.executeScriptFunction(
                                    script,
                                    functionName,
                                    injectedParams
                            ) { intermediateResult ->
                                val resultString = intermediateResult?.toString() ?: "null"
                                AppLogger.d(TAG, "Intermediate JS result: $resultString")
                                trySend(
                                        ToolResult(
                                                toolName = tool.name,
                                                success = true,
                                                result = StringResultData(resultString)
                                        )
                                )
                            }

                    val executionTime = System.currentTimeMillis() - startTime
                    AppLogger.d(
                            TAG,
                            "Script execution completed in ${executionTime}ms with result type: ${scriptResult?.javaClass?.name ?: "null"}"
                    )

                    // Handle different types of results
                    when {
                        scriptResult == null -> {
                            send(
                                    ToolResult(
                                            toolName = tool.name,
                                            success = false,
                                            result = StringResultData(""),
                                            error = "Script returned null result"
                                    )
                            )
                        }
                        scriptResult is String && scriptResult.startsWith("Error:") -> {
                            val errorMsg = scriptResult.substring("Error:".length).trim()
                            AppLogger.e(TAG, "Script execution error: $errorMsg")
                            send(
                                    ToolResult(
                                            toolName = tool.name,
                                            success = false,
                                            result = StringResultData(""),
                                            error = errorMsg
                                    )
                            )
                        }
                        else -> {
                            val finalResultString = scriptResult.toString()
                            AppLogger.d(
                                    TAG,
                                    "Final script result: ${finalResultString.take(100)}${if (finalResultString.length > 100) "..." else ""}"
                            )
                            send(
                                    ToolResult(
                                            toolName = tool.name,
                                            success = true,
                                            result = StringResultData(finalResultString)
                                    )
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                AppLogger.w(TAG, "Script execution timed out: ${e.message}")
                send(
                        ToolResult(
                                toolName = tool.name,
                                success = false,
                                result = StringResultData(""),
                                error = "Script execution timed out after ${JsTimeoutConfig.SCRIPT_TIMEOUT_MS}ms"
                        )
                )
            } catch (e: Exception) {
                // Catch other execution exceptions
                AppLogger.e(TAG, "Exception during script execution: ${e.message}", e)
                send(
                        ToolResult(
                                toolName = tool.name,
                                success = false,
                                result = StringResultData(""),
                                error = "Script execution failed: ${e.message}"
                        )
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing script for tool ${tool.name}: ${e.message}", e)
            send(
                    ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = "Script execution error: ${e.message}"
                    )
            )
        } finally {
            engine?.let { releaseEngine(it) }
        }
    }

    fun executeComposeDsl(
            script: String,
            packageName: String = "",
            uiModuleId: String = "",
            toolPkgId: String = "",
            state: Map<String, Any?> = emptyMap(),
            memo: Map<String, Any?> = emptyMap(),
            moduleSpec: Map<String, Any?> = emptyMap(),
            envOverrides: Map<String, String> = emptyMap()
    ): String {
        val engine = acquireEngineBlocking()
        try {
            val runtimeOptions = mutableMapOf<String, Any?>()
            if (packageName.isNotBlank()) {
                runtimeOptions["packageName"] = packageName
            }
            if (uiModuleId.isNotBlank()) {
                runtimeOptions["uiModuleId"] = uiModuleId
            }
            if (toolPkgId.isNotBlank()) {
                runtimeOptions["toolPkgId"] = toolPkgId
            }
            if (state.isNotEmpty()) {
                runtimeOptions["state"] = state
            }
            if (memo.isNotEmpty()) {
                runtimeOptions["memo"] = memo
            }
            if (moduleSpec.isNotEmpty()) {
                runtimeOptions["moduleSpec"] = moduleSpec
            }

            if (packageName.isNotBlank()) {
                val stateId = packageManager.getActivePackageStateId(packageName)
                if (!stateId.isNullOrBlank()) {
                    runtimeOptions["__custard_package_state"] = stateId
                }
            }
            val language = LocaleUtils.getCurrentLanguage(context)
            if (language.isNotBlank()) {
                runtimeOptions["__custard_package_lang"] = language
            }

            val result =
                    engine.executeComposeDslScript(
                            script = script,
                            runtimeOptions = runtimeOptions,
                            envOverrides = envOverrides
                    )
            return result?.toString() ?: "null"
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing compose_dsl script: ${e.message}", e)
            return "Error: ${e.message}"
        } finally {
            releaseEngine(engine)
        }
    }

    /** Clean up resources when the manager is no longer needed */
    fun destroy() {
        enginePool.close()
        allEngines.forEach { it.destroy() }
        allEngines.clear()
    }
}
