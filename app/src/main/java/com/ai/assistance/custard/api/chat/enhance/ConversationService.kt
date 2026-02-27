package com.ai.assistance.custard.api.chat.enhance

import android.content.Context
import com.ai.assistance.custard.util.AppLogger
import com.ai.assistance.custard.core.config.SystemPromptConfig
import com.ai.assistance.custard.R
import com.ai.assistance.custard.core.tools.AIToolHandler
import com.ai.assistance.custard.core.tools.packTool.PackageManager
import com.ai.assistance.custard.data.model.AITool
import com.ai.assistance.custard.data.model.FunctionType
import com.ai.assistance.custard.data.model.PreferenceProfile
import com.ai.assistance.custard.data.model.ToolParameter
import com.ai.assistance.custard.core.tools.UIPageResultData
import com.ai.assistance.custard.core.tools.SimplifiedUINode
import com.ai.assistance.custard.core.config.FunctionalPrompts
import com.ai.assistance.custard.data.preferences.ApiPreferences
import com.ai.assistance.custard.data.preferences.DisplayPreferencesManager
import com.ai.assistance.custard.data.preferences.WaifuPreferences
import com.ai.assistance.custard.data.preferences.CharacterCardManager
import com.ai.assistance.custard.data.model.PromptFunctionType
import com.ai.assistance.custard.data.preferences.PromptTagManager
import com.ai.assistance.custard.data.preferences.preferencesManager
import com.ai.assistance.custard.util.ChatUtils
import com.ai.assistance.custard.core.tools.ToolProgressBus
import com.ai.assistance.custard.util.streamnative.NativeXmlSplitter
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.ai.assistance.custard.core.tools.ComputerDesktopActionResultData
import com.ai.assistance.custard.util.LocaleUtils
import com.ai.assistance.custard.api.chat.enhance.MultiServiceManager
import com.ai.assistance.custard.data.repository.CustomEmojiRepository
import com.ai.assistance.custard.api.chat.llmprovider.MediaLinkBuilder

