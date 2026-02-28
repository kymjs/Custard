package com.kymjs.ai.custard.core.chat

import android.annotation.SuppressLint
import android.content.Context
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.util.AppLogger
import com.kymjs.ai.custard.api.chat.EnhancedAIService
import com.kymjs.ai.custard.api.chat.plan.PlanModeManager
import com.kymjs.ai.custard.api.chat.llmprovider.MediaLinkParser
import com.kymjs.ai.custard.api.chat.llmprovider.MediaLinkBuilder
import com.kymjs.ai.custard.core.tools.AIToolHandler
import com.kymjs.ai.custard.core.tools.MemoryQueryResultData
import com.kymjs.ai.custard.data.model.AITool
import com.kymjs.ai.custard.data.model.AttachmentInfo
import com.kymjs.ai.custard.data.model.ChatMessage
import com.kymjs.ai.custard.data.model.ToolParameter
import com.kymjs.ai.custard.data.model.PromptFunctionType
import com.kymjs.ai.custard.data.preferences.ApiPreferences
import com.kymjs.ai.custard.ui.features.chat.webview.workspace.process.WorkspaceAttachmentProcessor
import com.kymjs.ai.custard.util.ImagePoolManager
import com.kymjs.ai.custard.util.MediaPoolManager
import com.kymjs.ai.custard.util.ChatUtils
import com.kymjs.ai.custard.util.ChatMarkupRegex
import com.kymjs.ai.custard.data.model.InputProcessingState
import com.kymjs.ai.custard.util.stream.SharedStream
import com.kymjs.ai.custard.util.stream.share
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 单例对象，负责管理与 EnhancedAIService 的所有通信。
 *
 * 主要职责:
 * - 构建发送给AI的消息请求。
 * - 发送消息并处理流式响应。
 * - 请求生成对话总结。
 *
 * 设计原则:
 * - **无状态**: 本身不持有任何特定聊天的状态。所有需要的数据都通过方法参数传入。
 * - **职责明确**: 仅处理与AI服务的交互，UI更新和数据持久化由调用方负责。
 * - **封装逻辑**: 内部封装了与AI交互的策略，如是否需要总结、如何从历史中提取记忆等。
 */
@SuppressLint("StaticFieldLeak")
object AIMessageManager {
    private const val TAG = "AIMessageManager"
    // 聊天总结的消息数量阈值 - 移除硬编码，改用动态设置
    // private const val SUMMARY_CHUNK_SIZE = 4

    // 使用独立的协程作用域，确保AI操作的生命周期独立于任何特定的ViewModel
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val DEFAULT_CHAT_KEY = "__DEFAULT_CHAT__"

    private val activeEnhancedAiServiceByChatId = ConcurrentHashMap<String, EnhancedAIService>()
    private val activePlanModeManagerByChatId = ConcurrentHashMap<String, PlanModeManager>()

    @Volatile private var lastActiveChatKey: String = DEFAULT_CHAT_KEY

    private lateinit var toolHandler: AIToolHandler
    private lateinit var context: Context
    private lateinit var apiPreferences: ApiPreferences

    fun initialize(context: Context) {
        this.context = context
        toolHandler = AIToolHandler.getInstance(context)
        apiPreferences = ApiPreferences.getInstance(context)
    }

