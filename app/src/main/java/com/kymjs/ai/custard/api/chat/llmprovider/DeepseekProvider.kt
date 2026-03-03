package com.kymjs.ai.custard.api.chat.llmprovider

import android.content.Context
import com.kymjs.ai.custard.util.AppLogger
import com.kymjs.ai.custard.data.model.ModelParameter
import com.kymjs.ai.custard.data.model.ToolPrompt
import com.kymjs.ai.custard.util.ChatUtils
import com.kymjs.ai.custard.util.stream.Stream
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 针对DeepSeek模型的特定API Provider。
 * 继承自OpenAIProvider，以重用大部分兼容逻辑，但特别处理了`reasoning_content`参数。
 * 当启用推理模式时，会将assistant消息中的<think>标签内容提取出来作为reasoning_content字段。
 */
class DeepseekProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: com.kymjs.ai.custard.data.model.ApiProviderType = com.kymjs.ai.custard.data.model.ApiProviderType.DEEPSEEK,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false
) : OpenAIProvider(
        apiEndpoint = apiEndpoint,
        apiKeyProvider = apiKeyProvider,
        modelName = modelName,
        client = client,
        customHeaders = customHeaders,
        providerType = providerType,
        supportsVision = supportsVision,
        supportsAudio = supportsAudio,
        supportsVideo = supportsVideo,
        enableToolCall = enableToolCall
    ) {

    /**
     * 重写创建请求体的方法，以支持DeepSeek的`reasoning_content`参数。
     * 当启用推理模式时，需要特殊处理消息格式。
     */
    override fun createRequestBody(
        context: Context,
        message: String,
        chatHistory: List<Pair<String, String>>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean
    ): RequestBody {
        fun applyThinkingParamsIfNeeded(jsonObject: JSONObject) {
            if (!enableThinking) return

            // DeepSeek Thinking Mode: thinking: { type: enabled }
            jsonObject.put(
                "thinking",
                JSONObject().apply {
                    put("type", "enabled")
                }
            )
        }

        // 如果未启用推理模式，直接使用父类的实现
        // 推理模式固定开启，需要特殊处理
        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream)

        // DeepSeek Thinking Mode (官方字段为 thinking: { enabled/disabled })
        // 这里仅在 enableThinking=true 时开启。
        applyThinkingParamsIfNeeded(jsonObject)

        // 添加已启用的模型参数
        for (param in modelParameters) {
            if (param.isEnabled) {
                when (param.valueType) {
                    com.kymjs.ai.custard.data.model.ParameterValueType.INT ->
                        jsonObject.put(param.apiName, param.currentValue as Int)
                    com.kymjs.ai.custard.data.model.ParameterValueType.FLOAT ->
                        jsonObject.put(param.apiName, param.currentValue as Float)
                    com.kymjs.ai.custard.data.model.ParameterValueType.STRING ->
                        jsonObject.put(param.apiName, param.currentValue as String)
                    com.kymjs.ai.custard.data.model.ParameterValueType.BOOLEAN ->
                        jsonObject.put(param.apiName, param.currentValue as Boolean)
                    com.kymjs.ai.custard.data.model.ParameterValueType.OBJECT -> {
                        val raw = param.currentValue.toString().trim()
                        val parsed: Any? = try {
                            when {
                                raw.startsWith("{") -> JSONObject(raw)
                                raw.startsWith("[") -> JSONArray(raw)
                                else -> null
                            }
                        } catch (e: Exception) {
                            AppLogger.w("DeepseekProvider", "OBJECT参数解析失败: ${param.apiName}", e)
                            null
                        }
                        if (parsed != null) {
                            jsonObject.put(param.apiName, parsed)
                        } else {
                            jsonObject.put(param.apiName, raw)
                        }
                    }
                }
            }
        }

        // 当工具为空时，将enableToolCall视为false
        val effectiveEnableToolCall = enableToolCall && availableTools != null && availableTools.isNotEmpty()

        // 如果启用Tool Call且传入了工具列表，添加tools定义
        var toolsJson: String? = null
        if (effectiveEnableToolCall) {
            val tools = buildToolDefinitions(availableTools!!)
            if (tools.length() > 0) {
                jsonObject.put("tools", tools)
                jsonObject.put("tool_choice", "auto")
                toolsJson = tools.toString()
            }
        }

        // 使用特殊的消息构建方法（支持reasoning_content）
        val messagesArray = buildMessagesWithReasoning(context, message, chatHistory, effectiveEnableToolCall)
        jsonObject.put("messages", messagesArray)

        // ⚠️ 重要：调用 TokenCacheManager 计算输入 token 数量
        // 虽然 buildMessagesWithReasoning 不返回 token 计数，但我们需要更新缓存管理器的状态
        tokenCacheManager.calculateInputTokens(message, chatHistory, toolsJson)

        // 记录最终的请求体（省略过长的tools字段）
        val logJson = JSONObject(jsonObject.toString())
        if (logJson.has("tools")) {
            val toolsArray = logJson.getJSONArray("tools")
            logJson.put("tools", "[${toolsArray.length()} tools omitted for brevity]")
        }
        val sanitizedLogJson = sanitizeImageDataForLogging(logJson)
        logLargeString("DeepseekProvider", sanitizedLogJson.toString(4), "Final DeepSeek reasoning mode request body: ")

        return jsonObject.toString().toRequestBody(JSON)
    }

    /**
     * 构建支持reasoning_content的消息数组
     * 对于assistant角色的消息，提取<think>标签内容作为reasoning_content
     */
    private fun buildMessagesWithReasoning(
        context: Context,
        message: String,
        chatHistory: List<Pair<String, String>>,
        useToolCall: Boolean
    ): JSONArray {
        val messagesArray = JSONArray()

        // 检查当前消息是否已经在历史记录的末尾（避免重复）
        val isMessageInHistory = chatHistory.isNotEmpty() && chatHistory.last().second == message

        // 如果消息已在历史中，只处理历史；否则需要处理历史+当前消息
        val effectiveHistory = if (isMessageInHistory) {
            chatHistory
        } else {
            chatHistory + ("user" to message)
        }

        // 追踪上一个assistant消息中的tool_call_ids
        val lastToolCallIds = mutableListOf<String>()

        if (effectiveHistory.isNotEmpty()) {
            // 使用统一的角色映射，保留think标签内容
            val standardizedHistory = ChatUtils.mapChatHistoryToStandardRoles(effectiveHistory, extractThinking = true)
            val mergedHistory = mutableListOf<Pair<String, String>>()

            for ((role, content) in standardizedHistory) {
                if (mergedHistory.isNotEmpty() &&
                    role == mergedHistory.last().first &&
                    role != "system"
                ) {
                    val lastMessage = mergedHistory.last()
                    mergedHistory[mergedHistory.size - 1] =
                        Pair(lastMessage.first, lastMessage.second + "\n" + content)
                } else {
                    mergedHistory.add(Pair(role, content))
                }
            }

            for ((role, originalContent) in mergedHistory) {
                // 对于assistant消息，提取reasoning_content
                if (role == "assistant") {
                    // 提取think标签内容
                    val (content, reasoningContent) = ChatUtils.extractThinkingContent(originalContent)

                    if (useToolCall) {
                        // 启用Tool Call时，解析XML tool calls
                        val (textContent, toolCalls) = parseXmlToolCalls(content)
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)

                        // DeepSeek推理模式要求所有assistant消息都必须有reasoning_content字段
                        historyMessage.put("reasoning_content", reasoningContent)

                        val effectiveContent = if (content.isBlank()) {
                            "[Empty]"
                        } else if (textContent.isNotEmpty()) {
                            textContent
                        } else {
                            null
                        }

                        if (effectiveContent != null) {
                            historyMessage.put("content", buildContentField(context, effectiveContent))
                        } else {
                            historyMessage.put("content", null)
                        }

                        if (toolCalls != null && toolCalls.length() > 0) {
                            historyMessage.put("tool_calls", toolCalls)
                            // 记录tool_call_ids
                            lastToolCallIds.clear()
                            for (i in 0 until toolCalls.length()) {
                                lastToolCallIds.add(toolCalls.getJSONObject(i).getString("id"))
                            }
                        }

                        messagesArray.put(historyMessage)
                    } else {
                        // 不使用Tool Call时，简单处理
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)

                        // DeepSeek推理模式要求所有assistant消息都必须有reasoning_content字段
                        historyMessage.put("reasoning_content", reasoningContent)

                        historyMessage.put("content", buildContentField(context, content.ifBlank { "[Empty]" }))
                        messagesArray.put(historyMessage)
                    }
                } else {
                    // 非assistant消息，使用原有逻辑
                    if (useToolCall && role == "user") {
                        val (textContent, toolResults) = parseXmlToolResults(originalContent)
                        
                        // 标记是否处理了tool_call
                        var hasHandledToolCalls = false

                        if (lastToolCallIds.isNotEmpty()) {
                            val resultsList = toolResults ?: emptyList()
                            val resultCount = resultsList.size
                            val callCount = lastToolCallIds.size

                            // 遍历所有待处理的tool call
                            for (i in 0 until callCount) {
                                val toolCallId = lastToolCallIds[i]
                                val toolMessage = JSONObject()
                                toolMessage.put("role", "tool")
                                toolMessage.put("tool_call_id", toolCallId)

                                if (i < resultCount) {
                                    // 有对应的结果
                                    val (_, resultContent) = resultsList[i]
                                    toolMessage.put("content", resultContent)
                                } else {
                                    // 没有结果，补充取消状态
                                    toolMessage.put("content", "User cancelled")
                                }
                                messagesArray.put(toolMessage)
                            }
                            
                            hasHandledToolCalls = true
                            
                            // 如果有多余的tool_result，记录警告
                            if (resultCount > callCount) {
                                AppLogger.w("DeepseekProvider", "发现多余的tool_result: $resultCount results vs $callCount tool_calls")
                            }
                            
                            // 使用后清空
                            lastToolCallIds.clear()
                        }

                        if (textContent.isNotEmpty()) {
                            val userMessage = JSONObject()
                            userMessage.put("role", "user")
                            userMessage.put("content", buildContentField(context, textContent))
                            messagesArray.put(userMessage)
                        } else if (!hasHandledToolCalls) {
                            // 如果没有处理任何tool_call，保留原始内容
                            val historyMessage = JSONObject()
                            historyMessage.put("role", role)
                            historyMessage.put("content", buildContentField(context, originalContent))
                            messagesArray.put(historyMessage)
                        } else {
                        }
                    } else {
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)
                        historyMessage.put("content", buildContentField(context, originalContent))
                        messagesArray.put(historyMessage)
                    }
                }
            }
        }

        return messagesArray
    }

    override suspend fun sendMessage(
        context: Context,
        message: String,
        chatHistory: List<Pair<String, String>>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onNonFatalError: suspend (error: String) -> Unit
    ): Stream<String> {
        // 直接调用父类的sendMessage实现
        return super.sendMessage(context, message, chatHistory, modelParameters, enableThinking, stream, availableTools, preserveThinkInHistory, onTokensUpdated, onNonFatalError)
    }
}