/** 处理会话相关功能的服务类，包括会话总结、偏好处理和对话切割准备 */
class ConversationService(
    private val context: Context,
    private val customEmojiRepository: CustomEmojiRepository
    ) {

    companion object {
        private const val TAG = "ConversationService"
    }

    private val apiPreferences = ApiPreferences.getInstance(context)
    private val displayPreferencesManager = DisplayPreferencesManager.getInstance(context)
    private val waifuPreferences = WaifuPreferences.getInstance(context)
    private val characterCardManager = CharacterCardManager.getInstance(context)
    private val userPreferencesManager = preferencesManager
    private val conversationMutex = Mutex()

    /**
     * 生成对话总结
     * @param messages 要总结的消息列表
     * @return 生成的总结文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            multiServiceManager: MultiServiceManager
    ): String {
        return generateSummary(messages, null, multiServiceManager)
    }

    /**
     * 生成对话总结，并且包含上一次的总结内容
     * @param messages 要总结的消息列表
     * @param previousSummary 上一次的总结内容，可以为null
     * @return 生成的总结文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            previousSummary: String?,
            multiServiceManager: MultiServiceManager
    ): String {
        try {
            val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
            val systemPrompt = FunctionalPrompts.buildSummarySystemPrompt(previousSummary, useEnglish)

            val finalMessages = listOf(Pair("system", systemPrompt)) + messages

            // Get all model parameters from preferences (with enabled state)
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.SUMMARY)

            // 获取SUMMARY功能类型的AIService实例
            val summaryService = multiServiceManager.getServiceForFunction(FunctionType.SUMMARY)

            // 使用summaryService发送请求，收集完整响应
            val contentBuilder = StringBuilder()

            ToolProgressBus.update(
                ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
                0.05f,
                context.getString(R.string.conversation_summary_preparing)
            )

            data class Stage(
                val matchers: List<(String) -> Boolean>,
                val progress: Float,
                val message: String
            )

            val stages = listOf(
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_MARKER_EN) || it.contains(FunctionalPrompts.SUMMARY_MARKER_CN) }),
                    progress = 0.20f,
                    message = context.getString(R.string.conversation_summary_writing_title)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_CORE_TASK_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_CORE_TASK_CN) }),
                    progress = 0.40f,
                    message = context.getString(R.string.conversation_summary_core_task)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_INTERACTION_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_INTERACTION_CN) }),
                    progress = 0.55f,
                    message = context.getString(R.string.conversation_summary_interaction)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_PROGRESS_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_PROGRESS_CN) }),
                    progress = 0.70f,
                    message = context.getString(R.string.conversation_summary_progress)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_KEY_INFO_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_KEY_INFO_CN) }),
                    progress = 0.85f,
                    message = context.getString(R.string.conversation_summary_key_info)
                ),
                Stage(
                    matchers = listOf({ it.contains("=======================================") || it.contains("============================") }),
                    progress = 0.95f,
                    message = context.getString(R.string.conversation_summary_finishing)
                )
            )

            var lastStageIndex = -1
            fun updateStageIfNeeded() {
                if (lastStageIndex + 1 >= stages.size) return
                val snapshot = contentBuilder.toString()
                while (lastStageIndex + 1 < stages.size) {
                    val next = stages[lastStageIndex + 1]
                    val matched = next.matchers.any { it(snapshot) }
                    if (!matched) break
                    lastStageIndex += 1
                    ToolProgressBus.update(
                        ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
                        next.progress,
                        next.message
                    )
                }
            }

            // 使用新的Stream API
            val stream =
                    summaryService.sendMessage(
                            context = context,
                            message = FunctionalPrompts.summaryUserMessage(useEnglish),
                            chatHistory = finalMessages,
                            modelParameters = modelParameters
                    )

            // 收集流中的所有内容
            stream.collect { content ->
                contentBuilder.append(content)
                updateStageIfNeeded()
            }

            ToolProgressBus.update(
                ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
                1f,
                context.getString(R.string.conversation_summary_completed)
            )

            // 获取完整的总结内容
            val summaryContent = ChatUtils.removeThinkingContent(contentBuilder.toString().trim())

            // 如果内容为空，返回默认消息
            if (summaryContent.isBlank()) {
                return "Conversation Summary: Unable to generate valid summary."
            }

            // 获取本次总结生成的token统计
            val inputTokens = summaryService.inputTokenCount
            val cachedInputTokens = summaryService.cachedInputTokenCount
            val outputTokens = summaryService.outputTokenCount

            // 将总结token计数添加到用户偏好分析的token统计中
            try {
                AppLogger.d(TAG, "总结生成使用了输入token: $inputTokens, 缓存token: $cachedInputTokens, 输出token: $outputTokens")
                apiPreferences.updateTokensForProviderModel(summaryService.providerModel, inputTokens, outputTokens, cachedInputTokens)
                
                // Update request count for summary generation
                apiPreferences.incrementRequestCountForProviderModel(summaryService.providerModel)
                
                AppLogger.d(TAG, "已将总结token统计添加到用户偏好分析token计数中")
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新token统计失败", e)
            }

            return summaryContent
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成总结时出错", e)
            // return "对话摘要：生成摘要时出错，但对话仍在继续。"
            throw e
        }
    }

    /**
     * 为聊天准备对话历史记录
     * @param chatHistory 原始聊天历史
     * @param processedInput 处理后的用户输入
     * @param workspacePath 当前绑定的工作区路径，可以为null
     * @param packageManager 包管理器
     * @param promptFunctionType 提示函数类型
     * @param thinkingGuidance 是否需要思考指导
     * @param enableMemoryQuery Whether the AI is allowed to query memories.
     * @param hasImageRecognition Whether a backend image recognition service is configured
     * @return 准备好的对话历史列表
     */
    suspend fun prepareConversationHistory(
            chatHistory: List<Pair<String, String>>,
            processedInput: String,
            workspacePath: String?,
            workspaceEnv: String? = null,
            packageManager: PackageManager,
            promptFunctionType: PromptFunctionType,
            thinkingGuidance: Boolean = false,
            customSystemPromptTemplate: String? = null,
            enableMemoryQuery: Boolean = true,
            roleCardId: String? = null,
            proxySenderName: String? = null,
            hasImageRecognition: Boolean = false,
            hasAudioRecognition: Boolean = false,
            hasVideoRecognition: Boolean = false,
            chatModelHasDirectAudio: Boolean = false,
            chatModelHasDirectVideo: Boolean = false,
            useToolCallApi: Boolean = false,
            strictToolCall: Boolean = false,
            chatModelHasDirectImage: Boolean = false
    ): List<Pair<String, String>> {
        val preparedHistory = mutableListOf<Pair<String, String>>()
        conversationMutex.withLock {
            // Add system prompt if not already present
            if (!chatHistory.any { it.first == "system" }) {
                val safeProxySenderName = proxySenderName?.takeIf { it.isNotBlank() }

                val preferencesText = if (safeProxySenderName == null) {
                    val activeProfile = preferencesManager.getUserPreferencesFlow().first()
                    buildPreferencesText(activeProfile)
                } else {
                    val proxyCard = characterCardManager.findCharacterCardByName(safeProxySenderName)
                    if (proxyCard == null) {
                        ""
                    } else {
                        characterCardManager.combinePrompts(proxyCard.id)
                    }
                }

                // 根据功能类型获取对应的提示词
                val effectiveRoleCardId = roleCardId?.takeIf { it.isNotBlank() }
                val activeCard = effectiveRoleCardId?.let {
                    characterCardManager.getCharacterCardFlow(it).first()
                }
                val systemTagId =
                        when (promptFunctionType) {
                            PromptFunctionType.VOICE -> PromptTagManager.SYSTEM_VOICE_TAG_ID
                            PromptFunctionType.DESKTOP_PET ->
                                    PromptTagManager.SYSTEM_DESKTOP_PET_TAG_ID
                            else -> PromptTagManager.SYSTEM_CHAT_TAG_ID
                        }
                
                val introPrompt = activeCard?.let {
                    characterCardManager.combinePrompts(
                        it.id,
                        listOf(systemTagId)
                    )
                }.orEmpty()

                // 获取自定义系统提示模板
                val finalCustomSystemPromptTemplate = customSystemPromptTemplate ?: apiPreferences.customSystemPromptTemplateFlow.first()

                // 获取工具启用状态
                val enableTools = apiPreferences.enableToolsFlow.first()
                val disableUserPreferenceDescription =
                        apiPreferences.disableUserPreferenceDescriptionFlow.first()
                val disableLatexDescription = apiPreferences.disableLatexDescriptionFlow.first()
                val toolPromptVisibility = runCatching {
                    apiPreferences.toolPromptVisibilityFlow.first()
                }.getOrElse { emptyMap() }

                val safBookmarkNames = runCatching {
                    apiPreferences.safBookmarksFlow.first().map { it.name }
                }.getOrElse { emptyList() }

                val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")

                // 获取系统提示词，现在传入workspacePath和识图配置状态
                val systemPrompt = SystemPromptConfig.getSystemPromptWithCustomPrompts(
                    packageManager = packageManager,
                    workspacePath = workspacePath,
                    workspaceEnv = workspaceEnv,
                    safBookmarkNames = safBookmarkNames,
                    customIntroPrompt = introPrompt,
                    useEnglish = useEnglish,
                    thinkingGuidance = thinkingGuidance,
                    customSystemPromptTemplate = finalCustomSystemPromptTemplate,
                    enableTools = enableTools,
                    enableMemoryQuery = enableMemoryQuery,
                    hasImageRecognition = hasImageRecognition,
                    chatModelHasDirectImage = chatModelHasDirectImage,
                    hasAudioRecognition = hasAudioRecognition,
                    hasVideoRecognition = hasVideoRecognition,
                    chatModelHasDirectAudio = chatModelHasDirectAudio,
                    chatModelHasDirectVideo = chatModelHasDirectVideo,
                    useToolCallApi = useToolCallApi,
                    strictToolCall = strictToolCall,
                    disableLatexDescription = disableLatexDescription,
                    toolVisibility = toolPromptVisibility
                )

                // 构建waifu特殊规则
                val waifuRulesText = if(waifuPreferences.enableWaifuModeFlow.first()) buildWaifuRulesText() else ""
                // 桌宠模式：添加<mood>标签协议（仅桌宠环境生效）
                val desktopPetRulesText = if (promptFunctionType == PromptFunctionType.DESKTOP_PET) buildDesktopPetMoodRulesText() else ""
                AppLogger.d("petRules", desktopPetRulesText)

                // 构建最终的系统提示词
                val finalSystemPrompt = buildString {
                    append(desktopPetRulesText)
                    append(systemPrompt)
                    append(waifuRulesText)
                    if (!disableUserPreferenceDescription && preferencesText.isNotEmpty()) {
                        append("\n\nUser preference description: ")
                        append(preferencesText)
                    }
                }

                // 替换提示词中的占位符
                val aiName = activeCard?.name ?: context.getString(R.string.app_name)
                val finalSystemPromptWithReplacements = replacePromptPlaceholders(
                    finalSystemPrompt,
                    aiName
                )
                preparedHistory.add(0, Pair("system", finalSystemPromptWithReplacements))
            }

            // Process each message in chat history
            chatHistory.forEachIndexed { index, message ->
                val role = message.first
                val content = message.second

                // If it's an assistant message, check for tool results
                if (role == "assistant") {
                    val xmlTags = splitXmlTag(content)
                    if (xmlTags.isNotEmpty()) {
                        // Process the message with tool results
                        processChatMessageWithTools(content, xmlTags, preparedHistory, index, chatHistory.size)
                    } else {
                        // Add the message as is
                        preparedHistory.add(message)
                    }
                } else {
                    // Add user or system messages as is
                    preparedHistory.add(message)
                }
            }
        }
        return preparedHistory
    }

    /**
     * 提取内容中的XML标签
     * @param content 要处理的内容
     * @return 提取的XML标签列表，每项包含[标签名称, 标签内容]
     */
    fun splitXmlTag(content: String): List<List<String>> {
        return NativeXmlSplitter.splitXmlTag(content)
    }

    /** 处理包含工具结果的聊天消息，并按顺序重新组织消息 任务完成和等待用户响应的status标签算作AI消息，其他status和warning算作用户消息 工具结果为用户消息 */
    suspend fun processChatMessageWithTools(
            content: String,
            xmlTags: List<List<String>>,
            conversationHistory: MutableList<Pair<String, String>>,
            messageIndex: Int,
            totalMessages: Int
    ) {
        if (xmlTags.isEmpty()) {
            // 如果没有XML标签，直接添加为AI消息
            conversationHistory.add(Pair("assistant", content))
            return
        }

        // 按顺序处理标签
        val segments = mutableListOf<Pair<String, String>>() // 角色, 内容

        for (tag in xmlTags) {
            val tagName = tag[0]
            var tagContent = tag[1]

            // 对于text类型（纯文本），直接作为AI消息
            if (tagName == "text") {
                if (tagContent.isNotBlank()) {
                    segments.add(Pair("assistant", tagContent))
                }
                continue
            }

            // 根据标签类型分配角色
            when (tagName) {
                "think", "thinking" -> {
                    // 保留完整的think标签（用于DeepSeek推理模式）
                    segments.add(Pair("assistant", tagContent))
                }
                "status" -> {
                    // 判断status类型
                    if (tagContent.contains("type=\"complete\"") ||
                                    tagContent.contains("type=\"wait_for_user_need\"")
                    ) {
                        segments.add(Pair("assistant", tagContent))
                    } else {
                        segments.add(Pair("user", tagContent))
                    }
                }
                "tool_result" -> {
                    segments.add(Pair("user", tagContent))
                }
                else -> {
                    segments.add(Pair("assistant", tagContent))
                }
            }
        }

        // 合并连续的相同角色消息
        val mergedSegments = mutableListOf<Pair<String, String>>()
        var currentRole = ""
        var currentContent = StringBuilder()

        for (segment in segments) {
            if (segment.first == currentRole) {
                // 如果角色与当前角色相同，则合并内容
                currentContent.append("\n").append(segment.second)
            } else {
                // 角色不同，先保存当前内容（如果有）
                if (currentContent.isNotEmpty()) {
                    mergedSegments.add(Pair(currentRole, currentContent.toString().trim()))
                    currentContent.clear()
                }
                // 更新当前角色和内容
                currentRole = segment.first
                currentContent.append(segment.second)
            }
        }

        // 添加最后一条消息
        if (currentContent.isNotEmpty()) {
            mergedSegments.add(Pair(currentRole, currentContent.toString().trim()))
        }

        // 将合并后的消息添加到对话历史
        conversationHistory.addAll(mergedSegments)
    }

    /** Build a formatted preferences text string from a PreferenceProfile */
    fun buildPreferencesText(profile: PreferenceProfile): String {
        val parts = mutableListOf<String>()

        if (profile.gender.isNotEmpty()) {
            parts.add("Gender: ${profile.gender}")
        }

        if (profile.birthDate > 0) {
            // Convert timestamp to age and format as text
            val today = Calendar.getInstance()
            val birthCal = Calendar.getInstance().apply { timeInMillis = profile.birthDate }
            var age = today.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
            // Adjust age if birthday hasn't occurred yet this year
            if (today.get(Calendar.MONTH) < birthCal.get(Calendar.MONTH) ||
                            (today.get(Calendar.MONTH) == birthCal.get(Calendar.MONTH) &&
                                    today.get(Calendar.DAY_OF_MONTH) <
                                            birthCal.get(Calendar.DAY_OF_MONTH))
            ) {
                age--
            }
            parts.add("Age: $age")

            // Also add birth date for more precise information
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            parts.add("Birth Date: ${dateFormat.format(java.util.Date(profile.birthDate))}")
        }

        if (profile.personality.isNotEmpty()) {
            parts.add("Personality: ${profile.personality}")
        }

        if (profile.identity.isNotEmpty()) {
            parts.add("Identity: ${profile.identity}")
        }

        if (profile.occupation.isNotEmpty()) {
            parts.add("Occupation: ${profile.occupation}")
        }

        if (profile.aiStyle.isNotEmpty()) {
            parts.add("Expected AI Style: ${profile.aiStyle}")
        }

        return parts.joinToString("; ")
    }

    /** Data class for search-replace operations, used for JSON deserialization. */
    private data class SearchReplaceOperation(val search: String, val replace: String)

    /**
     * Flattens the hierarchical UI node structure into a simple, flat list of key elements.
     * This provides a much cleaner context for the AI to make decisions.
     */
    private fun flattenUiInfo(pageInfo: UIPageResultData): String {
        val clickableElements = mutableListOf<String>()
        val screenTexts = mutableListOf<String>()

        fun traverse(node: SimplifiedUINode) {
            // If the node is clickable, treat it as an atomic unit. We'll gather all text from
            // its entire subtree to form a comprehensive description for the AI.
            if (node.isClickable) {
                val parts = mutableListOf<String>()
                
                // Start by collecting standard properties like resource ID, class, and bounds.
                node.resourceId?.takeIf { it.isNotBlank() }?.let { parts.add("id: $it") }

                // --- NEW: Recursively find all text and content descriptions in the subtree ---
                val descriptiveTexts = mutableListOf<String>()
                fun findTextsRecursively(n: SimplifiedUINode) {
                    n.text?.takeIf { it.isNotBlank() }?.let { descriptiveTexts.add(it) }
                    n.contentDesc?.takeIf { it.isNotBlank() }?.let { descriptiveTexts.add(it) }
                    n.children.forEach(::findTextsRecursively)
                }
                findTextsRecursively(node)

                // Combine all found texts into a single descriptive string. This is crucial for
                // elements where the text label is in a child node of the clickable area.
                val combinedText = descriptiveTexts.distinct().joinToString(" | ")
                if (combinedText.isNotBlank()) {
                    // Using "desc" to signify this is a constructed description. Increased length.
                    parts.add("desc: \"${combinedText.replace("\"", "'").take(80)}\"")
                }
                // --- END NEW ---

                node.className?.let { parts.add("class: ${it.substringAfterLast('.')}") }
                node.bounds?.let { parts.add("bounds: ${it.replace(' ', ',')}") }

                // Only add the element if it has some identifiable information.
                if (parts.isNotEmpty()) {
                    clickableElements.add("[${parts.joinToString(", ")}]")
                }
                // Once an element is identified as clickable, we don't process its children separately.
            } else {
                // If the node is not clickable, add its text for general context and continue traversal.
                node.text?.takeIf { it.isNotBlank() }?.let {
                    screenTexts.add("\"${it.replace("\"", "'").take(70)}\"")
                }
                node.children.forEach(::traverse)
            }
        }

        traverse(pageInfo.uiElements)

        // Use distinct to remove duplicate text entries from non-clickable elements.
        val distinctScreenTexts = screenTexts.distinct()

        return """
        Package: ${pageInfo.packageName}
        Activity: ${pageInfo.activityName}
        Clickable Elements:
        ${clickableElements.joinToString("\n")}
        Screen Text (
        for context):
        ${distinctScreenTexts.joinToString("\n")}
        """.trimIndent()
    }

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keysItr = this.keys()
        while (keysItr.hasNext()) {
            val key = keysItr.next()
            var value = this.get(key)
            if (value is JSONObject) {
                value = value.toMap()
            }
            if (value is JSONArray) {
                value = value.toList()
            }
            map[key] = value
        }
        return map
    }

    private fun JSONArray.toList(): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until this.length()) {
            var value = this.get(i)
            if (value is JSONObject) {
                value = value.toMap()
            }
            if (value is JSONArray) {
                value = value.toList()
            }
            list.add(value)
        }
        return list
    }

    private fun createToolFromJson(type: String, arg: Any): AITool {
        val parameters = mutableListOf<ToolParameter>()
        when (arg) {
            is Map<*, *> -> {
                arg.forEach { (key, value) ->
                    val stringValue = when (value) {
                        is Double -> {
                            // If the double has no fractional part, convert to Int string to avoid parse errors.
                            if (value % 1.0 == 0.0) {
                                value.toInt().toString()
                            } else {
                                value.toString()
                            }
                        }
                        else -> value.toString()
                    }
                    parameters.add(ToolParameter(key.toString(), stringValue))
                }
            }
            is String -> {
                 // Fallback for when the AI returns a raw string instead of a JSON object.
                 when (type) {
                     "press_key" -> parameters.add(ToolParameter("key_code", arg))
                     "set_input_text" -> parameters.add(ToolParameter("text", arg))
                     "start_app" -> parameters.add(ToolParameter("package_name", arg))
                 }
            }
        }
        return AITool(type, parameters)
    }

    /**
     * 构建waifu模式的特殊规则文本
     * @return 格式化的waifu规则文本，如果没有规则则返回空字符串
     */
    private suspend fun buildWaifuRulesText(): String {
        val waifuDisableActions = waifuPreferences.waifuDisableActionsFlow.first()
        val waifuEnableEmoticons = waifuPreferences.waifuEnableEmoticonsFlow.first()
        val waifuEnableSelfie = waifuPreferences.waifuEnableSelfieFlow.first()
        val waifuSelfiePrompt = waifuPreferences.waifuSelfiePromptFlow.first()
        val waifuRules = mutableListOf<String>()
        
        if (waifuDisableActions) {
            waifuRules.add(FunctionalPrompts.waifuDisableActionsRule())
        }
        
        if (waifuEnableEmoticons) {
            // 动态获取当前可用的表情分组
            val availableCategories = try {
                customEmojiRepository.getAllCategories().first()
            } catch (e: Exception) {
                com.ai.assistance.custard.util.AppLogger.e("ConversationService", "获取表情分组失败", e)
                emptyList()
            }
            
            if (availableCategories.isNotEmpty()) {
                val emotionListText = availableCategories.joinToString(", ")
                waifuRules.add(FunctionalPrompts.waifuEmotionRule(emotionListText))
            } else {
                // 如果没有自定义表情，则不添加情绪规则，或明确告知没有可用表情
                waifuRules.add(FunctionalPrompts.waifuNoCustomEmojiRule())
            }
        }
        
        if (waifuEnableSelfie) {
            waifuRules.add(FunctionalPrompts.waifuSelfieRule(waifuSelfiePrompt))
        }
        
        return if (waifuRules.isNotEmpty()) {
            buildString {
                append("\n\n[Extra Rules]")
                waifuRules.forEach { rule ->
                    append("\n- $rule")
                }
            }
        } else ""
    }

    /**
     * 桌宠模式的<mood>标签规则，仅在桌宠环境下添加到系统提示中。
     * 标签内容仅允许：angry, happy, shy, aojiao, cry。
     * 当心情平静或无特殊情绪时不要输出<mood>标签（应用将自动使用默认视频）。
     */
    private fun buildDesktopPetMoodRulesText(): String {
        return FunctionalPrompts.desktopPetMoodRulesText()
    }

    /**
     * Replaces placeholders in the system prompt with actual values.
     * This is necessary because the AI might return placeholders like {{user}} or {{char}}.
     *
     * @param prompt The system prompt containing placeholders.
     * @param aiName The actual AI name to replace {{char}}.
     * @return The prompt with placeholders replaced.
     */
    private suspend fun replacePromptPlaceholders(prompt: String, aiName: String): String {
        var finalPrompt = prompt
        
        // 获取全局用户名
        val globalUserName = displayPreferencesManager.globalUserName.first() ?: "User"
        
        // 替换占位符
        finalPrompt = finalPrompt.replace("{{user}}", globalUserName)
        finalPrompt = finalPrompt.replace("{{char}}", aiName)
        
        return finalPrompt
    }

    /**
     * 翻译文本功能
     * @param text 要翻译的文本
     * @param multiServiceManager 多服务管理器
     * @return 翻译后的文本
     */
    suspend fun translateText(text: String, multiServiceManager: MultiServiceManager): String {
        val currentLanguage = LocaleUtils.getCurrentLanguage(context)
        
        // 根据当前语言确定目标语言
        val targetLanguage = when (currentLanguage) {
            "zh" -> context.getString(R.string.conversation_language_chinese)
            "en" -> "English"
            else -> context.getString(R.string.conversation_language_chinese) // 默认翻译为中文
        }
        
        val translationPrompt = """
${FunctionalPrompts.translationUserPrompt(targetLanguage, text)}
        """.trim()
        
        val chatHistory = listOf(
            Pair("system", FunctionalPrompts.translationSystemPrompt())
        )
        
        val contentBuilder = StringBuilder()
        
        try {
            // 获取翻译功能的AIService实例
            val translationService = multiServiceManager.getServiceForFunction(FunctionType.TRANSLATION)
            
            // 获取模型参数
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.TRANSLATION)
            
            val stream = translationService.sendMessage(
                context = context,
                message = translationPrompt,
                chatHistory = chatHistory,
                modelParameters = modelParameters
            )
            
            stream.collect { content ->
                contentBuilder.append(content)
            }
            
            return contentBuilder.toString().trim()
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * 自动生成工具包描述
     * @param pluginName 工具包名称
     * @param toolDescriptions 工具描述列表
     * @param multiServiceManager 多服务管理器
     * @return 生成的工具包描述
     */
    suspend fun generatePackageDescription(
        pluginName: String,
        toolDescriptions: List<String>,
        multiServiceManager: MultiServiceManager
    ): String {
        if (toolDescriptions.isEmpty()) {
            return ""
        }
        
        val toolList = toolDescriptions.joinToString("\n") { "- $it" }

        val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
        val descriptionPrompt =
            FunctionalPrompts.packageDescriptionUserPrompt(
                pluginName = pluginName,
                toolList = toolList,
                useEnglish = useEnglish
            )

        val chatHistory =
            listOf(Pair("system", FunctionalPrompts.packageDescriptionSystemPrompt(useEnglish)))
        
        val contentBuilder = StringBuilder()
        
        try {
            // 获取总结功能的AIService实例
            val summaryService = multiServiceManager.getServiceForFunction(FunctionType.SUMMARY)
            
            // 获取模型参数
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.SUMMARY)
            
            val stream = summaryService.sendMessage(
                context = context,
                message = descriptionPrompt,
                chatHistory = chatHistory,
                modelParameters = modelParameters
            )
            
            stream.collect { content ->
                contentBuilder.append(content)
            }
            
            val result = ChatUtils.removeThinkingContent(contentBuilder.toString().trim())
            
            // 如果生成失败或内容为空，返回空字符串表示生成失败
            return if (result.isBlank()) {
                ""
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成工具包描述时出错", e)
            return ""
        }
    }

    /**
     * 使用识图模型分析图片
     * @param imagePath 图片路径
     * @param userIntent 用户意图，例如"这个图片里面有什么"、"图片的题目公式是什么"等
     * @param multiServiceManager 多服务管理器
     * @return AI分析结果
     */
    suspend fun analyzeImageWithIntent(
        imagePath: String,
        userIntent: String?,
        multiServiceManager: MultiServiceManager
    ): String {
        return try {
            val service = multiServiceManager.getServiceForFunction(FunctionType.IMAGE_RECOGNITION)
            
            // 添加图片到池子并获取ID
            val imageId = com.ai.assistance.custard.util.ImagePoolManager.addImage(imagePath)
            if (imageId == "error") {
                return "Failed to load image: $imagePath"
            }

            // 构建提示词，包含用户意图和图片链接
            val imageLink = MediaLinkBuilder.image(context, imageId)
            val prompt = if (userIntent.isNullOrBlank()) {
                "$imageLink\n${context.getString(R.string.conversation_analyze_image_prompt)}"
            } else {
                "$imageLink\n$userIntent"
            }
            
            // 获取模型参数
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.IMAGE_RECOGNITION)
            
            // 调用AI服务分析图片
            val result = StringBuilder()
            service.sendMessage(
                context = context,
                message = prompt,
                chatHistory = emptyList(),
                modelParameters = modelParameters
            ).collect { chunk ->
                result.append(chunk)
            }
            
            // 清理图片缓存
            com.ai.assistance.custard.util.ImagePoolManager.removeImage(imageId)
            
            result.toString()
        } catch (e: Exception) {
            AppLogger.e(TAG, "识图分析失败", e)
            "Image recognition failed: ${e.message}"
        }
    }

    suspend fun analyzeAudioWithIntent(
        audioPath: String,
        userIntent: String?,
        multiServiceManager: MultiServiceManager
    ): String {
        return try {
            val service = multiServiceManager.getServiceForFunction(FunctionType.AUDIO_RECOGNITION)

            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(java.io.File(audioPath).extension.lowercase())
                ?: "audio/*"

            val mediaId = com.ai.assistance.custard.util.MediaPoolManager.addMedia(audioPath, mimeType)
            if (mediaId == "error") {
                return "Failed to load audio: $audioPath"
            }

            val audioLink = MediaLinkBuilder.audio(context, mediaId)
            val prompt = if (userIntent.isNullOrBlank()) {
                "$audioLink\n${context.getString(R.string.conversation_analyze_audio_prompt)}"
            } else {
                "$audioLink\n$userIntent"
            }

            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.AUDIO_RECOGNITION)

            val result = StringBuilder()
            service.sendMessage(
                context = context,
                message = prompt,
                chatHistory = emptyList(),
                modelParameters = modelParameters
            ).collect { chunk ->
                result.append(chunk)
            }

            com.ai.assistance.custard.util.MediaPoolManager.removeMedia(mediaId)
            result.toString()
        } catch (e: Exception) {
            AppLogger.e(TAG, "音频识别失败", e)
            "Audio recognition failed: ${e.message}"
        }
    }

    suspend fun analyzeVideoWithIntent(
        videoPath: String,
        userIntent: String?,
        multiServiceManager: MultiServiceManager
    ): String {
        return try {
            val service = multiServiceManager.getServiceForFunction(FunctionType.VIDEO_RECOGNITION)

            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(java.io.File(videoPath).extension.lowercase())
                ?: "video/*"

            val mediaId = com.ai.assistance.custard.util.MediaPoolManager.addMedia(videoPath, mimeType)
            if (mediaId == "error") {
                return "Failed to load video: $videoPath"
            }

            val videoLink = MediaLinkBuilder.video(context, mediaId)
            val prompt = if (userIntent.isNullOrBlank()) {
                "$videoLink\n${context.getString(R.string.conversation_analyze_video_prompt)}"
            } else {
                "$videoLink\n$userIntent"
            }

            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.VIDEO_RECOGNITION)

            val result = StringBuilder()
            service.sendMessage(
                context = context,
                message = prompt,
                chatHistory = emptyList(),
                modelParameters = modelParameters
            ).collect { chunk ->
                result.append(chunk)
            }

            com.ai.assistance.custard.util.MediaPoolManager.removeMedia(mediaId)
            result.toString()
        } catch (e: Exception) {
            AppLogger.e(TAG, "视频识别失败", e)
            "Video recognition failed: ${e.message}"
        }
    }
}

