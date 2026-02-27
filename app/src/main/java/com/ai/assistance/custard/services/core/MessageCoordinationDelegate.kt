package com.ai.assistance.custard.services.core

import android.content.Context
import com.ai.assistance.custard.R
import com.ai.assistance.custard.util.AppLogger
import com.ai.assistance.custard.api.chat.EnhancedAIService
import com.ai.assistance.custard.core.chat.AIMessageManager
import com.ai.assistance.custard.data.model.FunctionType
import com.ai.assistance.custard.data.model.PromptFunctionType
import com.ai.assistance.custard.data.model.ChatMessage
import com.ai.assistance.custard.data.model.InputProcessingState
import com.ai.assistance.custard.ui.features.chat.viewmodel.UiStateDelegate
import com.ai.assistance.custard.data.preferences.CharacterCardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/**
 * 消息协调委托类
 * 负责消息发送、自动总结、附件清理等核心协调逻辑
 */
class MessageCoordinationDelegate(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val chatHistoryDelegate: ChatHistoryDelegate,
    private val messageProcessingDelegate: MessageProcessingDelegate,
    private val tokenStatsDelegate: TokenStatisticsDelegate,
    private val apiConfigDelegate: ApiConfigDelegate,
    private val attachmentDelegate: AttachmentDelegate,
    private val uiStateDelegate: UiStateDelegate,
    private val getEnhancedAiService: () -> EnhancedAIService?,
    private val updateWebServerForCurrentChat: (String) -> Unit,
    private val resetAttachmentPanelState: () -> Unit,
    private val clearReplyToMessage: () -> Unit,
    private val getReplyToMessage: () -> ChatMessage?
) {
    companion object {
        private const val TAG = "MessageCoordinationDelegate"
    }

    // 总结状态（使用 summarizeHistory 时）
    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _summarizingChatId = MutableStateFlow<String?>(null)
    val summarizingChatId: StateFlow<String?> = _summarizingChatId.asStateFlow()

    // 发送消息触发的异步总结状态（使用 launchAsyncSummaryForSend 时）
    private val _isSendTriggeredSummarizing = MutableStateFlow(false)
    val isSendTriggeredSummarizing: StateFlow<Boolean> = _isSendTriggeredSummarizing.asStateFlow()

    private val _sendTriggeredSummarizingChatId = MutableStateFlow<String?>(null)
    val sendTriggeredSummarizingChatId: StateFlow<String?> = _sendTriggeredSummarizingChatId.asStateFlow()

    // 保存总结任务的 Job 引用，用于取消
    private var summaryJob: Job? = null

    // 保存当前的 promptFunctionType，用于自动继续时保持提示词一致性
    private var currentPromptFunctionType: PromptFunctionType = PromptFunctionType.CHAT

    private var nonFatalErrorCollectorJob: Job? = null
    private val characterCardManager = CharacterCardManager.getInstance(context)

    init {
        ensureNonFatalErrorCollectorStarted()
    }

    private fun ensureNonFatalErrorCollectorStarted() {
        if (nonFatalErrorCollectorJob?.isActive == true) return
        nonFatalErrorCollectorJob = coroutineScope.launch {
            messageProcessingDelegate.nonFatalErrorEvent.collect { errorMessage ->
                uiStateDelegate.showToast(errorMessage)
            }
        }
    }

    /**
     * 发送用户消息
     * 检查是否有当前对话，如果没有则自动创建新对话
     */
    fun sendUserMessage(
        promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        roleCardIdOverride: String? = null,
        chatIdOverride: String? = null,
        messageTextOverride: String? = null,
        proxySenderNameOverride: String? = null
    ) {
        // 仅在没有指定 chatId 的情况下，才需要确保有当前对话
        if (chatIdOverride.isNullOrBlank() && chatHistoryDelegate.currentChatId.value == null) {
            AppLogger.d(TAG, "当前没有活跃对话，自动创建新对话")

            // 使用 coroutineScope 启动协程
            coroutineScope.launch {
                // 使用现有的createNewChat方法创建新对话
                chatHistoryDelegate.createNewChat()

                // 等待对话ID更新
                var waitCount = 0
                while (chatHistoryDelegate.currentChatId.value == null && waitCount < 10) {
                    delay(100) // 短暂延迟等待对话创建完成
                    waitCount++
                }

                if (chatHistoryDelegate.currentChatId.value == null) {
                    AppLogger.e(TAG, "创建新对话超时，无法发送消息")
                    uiStateDelegate.showErrorMessage(context.getString(R.string.chat_cannot_create_new))
                    return@launch
                }

                AppLogger.d(
                    TAG,
                    "新对话创建完成，ID: ${chatHistoryDelegate.currentChatId.value}，现在发送消息"
                )

                // 对话创建完成后，发送消息
                sendMessageInternal(
                    promptFunctionType,
                    roleCardIdOverride = roleCardIdOverride,
                    chatIdOverride = chatIdOverride,
                    messageTextOverride = messageTextOverride,
                    proxySenderNameOverride = proxySenderNameOverride
                )
            }
        } else {
            // 已有对话，直接发送消息
            sendMessageInternal(
                promptFunctionType,
                roleCardIdOverride = roleCardIdOverride,
                chatIdOverride = chatIdOverride,
                messageTextOverride = messageTextOverride,
                proxySenderNameOverride = proxySenderNameOverride
            )
        }
    }

    /**
     * 内部发送消息的逻辑
     */
    private fun sendMessageInternal(
        promptFunctionType: PromptFunctionType,
        isContinuation: Boolean = false,
        skipSummaryCheck: Boolean = false,
        isAutoContinuation: Boolean = false,
        roleCardIdOverride: String? = null,
        chatIdOverride: String? = null,
        messageTextOverride: String? = null,
        proxySenderNameOverride: String? = null
    ) {
        // 如果不是自动续写，更新当前的 promptFunctionType
        if (!isAutoContinuation) {
            currentPromptFunctionType = promptFunctionType
        }
        val isBackgroundSend = !chatIdOverride.isNullOrBlank()
        // 获取当前聊天ID和工作区路径
        val chatId = chatIdOverride ?: chatHistoryDelegate.currentChatId.value
        if (chatId == null) {
            uiStateDelegate.showErrorMessage(context.getString(R.string.chat_no_active_conversation))
            return
        }
        val currentChat = chatHistoryDelegate.chatHistories.value.find { it.id == chatId }
        val workspacePath = currentChat?.workspace
        val workspaceEnv = currentChat?.workspaceEnv

        if (!isBackgroundSend) {
            // 更新本地Web服务器的聊天ID
            updateWebServerForCurrentChat(chatId)
        }

        // 获取当前附件列表
        val currentAttachments = if (isBackgroundSend) emptyList() else attachmentDelegate.attachments.value

        // 当前请求使用的Token使用率阈值，默认使用配置值
        var tokenUsageThresholdForSend = apiConfigDelegate.summaryTokenThreshold.value.toDouble()

        // 如果不是续写，检查是否需要总结
        if (!isBackgroundSend && !isContinuation && !skipSummaryCheck) {
            val currentMessages = chatHistoryDelegate.chatHistory.value
            val currentTokens = tokenStatsDelegate.currentWindowSizeFlow.value
            val maxTokens = (apiConfigDelegate.contextLength.value * 1024).toInt()

            val isShouldGenerateSummary = AIMessageManager.shouldGenerateSummary(
                messages = currentMessages,
                currentTokens = currentTokens,
                maxTokens = maxTokens,
                tokenUsageThreshold = tokenUsageThresholdForSend,
                enableSummary = apiConfigDelegate.enableSummary.value,
                enableSummaryByMessageCount = apiConfigDelegate.enableSummaryByMessageCount.value,
                summaryMessageCountThreshold = apiConfigDelegate.summaryMessageCountThreshold.value
            )

            if (isShouldGenerateSummary) {
                val snapshotMessages = currentMessages.toList()
                val insertPosition = chatHistoryDelegate.findProperSummaryPosition(snapshotMessages)

                // 异步生成总结，不阻塞当前消息发送
                launchAsyncSummaryForSend(snapshotMessages, insertPosition, chatId)

                // 本次请求的Token阈值在原基础上增加 0.5
                tokenUsageThresholdForSend += 0.5
            }
        }

        val proxySenderName = proxySenderNameOverride?.takeIf { it.isNotBlank() }

        // 检测是否附着了记忆文件夹
        val hasMemoryFolder = currentAttachments.any {
            it.fileName == "memory_context.xml" && it.mimeType == "application/xml"
        }

        // 如果是proxy sender，视为关闭记忆附着
        val shouldEnableMemoryQuery = if (proxySenderName.isNullOrBlank()) {
            apiConfigDelegate.enableMemoryQuery.value || hasMemoryFolder
        } else {
            false
        }

        val roleCardId = roleCardIdOverride?.takeIf { it.isNotBlank() }
            ?: runBlocking { characterCardManager.activeCharacterCardIdFlow.first() }

        // 调用messageProcessingDelegate发送消息，并传递附件信息和工作区路径
        messageProcessingDelegate.sendUserMessage(
            attachments = currentAttachments,
            chatId = chatId,
            messageTextOverride = messageTextOverride,
            proxySenderNameOverride = proxySenderName,
            workspacePath = workspacePath,
            workspaceEnv = workspaceEnv,
            promptFunctionType = promptFunctionType,
            roleCardId = roleCardId,
            // Safety: thinking guidance and thinking mode are mutually exclusive.
            // When guidance is enabled, we avoid enabling provider-level thinking simultaneously.
            enableThinking = apiConfigDelegate.enableThinkingMode.value && !apiConfigDelegate.enableThinkingGuidance.value,
            thinkingGuidance = apiConfigDelegate.enableThinkingGuidance.value,
            enableMemoryQuery = shouldEnableMemoryQuery,
            enableWorkspaceAttachment = !workspacePath.isNullOrBlank(),
            maxTokens = (apiConfigDelegate.contextLength.value * 1024).toInt(),
            tokenUsageThreshold = tokenUsageThresholdForSend,
            replyToMessage = if (isBackgroundSend) null else getReplyToMessage(),
            isAutoContinuation = isAutoContinuation,
            enableSummary = if (isBackgroundSend) false else apiConfigDelegate.enableSummary.value
        )

        // 只有在非续写（即用户主动发送）时才清空附件和UI状态
        if (!isBackgroundSend && !isContinuation) {
            if (currentAttachments.isNotEmpty()) {
                attachmentDelegate.clearAttachments()
            }
            resetAttachmentPanelState()
            clearReplyToMessage()
        }
    }

    /**
     * 手动更新记忆
     */
    fun manuallyUpdateMemory() {
        coroutineScope.launch {
            val enhancedAiService = getEnhancedAiService()
            if (enhancedAiService == null) {
                uiStateDelegate.showToast(context.getString(R.string.chat_ai_service_unavailable_memory))
                return@launch
            }
            if (chatHistoryDelegate.chatHistory.value.isEmpty()) {
                uiStateDelegate.showToast(context.getString(R.string.chat_history_empty_no_update))
                return@launch
            }

            try {
                // Convert ChatMessage list to List<Pair<String, String>>
                val history = chatHistoryDelegate.chatHistory.value.map { it.sender to it.content }
                // Get the last message content
                val lastMessageContent =
                    chatHistoryDelegate.chatHistory.value.lastOrNull()?.content ?: ""

                enhancedAiService.saveConversationToMemory(
                    history,
                    lastMessageContent
                )
                uiStateDelegate.showToast(context.getString(R.string.chat_memory_manually_updated))
            } catch (e: Exception) {
                AppLogger.e(TAG, "手动更新记忆失败", e)
                uiStateDelegate.showErrorMessage(
                    context.getString(
                        R.string.chat_manual_update_memory_failed,
                        e.message ?: ""
                    )
                )
            }
        }
    }

    /**
     * 手动触发对话总结
     */
    fun manuallySummarizeConversation() {
        if (_isSummarizing.value) {
            uiStateDelegate.showToast(context.getString(R.string.chat_summarizing_please_wait))
            return
        }
        coroutineScope.launch {
            val success = summarizeHistory(autoContinue = false)
            if (success) {
                uiStateDelegate.showToast(context.getString(R.string.chat_conversation_summary_generated))
            }
        }
    }

    /**
     * 处理Token超限的情况，触发一次历史总结并继续。
     */
    fun handleTokenLimitExceeded(chatId: String?) {
        AppLogger.d(TAG, "接收到Token超限信号，开始执行总结并继续...")
        summaryJob = coroutineScope.launch {
            summarizeHistory(autoContinue = true, chatIdOverride = chatId)
            summaryJob = null
        }
    }

    /**
     * 取消正在进行的总结操作
     */
    fun cancelSummary() {
        if (_isSummarizing.value) {
            AppLogger.d(TAG, "取消正在进行的总结操作")
            val targetChatId = _summarizingChatId.value
            summaryJob?.cancel()
            summaryJob = null
            _isSummarizing.value = false
            _summarizingChatId.value = null
            // 重置状态
            messageProcessingDelegate.resetLoadingState()
            if (targetChatId != null) {
                messageProcessingDelegate.setSuppressIdleCompletedStateForChat(targetChatId, false)
                messageProcessingDelegate.setInputProcessingStateForChat(
                    targetChatId,
                    InputProcessingState.Idle
                )
            }
        }
    }

    private fun launchAsyncSummaryForSend(
        snapshotMessages: List<ChatMessage>,
        insertPosition: Int,
        originalChatId: String?
    ) {
        if (snapshotMessages.isEmpty() || originalChatId == null) {
            return
        }

        // 标记：有一次发送触发的异步总结正在进行
        _isSendTriggeredSummarizing.value = true
        _sendTriggeredSummarizingChatId.value = originalChatId
        messageProcessingDelegate.setPendingAsyncSummaryUiForChat(originalChatId, true)
        messageProcessingDelegate.setSuppressIdleCompletedStateForChat(originalChatId, true)
        messageProcessingDelegate.setInputProcessingStateForChat(
            originalChatId,
            InputProcessingState.Summarizing(context.getString(R.string.chat_compressing_history))
        )

        coroutineScope.launch {
            try {
                val service = getEnhancedAiService() ?: return@launch

                val summaryMessage = AIMessageManager.summarizeMemory(
                    enhancedAiService = service,
                    messages = snapshotMessages,
                    autoContinue = false
                ) ?: return@launch

                val currentChatId = chatHistoryDelegate.currentChatId.value
                if (currentChatId != originalChatId) {
                    AppLogger.d(
                        TAG,
                        "Async summary skipped: chat switched from $originalChatId to $currentChatId"
                    )
                    return@launch
                }

                val currentMessages = chatHistoryDelegate.chatHistory.value
                if (insertPosition < 0 || insertPosition > currentMessages.size) {
                    AppLogger.w(
                        TAG,
                        "Async summary insert skipped: position out of bounds: $insertPosition, size=${currentMessages.size}"
                    )
                    return@launch
                }

                chatHistoryDelegate.addSummaryMessage(summaryMessage, insertPosition)

                val newHistoryForTokens =
                    AIMessageManager.getMemoryFromMessages(chatHistoryDelegate.chatHistory.value)
                val chatService = service.getAIServiceForFunction(FunctionType.CHAT)
                val newWindowSize = chatService.calculateInputTokens("", newHistoryForTokens)
                val chatServiceForStats = service.getAIServiceForFunction(FunctionType.CHAT)
                val (inputTokens, outputTokens) = tokenStatsDelegate.getCumulativeTokenCounts(
                    originalChatId
                )
                chatHistoryDelegate.saveCurrentChat(
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    actualContextWindowSize = newWindowSize,
                    chatIdOverride = originalChatId
                )
                withContext(Dispatchers.Main) {
                    tokenStatsDelegate.setTokenCounts(
                        originalChatId,
                        inputTokens,
                        outputTokens,
                        newWindowSize
                    )
                }
                AppLogger.d(TAG, "Async summary completed, updated window size: $newWindowSize")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Async summary during send failed: ${e.message}", e)
            } finally {
                _isSendTriggeredSummarizing.value = false

                if (_sendTriggeredSummarizingChatId.value == originalChatId) {
                    _sendTriggeredSummarizingChatId.value = null
                }

                messageProcessingDelegate.setPendingAsyncSummaryUiForChat(originalChatId, false)
                messageProcessingDelegate.setSuppressIdleCompletedStateForChat(originalChatId, false)

                // 如果当前处于 Summarizing 状态（例如主界面在回复完成后锁定了总结状态），
                // 当异步总结结束时，主动恢复到 Idle
                val currentState =
                    messageProcessingDelegate.inputProcessingStateByChatId.value[originalChatId]
                if (currentState is InputProcessingState.Summarizing) {
                    messageProcessingDelegate.setInputProcessingStateForChat(
                        originalChatId,
                        InputProcessingState.Idle
                    )
                }
            }
        }
    }

    /**
     * 执行历史总结并自动继续对话的核心逻辑
     */
    private suspend fun summarizeHistory(
        autoContinue: Boolean = true,
        promptFunctionType: PromptFunctionType? = null,
        chatIdOverride: String? = null
    ): Boolean {
        if (_isSummarizing.value) {
            AppLogger.d(TAG, "已在总结中，忽略本次请求")
            return false
        }
        _isSummarizing.value = true
        val currentChatId = chatIdOverride ?: chatHistoryDelegate.currentChatId.value
        _summarizingChatId.value = currentChatId
        if (currentChatId != null) {
            messageProcessingDelegate.setSuppressIdleCompletedStateForChat(currentChatId, true)
            messageProcessingDelegate.setInputProcessingStateForChat(
                currentChatId,
                InputProcessingState.Summarizing(context.getString(R.string.chat_compressing_history))
            )
        }

        var summarySuccess = false
        try {
            val service = getEnhancedAiService()
            if (service == null) {
                uiStateDelegate.showErrorMessage(context.getString(R.string.chat_ai_service_unavailable_summarize))
                return false
            }

            val currentMessages = chatHistoryDelegate.chatHistory.value
            if (currentMessages.isEmpty()) {
                AppLogger.d(TAG, "历史记录为空，无需总结")
                return false
            }

            val insertPosition = chatHistoryDelegate.findProperSummaryPosition(currentMessages)
            val summaryMessage =
                AIMessageManager.summarizeMemory(service, currentMessages, autoContinue)

            if (summaryMessage != null) {
                chatHistoryDelegate.addSummaryMessage(summaryMessage, insertPosition)

                // 更新窗口大小
                val newHistoryForTokens =
                    AIMessageManager.getMemoryFromMessages(chatHistoryDelegate.chatHistory.value)
                val chatService = service.getAIServiceForFunction(FunctionType.CHAT)
                val newWindowSize = chatService.calculateInputTokens("", newHistoryForTokens)
                val currentChatIdForStats = currentChatId
                val (inputTokens, outputTokens) = tokenStatsDelegate.getCumulativeTokenCounts(
                    currentChatIdForStats
                )
                chatHistoryDelegate.saveCurrentChat(
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    actualContextWindowSize = newWindowSize,
                    chatIdOverride = currentChatIdForStats
                )
                withContext(Dispatchers.Main) {
                    tokenStatsDelegate.setTokenCounts(
                        currentChatIdForStats,
                        inputTokens,
                        outputTokens,
                        newWindowSize
                    )
                }
                AppLogger.d(TAG, "总结完成，更新窗口大小为: $newWindowSize")
                summarySuccess = true
            } else {
                AppLogger.w(TAG, "总结失败或无需总结")
                uiStateDelegate.showErrorMessage(context.getString(R.string.chat_summarize_failed_no_valid_summary))
            }
        } catch (e: CancellationException) {
            // 总结被取消，这是正常流程
            AppLogger.d(TAG, "总结操作被取消")
            throw e // 重新抛出取消异常，让协程正确取消
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成总结时出错: ${e.message}", e)
            uiStateDelegate.showErrorMessage(
                context.getString(
                    R.string.chat_summarize_generation_failed,
                    e.message ?: ""
                )
            )
        } finally {
            _isSummarizing.value = false
            if (_summarizingChatId.value == currentChatId) {
                _summarizingChatId.value = null
            }
            val wasSummarizing =
                currentChatId != null &&
                    messageProcessingDelegate.inputProcessingStateByChatId.value[currentChatId] is InputProcessingState.Summarizing

            // 确保加载状态被重置，避免阻塞自动续写
            messageProcessingDelegate.resetLoadingState()

            if (currentChatId != null) {
                messageProcessingDelegate.setSuppressIdleCompletedStateForChat(currentChatId, false)
            }

            if (summarySuccess) {
                if (autoContinue) {
                    AppLogger.d(TAG, "总结成功，自动继续对话...")
                    // 使用传入的 promptFunctionType 或当前保存的 promptFunctionType，保持提示词一致性
                    val continuationPromptType = promptFunctionType ?: currentPromptFunctionType
                    sendMessageInternal(
                        promptFunctionType = continuationPromptType,
                        isContinuation = true,
                        isAutoContinuation = true
                    )
                } else if (wasSummarizing) {
                    // 总结成功且不自动续写时，主动恢复到Idle
                    if (currentChatId != null) {
                        messageProcessingDelegate.setInputProcessingStateForChat(currentChatId, InputProcessingState.Idle)
                    }
                }
            } else if (wasSummarizing) {
                // 总结未成功时也恢复到Idle，避免卡在Summarizing状态
                if (currentChatId != null) {
                    messageProcessingDelegate.setInputProcessingStateForChat(currentChatId, InputProcessingState.Idle)
                }
            }
        }
        return summarySuccess
    }
}