    /**
     * 构建用户消息的完整内容，包括附件和记忆标签。
     *
     * @param messageText 用户输入的原始文本。
     * @param attachments 附件列表。
     * @param enableMemoryQuery 是否允许AI查询记忆。
     * @param enableWorkspaceAttachment 是否启用工作区附着功能。
     * @param workspacePath 工作区路径。
     * @param workspaceEnv 工作区环境。
     * @param replyToMessage 回复消息。
     * @param enableDirectImageProcessing 是否将图片附件转换为link标签（用于直接图片处理）。
     * @param enableDirectAudioProcessing 是否将音频附件转换为link标签（用于直接音频处理）。
     * @param enableDirectVideoProcessing 是否将视频附件转换为link标签（用于直接视频处理）。
     * @return 格式化后的完整消息字符串。
     */
    suspend fun buildUserMessageContent(
        messageText: String,
        proxySenderName: String? = null,
        attachments: List<AttachmentInfo>,
        enableMemoryQuery: Boolean,
        enableWorkspaceAttachment: Boolean = false,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        replyToMessage: ChatMessage? = null,
        enableDirectImageProcessing: Boolean = false,
        enableDirectAudioProcessing: Boolean = false,
        enableDirectVideoProcessing: Boolean = false
    ): String {
        val proxySenderTag =
            if (!proxySenderName.isNullOrBlank() &&
                !messageText.contains("<proxy_sender", ignoreCase = true)
            ) {
                val safeProxySenderName = proxySenderName.replace("\"", "'")
                "<proxy_sender name=\"$safeProxySenderName\"/>"
            } else {
                ""
            }

        // 1. 构建回复标签（如果有回复消息）
        val replyTag = replyToMessage?.let { message ->
            val cleanContent = message.content
                .replace(Regex("<[^>]*>"), "") // 移除XML标签
                .trim()
                .let { if (it.length > 100) it.take(100) + "..." else it }

            val roleName = message.roleName ?: if (message.sender == "ai") "AI" else context.getString(R.string.ai_message_user)
            val instruction = context.getString(R.string.ai_message_replying_to_previous)
            "<reply_to sender=\"${roleName}\" timestamp=\"${message.timestamp}\">${instruction}\"${cleanContent}\"</reply_to>"
        } ?: ""

        // 3. 根据开关决定是否生成工作区附着
        val workspaceTag = if (enableWorkspaceAttachment && !workspacePath.isNullOrBlank() && !messageText.contains("<workspace_attachment>", ignoreCase = true)) {
            try {
                val workspaceContent = WorkspaceAttachmentProcessor.generateWorkspaceAttachment(
                    context = context,
                    workspacePath = workspacePath,
                    workspaceEnv = workspaceEnv
                )
                "<workspace_attachment>$workspaceContent</workspace_attachment>"
            } catch (e: Exception) {
                AppLogger.e(TAG, "生成工作区附着失败", e)
                ""
            }
        } else ""

        // 4. 构建附件标签
        val attachmentTags = if (attachments.isNotEmpty()) {
            attachments.joinToString(" ") { attachment ->
                // 如果启用直接图片处理且附件是图片，转换为link标签
                if (enableDirectImageProcessing && attachment.mimeType.startsWith("image/", ignoreCase = true)) {
                    try {
                        val imageId = ImagePoolManager.addImage(attachment.filePath)
                        MediaLinkBuilder.image(context, imageId)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "添加图片到池失败: ${attachment.filePath}", e)
                        // 失败时回退到普通附件格式
                        val attributes = buildString {
                            append("id=\"${attachment.filePath}\" ")
                            append("filename=\"${attachment.fileName}\" ")
                            append("type=\"${attachment.mimeType}\"")
                            if (attachment.fileSize > 0) {
                                append(" size=\"${attachment.fileSize}\"")
                            }
                        }
                        "<attachment $attributes>${attachment.content}</attachment>"
                    }
                } else if (enableDirectAudioProcessing && attachment.mimeType.startsWith("audio/", ignoreCase = true)) {
                    try {
                        val audioId = MediaPoolManager.addMedia(attachment.filePath, attachment.mimeType)
                        if (audioId == "error") {
                            throw IllegalStateException("addMedia returned error")
                        }
                        MediaLinkBuilder.audio(context, audioId)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "添加音频到池失败: ${attachment.filePath}", e)
                        val attributes = buildString {
                            append("id=\"${attachment.filePath}\" ")
                            append("filename=\"${attachment.fileName}\" ")
                            append("type=\"${attachment.mimeType}\"")
                            if (attachment.fileSize > 0) {
                                append(" size=\"${attachment.fileSize}\"")
                            }
                        }
                        "<attachment $attributes>${attachment.content}</attachment>"
                    }
                } else if (enableDirectVideoProcessing && attachment.mimeType.startsWith("video/", ignoreCase = true)) {
                    try {
                        val videoId = MediaPoolManager.addMedia(attachment.filePath, attachment.mimeType)
                        if (videoId == "error") {
                            throw IllegalStateException("addMedia returned error")
                        }
                        MediaLinkBuilder.video(context, videoId)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "添加视频到池失败: ${attachment.filePath}", e)
                        val attributes = buildString {
                            append("id=\"${attachment.filePath}\" ")
                            append("filename=\"${attachment.fileName}\" ")
                            append("type=\"${attachment.mimeType}\"")
                            if (attachment.fileSize > 0) {
                                append(" size=\"${attachment.fileSize}\"")
                            }
                        }
                        "<attachment $attributes>${attachment.content}</attachment>"
                    }
                } else {
                    // 非图片或未启用直接图片处理，使用普通附件格式
                    val attributes = buildString {
                        append("id=\"${attachment.filePath}\" ")
                        append("filename=\"${attachment.fileName}\" ")
                        append("type=\"${attachment.mimeType}\"")
                        if (attachment.fileSize > 0) {
                            append(" size=\"${attachment.fileSize}\"")
                        }
                    }
                    "<attachment $attributes>${attachment.content}</attachment>"
                }
            }
        } else ""

        // 5. 组合最终消息
        return listOf(proxySenderTag, messageText, attachmentTags, workspaceTag, replyTag)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /**
     * 发送消息给AI服务。
     *
     * @param enhancedAiService AI服务实例。
     * @param chatId 聊天ID。
     * @param messageContent 已经构建好的完整消息内容。
     * @param chatHistory 完整的聊天历史记录。
     * @param workspacePath 当前工作区路径。
     * @param promptFunctionType 提示功能类型。
     * @param enableThinking 是否启用思考过程。
     * @param thinkingGuidance 是否启用思考引导。
     * @param enableMemoryQuery 是否允许AI查询记忆。
     * @param maxTokens 最大token数量。
     * @param tokenUsageThreshold token使用阈值。
     * @param onNonFatalError 非致命错误回调。
     * @param onTokenLimitExceeded token限制超出回调。
     * @param characterName 角色名称，用于通知。
     * @param avatarUri 角色头像URI，用于通知。
     * @param roleCardId 角色卡片ID。
     * @return 包含AI响应流的ChatMessage对象。
     */
    suspend fun sendMessage(
        enhancedAiService: EnhancedAIService,
        chatId: String? = null,
        messageContent: String,
        chatHistory: List<ChatMessage>,
        workspacePath: String?,
        promptFunctionType: PromptFunctionType,
        enableThinking: Boolean,
        thinkingGuidance: Boolean,
        enableMemoryQuery: Boolean,
        maxTokens: Int,
        tokenUsageThreshold: Double,
        onNonFatalError: suspend (error: String) -> Unit,
        onTokenLimitExceeded: (suspend () -> Unit)? = null,
        characterName: String? = null,
        avatarUri: String? = null,
        roleCardId: String,
        proxySenderName: String? = null
    ): SharedStream<String> {
        val chatKey = chatId ?: DEFAULT_CHAT_KEY
        lastActiveChatKey = chatKey
        activeEnhancedAiServiceByChatId[chatKey] = enhancedAiService

        val memory = getMemoryFromMessages(chatHistory)

        // 检查是否启用了深度搜索模式（计划模式）
        val isDeepSearchEnabled = apiPreferences.enableAiPlanningFlow.first()

        return withContext(Dispatchers.IO) {
            val maxImageHistoryUserTurns = apiPreferences.maxImageHistoryUserTurnsFlow.first()
            val maxMediaHistoryUserTurns = apiPreferences.maxMediaHistoryUserTurnsFlow.first()

            val memoryAfterImageLimit = limitImageLinksInChatHistory(memory, maxImageHistoryUserTurns)
            val memoryForRequest = limitMediaLinksInChatHistory(memoryAfterImageLimit, maxMediaHistoryUserTurns)
            val beforeImageLinkCount = memory.count { (_, content) -> MediaLinkParser.hasImageLinks(content) }
            val afterImageLinkCount = memoryForRequest.count { (_, content) -> MediaLinkParser.hasImageLinks(content) }
            if (beforeImageLinkCount != afterImageLinkCount) {
                AppLogger.d(
                    TAG,
                    "历史图片裁剪生效: limit=$maxImageHistoryUserTurns, before=$beforeImageLinkCount, after=$afterImageLinkCount"
                )
            }

            val beforeMediaLinkCount = memory.count { (_, content) -> MediaLinkParser.hasMediaLinks(content) }
            val afterMediaLinkCount = memoryForRequest.count { (_, content) -> MediaLinkParser.hasMediaLinks(content) }
            if (beforeMediaLinkCount != afterMediaLinkCount) {
                AppLogger.d(
                    TAG,
                    "历史音视频裁剪生效: limit=$maxMediaHistoryUserTurns, before=$beforeMediaLinkCount, after=$afterMediaLinkCount"
                )
            }

            if (isDeepSearchEnabled) {
                // 创建计划模式管理器
                val planModeManager = PlanModeManager(context, enhancedAiService)

                // 检查消息是否适合使用深度搜索模式
                val shouldUseDeepSearch = planModeManager.shouldUseDeepSearchMode(messageContent)

                if (shouldUseDeepSearch) {
                    activePlanModeManagerByChatId[chatKey] = planModeManager
                    AppLogger.d(TAG, "启用深度搜索模式处理消息")

                    // 设置执行计划的特定UI状态
                    enhancedAiService.setInputProcessingState(
                        InputProcessingState.ExecutingPlan(context.getString(R.string.ai_message_executing_deep_search))
                    )

                    // 使用深度搜索模式
                    return@withContext planModeManager.executeDeepSearchMode(
                        userMessage = messageContent,
                        chatHistory = memoryForRequest,
                        workspacePath = workspacePath,
                        maxTokens = maxTokens,
                        tokenUsageThreshold = tokenUsageThreshold,
                        onNonFatalError = onNonFatalError
                    ).share(
                        scope = scope,
                        onComplete = {
                            activePlanModeManagerByChatId.remove(chatKey)
                            activeEnhancedAiServiceByChatId.remove(chatKey)
                        }
                    )
                } else {
                    activePlanModeManagerByChatId.remove(chatKey)
                    AppLogger.d(TAG, "消息不适合深度搜索模式，使用普通模式")
                }
            } else {
                activePlanModeManagerByChatId.remove(chatKey)
            }

            // 获取流式输出设置
            val disableStreamOutput = apiPreferences.disableStreamOutputFlow.first()
            val enableStream = !disableStreamOutput

            // 使用普通模式
            enhancedAiService.sendMessage(
                message = messageContent,
                chatId = chatId,
                chatHistory = memoryForRequest, // Correct parameter name is chatHistory
                workspacePath = workspacePath,
                promptFunctionType = promptFunctionType,
                enableThinking = enableThinking,
                thinkingGuidance = thinkingGuidance,
                enableMemoryQuery = enableMemoryQuery,
                maxTokens = maxTokens,
                tokenUsageThreshold = tokenUsageThreshold,
                onNonFatalError = onNonFatalError,
                onTokenLimitExceeded = onTokenLimitExceeded, // 传递回调
                characterName = characterName,
                avatarUri = avatarUri,
                roleCardId = roleCardId,
                proxySenderName = proxySenderName,
                stream = enableStream
            ).share(
                scope = scope,
                onComplete = {
                    activePlanModeManagerByChatId.remove(chatKey)
                    activeEnhancedAiServiceByChatId.remove(chatKey)
                }
            )
        }
    }

    private fun limitMediaLinksInChatHistory(
        history: List<Pair<String, String>>,
        keepLastUserMediaTurns: Int
    ): List<Pair<String, String>> {
        val limit = keepLastUserMediaTurns.coerceAtLeast(0)
        val totalUserTurns = history.count { (role, _) -> role == "user" }
        val keepFromTurn = (totalUserTurns - limit).coerceAtLeast(0)

        var currentUserTurnIndex = -1
        return history.map { (role, content) ->
            if (role == "user") {
                currentUserTurnIndex += 1
            }

            val shouldKeepMedia = limit > 0 && currentUserTurnIndex >= keepFromTurn
            if (!shouldKeepMedia && MediaLinkParser.hasMediaLinks(content)) {
                val removed = MediaLinkParser.removeMediaLinks(content).trim()
                role to (removed.ifBlank { context.getString(R.string.ai_message_media_omitted) })
            } else {
                role to content
            }
        }
    }

    private fun limitImageLinksInChatHistory(
        history: List<Pair<String, String>>,
        keepLastUserImageTurns: Int
    ): List<Pair<String, String>> {
        val limit = keepLastUserImageTurns.coerceAtLeast(0)
        val totalUserTurns = history.count { (role, _) -> role == "user" }
        val keepFromTurn = (totalUserTurns - limit).coerceAtLeast(0)

        var currentUserTurnIndex = -1
        return history.map { (role, content) ->
            if (role == "user") {
                currentUserTurnIndex += 1
            }

            val shouldKeepImages = limit > 0 && currentUserTurnIndex >= keepFromTurn
            if (!shouldKeepImages && MediaLinkParser.hasImageLinks(content)) {
                val removed = MediaLinkParser.removeImageLinks(content).trim()
                role to (removed.ifBlank { context.getString(R.string.ai_message_image_omitted) })
            } else {
                role to content
            }
        }
    }

    /**
     * 取消当前正在进行的AI操作。
     * 这会同时尝试取消计划执行（如果正在进行）和底层的AI流。
     */
    fun cancelCurrentOperation() {
        cancelOperation(lastActiveChatKey)
    }

    fun cancelOperation(chatId: String) {
        val chatKey = chatId.ifBlank { DEFAULT_CHAT_KEY }
        AppLogger.d(TAG, "请求取消AI操作: chatId=$chatKey")

        activePlanModeManagerByChatId.remove(chatKey)?.let {
            AppLogger.d(TAG, "正在取消计划模式执行: chatId=$chatKey")
            it.cancel()
        }

        activeEnhancedAiServiceByChatId.remove(chatKey)?.let {
            AppLogger.d(TAG, "正在取消 EnhancedAIService 对话: chatId=$chatKey")
            it.cancelConversation()
        }

        AppLogger.d(TAG, "AI操作取消请求已发送: chatId=$chatKey")
    }

    fun cancelAllOperations() {
        AppLogger.d(TAG, "请求取消所有AI操作...")
        val keys = (activeEnhancedAiServiceByChatId.keys + activePlanModeManagerByChatId.keys).toSet()
        keys.forEach { cancelOperation(it) }
        AppLogger.d(TAG, "所有AI操作取消请求已发送。")
    }

    /**
     * 请求AI服务生成对话总结。
     *
     * @param enhancedAiService AI服务实例。
     * @param messages 需要总结的消息列表。
     * @param autoContinue 是否为自动续写模式，如果是则在总结消息尾部添加续写提示。
     * @return 包含总结内容的ChatMessage对象，如果无需总结或总结失败则返回null。
     */
    suspend fun summarizeMemory(
        enhancedAiService: EnhancedAIService,
        messages: List<ChatMessage>,
        autoContinue: Boolean = false
    ): ChatMessage? {
        val lastSummaryIndex = messages.indexOfLast { it.sender == "summary" }
        val previousSummary = if (lastSummaryIndex != -1) messages[lastSummaryIndex].content.trim() else null

        val messagesToSummarize = when {
            lastSummaryIndex == -1 -> messages.filter { it.sender == "user" || it.sender == "ai" }
            else -> messages.subList(lastSummaryIndex + 1, messages.size)
                .filter { it.sender == "user" || it.sender == "ai" }
        }

        if (messagesToSummarize.isEmpty()) {
            AppLogger.d(TAG, "没有新消息需要总结")
            return null
        }

        val memoryTagRegex = Regex("<memory>.*?</memory>", RegexOption.DOT_MATCHES_ALL)
        val conversationReviewEntries = mutableListOf<Pair<String, String>>()
        fun normalizeForReview(text: String): String {
            return text
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun condenseHeadTail(text: String, headChars: Int, tailChars: Int): String {
            val normalized = normalizeForReview(text)
            val head = headChars.coerceAtLeast(0)
            val tail = tailChars.coerceAtLeast(0)
            val minTotal = head + tail
            if (normalized.length <= minTotal + 3) return normalized
            if (head == 0 && tail == 0) return "..."
            if (head == 0) return "..." + normalized.takeLast(tail)
            if (tail == 0) return normalized.take(head) + "..."
            return normalized.take(head) + "..." + normalized.takeLast(tail)
        }

        fun stripMediaLinksForAssistant(text: String): String {
            var cleaned = text
            val removedImages = MediaLinkParser.hasImageLinks(cleaned)
            if (removedImages) {
                cleaned = MediaLinkParser.removeImageLinks(cleaned)
            }
            val removedMedia = MediaLinkParser.hasMediaLinks(cleaned)
            if (removedMedia) {
                cleaned = MediaLinkParser.removeMediaLinks(cleaned)
            }
            cleaned = cleaned.trim()
            if (cleaned.isBlank()) {
                return when {
                    removedImages -> context.getString(R.string.ai_message_image_omitted)
                    removedMedia -> context.getString(R.string.ai_message_media_omitted)
                    else -> ""
                }
            }
            return cleaned
        }

        fun pruneUserMessageForReview(text: String): String {
            val removedLargeTags = text
                .replace(
                    Regex("<workspace_attachment>[\\s\\S]*?</workspace_attachment>", RegexOption.DOT_MATCHES_ALL),
                    context.getString(R.string.ai_message_workspace_omitted)
                )
                .replace(
                    Regex("<attachment[\\s\\S]*?</attachment>", RegexOption.DOT_MATCHES_ALL),
                    context.getString(R.string.ai_message_attachment_omitted)
                )
                .replace(
                    Regex("<reply_to[\\s\\S]*?</reply_to>", RegexOption.DOT_MATCHES_ALL),
                    context.getString(R.string.ai_message_reply_omitted)
                )

            return ChatMarkupRegex.toolResultTagWithAttrs.replace(removedLargeTags) { mr ->
                val attrs = mr.groupValues.getOrNull(1) ?: ""
                val name = ChatMarkupRegex.nameAttr
                    .find(attrs)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.ifBlank { null }
                if (name != null) {
                    context.getString(R.string.ai_message_tool_result_omitted, name)
                } else {
                    context.getString(R.string.ai_message_tool_result_omitted_short)
                }
            }
        }

        fun condenseUserForReview(text: String): String {
            val pruned = pruneUserMessageForReview(text)
            return condenseHeadTail(pruned, headChars = 60, tailChars = 20)
        }

        fun condenseAssistantForReview(text: String): String {
            val cleaned = ChatUtils.removeThinkingContent(text)
            val normalized = normalizeForReview(cleaned)
            if (normalized.isBlank()) return "[Empty]"

            data class Segment(
                val kind: String,
                val raw: String,
                val toolName: String? = null,
                val status: String? = null
            )

            val blockRegex = ChatMarkupRegex.toolOrToolResultBlock
            val nameAttrRegex = ChatMarkupRegex.nameAttr
            val statusAttrRegex = ChatMarkupRegex.statusAttr

            val segments = mutableListOf<Segment>()
            var lastEnd = 0
            for (m in blockRegex.findAll(normalized)) {
                val start = m.range.first
                val endExclusive = m.range.last + 1
                if (start > lastEnd) {
                    segments.add(Segment(kind = "text", raw = normalized.substring(lastEnd, start)))
                }

                val block = m.value
                if (block.trimStart().startsWith("<tool", ignoreCase = true)) {
                    val toolName = m.groupValues.getOrNull(2)?.ifBlank { null } ?: "tool"
                    segments.add(Segment(kind = "tool", raw = block, toolName = toolName))
                } else {
                    val attrs = m.groupValues.getOrNull(4) ?: ""
                    val toolName = nameAttrRegex.find(attrs)?.groupValues?.getOrNull(1)?.ifBlank { null } ?: "tool"
                    val status = statusAttrRegex.find(attrs)?.groupValues?.getOrNull(1)?.ifBlank { null }
                    segments.add(Segment(kind = "tool_result", raw = block, toolName = toolName, status = status))
                }

                lastEnd = endExclusive
            }
            if (lastEnd < normalized.length) {
                segments.add(Segment(kind = "text", raw = normalized.substring(lastEnd)))
            }

            val cleanedSegments = segments
                .mapNotNull { seg ->
                    when (seg.kind) {
                        "text" -> {
                            val stripped = seg.raw.replace(Regex("<[^>]*>"), " ").trim()
                            if (stripped.isBlank()) null else seg.copy(raw = stripped)
                        }
                        else -> seg
                    }
                }
                .toMutableList()

            val maxSegments = 13
            if (cleanedSegments.size > maxSegments) {
                val head = cleanedSegments.take(6)
                val tail = cleanedSegments.takeLast(5)
                val omitted = (cleanedSegments.size - head.size - tail.size).coerceAtLeast(0)
                cleanedSegments.clear()
                cleanedSegments.addAll(head)
                cleanedSegments.add(Segment(kind = "text", raw = context.getString(R.string.ai_message_omitted_segment, omitted) ) )
                cleanedSegments.addAll(tail)
            }

            val lastTextIndex = cleanedSegments.indexOfLast { it.kind == "text" }
            val parts = cleanedSegments.mapIndexedNotNull { index, seg ->
                when (seg.kind) {
                    "text" -> {
                        val headChars = if (index == lastTextIndex) 60 else 24
                        val tailChars = if (index == lastTextIndex) 24 else 12
                        condenseHeadTail(seg.raw, headChars = headChars, tailChars = tailChars).takeIf { it.isNotBlank() }
                    }
                    "tool" -> context.getString(R.string.ai_message_tool_start, seg.toolName ?: "tool")
                    "tool_result" -> {
                        val s = seg.status?.lowercase()
                        val statusText = when {
                            s == null -> ""
                            s == "success" -> context.getString(R.string.ai_message_success)
                            s == "error" -> context.getString(R.string.ai_message_failure)
                            else -> s
                        }
                        val name = seg.toolName ?: "tool"
                        if (statusText.isBlank()) {
                            context.getString(R.string.ai_message_result_omitted, name)
                        } else {
                            context.getString(R.string.ai_message_result_omitted_with_status, name, statusText)
                        }
                    }
                    else -> null
                }
            }

            val combined = parts.joinToString(" ").trim()
            return if (combined.isBlank()) "[Empty]" else combined
        }

        val conversationToSummarize = messagesToSummarize.mapIndexed { index, message ->
            val role = if (message.sender == "user") "user" else "assistant"
            val cleanedContent = if (role == "user") {
                message.content.replace(memoryTagRegex, "").trim()
            } else {
                stripMediaLinksForAssistant(message.content)
            }
            if (cleanedContent.isNotBlank()) {
                val displayContent =
                    if (role == "assistant") condenseAssistantForReview(cleanedContent) else condenseUserForReview(cleanedContent)
                conversationReviewEntries.add(role to displayContent)
            }
            Pair(role, "#${index + 1}: $cleanedContent")
        }

        return try {
            AppLogger.d(TAG, "开始使用AI生成对话总结：总结 ${messagesToSummarize.size} 条消息")
            val summary = enhancedAiService.generateSummary(conversationToSummarize, previousSummary)
            AppLogger.d(TAG, "AI生成总结完成: ${summary.take(50)}...")

            if (summary.isBlank()) {
                AppLogger.e(TAG, "AI生成的总结内容为空，放弃本次总结")
                null
            } else {
                // 如果是自动续写，在总结消息尾部添加续写提示
                val trimmedSummary = summary.trim()
                val summaryWithQuotes = buildString {
                    append(trimmedSummary)
                    if (conversationReviewEntries.isNotEmpty()) {
                        append(context.getString(R.string.ai_message_dialogue_review))
                        conversationReviewEntries.forEach { (role, content) ->
                            append("- ")
                            append(if (role == "user") context.getString(R.string.ai_message_user_label) else "AI")
                            append(": ")
                            append(content)
                            append("\n")
                        }
                    }
                }.trimEnd()

                val finalSummary = if (autoContinue) {
                    context.getString(R.string.ai_message_continue_task_if_complete, summaryWithQuotes)
                } else {
                    summaryWithQuotes
                }
                
                ChatMessage(
                    sender = "summary",
                    content = finalSummary,
                    timestamp = System.currentTimeMillis(),
                    roleName = "system" // 总结消息的角色名
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            AppLogger.e(TAG, "AI生成总结过程中发生异常", e)
            throw e
        }
    }

    /**
     * 判断是否应该生成对话总结。
     *
     * @param messages 完整的消息列表。
     * @param currentTokens 当前上下文的token数量。
     * @param maxTokens 上下文窗口的最大token数量。
     * @return 如果应该生成总结，则返回true。
     */
    fun shouldGenerateSummary(
        messages: List<ChatMessage>,
        currentTokens: Int,
        maxTokens: Int,
        tokenUsageThreshold: Double,
        enableSummary: Boolean,
        enableSummaryByMessageCount: Boolean,
        summaryMessageCountThreshold: Int
    ): Boolean {
        // 首先检查总结功能是否启用
        if (!enableSummary) {
            return false
        }

        // 检查Token阈值
        if (maxTokens > 0) {
            val usageRatio = currentTokens.toDouble() / maxTokens.toDouble()
            if (usageRatio >= tokenUsageThreshold) {
                AppLogger.d(TAG, "Token usage ($usageRatio) exceeds threshold ($tokenUsageThreshold). Triggering summary.")
                return true
            }
        }

        // 检查消息条数阈值（如果启用）
        if (enableSummaryByMessageCount) {
            val lastSummaryIndex = messages.indexOfLast { it.sender == "summary" }
            val relevantMessages = if (lastSummaryIndex != -1) {
                messages.subList(lastSummaryIndex + 1, messages.size)
            } else {
                messages
            }
            val userAiMessagesSinceLastSummary = relevantMessages.count { it.sender == "user"}

            if (userAiMessagesSinceLastSummary >= summaryMessageCountThreshold) {
                AppLogger.d(TAG, "自上次总结后新消息数量达到阈值 ($userAiMessagesSinceLastSummary)，生成总结.")
                return true
            }
        }

        AppLogger.d(TAG, "未达到生成总结的条件. Token使用率: ${if (maxTokens > 0) currentTokens.toDouble() / maxTokens else 0.0}")
        return false
    }

    /**
     * 从完整的聊天记录中提取用于AI上下文的“记忆”。
     * 这会获取上次总结之后的所有消息。
     *
     * @param messages 完整的聊天记录。
     * @return 一个Pair列表，包含角色和内容，用于AI请求。
     */
    fun getMemoryFromMessages(messages: List<ChatMessage>): List<Pair<String, String>> {
        val lastSummaryIndex = messages.indexOfLast { it.sender == "summary" }
        val relevantMessages = if (lastSummaryIndex != -1) {
            messages.subList(lastSummaryIndex, messages.size)
        } else {
            messages
        }
        return relevantMessages
            .filter { it.sender == "user" || it.sender == "ai" || it.sender == "summary" }
            .map {
                val role = if (it.sender == "ai") "assistant" else "user" // "summary" is treated as user-side context
                Pair(role, it.content)
            }
    }
} 