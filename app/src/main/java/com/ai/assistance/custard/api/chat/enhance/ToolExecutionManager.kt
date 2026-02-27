package com.ai.assistance.custard.api.chat.enhance

import com.ai.assistance.custard.util.AppLogger
import com.ai.assistance.custard.core.tools.AIToolHandler
import com.ai.assistance.custard.core.tools.StringResultData
import com.ai.assistance.custard.core.tools.ToolExecutor
import com.ai.assistance.custard.data.model.ToolInvocation
import com.ai.assistance.custard.data.model.ToolResult
import com.ai.assistance.custard.core.tools.packTool.PackageManager
import com.ai.assistance.custard.util.stream.StreamCollector
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.ai.assistance.custard.data.model.AITool
import com.ai.assistance.custard.data.model.ToolParameter
import com.ai.assistance.custard.ui.common.displays.MessageContentParser
import com.ai.assistance.custard.util.stream.plugins.StreamXmlPlugin
import com.ai.assistance.custard.util.stream.splitBy
import com.ai.assistance.custard.util.stream.stream
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/** Utility class for managing tool executions */
object ToolExecutionManager {
    private const val TAG = "ToolExecutionManager"

    private fun ensureEndsWithNewline(content: String): String {
        return if (content.endsWith("\n")) content else "$content\n"
    }

    private fun resolveDisplayToolName(tool: AITool): String {
        if (tool.name != "package_proxy") {
            return tool.name
        }
        val targetToolName = tool.parameters
            .firstOrNull { it.name == "tool_name" }
            ?.value
            ?.trim()
            .orEmpty()
        return if (targetToolName.isNotBlank()) targetToolName else tool.name
    }

    /**
     * 从 AI 响应中提取工具调用。
     * @param response AI 的响应字符串。
     * @return 检测到的工具调用列表。
     */
    suspend fun extractToolInvocations(response: String): List<ToolInvocation> {
        val invocations = mutableListOf<ToolInvocation>()
        val content = response

        val charStream = content.stream()
        val plugins = listOf(StreamXmlPlugin())

        charStream.splitBy(plugins).collect { group ->
            val chunkContent = StringBuilder()
            group.stream.collect { chunk -> chunkContent.append(chunk) }
            val chunkString = chunkContent.toString()

            if (chunkString.isEmpty()) return@collect

            if (group.tag is StreamXmlPlugin) {
                if (chunkString.startsWith("<tool") && chunkString.contains("</tool>")) {
                    val nameMatch = MessageContentParser.namePattern.find(chunkString)
                    val toolName = nameMatch?.groupValues?.get(1) ?: return@collect

                    val parameters = mutableListOf<ToolParameter>()
                    MessageContentParser.toolParamPattern.findAll(chunkString)
                        .forEach { paramMatch ->
                            val paramName = paramMatch.groupValues[1]
                            val paramValue = paramMatch.groupValues[2]
                            parameters.add(ToolParameter(paramName, unescapeXml(paramValue)))
                        }

                    val tool = AITool(name = toolName, parameters = parameters)
                    invocations.add(ToolInvocation(tool, chunkString, chunkString.indices))
                }
            }
        }

        AppLogger.d(
            TAG,
            "Found ${invocations.size} tool invocations: ${invocations.map { resolveDisplayToolName(it.tool) }}"
        )
        return invocations
    }

