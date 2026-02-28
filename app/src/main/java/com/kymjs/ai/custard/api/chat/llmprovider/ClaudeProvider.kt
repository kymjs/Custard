package com.kymjs.ai.custard.api.chat.llmprovider

import android.content.Context
import com.kymjs.ai.custard.util.AppLogger
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.data.model.ApiProviderType
import com.kymjs.ai.custard.data.model.ModelOption
import com.kymjs.ai.custard.data.model.ModelParameter
import com.kymjs.ai.custard.data.model.ToolPrompt
import com.kymjs.ai.custard.api.chat.llmprovider.EndpointCompleter
import com.kymjs.ai.custard.util.ChatUtils
import com.kymjs.ai.custard.util.StreamingJsonXmlConverter
import com.kymjs.ai.custard.util.ChatMarkupRegex
import com.kymjs.ai.custard.util.TokenCacheManager
import com.kymjs.ai.custard.util.exceptions.UserCancellationException
import com.kymjs.ai.custard.util.stream.SharedStream
import com.kymjs.ai.custard.util.stream.Stream
import com.kymjs.ai.custard.util.stream.StreamCollector
import com.kymjs.ai.custard.util.stream.stream
import com.kymjs.ai.custard.api.chat.llmprovider.MediaLinkParser
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Claude API的实现，处理Claude特有的API格式 */
class ClaudeProvider(
    private val apiEndpoint: String,
    private val apiKeyProvider: ApiKeyProvider,
    private val modelName: String,
    private val client: OkHttpClient,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val providerType: ApiProviderType = ApiProviderType.ANTHROPIC,
    private val enableToolCall: Boolean = false // 是否启用Tool Call接口（预留，Claude有原生tool支持）
) : AIService {
    // private val client: OkHttpClient = HttpClientFactory.instance

    private val JSON = "application/json".toMediaType()
    private val ANTHROPIC_VERSION = "2023-06-01" // Claude API版本

     private val DEFAULT_MAX_TOKENS = 4096

    // 当前活跃的Call对象，用于取消流式传输
    private var activeCall: Call? = null
    private var activeResponse: Response? = null
    @Volatile private var isManuallyCancelled = false

    /**
     * 不可重试的异常，通常由客户端错误（如4xx状态码）引起
     */
    class NonRetriableException(message: String, cause: Throwable? = null) : IOException(message, cause)

    // 添加token计数器
    private val tokenCacheManager = TokenCacheManager()

    // 公开token计数
    override val inputTokenCount: Int
        get() = tokenCacheManager.totalInputTokenCount
    override val cachedInputTokenCount: Int
        get() = tokenCacheManager.cachedInputTokenCount
    override val outputTokenCount: Int
        get() = tokenCacheManager.outputTokenCount

    // 供应商:模型标识符
    override val providerModel: String
        get() = "${providerType.name}:$modelName"

    // 重置token计数
    override fun resetTokenCounts() {
        tokenCacheManager.resetTokenCounts()
    }

    // 取消当前流式传输
    override fun cancelStreaming() {
        isManuallyCancelled = true

        // 1. 强制关闭 Response（这会立即中断流读取操作）
        activeResponse?.let {
            try {
                it.close()
                AppLogger.d("AIService", "已强制关闭Response流")
            } catch (e: Exception) {
                AppLogger.w("AIService", "关闭Response时出错: ${e.message}")
            }
        }
        activeResponse = null

        // 2. 取消 Call
        activeCall?.let {
            if (!it.isCanceled()) {
                it.cancel()
                AppLogger.d("AIService", "已取消当前流式传输，Call已中断")
            }
        }
        activeCall = null

        AppLogger.d("AIService", "取消标志已设置，流读取将立即被中断")
    }

     private fun headersForLog(headers: Headers): String {
         return buildString {
             headers.names().forEach { name ->
                 val value = when {
                     name.equals("x-api-key", ignoreCase = true) -> "[REDACTED]"
                     name.equals("authorization", ignoreCase = true) -> "[REDACTED]"
                     else -> headers[name] ?: ""
                 }
                 append(name)
                 append(": ")
                 append(value)
                 append('\n')
             }
         }.trimEnd()
     }

    // ==================== Tool Call 支持 ====================

    /**
     * XML转义/反转义工具
     */
    private object XmlEscaper {
        fun escape(text: String): String {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;")
        }

        fun unescape(text: String): String {
            return text.replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&amp;", "&")
        }
    }

    private fun sanitizeToolCallId(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_' || ch == '-') {
                sb.append(ch)
            } else {
                sb.append('_')
            }
        }
        var out = sb.toString().replace(Regex("_+"), "_")
        out = out.trim('_')
        return if (out.isEmpty()) "toolu" else out
    }

    private fun stableIdHashPart(raw: String): String {
        val hash = raw.hashCode()
        val positive = if (hash == Int.MIN_VALUE) 0 else kotlin.math.abs(hash)
        var base = positive.toString(36)
        base = base.filter { it.isLetterOrDigit() }.lowercase()
        return if (base.isEmpty()) "0" else base
    }

    /**
     * 解析XML格式的tool调用，转换为Claude Tool格式
     * @return Pair<文本内容, tool_use数组>
     */
    private fun parseXmlToolCalls(content: String): Pair<String, JSONArray?> {
        if (!enableToolCall) return Pair(content, null)

        val matches = ChatMarkupRegex.toolCallPattern.findAll(content)

        if (!matches.any()) {
            return Pair(content, null)
        }

        val toolUses = JSONArray()
        var textContent = content
        var callIndex = 0

        matches.forEach { match ->
            val toolName = match.groupValues[1]
            val toolBody = match.groupValues[2]

            // 解析参数
            val input = JSONObject()

            ChatMarkupRegex.toolParamPattern.findAll(toolBody).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                input.put(paramName, paramValue)
            }

            // 构建tool_use对象（Claude格式）
            val toolNamePart = sanitizeToolCallId(toolName)
            val hashPart = stableIdHashPart("${toolName}:${input}")
            val callId = sanitizeToolCallId("toolu_${toolNamePart}_${hashPart}_$callIndex")
            toolUses.put(JSONObject().apply {
                put("type", "tool_use")
                put("id", callId)
                put("name", toolName)
                put("input", input)
            })

            callIndex++
            AppLogger.d("AIService", "XML→ClaudeToolUse: $toolName -> ID: $callId")

            // 从文本内容中移除tool标签
            textContent = textContent.replace(match.value, "")
        }
        
        return Pair(textContent.trim(), toolUses)
    }
    
    /**
     * 解析XML格式的tool_result，转换为Claude Tool Result格式
     * @return Pair<文本内容, tool_result数组>
     */
    private fun parseXmlToolResults(content: String): Pair<String, List<Pair<String, String>>?> {
        if (!enableToolCall) return Pair(content, null)
        
        val matches = ChatMarkupRegex.toolResultAnyPattern.findAll(content)
        
        if (!matches.any()) {
            return Pair(content, null)
        }
        
        val results = mutableListOf<Pair<String, String>>()
        var textContent = content
        var resultIndex = 0
        
        matches.forEach { match ->
            val fullContent = match.groupValues[1].trim()
            val contentMatch = ChatMarkupRegex.contentTag.find(fullContent)
            val resultContent = if (contentMatch != null) {
                contentMatch.groupValues[1].trim()
            } else {
                fullContent
            }
            
            results.add(Pair("toolu_result_${resultIndex}", resultContent))
            textContent = textContent.replace(match.value, "").trim()
            
            AppLogger.d("AIService", "解析Claude tool_result #$resultIndex, content length=${resultContent.length}")
            resultIndex++
        }
        
        return Pair(textContent.trim(), results)
    }
    
    /**
     * 从ToolPrompt列表构建Claude格式的Tool Definitions
     */
    private fun buildToolDefinitionsForClaude(toolPrompts: List<ToolPrompt>): JSONArray {
        val tools = JSONArray()
        
        for (tool in toolPrompts) {
            tools.put(JSONObject().apply {
                put("name", tool.name)
                // 组合description和details作为完整描述
                val fullDescription = if (tool.details.isNotEmpty()) {
                    "${tool.description}\n${tool.details}"
                } else {
                    tool.description
                }
                put("description", fullDescription)
                
                // 使用结构化参数构建input_schema
                val inputSchema = buildSchemaFromStructured(tool.parametersStructured ?: emptyList())
                put("input_schema", inputSchema)
            })
        }
        
        return tools
    }
    
    /**
     * 从结构化参数构建JSON Schema（Claude格式）
     */
    private fun buildSchemaFromStructured(params: List<com.kymjs.ai.custard.data.model.ToolParameterSchema>): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
        }
        
        val properties = JSONObject()
        val required = JSONArray()
        
        for (param in params) {
            properties.put(param.name, JSONObject().apply {
                put("type", param.type)
                put("description", param.description)
                if (param.default != null) {
                    put("default", param.default)
                }
            })
            
            if (param.required) {
                required.put(param.name)
            }
        }
        
        schema.put("properties", properties)
        if (required.length() > 0) {
            schema.put("required", required)
        }
        
        return schema
    }
    
    /**
     * 构建包含文本和图片的content数组
     */
    private fun buildContentArray(text: String): JSONArray {
        val contentArray = JSONArray()

        val textAfterMediaRemoval = if (MediaLinkParser.hasMediaLinks(text)) {
            AppLogger.w("AIService", "检测到音视频链接，但Claude格式当前仅支持图片，多媒体链接将被移除")
            MediaLinkParser.removeMediaLinks(text).trim()
        } else {
            text
        }
        
        // 检查是否包含图片链接
        if (MediaLinkParser.hasImageLinks(textAfterMediaRemoval)) {
            val imageLinks = MediaLinkParser.extractImageLinks(textAfterMediaRemoval)
            val textWithoutLinks = MediaLinkParser.removeImageLinks(textAfterMediaRemoval).trim()
            
            // 添加图片
            imageLinks.forEach { link ->
                contentArray.put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", link.mimeType)
                        put("data", link.base64Data)
                    })
                })
            }
            
            // 添加文本（如果有）
            if (textWithoutLinks.isNotEmpty()) {
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", textWithoutLinks)
                })
            }
        } else {
            // 纯文本消息
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", textAfterMediaRemoval)
            })
        }
        
        return contentArray
    }

    /**
     * 构建Claude的消息体和计算Token的核心逻辑
     */
    private fun buildMessagesAndCountTokens(
            message: String,
            chatHistory: List<Pair<String, String>>,
            preserveThinkInHistory: Boolean
    ): Triple<JSONArray, String?, Int> {
        val messagesArray = JSONArray()

        // 使用TokenCacheManager计算token数量
        val tokenCount = tokenCacheManager.calculateInputTokens(message, chatHistory)

        // 检查当前消息是否已经在历史记录的末尾（避免重复）
        val isMessageInHistory = chatHistory.isNotEmpty() && chatHistory.last().second == message
        
        // 如果消息已在历史中，只处理历史；否则需要处理历史+当前消息
        val effectiveHistory = if (isMessageInHistory) {
            chatHistory
        } else {
            chatHistory + ("user" to message)
        }

        // 提取系统消息
        val systemMessages = effectiveHistory.filter { it.first.equals("system", ignoreCase = true) }
        var systemPrompt: String? = null

        if (systemMessages.isNotEmpty()) {
            systemPrompt = systemMessages.joinToString("\n\n") { it.second }
        }

        // 处理用户和助手消息
        val historyWithoutSystem =
                ChatUtils.mapChatHistoryToStandardRoles(effectiveHistory, extractThinking = preserveThinkInHistory).filter {
                    it.first != "system"
                }
        
        val mergedHistory = mutableListOf<Pair<String, String>>()
        for ((role, content) in historyWithoutSystem) {
            if (mergedHistory.isNotEmpty() && mergedHistory.last().first == role) {
                val lastMessage = mergedHistory.last()
                mergedHistory[mergedHistory.size - 1] =
                    Pair(role, lastMessage.second + "\n" + content)
            } else {
                mergedHistory.add(Pair(role, content))
            }
        }

        // 追踪上一个assistant消息中的tool_use ids，用于匹配tool结果
        val lastToolUseIds = mutableListOf<String>()
        
        // 添加历史消息
        for ((role, content) in mergedHistory) {
            val claudeRole = if (role == "assistant") "assistant" else "user"
            
            // 当启用Tool Call API时，转换XML格式的工具调用
            if (enableToolCall) {
                if (role == "assistant") {
                    // 解析assistant消息中的XML tool calls
                    val (textContent, toolUses) = parseXmlToolCalls(content)
                    
                    val contentArray = JSONArray()
                    // 先添加文本内容
                    if (textContent.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", textContent)
                        })
                    }
                    // 再添加tool_use
                    if (toolUses != null && toolUses.length() > 0) {
                        for (i in 0 until toolUses.length()) {
                            contentArray.put(toolUses.getJSONObject(i))
                        }
                        // 记录这些tool_use ids供后续tool_result使用
                        lastToolUseIds.clear()
                        for (i in 0 until toolUses.length()) {
                            lastToolUseIds.add(toolUses.getJSONObject(i).getString("id"))
                        }
                    }
                    
                    val messageObject = JSONObject()
                    messageObject.put("role", claudeRole)
                    messageObject.put("content", contentArray)
                    messagesArray.put(messageObject)
                } else if (role == "user") {
                    // 解析user消息中的XML tool_result
                    val (textContent, toolResults) = parseXmlToolResults(content)
                    
                    val contentArray = JSONArray()
                    // 先添加tool_result（只转换有对应tool_use_id的）
                    if (toolResults != null && toolResults.isNotEmpty() && lastToolUseIds.isNotEmpty()) {
                        // 只转换有对应tool_use_id的tool_result
                        val validCount = minOf(toolResults.size, lastToolUseIds.size)
                        
                        for (index in 0 until validCount) {
                            val (_, resultContent) = toolResults[index]
                            contentArray.put(JSONObject().apply {
                                put("type", "tool_result")
                                put("tool_use_id", lastToolUseIds[index])
                                put("content", resultContent)
                            })
                            AppLogger.d("AIService", "历史XML→ClaudeToolResult: ID=${lastToolUseIds[index]}, content length=${resultContent.length}")
                        }
                        
                        // 如果有多余的tool_result，记录警告
                        if (toolResults.size > validCount) {
                            AppLogger.w("AIService", "发现多余的tool_result: ${toolResults.size} results vs ${lastToolUseIds.size} tool_uses，忽略多余的${toolResults.size - validCount}个")
                        }
                        
                        lastToolUseIds.clear()
                    }
                    // 再添加文本内容
                    if (textContent.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", textContent)
                        })
                    } else if (contentArray.length() == 0) {
                        // 如果没有任何内容，保留原始content
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", content)
                        })
                    }
                    
                    val messageObject = JSONObject()
                    messageObject.put("role", claudeRole)
                    messageObject.put("content", contentArray)
                    messagesArray.put(messageObject)
                } else {
                    // system等其他角色正常处理
                    val messageObject = JSONObject()
                    messageObject.put("role", claudeRole)
                    messageObject.put("content", buildContentArray(content))
                    messagesArray.put(messageObject)
                }
            } else {
                // 不启用Tool Call API时，保持原样
                val messageObject = JSONObject()
                messageObject.put("role", claudeRole)
                messageObject.put("content", buildContentArray(content))
                messagesArray.put(messageObject)
            }
        }

        return Triple(messagesArray, systemPrompt, tokenCount)
    }


    override suspend fun calculateInputTokens(
            message: String,
            chatHistory: List<Pair<String, String>>,
            availableTools: List<ToolPrompt>?
    ): Int {
        // 构建工具定义的JSON字符串
        val toolsJson = if (enableToolCall && availableTools != null && availableTools.isNotEmpty()) {
            val tools = buildToolDefinitionsForClaude(availableTools)
            if (tools.length() > 0) tools.toString() else null
        } else {
            null
        }
        // 使用缓存管理器进行快速估算
        return tokenCacheManager.calculateInputTokens(message, chatHistory, toolsJson)
    }

    // 创建Claude API请求体
    private fun createRequestBody(
            message: String,
            chatHistory: List<Pair<String, String>>,
            modelParameters: List<ModelParameter<*>> = emptyList(),
            enableThinking: Boolean,
            stream: Boolean = true,
            availableTools: List<ToolPrompt>? = null,
            preserveThinkInHistory: Boolean = false
    ): RequestBody {
        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream)

        // 添加已启用的模型参数
        addParameters(jsonObject, modelParameters)

         val maxTokensFromParams = modelParameters
             .firstOrNull { it.apiName == "max_tokens" }
             ?.currentValue
         val maxTokensValue = (maxTokensFromParams as? Number)?.toInt()?.takeIf { it > 0 }
             ?: jsonObject.optInt("max_tokens", 0).takeIf { it > 0 }
             ?: DEFAULT_MAX_TOKENS
         jsonObject.put("max_tokens", maxTokensValue)

        // 添加 Tool Call 工具定义（如果启用且有可用工具）
        var toolsJson: String? = null
        if (enableToolCall && availableTools != null && availableTools.isNotEmpty()) {
            val tools = buildToolDefinitionsForClaude(availableTools)
            if (tools.length() > 0) {
                jsonObject.put("tools", tools)
                toolsJson = tools.toString() // 保存工具定义用于token计算
                AppLogger.d("AIService", "已添加 ${tools.length()} 个 Claude Tool Definitions")
            }
        }

        // 使用TokenCacheManager计算输入token（包含工具定义），并继续使用原有逻辑构建消息体
        tokenCacheManager.calculateInputTokens(message, chatHistory, toolsJson)
        val (messagesArray, systemPrompt, _) = buildMessagesAndCountTokens(message, chatHistory, preserveThinkInHistory)

        jsonObject.put("messages", messagesArray)

        // Claude对系统消息的处理有所不同，它使用system参数
        if (systemPrompt != null) {
            jsonObject.put("system", systemPrompt)
        }

        // 添加extended thinking支持
        if (enableThinking) {
            val thinkingObject = JSONObject()
            thinkingObject.put("type", "enabled")

             val budgetTokensFromParams = modelParameters
                 .firstOrNull { it.apiName == "budget_tokens" }
                 ?.currentValue
             val budgetTokensValue = (budgetTokensFromParams as? Number)?.toInt()?.takeIf { it > 0 }
                 ?: minOf(1024, maxTokensValue)
             thinkingObject.put("budget_tokens", budgetTokensValue)

            jsonObject.put("thinking", thinkingObject)
            AppLogger.d("AIService", "启用Claude的extended thinking功能")
        }

        // 日志输出时省略过长的tools字段
        val logJson = JSONObject(jsonObject.toString())
        if (logJson.has("tools")) {
            val toolsArray = logJson.getJSONArray("tools")
            logJson.put("tools", "[${toolsArray.length()} tools omitted for brevity]")
        }
        AppLogger.d("AIService", "Claude请求体: ${logJson.toString(4)}")
        return jsonObject.toString().toRequestBody(JSON)
    }

    // 添加模型参数
    private fun addParameters(jsonObject: JSONObject, modelParameters: List<ModelParameter<*>>) {
        for (param in modelParameters) {
            if (param.isEnabled) {
                when (param.apiName) {
                    "temperature" ->
                            jsonObject.put("temperature", (param.currentValue as Number).toFloat())
                    "top_p" -> jsonObject.put("top_p", (param.currentValue as Number).toFloat())
                    "top_k" -> jsonObject.put("top_k", (param.currentValue as Number).toInt())
                    "max_tokens" ->
                            jsonObject.put("max_tokens", (param.currentValue as Number).toInt())
                    "max_tokens_to_sample" ->
                            jsonObject.put(
                                    "max_tokens_to_sample",
                                    (param.currentValue as Number).toInt()
                            )
                    "stop_sequences" -> {
                        // 处理停止序列
                        val stopSequences = param.currentValue as? List<*>
                        if (stopSequences != null) {
                            val stopArray = JSONArray()
                            stopSequences.forEach { stopArray.put(it.toString()) }
                            jsonObject.put("stop_sequences", stopArray)
                        }
                    }
                    // 忽略thinking相关参数，因为它们会在单独的部分处理
                    "thinking",
                    "budget_tokens" -> {
                        // 忽略，在特定部分处理
                    }
                    else -> {
                        // 添加其他Claude特定参数
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
                                    AppLogger.w("AIService", "Claude OBJECT参数解析失败: ${param.apiName}", e)
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
                AppLogger.d("AIService", "添加Claude参数 ${param.apiName} = ${param.currentValue}")
            }
        }
    }

    // 创建请求
    private suspend fun createRequest(requestBody: RequestBody): Request {
        val currentApiKey = apiKeyProvider.getApiKey()
        val completedEndpoint = EndpointCompleter.completeEndpoint(apiEndpoint, providerType)
        val builder =
                Request.Builder()
                        .url(completedEndpoint)
                        .post(requestBody)
                        .addHeader("x-api-key", currentApiKey)
                        .addHeader("anthropic-version", ANTHROPIC_VERSION)
                        .addHeader("Content-Type", "application/json")

        // 添加自定义请求头
        customHeaders.forEach { (key, value) ->
            builder.addHeader(key, value)
        }

        val request = builder.build()
        AppLogger.d("AIService", "Claude请求URL: ${request.url}")
        AppLogger.d("AIService", "Claude请求头: \n${headersForLog(request.headers)}")
        return request
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
    ): Stream<String> = stream {
        isManuallyCancelled = false
        resetTokenCounts()

        val maxRetries = 3
        var retryCount = 0
        var lastException: Exception? = null
        val receivedContent = StringBuilder()

        fun parseAnthropicNonStreaming(jsonResponse: JSONObject): String {
            val content = jsonResponse.optJSONArray("content") ?: return ""
            if (content.length() <= 0) return ""
            val fullText = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> {
                        val text = block.optString("text", "")
                        if (text.isNotEmpty()) fullText.append(text)
                    }
                    "thinking" -> {
                        val thinking = block.optString("thinking", "")
                        if (thinking.isNotEmpty()) {
                            fullText.append("\n<think>")
                            fullText.append(thinking)
                            fullText.append("</think>\n")
                        }
                    }
                    "redacted_thinking" -> {
                    }
                    "tool_use" -> {
                        if (enableToolCall) {
                            val toolName = block.optString("name", "")
                            if (toolName.isNotEmpty()) {
                                fullText.append("\n<tool name=\"$toolName\">")
                                val input = block.optJSONObject("input")
                                if (input != null) {
                                    val converter = StreamingJsonXmlConverter()
                                    val events = converter.feed(input.toString())
                                    events.forEach { event ->
                                        when (event) {
                                            is StreamingJsonXmlConverter.Event.Tag -> fullText.append(event.text)
                                            is StreamingJsonXmlConverter.Event.Content -> fullText.append(event.text)
                                        }
                                    }
                                    val flushEvents = converter.flush()
                                    flushEvents.forEach { event ->
                                        when (event) {
                                            is StreamingJsonXmlConverter.Event.Tag -> fullText.append(event.text)
                                            is StreamingJsonXmlConverter.Event.Content -> fullText.append(event.text)
                                        }
                                    }
                                }
                                fullText.append("\n</tool>\n")
                            }
                        }
                    }
                }
            }
            return fullText.toString()
        }

        fun parseOpenAiNonStreaming(jsonResponse: JSONObject): String {
            val choices = jsonResponse.optJSONArray("choices") ?: return ""
            if (choices.length() <= 0) return ""
            val first = choices.optJSONObject(0) ?: return ""
            val messageObj = first.optJSONObject("message")
            return messageObj?.optString("content", "") ?: ""
        }

        AppLogger.d("AIService", "准备连接到Claude AI服务...")
        while (retryCount < maxRetries) {
            if (isManuallyCancelled) {
                AppLogger.d("AIService", "【Claude】请求被用户取消，停止重试。")
                throw UserCancellationException(context.getString(R.string.openai_error_request_cancelled))
            }

            val call = try {
                val currentMessage: String
                val currentHistory: List<Pair<String, String>>
                if (retryCount > 0 && receivedContent.isNotEmpty()) {
                    AppLogger.d("AIService", "【Claude 重试】准备续写请求，已接收内容: ${receivedContent.length}")
                    currentMessage = message + "\n\n[SYSTEM NOTE] The previous response was cut off by a network error. You MUST continue from the exact point of interruption. Do not repeat any content. If you were in the middle of a code block or XML tag, complete it. Just output the text that would have come next."
                    val resumeHistory = chatHistory.toMutableList()
                    resumeHistory.add("assistant" to receivedContent.toString())
                    currentHistory = resumeHistory
                } else {
                    currentMessage = message
                    currentHistory = chatHistory
                }

                val requestBody = createRequestBody(
                    currentMessage,
                    currentHistory,
                    modelParameters,
                    enableThinking,
                    stream,
                    availableTools,
                    preserveThinkInHistory
                )
                onTokensUpdated(
                    tokenCacheManager.totalInputTokenCount,
                    tokenCacheManager.cachedInputTokenCount,
                    tokenCacheManager.outputTokenCount
                )
                val request = createRequest(requestBody)
                client.newCall(request)
            } catch (e: Exception) {
                throw e
            }

            activeCall = call
            try {
                AppLogger.d("AIService", "正在建立连接...")
                withContext(Dispatchers.IO) {
                    val response = call.execute()
                    activeResponse = response
                    try {
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: context.getString(R.string.openai_error_no_error_details)
                            if (response.code in 400..499) {
                                throw NonRetriableException(context.getString(R.string.openai_error_api_request_failed_with_status, response.code, errorBody))
                            }
                            throw IOException(context.getString(R.string.openai_error_api_request_failed_with_status, response.code, errorBody))
                        }

                        AppLogger.d("AIService", "连接成功，等待响应...")
                        val responseBody = response.body ?: throw IOException(context.getString(R.string.provider_error_response_empty))

                        val contentType = response.header("Content-Type") ?: ""
                        AppLogger.d(
                            "AIService",
                            "Claude响应状态: code=${response.code}, contentType=$contentType"
                        )

                        val preview = runCatching { response.peekBody(4096).string() }.getOrNull().orEmpty()
                        val previewTrim = preview.trimStart()
                        val looksLikeJson = previewTrim.startsWith("{") || previewTrim.startsWith("[")
                        val looksLikeSse = previewTrim.startsWith("data:") || preview.contains("\ndata:")
                        val isEventStream = contentType.contains("event-stream", ignoreCase = true)
                        AppLogger.d(
                            "AIService",
                            "Claude响应格式检测: looksLikeJson=$looksLikeJson, looksLikeSse=$looksLikeSse, isEventStream=$isEventStream"
                        )

                        if (stream && !looksLikeSse && looksLikeJson) {
                            val responseText = responseBody.string().trim()
                            val json = JSONObject(responseText)
                            val resultText = parseAnthropicNonStreaming(json).ifBlank { parseOpenAiNonStreaming(json) }
                            if (resultText.isNotBlank()) {
                                emit(resultText)
                                receivedContent.append(resultText)
                                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(resultText))
                                onTokensUpdated(
                                    tokenCacheManager.totalInputTokenCount,
                                    tokenCacheManager.cachedInputTokenCount,
                                    tokenCacheManager.outputTokenCount
                                )
                            } else {
                                throw IOException(context.getString(R.string.provider_error_parsing_failed))
                            }
                            return@withContext
                        }

                        if (!stream) {
                            val responseText = responseBody.string().trim()
                            val json = JSONObject(responseText)
                            val resultText = parseAnthropicNonStreaming(json).ifBlank { parseOpenAiNonStreaming(json) }
                            if (resultText.isNotBlank()) {
                                emit(resultText)
                                receivedContent.append(resultText)
                                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(resultText))
                                onTokensUpdated(
                                    tokenCacheManager.totalInputTokenCount,
                                    tokenCacheManager.cachedInputTokenCount,
                                    tokenCacheManager.outputTokenCount
                                )
                            }
                            return@withContext
                        }

                        val reader = responseBody.charStream().buffered()
                        var currentToolParser: StreamingJsonXmlConverter? = null
                        var isInToolCall = false
                        var isInThinkingBlock = false
                        var emittedAny = false
                        val nonSseJsonLinesBuffer = StringBuilder()

                        while (true) {
                            val rawLine = reader.readLine() ?: break
                            val line = rawLine.trim()
                            if (activeCall?.isCanceled() == true) {
                                AppLogger.d("AIService", "流式传输已被取消，提前退出处理")
                                break
                            }
                            if (!line.startsWith("data:")) {
                                // 某些兼容端点可能直接返回 JSON/JSONL（不带 SSE 的 data: 前缀）
                                if ((line.startsWith("{") || line.startsWith("[")) &&
                                    nonSseJsonLinesBuffer.length < 2_000_000
                                ) {
                                    nonSseJsonLinesBuffer.append(line).append('\n')
                                }
                                continue
                            }
                            val data = line.substringAfter("data:").trimStart()
                            if (data == "[DONE]") break
                            if (data.isBlank()) continue

                            val jsonResponse = runCatching { JSONObject(data) }.getOrNull() ?: continue
                            val type = jsonResponse.optString("type", "")

                            // OpenAI-style chunk (no `type`)
                            if (type.isBlank()) {
                                val choices = jsonResponse.optJSONArray("choices")
                                val first = choices?.optJSONObject(0)
                                val delta = first?.optJSONObject("delta")
                                val content = delta?.optString("content", "").orEmpty()
                                if (content.isNotEmpty()) {
                                    emittedAny = true
                                    tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(content))
                                    onTokensUpdated(
                                        tokenCacheManager.totalInputTokenCount,
                                        tokenCacheManager.cachedInputTokenCount,
                                        tokenCacheManager.outputTokenCount
                                    )
                                    emit(content)
                                    receivedContent.append(content)
                                }
                                continue
                            }

                            when (type) {
                                "ping" -> {
                                }
                                "content_block_start" -> {
                                    val contentBlock = jsonResponse.optJSONObject("content_block")
                                    if (contentBlock != null) {
                                        when (contentBlock.optString("type")) {
                                            "tool_use" -> {
                                                if (enableToolCall) {
                                                    val toolName = contentBlock.optString("name", "")
                                                    if (toolName.isNotEmpty()) {
                                                        val toolStartTag = "\n<tool name=\"$toolName\">"
                                                        emittedAny = true
                                                        emit(toolStartTag)
                                                        receivedContent.append(toolStartTag)

                                                        currentToolParser = StreamingJsonXmlConverter()
                                                        isInToolCall = true

                                                        val input = contentBlock.optJSONObject("input")
                                                        if (input != null) {
                                                            val events = currentToolParser!!.feed(input.toString())
                                                            events.forEach { event ->
                                                                when (event) {
                                                                    is StreamingJsonXmlConverter.Event.Tag -> {
                                                                        emit(event.text)
                                                                        receivedContent.append(event.text)
                                                                    }
                                                                    is StreamingJsonXmlConverter.Event.Content -> {
                                                                        emit(event.text)
                                                                        receivedContent.append(event.text)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            "thinking" -> {
                                                val thinkingStartTag = "\n<think>"
                                                emittedAny = true
                                                emit(thinkingStartTag)
                                                receivedContent.append(thinkingStartTag)
                                                isInThinkingBlock = true

                                                val initialThinking = contentBlock.optString("thinking", "")
                                                if (initialThinking.isNotEmpty()) {
                                                    tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(initialThinking))
                                                    onTokensUpdated(
                                                        tokenCacheManager.totalInputTokenCount,
                                                        tokenCacheManager.cachedInputTokenCount,
                                                        tokenCacheManager.outputTokenCount
                                                    )
                                                    emit(initialThinking)
                                                    receivedContent.append(initialThinking)
                                                }
                                            }
                                            "redacted_thinking" -> {
                                            }
                                        }
                                    }
                                }
                                "content_block_delta" -> {
                                    val delta = jsonResponse.optJSONObject("delta")
                                    if (delta != null) {
                                        val deltaType = delta.optString("type", "")
                                        if (deltaType == "text_delta" || delta.has("text")) {
                                            val content = delta.optString("text", "")
                                            if (content.isNotEmpty()) {
                                                emittedAny = true
                                                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(content))
                                                onTokensUpdated(
                                                    tokenCacheManager.totalInputTokenCount,
                                                    tokenCacheManager.cachedInputTokenCount,
                                                    tokenCacheManager.outputTokenCount
                                                )
                                                emit(content)
                                                receivedContent.append(content)
                                            }
                                        } else if (isInThinkingBlock && (deltaType == "thinking_delta" || delta.has("thinking"))) {
                                            val thinking = delta.optString("thinking", "")
                                            if (thinking.isNotEmpty()) {
                                                emittedAny = true
                                                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(thinking))
                                                onTokensUpdated(
                                                    tokenCacheManager.totalInputTokenCount,
                                                    tokenCacheManager.cachedInputTokenCount,
                                                    tokenCacheManager.outputTokenCount
                                                )
                                                emit(thinking)
                                                receivedContent.append(thinking)
                                            }
                                        } else if (enableToolCall && isInToolCall && currentToolParser != null && deltaType == "input_json_delta") {
                                            val partialJson = delta.optString("partial_json", "")
                                            if (partialJson.isNotEmpty()) {
                                                val events = currentToolParser!!.feed(partialJson)
                                                events.forEach { event ->
                                                    when (event) {
                                                        is StreamingJsonXmlConverter.Event.Tag -> {
                                                            emit(event.text)
                                                            receivedContent.append(event.text)
                                                        }
                                                        is StreamingJsonXmlConverter.Event.Content -> {
                                                            emit(event.text)
                                                            receivedContent.append(event.text)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                "content_block_stop" -> {
                                    if (isInToolCall && currentToolParser != null) {
                                        val events = currentToolParser!!.flush()
                                        events.forEach { event ->
                                            when (event) {
                                                is StreamingJsonXmlConverter.Event.Tag -> {
                                                    emit(event.text)
                                                    receivedContent.append(event.text)
                                                }
                                                is StreamingJsonXmlConverter.Event.Content -> {
                                                    emit(event.text)
                                                    receivedContent.append(event.text)
                                                }
                                            }
                                        }
                                        val toolEndTag = "\n</tool>\n"
                                        emit(toolEndTag)
                                        receivedContent.append(toolEndTag)

                                        isInToolCall = false
                                        currentToolParser = null
                                    } else if (isInThinkingBlock) {
                                        val thinkingEndTag = "</think>\n"
                                        emit(thinkingEndTag)
                                        receivedContent.append(thinkingEndTag)
                                        isInThinkingBlock = false
                                    }
                                }
                                "message_delta" -> {
                                }
                                "message_stop" -> {
                                    if (isInToolCall && currentToolParser != null) {
                                        val events = currentToolParser!!.flush()
                                        events.forEach { event ->
                                            when (event) {
                                                is StreamingJsonXmlConverter.Event.Tag -> {
                                                    emit(event.text)
                                                    receivedContent.append(event.text)
                                                }
                                                is StreamingJsonXmlConverter.Event.Content -> {
                                                    emit(event.text)
                                                    receivedContent.append(event.text)
                                                }
                                            }
                                        }
                                        val toolEndTag = "\n</tool>\n"
                                        emit(toolEndTag)
                                        receivedContent.append(toolEndTag)
                                        isInToolCall = false
                                        currentToolParser = null
                                    }
                                    if (isInThinkingBlock) {
                                        val thinkingEndTag = "</think>\n"
                                        emit(thinkingEndTag)
                                        receivedContent.append(thinkingEndTag)
                                        isInThinkingBlock = false
                                    }
                                    break
                                }
                            }
                        }

                        if (!emittedAny && nonSseJsonLinesBuffer.isNotBlank()) {
                            val buffered = nonSseJsonLinesBuffer.toString().trim()
                            AppLogger.w(
                                "AIService",
                                "Claude流式返回疑似JSON/JSONL(无data:前缀)，尝试回退解析。preview=${buffered.take(200)}"
                            )

                            // 先尝试整体当成一个JSON对象解析
                            val wholeJson = runCatching { JSONObject(buffered) }.getOrNull()
                            if (wholeJson != null) {
                                val resultText = parseAnthropicNonStreaming(wholeJson)
                                    .ifBlank { parseOpenAiNonStreaming(wholeJson) }
                                if (resultText.isNotBlank()) {
                                    emittedAny = true
                                    emit(resultText)
                                    receivedContent.append(resultText)
                                    tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(resultText))
                                    onTokensUpdated(
                                        tokenCacheManager.totalInputTokenCount,
                                        tokenCacheManager.cachedInputTokenCount,
                                        tokenCacheManager.outputTokenCount
                                    )
                                }
                            } else {
                                // 再尝试逐行解析（JSONL），优先支持 OpenAI-style delta
                                buffered.lineSequence().forEach { jsonLine ->
                                    val t = jsonLine.trim()
                                    if (!t.startsWith("{")) return@forEach
                                    val obj = runCatching { JSONObject(t) }.getOrNull() ?: return@forEach
                                    val choices = obj.optJSONArray("choices") ?: return@forEach
                                    val first = choices.optJSONObject(0) ?: return@forEach
                                    val delta = first.optJSONObject("delta") ?: return@forEach
                                    val content = delta.optString("content", "")
                                    if (content.isNotBlank()) {
                                        emittedAny = true
                                        tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(content))
                                        onTokensUpdated(
                                            tokenCacheManager.totalInputTokenCount,
                                            tokenCacheManager.cachedInputTokenCount,
                                            tokenCacheManager.outputTokenCount
                                        )
                                        emit(content)
                                        receivedContent.append(content)
                                    }
                                }
                            }
                        }

                        if (!emittedAny && previewTrim.isNotEmpty() && looksLikeJson) {
                            AppLogger.w("AIService", "Claude流式响应未解析到任何内容，可能不是SSE，preview=${previewTrim.take(200)}")
                        }
                    } finally {
                        response.close()
                        AppLogger.d("AIService", "【Claude】关闭响应连接")
                    }
                }

                AppLogger.d("AIService", "【Claude】请求成功完成")
                return@stream
            } catch (e: NonRetriableException) {
                AppLogger.e("AIService", "【Claude】发生不可重试错误", e)
                throw e
            } catch (e: SocketTimeoutException) {
                if (isManuallyCancelled) {
                    AppLogger.d("AIService", "【Claude】请求被用户取消，停止重试。")
                    throw UserCancellationException(context.getString(R.string.openai_error_request_cancelled), e)
                }
                lastException = e
                retryCount++
                if (retryCount >= maxRetries) {
                    AppLogger.e("AIService", "【Claude】连接超时且达到最大重试次数", e)
                    throw IOException(context.getString(R.string.openai_error_timeout_max_retries, e.message ?: ""))
                }
                AppLogger.w("AIService", "【Claude】连接超时，正在进行第 $retryCount 次重试...", e)
                onNonFatalError(context.getString(R.string.provider_error_retry_message, context.getString(R.string.provider_error_timeout), retryCount))
                delay(1000L * (1 shl (retryCount - 1)))
            } catch (e: UnknownHostException) {
                if (isManuallyCancelled) {
                    AppLogger.d("AIService", "【Claude】请求被用户取消，停止重试。")
                    throw UserCancellationException(context.getString(R.string.openai_error_request_cancelled), e)
                }
                lastException = e
                retryCount++
                if (retryCount >= maxRetries) {
                    AppLogger.e("AIService", "【Claude】无法解析主机且达到最大重试次数", e)
                    throw IOException(context.getString(R.string.openai_error_cannot_connect))
                }
                AppLogger.w("AIService", "【Claude】无法解析主机，正在进行第 $retryCount 次重试...", e)
                onNonFatalError(context.getString(R.string.provider_error_retry_message, context.getString(R.string.provider_error_unknown_host), retryCount))
                delay(1000L * (1 shl (retryCount - 1)))
            } catch (e: IOException) {
                if (isManuallyCancelled) {
                    AppLogger.d("AIService", "【Claude】请求被用户取消，停止重试。")
                    throw UserCancellationException(context.getString(R.string.openai_error_request_cancelled), e)
                }
                lastException = e
                retryCount++
                if (retryCount >= maxRetries) {
                    AppLogger.e("AIService", "【Claude】达到最大重试次数", e)
                    throw IOException(context.getString(R.string.openai_error_max_retries, e.message ?: ""))
                }
                AppLogger.w("AIService", "【Claude】网络中断，正在进行第 $retryCount 次重试...", e)
                onNonFatalError(context.getString(R.string.provider_error_retry_message, context.getString(R.string.provider_error_network_interrupted), retryCount))
                delay(1000L * (1 shl (retryCount - 1)))
            } catch (e: Exception) {
                if (isManuallyCancelled) {
                    AppLogger.d("AIService", "【Claude】请求被用户取消，停止重试。")
                    throw UserCancellationException(context.getString(R.string.openai_error_request_cancelled), e)
                }
                AppLogger.e("AIService", "【Claude】发生未知异常，停止重试", e)
                throw IOException(context.getString(R.string.openai_error_response_failed, e.message ?: ""), e)
            } finally {
                activeCall = null
                activeResponse = null
            }
        }

        lastException?.let { ex ->
            AppLogger.e("AIService", "【Claude】重试失败，请检查网络连接", ex)
        } ?: AppLogger.e("AIService", "【Claude】重试失败，请检查网络连接")
        throw IOException(context.getString(R.string.openai_error_connection_timeout, maxRetries, lastException?.message ?: ""))
    }

    /**
     * 获取模型列表 注意：此方法直接调用ModelListFetcher获取模型列表
     * @return 模型列表结果
     */
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        // 调用ModelListFetcher获取模型列表
        return ModelListFetcher.getModelsList(
            context = context,
            apiKey = apiKeyProvider.getApiKey(),
            apiEndpoint = apiEndpoint,
            apiProviderType = providerType
        )
    }

    override suspend fun testConnection(context: Context): Result<String> {
        return try {
            // 通过发送一条短消息来测试完整的连接、认证和API端点。
            // 这比getModelsList更可靠，因为它直接命中了聊天API。
            // 提供一个通用的系统提示，以防止某些需要它的模型出现错误。
            val testHistory = listOf("system" to "You are a helpful assistant.")
            val stream = sendMessage(context, "Hi", testHistory, emptyList(), false, onTokensUpdated = { _, _, _ -> }, onNonFatalError = {})

            // 消耗流以确保连接有效。
            // 对 "Hi" 的响应应该很短，所以这会很快完成。
            stream.collect { _ -> }

            Result.success(context.getString(R.string.openai_connection_success))
        } catch (e: Exception) {
            AppLogger.e("AIService", "连接测试失败", e)
            Result.failure(IOException(context.getString(R.string.openai_connection_test_failed, e.message ?: ""), e))
        }
    }
}
