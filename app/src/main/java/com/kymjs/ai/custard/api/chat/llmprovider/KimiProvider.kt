package com.kymjs.ai.custard.api.chat.llmprovider

import android.content.Context
import com.kymjs.ai.custard.data.model.ApiProviderType
import com.kymjs.ai.custard.data.model.ModelParameter
import com.kymjs.ai.custard.data.model.ToolPrompt
import com.kymjs.ai.custard.util.AppLogger
import com.kymjs.ai.custard.util.ChatUtils
import com.kymjs.ai.custard.util.stream.Stream
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kimi K2.5 Provider (Moonshot API).
 * Mirrors DeepseekProvider behavior for reasoning_content handling when thinking is enabled.
 */
class KimiProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: ApiProviderType = ApiProviderType.MOONSHOT,
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
        fun applyThinkingParams(jsonObject: JSONObject) {
            jsonObject.put(
                "thinking",
                JSONObject().apply {
                    put("type", if (enableThinking) "enabled" else "disabled")
                }
            )
        }

        if (!enableThinking) {
            val baseRequestBodyJson =
                super.createRequestBodyInternal(context, message, chatHistory, modelParameters, stream, availableTools, preserveThinkInHistory)
            val jsonObject = JSONObject(baseRequestBodyJson)
            applyThinkingParams(jsonObject)
            return jsonObject.toString().toRequestBody(JSON)
        }

        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream)
        applyThinkingParams(jsonObject)

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
                            AppLogger.w("KimiProvider", "OBJECT参数解析失败: ${param.apiName}", e)
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

        val effectiveEnableToolCall = enableToolCall && availableTools != null && availableTools.isNotEmpty()

        var toolsJson: String? = null
        if (effectiveEnableToolCall) {
            val tools = buildToolDefinitions(availableTools!!)
            if (tools.length() > 0) {
                jsonObject.put("tools", tools)
                jsonObject.put("tool_choice", "auto")
                toolsJson = tools.toString()
            }
        }

        val messagesArray = buildMessagesWithReasoning(context, message, chatHistory, effectiveEnableToolCall)
        jsonObject.put("messages", messagesArray)

        tokenCacheManager.calculateInputTokens(message, chatHistory, toolsJson)

        val logJson = JSONObject(jsonObject.toString())
        if (logJson.has("tools")) {
            val toolsArray = logJson.getJSONArray("tools")
            logJson.put("tools", "[${toolsArray.length()} tools omitted for brevity]")
        }
        val sanitizedLogJson = sanitizeImageDataForLogging(logJson)
        logLargeString("KimiProvider", sanitizedLogJson.toString(4), "Final Kimi K2.5 request body: ")

        return jsonObject.toString().toRequestBody(JSON)
    }

    private fun buildMessagesWithReasoning(
        context: Context,
        message: String,
        chatHistory: List<Pair<String, String>>,
        useToolCall: Boolean
    ): JSONArray {
        val messagesArray = JSONArray()

        val isMessageInHistory = chatHistory.isNotEmpty() && chatHistory.last().second == message
        val effectiveHistory = if (isMessageInHistory) {
            chatHistory
        } else {
            chatHistory + ("user" to message)
        }

        val lastToolCallIds = mutableListOf<String>()

        if (effectiveHistory.isNotEmpty()) {
            val standardizedHistory = ChatUtils.mapChatHistoryToStandardRoles(effectiveHistory, extractThinking = true)
            val mergedHistory = mutableListOf<Pair<String, String>>()

            for ((role, content) in standardizedHistory) {
                if (mergedHistory.isNotEmpty() && role == mergedHistory.last().first && role != "system") {
                    val lastMessage = mergedHistory.last()
                    mergedHistory[mergedHistory.size - 1] =
                        Pair(lastMessage.first, lastMessage.second + "\n" + content)
                } else {
                    mergedHistory.add(Pair(role, content))
                }
            }

            for ((role, originalContent) in mergedHistory) {
                if (role == "assistant") {
                    val (content, reasoningContent) = ChatUtils.extractThinkingContent(originalContent)

                    if (useToolCall) {
                        val (textContent, toolCalls) = parseXmlToolCalls(content)
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)
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
                            lastToolCallIds.clear()
                            for (i in 0 until toolCalls.length()) {
                                lastToolCallIds.add(toolCalls.getJSONObject(i).getString("id"))
                            }
                        }

                        messagesArray.put(historyMessage)
                    } else {
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)
                        historyMessage.put("reasoning_content", reasoningContent)
                        historyMessage.put("content", buildContentField(context, content.ifBlank { "[Empty]" }))
                        messagesArray.put(historyMessage)
                    }
                } else {
                    if (useToolCall && role == "user") {
                        val (textContent, toolResults) = parseXmlToolResults(originalContent)
                        var hasHandledToolCalls = false

                        if (lastToolCallIds.isNotEmpty()) {
                            val resultsList = toolResults ?: emptyList()
                            val resultCount = resultsList.size
                            val callCount = lastToolCallIds.size

                            for (i in 0 until callCount) {
                                val toolCallId = lastToolCallIds[i]
                                val toolMessage = JSONObject()
                                toolMessage.put("role", "tool")
                                toolMessage.put("tool_call_id", toolCallId)

                                if (i < resultCount) {
                                    val (_, resultContent) = resultsList[i]
                                    toolMessage.put("content", resultContent)
                                } else {
                                    toolMessage.put("content", "User cancelled")
                                }
                                messagesArray.put(toolMessage)
                            }

                            hasHandledToolCalls = true
                            if (resultCount > callCount) {
                                AppLogger.w("KimiProvider", "发现多余的tool_result: $resultCount results vs $callCount tool_calls")
                            }
                            lastToolCallIds.clear()
                        }

                        if (textContent.isNotEmpty()) {
                            val userMessage = JSONObject()
                            userMessage.put("role", "user")
                            userMessage.put("content", buildContentField(context, textContent))
                            messagesArray.put(userMessage)
                        } else if (!hasHandledToolCalls) {
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
        return super.sendMessage(
            context,
            message,
            chatHistory,
            modelParameters,
            enableThinking,
            stream,
            availableTools,
            preserveThinkInHistory,
            onTokensUpdated,
            onNonFatalError
        )
    }
}