    /**
     * Unescapes XML special characters
     * @param input The XML escaped string
     * @return Unescaped string
     */
    private fun unescapeXml(input: String): String {
        var result = input

        // 处理 CDATA 标记
        if (result.startsWith("<![CDATA[") && result.endsWith("]]>")) {
            result = result.substring(9, result.length - 3)
        }

        // 即使没有完整的 CDATA 标记，也尝试清理末尾的 ]]> 和开头的 <![CDATA[
        if (result.endsWith("]]>")) {
            result = result.substring(0, result.length - 3)
        }

        if (result.startsWith("<![CDATA[")) {
            result = result.substring(9)
        }

        return result.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    /**
     * Execute a tool safely, with parameter validation
     *
     * @param invocation The tool invocation to execute
     * @param executor The tool executor to use
     * @return The result of the tool execution
     */
    fun executeToolSafely(
        invocation: ToolInvocation,
        executor: ToolExecutor,
        toolHandler: AIToolHandler? = null
    ): Flow<ToolResult> {
        val validationResult = executor.validateParameters(invocation.tool)
        if (!validationResult.valid) {
            return flow {
                emit(
                    ToolResult(
                        toolName = invocation.tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Invalid parameters: ${validationResult.errorMessage}"
                    )
                )
            }
        }

        return executor.invokeAndStream(invocation.tool).catch { e ->
            AppLogger.e(TAG, "Tool execution error: ${invocation.tool.name}", e)
            toolHandler?.notifyToolExecutionError(invocation.tool, e)
            emit(
                ToolResult(
                    toolName = invocation.tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Tool execution error: ${e.message}"
                )
            )
        }
    }

    /**
     * Check if a tool requires permission and verify if it has permission
     *
     * @param toolHandler The AIToolHandler instance to use for permission checks
     * @param invocation The tool invocation to check permissions for
     * @return A pair containing (has permission, error result if no permission)
     */
    suspend fun checkToolPermission(
        toolHandler: AIToolHandler,
        invocation: ToolInvocation
    ): Pair<Boolean, ToolResult?> {
        // 检查是否强制拒绝权限（deny_tool标记）
        val hasPromptForPermission = !invocation.rawText.contains("deny_tool")

        if (hasPromptForPermission) {
            // 检查权限，如果需要则弹出权限请求界面
            val toolPermissionSystem = toolHandler.getToolPermissionSystem()
            val hasPermission = toolPermissionSystem.checkToolPermission(invocation.tool)

            // 如果权限被拒绝，创建错误结果
            if (!hasPermission) {
                val errorResult =
                    ToolResult(
                        toolName = invocation.tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "User cancelled the tool execution."
                    )
                toolHandler.notifyToolPermissionChecked(
                    invocation.tool,
                    granted = false,
                    reason = errorResult.error
                )
                return Pair(false, errorResult)
            }

            toolHandler.notifyToolPermissionChecked(invocation.tool, granted = true)
            return Pair(true, null)
        }

        toolHandler.notifyToolPermissionChecked(
            invocation.tool,
            granted = true,
            reason = "Permission check bypassed by deny_tool tag."
        )
        return Pair(true, null)
    }

    /**
     *
     * 执行工具调用，包括权限检查、并行/串行执行和结果聚合。
     * @param invocations 要执行的工具调用列表。
     * @param toolHandler AIToolHandler 的实例。
     * @param packageManager PackageManager 的实例。
     * @param collector 用于实时输出结果的 StreamCollector。
     * @return 所有工具执行结果的列表。
     */
    suspend fun executeInvocations(
        invocations: List<ToolInvocation>,
        toolHandler: AIToolHandler,
        packageManager: PackageManager,
        collector: StreamCollector<String>,
        callerName: String? = null,
        callerChatId: String? = null,
        callerCardId: String? = null
    ): List<ToolResult> = coroutineScope {
        // 默认工具注册现在可能在启动阶段被延后；这里确保在真正执行工具前已完成注册
        // registerDefaultTools() 是幂等且线程安全的，可安全重复调用
        withContext(Dispatchers.Default) {
            toolHandler.registerDefaultTools()
        }

        // 1. 权限检查
        val permittedInvocations = mutableListOf<ToolInvocation>()
        val permissionDeniedResults = mutableListOf<ToolResult>()
        for (invocation in invocations) {
            toolHandler.notifyToolCallRequested(invocation.tool)
            val (hasPermission, errorResult) = checkToolPermission(toolHandler, invocation)
            if (hasPermission) {
                permittedInvocations.add(invocation)
            } else {
                errorResult?.let {
                    permissionDeniedResults.add(it)
                    val toolResultStatusContent =
                        ConversationMarkupManager.formatToolResultForMessage(it)
                    collector.emit(ensureEndsWithNewline(toolResultStatusContent))
                }
            }
        }

        val injectedInvocations =
            if (callerName.isNullOrBlank() && callerChatId.isNullOrBlank() && callerCardId.isNullOrBlank()) {
                permittedInvocations
            } else {
                val jsPackageNames = packageManager.getAvailablePackages().keys
                permittedInvocations.map { invocation ->
                    val toolNameParts = invocation.tool.name.split(':', limit = 2)
                    val packName = toolNameParts.getOrNull(0)
                    val isJsPackageTool = toolNameParts.size == 2 && packName != null && jsPackageNames.contains(packName)
                    if (!isJsPackageTool) {
                        invocation
                    } else {
                        val updatedParams = invocation.tool.parameters.toMutableList()
                        if (!callerName.isNullOrBlank()) {
                            val hasCallerParam = updatedParams.any { it.name == "__custard_package_caller_name" }
                            if (!hasCallerParam) {
                                updatedParams.add(ToolParameter("__custard_package_caller_name", callerName))
                            }
                        }
                        if (!callerChatId.isNullOrBlank()) {
                            val hasChatIdParam = updatedParams.any { it.name == "__custard_package_chat_id" }
                            if (!hasChatIdParam) {
                                updatedParams.add(ToolParameter("__custard_package_chat_id", callerChatId))
                            }
                        }
                        if (!callerCardId.isNullOrBlank()) {
                            val hasCallerCardParam = updatedParams.any { it.name == "__custard_package_caller_card_id" }
                            if (!hasCallerCardParam) {
                                updatedParams.add(ToolParameter("__custard_package_caller_card_id", callerCardId))
                            }
                        }
                        invocation.copy(
                            tool = invocation.tool.copy(parameters = updatedParams)
                        )
                    }
                }
            }

        // 2. 按并行/串行对工具进行分组
        val parallelizableToolNames = setOf(
            "list_files", "read_file", "read_file_part", "read_file_full", "file_exists",
            "find_files", "file_info", "grep_code", "query_memory", "calculate", "ffmpeg_info"
        )
        val (parallelInvocations, serialInvocations) = injectedInvocations.partition {
            parallelizableToolNames.contains(
                it.tool.name
            )
        }

        // 3. 执行工具并收集聚合结果
        val executionResults = ConcurrentHashMap<ToolInvocation, ToolResult>()

        // 启动并行工具
        val parallelJobs = parallelInvocations.map { invocation ->
            async {
                val result = executeAndEmitTool(invocation, toolHandler, packageManager, collector)
                executionResults[invocation] = result
            }
        }

        // 顺序执行串行工具
        for (invocation in serialInvocations) {
            val result = executeAndEmitTool(invocation, toolHandler, packageManager, collector)
            executionResults[invocation] = result
        }

        // 等待所有并行任务完成
        parallelJobs.awaitAll()

        // 4. 按原始顺序重新排序结果
        val orderedAggregated = injectedInvocations.mapNotNull { executionResults[it] }

        // 5. 组合所有结果并返回
        permissionDeniedResults + orderedAggregated
    }

    /**
     * 封装单个工具的执行、实时输出和结果聚合的辅助函数
     */
    private suspend fun executeAndEmitTool(
        invocation: ToolInvocation,
        toolHandler: AIToolHandler,
        packageManager: PackageManager,
        collector: StreamCollector<String>
    ): ToolResult {
        val toolName = invocation.tool.name
        val displayToolName = resolveDisplayToolName(invocation.tool)

        return try {
            val executor = toolHandler.getToolExecutorOrActivate(toolName)
            if (executor == null) {
                // 如果仍然为 null，则构建错误消息
                val errorMessage =
                    buildToolNotAvailableErrorMessage(toolName, packageManager, toolHandler)
                val notAvailableContent =
                    ConversationMarkupManager.createToolNotAvailableError(toolName, errorMessage)
                collector.emit(ensureEndsWithNewline(notAvailableContent))
                val notAvailableResult =
                    ToolResult(
                        toolName = displayToolName,
                        success = false,
                        result = StringResultData(""),
                        error = errorMessage
                    )
                toolHandler.notifyToolExecutionResult(invocation.tool, notAvailableResult)
                return notAvailableResult
            }

            toolHandler.notifyToolExecutionStarted(invocation.tool)

            val collectedResults = mutableListOf<ToolResult>()
            executeToolSafely(invocation, executor, toolHandler).collect { result ->
                collectedResults.add(result)
                // 实时输出每个结果
                val toolResultStatusContent =
                    ConversationMarkupManager.formatToolResultForMessage(result)
                collector.emit(ensureEndsWithNewline(toolResultStatusContent))
            }

            // 为此调用聚合最终结果
            if (collectedResults.isEmpty()) {
                val emptyResult =
                    ToolResult(
                        toolName = displayToolName,
                        success = false,
                        result = StringResultData(""),
                        error = "The tool execution returned no results."
                    )
                toolHandler.notifyToolExecutionResult(invocation.tool, emptyResult)
                return emptyResult
            }

            val lastResult = collectedResults.last()
            val combinedResultString = collectedResults.joinToString("\n") { res ->
                (if (res.success) res.result.toString() else "Step error: ${res.error ?: "Unknown error"}").trim()
            }.trim()

            val finalResult =
                ToolResult(
                    toolName = displayToolName,
                    success = lastResult.success,
                    result = StringResultData(combinedResultString),
                    error = lastResult.error
                )
            toolHandler.notifyToolExecutionResult(invocation.tool, finalResult)
            return finalResult
        } finally {
            toolHandler.notifyToolExecutionFinished(invocation.tool)
        }
    }

    /**
     * 构建工具不可用的错误信息，统一逻辑避免重复
     */
    private suspend fun buildToolNotAvailableErrorMessage(
        toolName: String,
        packageManager: PackageManager,
        toolHandler: AIToolHandler
    ): String {
        return when {
            toolName.contains('.') && !toolName.contains(':') -> {
                val parts = toolName.split('.', limit = 2)
                "Tool invocation syntax error: for tools inside a package, use the 'packName:toolName' format instead of '${toolName}'. You may want to call '${parts.getOrNull(0)}:${parts.getOrNull(1)}'."
            }

            toolName.contains(':') -> {
                val parts = toolName.split(':', limit = 2)
                val packName = parts[0]
                val toolNamePart = parts.getOrNull(1) ?: ""
                val isJsPackageAvailable = packageManager.getAvailablePackages().containsKey(packName)
                val isMcpServerAvailable = packageManager.getAvailableServerPackages().containsKey(packName)
                val isAvailable = isJsPackageAvailable || isMcpServerAvailable

                if (!isAvailable) {
                    "The tool package or MCP server '$packName' does not exist."
                } else {
                    // 包存在，检查是否已激活（通过检查该包的任何工具是否已注册）
                    val packageTools =
                        packageManager.getPackageTools(packName)?.tools ?: emptyList()
                    val isAdviceTool = packageTools.any { it.advice && it.name == toolNamePart }
                    val isPackageActivated = packageTools
                        .filter { !it.advice }
                        .any { toolHandler.getToolExecutor("$packName:${it.name}") != null }

                    if (isAdviceTool) {
                        "Tool '$toolNamePart' is an advice-only entry in package '$packName' and is not executable."
                    } else if (isPackageActivated) {
                        // 包已激活但工具不存在
                        "Tool '$toolNamePart' does not exist in tool package '$packName'. Please use the 'use_package' tool and specify package name '$packName' to list all available tools in this package."
                    } else {
                        // 包未激活
                        "Tool package '$packName' is not activated. Auto-activation was attempted but failed, or tool '$toolNamePart' does not exist. Please use 'use_package' with package name '$packName' to check available tools."
                    }
                }
            }

            else -> {
                // 检查是否直接把包名当作工具名调用了
                val isPackageName = packageManager.getAvailablePackages().containsKey(toolName)
                if (isPackageName) {
                    "Error: '$toolName' is a tool package, not a tool. Please use the 'use_package' tool with package name '$toolName' to activate this package before using its tools."
                } else {
                    "Tool '${toolName}' is unavailable or does not exist. If this is a tool inside a package, call it using the 'packName:toolName' format."
                }
            }
        }
    }

}
