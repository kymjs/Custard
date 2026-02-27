package com.ai.assistance.custard.api.chat.llmprovider

import android.content.Context
import android.os.Environment
import com.ai.assistance.llama.LlamaSession
import com.ai.assistance.custard.R
import com.ai.assistance.custard.data.model.ApiProviderType
import com.ai.assistance.custard.data.model.ModelOption
import com.ai.assistance.custard.data.model.ModelParameter
import com.ai.assistance.custard.data.model.ToolPrompt
import com.ai.assistance.custard.data.model.ToolParameterSchema
import com.ai.assistance.custard.util.AppLogger
import com.ai.assistance.custard.util.ChatMarkupRegex
import com.ai.assistance.custard.util.ChatUtils
import com.ai.assistance.custard.util.LocaleUtils
import com.ai.assistance.custard.util.stream.Stream
import com.ai.assistance.custard.util.stream.stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class LlamaProvider(
    private val context: Context,
    private val modelName: String,
    private val threadCount: Int,
    private val contextSize: Int,
    private val providerType: ApiProviderType = ApiProviderType.LLAMA_CPP,
    private val enableToolCall: Boolean = false
) : AIService {

    companion object {
        private const val TAG = "LlamaProvider"

        fun getModelsDir(): File {
            return File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Operit/models/llama"
            )
        }

        fun getModelFile(_context: Context, modelName: String): File {
            return File(getModelsDir(), modelName)
        }
    }

    private var _inputTokenCount: Int = 0
    private var _outputTokenCount: Int = 0
    private var _cachedInputTokenCount: Int = 0

    @Volatile
    private var isCancelled = false

    private val sessionLock = Any()
    private var session: LlamaSession? = null

    override val inputTokenCount: Int
        get() = _inputTokenCount

    override val cachedInputTokenCount: Int
        get() = _cachedInputTokenCount

    override val outputTokenCount: Int
        get() = _outputTokenCount

    override val providerModel: String
        get() = "${providerType.name}:$modelName"

    override fun resetTokenCounts() {
        _inputTokenCount = 0
        _outputTokenCount = 0
        _cachedInputTokenCount = 0
    }

    private fun logLargeString(prefix: String, message: String) {
        val maxLogSize = 3000
        if (message.length <= maxLogSize) {
            AppLogger.d(TAG, "$prefix$message")
            return
        }

        val chunkCount = (message.length + maxLogSize - 1) / maxLogSize
        for (index in 0 until chunkCount) {
            val start = index * maxLogSize
            val end = minOf((index + 1) * maxLogSize, message.length)
            val chunk = message.substring(start, end)
            AppLogger.d(TAG, "$prefix Part ${index + 1}/$chunkCount: $chunk")
        }
    }

    override fun cancelStreaming() {
        isCancelled = true
        synchronized(sessionLock) {
            session?.cancel()
        }
    }

    override fun release() {
        synchronized(sessionLock) {
            session?.release()
            session = null
        }
    }

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return ModelListFetcher.getLlamaLocalModels(context)
    }

    override suspend fun testConnection(context: Context): Result<String> = withContext(Dispatchers.IO) {
        if (!LlamaSession.isAvailable()) {
            return@withContext Result.failure(Exception(LlamaSession.getUnavailableReason()))
        }

        val modelFile = getModelFile(context, modelName)
        if (!modelFile.exists()) {
            return@withContext Result.failure(Exception(context.getString(R.string.llama_error_model_file_not_exist, modelFile.absolutePath)))
        }

        val testSession = LlamaSession.create(
            pathModel = modelFile.absolutePath,
            nThreads = threadCount,
            nCtx = contextSize
        ) ?: return@withContext Result.failure(Exception(context.getString(R.string.llama_error_create_session_failed)))

        testSession.release()
        Result.success("llama.cpp backend is available (native ready).")
    }

    override suspend fun calculateInputTokens(
        message: String,
        chatHistory: List<Pair<String, String>>,
        availableTools: List<ToolPrompt>?
    ): Int {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                val s = ensureSessionLocked()
                if (s == null) return@runCatching null

                val (roles, contents) = buildPromptMessages(
                    message = message,
                    chatHistory = chatHistory,
                    availableTools = availableTools,
                    preserveThinkInHistory = false
                )

                val prompt = s.applyChatTemplate(roles, contents, true)
                    ?: return@runCatching null

                s.countTokens(prompt)
            }.getOrNull() ?: 0
        }
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
        isCancelled = false

        if (!LlamaSession.isAvailable()) {
            emit("${context.getString(R.string.llama_error_prefix)}: ${LlamaSession.getUnavailableReason()}")
            return@stream
        }

        val modelFile = getModelFile(context, modelName)
        if (!modelFile.exists()) {
            emit("${context.getString(R.string.llama_error_prefix)}: ${context.getString(R.string.llama_error_model_file_not_exist, modelFile.absolutePath)}")
            return@stream
        }

        val s = withContext(Dispatchers.IO) {
            ensureSessionLocked()
        }
        if (s == null) {
            emit(context.getString(R.string.llama_error_session_create_failed))
            return@stream
        }

        val (roles, contents) = buildPromptMessages(
            message = message,
            chatHistory = chatHistory,
            availableTools = availableTools,
            preserveThinkInHistory = preserveThinkInHistory
        )

        val effectiveEnableToolCall = shouldUseToolCall(availableTools)

        if (effectiveEnableToolCall) {
            AppLogger.d(TAG, "llama.cpp Tool Call转换已启用，tools=${availableTools?.size ?: 0}")
        }

        val prompt = withContext(Dispatchers.IO) {
            s.applyChatTemplate(roles, contents, true)
        }
        if (prompt.isNullOrBlank()) {
            emit(context.getString(R.string.llama_error_chat_template_failed))
            return@stream
        }

        logLargeString("Final prompt before llama generation: ", prompt)

        val temperature = modelParameters
            .firstOrNull { it.id == "temperature" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 1.0f
        val topP = modelParameters
            .firstOrNull { it.id == "top_p" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 1.0f
        val topK = modelParameters
            .firstOrNull { it.id == "top_k" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toInt() }
            ?: 0
        val repetitionPenalty = modelParameters
            .firstOrNull { it.id == "repetition_penalty" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 1.0f
        val frequencyPenalty = modelParameters
            .firstOrNull { it.id == "frequency_penalty" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 0.0f
        val presencePenalty = modelParameters
            .firstOrNull { it.id == "presence_penalty" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 0.0f

        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                s.setSamplingParams(
                    temperature = temperature,
                    topP = topP,
                    topK = topK,
                    repetitionPenalty = repetitionPenalty,
                    frequencyPenalty = frequencyPenalty,
                    presencePenalty = presencePenalty,
                    penaltyLastN = 64
                )
            }

            kotlin.runCatching {
                if (effectiveEnableToolCall && !availableTools.isNullOrEmpty()) {
                    val grammar = buildNativeToolCallGrammar(availableTools)
                    if (!grammar.isNullOrBlank()) {
                        val configured = s.setToolCallGrammar(
                            grammar = grammar,
                            triggerPatterns = buildNativeToolCallTriggerPatterns()
                        )
                        if (!configured) {
                            AppLogger.w(TAG, "Failed to enable native tool-call grammar; fallback to normal generation")
                        }
                    } else {
                        AppLogger.w(TAG, "Tool Call已启用，但grammar构建为空")
                        s.clearToolCallGrammar()
                    }
                } else {
                    s.clearToolCallGrammar()
                }
                Unit
            }.onFailure {
                AppLogger.w(TAG, "配置llama.cpp原生Tool Call grammar失败", it)
            }
        }

        _inputTokenCount = kotlin.runCatching { s.countTokens(prompt) }.getOrElse { 0 }
        _outputTokenCount = 0
        onTokensUpdated(_inputTokenCount, 0, 0)

        val requestedMaxNewTokens = modelParameters
            .find { it.name == "max_tokens" }
            ?.let { (it.currentValue as? Number)?.toInt() }
            ?: -1

        AppLogger.d(TAG, "开始llama.cpp推理，history=${chatHistory.size}, threads=$threadCount, n_ctx=$contextSize")

        var outputTokenCount = 0
        val toolCallOutputBuffer = StringBuilder()

        val success = withContext(Dispatchers.IO) {
            s.generateStream(prompt, requestedMaxNewTokens) { token ->
                if (isCancelled) {
                    false
                } else {
                    outputTokenCount += 1
                    _outputTokenCount = outputTokenCount

                    if (effectiveEnableToolCall) {
                        toolCallOutputBuffer.append(token)
                    } else {
                        runBlocking { emit(token) }
                    }

                    kotlin.runCatching {
                        kotlinx.coroutines.runBlocking {
                            onTokensUpdated(_inputTokenCount, 0, _outputTokenCount)
                        }
                    }

                    true
                }
            }
        }

        if (effectiveEnableToolCall) {
            val converted = convertToolCallPayloadToXml(toolCallOutputBuffer.toString())
            if (converted.isNotBlank()) {
                emit(converted)
            }
        }

        if (!success && !isCancelled) {
            kotlin.runCatching {
                onNonFatalError(context.getString(R.string.llama_error_inference_failed))
            }
            emit("\n\n${context.getString(R.string.llama_error_inference_tag)}")
        }

        AppLogger.i(TAG, "llama.cpp推理完成，输出token数: $_outputTokenCount")
    }

    private fun shouldUseToolCall(availableTools: List<ToolPrompt>?): Boolean {
        return enableToolCall && !availableTools.isNullOrEmpty()
    }

    private fun buildPromptMessages(
        message: String,
        chatHistory: List<Pair<String, String>>,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean
    ): Pair<List<String>, List<String>> {
        val effectiveEnableToolCall = shouldUseToolCall(availableTools)
        val normalizedHistory = ChatUtils.mapChatHistoryToStandardRoles(
            chatHistory,
            extractThinking = preserveThinkInHistory
        )
        val historyForPrompt = normalizedHistory.toMutableList()

        if (effectiveEnableToolCall && !availableTools.isNullOrEmpty()) {
            val addon = buildToolCallSystemAddon(availableTools)
            val firstSystemIndex = historyForPrompt.indexOfFirst { it.first == "system" }
            if (firstSystemIndex >= 0) {
                val origin = historyForPrompt[firstSystemIndex]
                historyForPrompt[firstSystemIndex] = Pair(
                    origin.first,
                    "${origin.second}\n\n$addon"
                )
            } else {
                historyForPrompt.add(0, "system" to addon)
            }
        }

        val roles = ArrayList<String>(historyForPrompt.size + 1)
        val contents = ArrayList<String>(historyForPrompt.size + 1)

        if (!effectiveEnableToolCall) {
            for ((role, content) in historyForPrompt) {
                roles.add(role)
                contents.add(content)
            }
            roles.add("user")
            contents.add(message)
            return Pair(roles, contents)
        }

        val lastToolCallIds = mutableListOf<String>()
        for ((role, content) in historyForPrompt) {
            appendHistoryMessageWithToolCall(role, content, roles, contents, lastToolCallIds)
        }

        roles.add("user")
        contents.add(message)

        return Pair(roles, contents)
    }

    private fun appendHistoryMessageWithToolCall(
        role: String,
        content: String,
        roles: MutableList<String>,
        contents: MutableList<String>,
        lastToolCallIds: MutableList<String>
    ) {
        when (role) {
            "assistant" -> {
                val (textContent, toolCalls) = parseXmlToolCalls(content)
                val messageContent = buildString {
                    if (textContent.isNotBlank()) {
                        append(textContent)
                    }
                    if (toolCalls != null && toolCalls.length() > 0) {
                        val payload = JSONObject().apply {
                            put("tool_calls", toolCalls)
                        }.toString()
                        if (isNotEmpty()) {
                            append("\n")
                        }
                        append(payload)
                    }
                }

                if (messageContent.isNotBlank()) {
                    roles.add("assistant")
                    contents.add(messageContent)
                }

                if (toolCalls != null && toolCalls.length() > 0) {
                    lastToolCallIds.clear()
                    for (i in 0 until toolCalls.length()) {
                        val callId = toolCalls.optJSONObject(i)?.optString("id", "") ?: ""
                        if (callId.isNotBlank()) {
                            lastToolCallIds.add(callId)
                        }
                    }
                } else {
                    lastToolCallIds.clear()
                }
            }

            "user" -> {
                val (textContent, toolResults) = parseXmlToolResults(content)
                var hasHandledToolResults = false

                if (lastToolCallIds.isNotEmpty()) {
                    val resultsList = toolResults ?: emptyList()
                    val callCount = lastToolCallIds.size

                    for (i in 0 until callCount) {
                        val toolCallId = lastToolCallIds[i]
                        val resultContent = if (i < resultsList.size) {
                            resultsList[i].second
                        } else {
                            "User cancelled"
                        }

                        roles.add("user")
                        contents.add(
                            JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", toolCallId)
                                put("content", resultContent)
                            }.toString()
                        )
                    }

                    hasHandledToolResults = true
                    lastToolCallIds.clear()
                }

                if (textContent.isNotBlank()) {
                    roles.add("user")
                    contents.add(textContent)
                } else if (!hasHandledToolResults) {
                    roles.add("user")
                    contents.add(content)
                }
            }

            else -> {
                roles.add(role)
                contents.add(content)
            }
        }
    }

    private fun buildNativeToolCallTriggerPatterns(): List<String> {
        return listOf(
            """[\s\S]*?(\{\s*"tool_calls"\s*:\s*\[)""",
            """[\s\S]*?```json\s*(\{\s*"tool_calls"\s*:\s*\[)""",
            """[\s\S]*?```\s*(\{\s*"tool_calls"\s*:\s*\[)"""
        )
    }

    private fun buildNativeToolCallGrammar(availableTools: List<ToolPrompt>): String? {
        val toolNames = availableTools
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (toolNames.isEmpty()) {
            return null
        }

        val toolNameRule = toolNames.joinToString(" | ") { toGbnfStringLiteral(it) }

        return """
root ::= ws object ws
object ::= "{" ws "\"tool_calls\"" ws ":" ws "[" ws tool-call (ws "," ws tool-call)* ws "]" ws "}"
tool-call ::= "{" ws "\"id\"" ws ":" ws string ws "," ws "\"type\"" ws ":" ws "\"function\"" ws "," ws "\"function\"" ws ":" ws function ws "}"
function ::= "{" ws "\"name\"" ws ":" ws tool-name ws "," ws "\"arguments\"" ws ":" ws json-value ws "}"
tool-name ::= $toolNameRule
json-value ::= json-object | json-array | string | number | boolean | null
json-object ::= "{" ws (string ws ":" ws json-value (ws "," ws string ws ":" ws json-value)*)? ws "}"
json-array ::= "[" ws (json-value (ws "," ws json-value)*)? ws "]"
string ::= "\"" char* "\"" ws
char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
number ::= ("-"? integral-part) ("." decimal-part)? ([eE] [-+]? integral-part)? ws
integral-part ::= "0" | [1-9] [0-9]{0,15}
decimal-part ::= [0-9]{1,16}
boolean ::= ("true" | "false") ws
null ::= "null" ws
ws ::= [ \t\n\r]*
""".trimIndent()
    }

    private fun toGbnfStringLiteral(raw: String): String {
        val escaped = buildString {
            raw.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code in 0x20..0x7E) {
                            append(ch)
                        } else {
                            append("\\u")
                            append(ch.code.toString(16).padStart(4, '0'))
                        }
                    }
                }
            }
        }
        return "\"$escaped\""
    }

    private fun buildToolCallSystemAddon(availableTools: List<ToolPrompt>): String {
        val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
        val toolNames = availableTools.map { it.name }.distinct().joinToString(", ")

        return if (useEnglish) {
            """
Tool-call mode is enabled.
When invoking tools, output valid JSON with top-level "tool_calls".
Use only these tool names: $toolNames
""".trimIndent()
        } else {
            """
已启用 Tool Call 模式。
调用工具时，请输出顶层为 "tool_calls" 的合法 JSON。
仅可使用以下工具名：$toolNames
""".trimIndent()
        }
    }

    private fun convertToolCallPayloadToXml(content: String): String {
        if (content.isBlank()) {
            return content
        }

        if (content.contains("<tool")) {
            return content
        }

        val toolCalls = parsePossibleToolCallsFromText(content) ?: return content
        val xml = convertToolCallsToXml(toolCalls)
        if (xml.isBlank()) {
            return content
        }
        return xml
    }

    private fun parsePossibleToolCallsFromText(content: String): JSONArray? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val candidates = LinkedHashSet<String>()
        candidates.add(trimmed)

        val extractedJson = ChatUtils.extractJson(trimmed).trim()
        if (extractedJson.isNotBlank()) {
            candidates.add(extractedJson)
        }

        val extractedArray = ChatUtils.extractJsonArray(trimmed).trim()
        if (extractedArray.isNotBlank()) {
            candidates.add(extractedArray)
        }

        val fencedRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fencedRegex.findAll(trimmed).forEach { match ->
            val fenced = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (fenced.isNotBlank()) {
                candidates.add(fenced)
            }
        }

        for (candidate in candidates) {
            val fromObj = kotlin.runCatching {
                extractToolCallsFromAny(JSONObject(candidate))
            }.getOrNull()
            if (fromObj != null && fromObj.length() > 0) {
                return fromObj
            }

            val fromArr = kotlin.runCatching {
                extractToolCallsFromAny(JSONArray(candidate))
            }.getOrNull()
            if (fromArr != null && fromArr.length() > 0) {
                return fromArr
            }
        }

        return null
    }

    private fun extractToolCallsFromAny(root: JSONObject): JSONArray? {
        root.optJSONArray("tool_calls")?.let { arr ->
            val normalized = normalizeToolCalls(arr)
            if (normalized.length() > 0) {
                return normalized
            }
        }

        root.optJSONObject("function_call")?.let { functionCall ->
            val normalized = normalizeSingleToolCall(functionCall, 0)
            if (normalized != null) {
                return JSONArray().put(normalized)
            }
        }

        if (root.optString("type", "") == "function_call") {
            val normalized = normalizeSingleToolCall(root, 0)
            if (normalized != null) {
                return JSONArray().put(normalized)
            }
        }

        root.optJSONArray("output")?.let { outputArr ->
            val normalized = normalizeToolCalls(outputArr)
            if (normalized.length() > 0) {
                return normalized
            }
        }

        return null
    }

    private fun extractToolCallsFromAny(root: JSONArray): JSONArray? {
        val normalized = normalizeToolCalls(root)
        return if (normalized.length() > 0) normalized else null
    }

    private fun normalizeToolCalls(source: JSONArray): JSONArray {
        val normalized = JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val normalizedCall = normalizeSingleToolCall(item, i)
            if (normalizedCall != null) {
                normalized.put(normalizedCall)
            }
        }
        return normalized
    }

    private fun normalizeSingleToolCall(raw: JSONObject, index: Int): JSONObject? {
        val functionObj = raw.optJSONObject("function")
        val functionCallObj = raw.optJSONObject("function_call")

        val name = when {
            functionObj != null -> functionObj.optString("name", "")
            raw.optString("name", "").isNotBlank() -> raw.optString("name", "")
            functionCallObj != null -> functionCallObj.optString("name", "")
            else -> ""
        }
        if (name.isBlank()) {
            return null
        }

        val argumentsValue: Any? = when {
            functionObj != null && functionObj.has("arguments") -> functionObj.opt("arguments")
            raw.has("arguments") -> raw.opt("arguments")
            functionCallObj != null && functionCallObj.has("arguments") -> functionCallObj.opt("arguments")
            else -> null
        }

        val arguments = when (argumentsValue) {
            is JSONObject, is JSONArray -> argumentsValue.toString()
            is String -> if (argumentsValue.isBlank()) "{}" else argumentsValue
            null -> "{}"
            else -> argumentsValue.toString()
        }

        val rawId = raw.optString("id", "")
            .ifBlank { raw.optString("call_id", "") }
            .ifBlank { "call_${sanitizeToolCallId(name)}_$index" }
        val callId = sanitizeToolCallId(rawId)

        return JSONObject().apply {
            put("id", callId)
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("arguments", arguments)
            })
        }
    }

    private fun buildToolDefinitions(toolPrompts: List<ToolPrompt>): JSONArray {
        val tools = JSONArray()

        for (tool in toolPrompts) {
            tools.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    val fullDescription = if (tool.details.isNotEmpty()) {
                        "${tool.description}\n${tool.details}"
                    } else {
                        tool.description
                    }
                    put("description", fullDescription)
                    val parametersSchema = buildSchemaFromStructured(tool.parametersStructured ?: emptyList())
                    put("parameters", parametersSchema)
                })
            })
        }

        return tools
    }

    private fun buildSchemaFromStructured(params: List<ToolParameterSchema>): JSONObject {
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

    private fun convertToolCallsToXml(toolCalls: JSONArray): String {
        val xml = StringBuilder()

        for (i in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(i) ?: continue
            val function = toolCall.optJSONObject("function") ?: continue

            val name = function.optString("name", "")
            if (name.isBlank()) {
                continue
            }

            val argumentsRaw = function.optString("arguments", "")
            val paramsObj = kotlin.runCatching {
                JSONObject(argumentsRaw)
            }.getOrNull()

            xml.append("<tool name=\"")
                .append(name)
                .append("\">")

            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = paramsObj.opt(key)
                    xml.append("\n<param name=\"")
                        .append(key)
                        .append("\">")
                        .append(escapeXml(value?.toString() ?: ""))
                        .append("</param>")
                }
            } else if (argumentsRaw.isNotBlank()) {
                xml.append("\n<param name=\"_raw_arguments\">")
                    .append(escapeXml(argumentsRaw))
                    .append("</param>")
            }

            xml.append("\n</tool>\n")
        }

        return xml.toString().trim()
    }

    private fun parseXmlToolCalls(content: String): Pair<String, JSONArray?> {
        val matches = ChatMarkupRegex.toolCallPattern.findAll(content)

        if (!matches.any()) {
            return Pair(content, null)
        }

        val toolCalls = JSONArray()
        var textContent = content
        var callIndex = 0

        matches.forEach { match ->
            val toolName = match.groupValues[1]
            val toolBody = match.groupValues[2]

            val params = JSONObject()
            ChatMarkupRegex.toolParamPattern.findAll(toolBody).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                params.put(paramName, paramValue)
            }

            val toolNamePart = sanitizeToolCallId(toolName)
            val hashPart = stableIdHashPart("${toolName}:${params}")
            val callId = sanitizeToolCallId("call_${toolNamePart}_${hashPart}_$callIndex")

            toolCalls.put(JSONObject().apply {
                put("id", callId)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", params.toString())
                })
            })

            callIndex++
            textContent = textContent.replace(match.value, "")
        }

        return Pair(textContent.trim(), toolCalls)
    }

    private fun parseXmlToolResults(content: String): Pair<String, List<Pair<String, String>>?> {
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

            results.add(Pair("call_result_$resultIndex", resultContent))
            textContent = textContent.replace(match.value, "").trim()
            resultIndex++
        }

        return Pair(textContent.trim(), results)
    }

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

    private fun escapeXml(text: String): String {
        return XmlEscaper.escape(text)
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
        return if (out.isEmpty()) "call" else out
    }

    private fun stableIdHashPart(raw: String): String {
        val hash = raw.hashCode()
        val positive = if (hash == Int.MIN_VALUE) 0 else abs(hash)
        var base = positive.toString(36)
        base = base.filter { it.isLetterOrDigit() }.lowercase()
        return if (base.isEmpty()) "0" else base
    }

    private fun ensureSessionLocked(): LlamaSession? {
        synchronized(sessionLock) {
            session?.let { return it }
            val modelFile = getModelFile(context, modelName)
            val created = LlamaSession.create(
                pathModel = modelFile.absolutePath,
                nThreads = threadCount,
                nCtx = contextSize
            )
            session = created
            return created
        }
    }

}
