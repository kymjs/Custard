package com.kymjs.ai.custard.api.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import com.kymjs.ai.custard.util.AppLogger
import com.kymjs.ai.custard.api.chat.enhance.ConversationMarkupManager
import com.kymjs.ai.custard.api.chat.enhance.ConversationRoundManager
import com.kymjs.ai.custard.api.chat.enhance.ConversationService
import com.kymjs.ai.custard.api.chat.enhance.FileBindingService
import com.kymjs.ai.custard.api.chat.enhance.InputProcessor
import com.kymjs.ai.custard.api.chat.enhance.MultiServiceManager
import com.kymjs.ai.custard.api.chat.enhance.ToolExecutionManager
import com.kymjs.ai.custard.api.chat.llmprovider.AIService
import com.kymjs.ai.custard.core.application.ActivityLifecycleManager
import com.kymjs.ai.custard.core.tools.AIToolHandler
import com.kymjs.ai.custard.core.tools.StringResultData
import com.kymjs.ai.custard.core.tools.packTool.PackageManager
import com.kymjs.ai.custard.data.model.FunctionType
import com.kymjs.ai.custard.data.model.InputProcessingState
import com.kymjs.ai.custard.data.model.PromptFunctionType
import com.kymjs.ai.custard.data.model.ToolInvocation
import com.kymjs.ai.custard.data.model.ToolResult
import com.kymjs.ai.custard.data.model.ModelConfigData
import com.kymjs.ai.custard.data.model.AITool
import com.kymjs.ai.custard.data.preferences.ApiPreferences
import com.kymjs.ai.custard.data.preferences.WakeWordPreferences
import com.kymjs.ai.custard.util.stream.Stream
import com.kymjs.ai.custard.util.stream.StreamCollector
import com.kymjs.ai.custard.util.stream.plugins.StreamXmlPlugin
import com.kymjs.ai.custard.util.stream.splitBy
import com.kymjs.ai.custard.util.stream.stream
import com.kymjs.ai.custard.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.kymjs.ai.custard.data.repository.CustomEmojiRepository
import com.kymjs.ai.custard.data.preferences.CharacterCardManager
import com.kymjs.ai.custard.data.preferences.UserPreferencesManager
import com.kymjs.ai.custard.core.config.SystemToolPrompts
import com.kymjs.ai.custard.data.model.ToolPrompt
import com.kymjs.ai.custard.data.model.ToolParameterSchema
import com.kymjs.ai.custard.util.ChatUtils
import com.kymjs.ai.custard.util.LocaleUtils

/**
 * Enhanced AI service that provides advanced conversational capabilities by integrating various
 * components like tool execution, conversation management, user preferences, and problem library.
 */
class EnhancedAIService private constructor(private val context: Context) {
    companion object {
        private const val TAG = "EnhancedAIService"

        @Volatile private var INSTANCE: EnhancedAIService? = null

        private val CHAT_INSTANCES = ConcurrentHashMap<String, EnhancedAIService>()

        private val FOREGROUND_REF_COUNT = AtomicInteger(0)

        /**
         * 获取EnhancedAIService实例
         * @param context 应用上下文
         * @return EnhancedAIService实.
         */
        fun getInstance(context: Context): EnhancedAIService {
            return INSTANCE
                    ?: synchronized(this) {
                        INSTANCE
                                ?: EnhancedAIService(context.applicationContext).also {
                                    INSTANCE = it
                                }
                    }
        }

        fun getChatInstance(context: Context, chatId: String): EnhancedAIService {
            val appContext = context.applicationContext
            return CHAT_INSTANCES[chatId]
                ?: synchronized(CHAT_INSTANCES) {
                    CHAT_INSTANCES[chatId]
                        ?: EnhancedAIService(appContext).also { CHAT_INSTANCES[chatId] = it }
                }
        }

        fun releaseChatInstance(chatId: String) {
            val instance = CHAT_INSTANCES.remove(chatId) ?: return
            runCatching {
                instance.cancelConversation()
            }.onFailure { e ->
                AppLogger.e(TAG, "释放chat实例资源失败: chatId=$chatId", e)
            }
        }

        /**
         * 获取指定功能类型的 AIService 实例（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型
         * @return AIService 实例
         */
        suspend fun getAIServiceForFunction(
                context: Context,
                functionType: FunctionType
        ): AIService {
            return getInstance(context).multiServiceManager.getServiceForFunction(functionType)
        }

        suspend fun getModelConfigForFunction(
            context: Context,
            functionType: FunctionType
        ): ModelConfigData {
            return getInstance(context).multiServiceManager.getModelConfigForFunction(functionType)
        }

        /**
         * 刷新指定功能类型的 AIService 实例（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型
         */
        suspend fun refreshServiceForFunction(context: Context, functionType: FunctionType) {
            val allInstances = buildList {
                add(getInstance(context))
                addAll(CHAT_INSTANCES.values)
            }.distinct()
            allInstances.forEach { it.multiServiceManager.refreshServiceForFunction(functionType) }
        }

        /**
         * 刷新所有 AIService 实例（非实例化方式）
         * @param context 应用上下文
         */
        suspend fun refreshAllServices(context: Context) {
            val allInstances = buildList {
                add(getInstance(context))
                addAll(CHAT_INSTANCES.values)
            }.distinct()
            allInstances.forEach { it.multiServiceManager.refreshAllServices() }
        }

        /**
         * 获取指定功能类型的当前输入token计数（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型
         * @return 输入token计数
         */
        suspend fun getCurrentInputTokenCountForFunction(
                context: Context,
                functionType: FunctionType
        ): Int {
            return getInstance(context)
                    .multiServiceManager
                    .getServiceForFunction(functionType)
                    .inputTokenCount
        }

        /**
         * 获取指定功能类型的当前输出token计数（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型
         * @return 输出token计数
         */
        suspend fun getCurrentOutputTokenCountForFunction(
                context: Context,
                functionType: FunctionType
        ): Int {
            return getInstance(context)
                    .multiServiceManager
                    .getServiceForFunction(functionType)
                    .outputTokenCount
        }

        /**
         * 重置指定功能类型或所有功能类型的token计数器（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型，如果为null则重置所有功能类型
         */
        suspend fun resetTokenCountersForFunction(
                context: Context,
                functionType: FunctionType? = null
        ) {
            val allInstances = buildList {
                add(getInstance(context))
                addAll(CHAT_INSTANCES.values)
            }.distinct()
            allInstances.forEach {
                if (functionType == null) {
                    it.multiServiceManager.resetAllTokenCounters()
                } else {
                    it.multiServiceManager.resetTokenCountersForFunction(functionType)
                }
            }
        }

        fun resetTokenCounters(context: Context) {
            val appContext = context.applicationContext
            val allInstances = buildList {
                add(getInstance(appContext))
                addAll(CHAT_INSTANCES.values)
            }.distinct()

            allInstances.forEach { instance ->
                instance.initScope.launch {
                    runCatching {
                        instance.multiServiceManager.resetAllTokenCounters()
                    }.onFailure { e ->
                        AppLogger.e(TAG, "重置token计数器失败", e)
                    }
                }
            }
        }

        /**
         * 处理文件绑定操作（非实例化方式）
         * @param context 应用上下文
         * @param originalContent 原始文件内容
         * @param aiGeneratedCode AI生成的代码（包含"//existing code"标记）
         * @return 混合后的文件内容
         */
        suspend fun applyFileBinding(
                context: Context,
                originalContent: String,
                aiGeneratedCode: String,
                onProgress: ((Float, String) -> Unit)? = null
        ): Pair<String, String> {
            // 获取EnhancedAIService实例
            val instance = getInstance(context)

            // 委托给FileBindingService处理
            return instance.fileBindingService.processFileBinding(
                    originalContent,
                    aiGeneratedCode,
                    onProgress
            )
        }

        suspend fun applyFileBindingOperations(
            context: Context,
            originalContent: String,
            operations: List<FileBindingService.StructuredEditOperation>,
            onProgress: ((Float, String) -> Unit)? = null
        ): Pair<String, String> {
            val instance = getInstance(context)
            return instance.fileBindingService.processFileBindingOperations(
                originalContent = originalContent,
                operations = operations,
                onProgress = onProgress
            )
        }

        /**
         * 自动生成工具包描述（非实例化方式）
         * @param context 应用上下文
         * @param pluginName 工具包名称
         * @param toolDescriptions 工具描述列表
         * @return 生成的工具包描述
         */
        suspend fun generatePackageDescription(
            context: Context,
            pluginName: String,
            toolDescriptions: List<String>
        ): String {
            return getInstance(context).generatePackageDescription(pluginName, toolDescriptions)
        }
    }

    // MultiServiceManager 管理不同功能的 AIService 实例
    private val multiServiceManager = MultiServiceManager(context)

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initMutex = Mutex()
    @Volatile private var isServiceManagerInitialized = false

    // 添加ConversationService实例
    private val conversationService = ConversationService(context, CustomEmojiRepository.getInstance(context))

    // 添加FileBindingService实例
    private val fileBindingService = FileBindingService(context)

    // Tool handler for executing tools
    private val toolHandler = AIToolHandler.getInstance(context)

    private suspend fun ensureInitialized() {
        if (isServiceManagerInitialized) return
        initMutex.withLock {
            if (isServiceManagerInitialized) return
            withContext(Dispatchers.IO) {
                multiServiceManager.initialize()
            }
            isServiceManagerInitialized = true
        }
    }

    // State flows for UI updates
    private val _inputProcessingState =
            MutableStateFlow<InputProcessingState>(InputProcessingState.Idle)
    val inputProcessingState = _inputProcessingState.asStateFlow()

    /**
     * 设置当前的输入处理状态
     * @param newState 新的状态
     */
    fun setInputProcessingState(newState: com.kymjs.ai.custard.data.model.InputProcessingState) {
        _inputProcessingState.value = newState
    }

    // Per-request token counts
    private val _perRequestTokenCounts = MutableStateFlow<Pair<Int, Int>?>(null)
    val perRequestTokenCounts: StateFlow<Pair<Int, Int>?> = _perRequestTokenCounts.asStateFlow()

    // Conversation management
    // private val streamBuffer = StringBuilder() // Moved to MessageExecutionContext
    // private val roundManager = ConversationRoundManager() // Moved to MessageExecutionContext
    // private val isConversationActive = AtomicBoolean(false) // Moved to MessageExecutionContext

    // Api Preferences for settings
    private val apiPreferences = ApiPreferences.getInstance(context)

    // Execution context for a single sendMessage call to achieve concurrency
    private data class MessageExecutionContext(
        val streamBuffer: StringBuilder = StringBuilder(),
        val roundManager: ConversationRoundManager = ConversationRoundManager(),
        val isConversationActive: AtomicBoolean = AtomicBoolean(true),
        val conversationHistory: MutableList<Pair<String, String>>,
    )

    // Coroutine management
    private val toolProcessingScope = CoroutineScope(Dispatchers.IO)
    private val toolExecutionJobs = ConcurrentHashMap<String, Job>()
    // private val conversationHistory = mutableListOf<Pair<String, String>>() // Moved to MessageExecutionContext
    // private val conversationMutex = Mutex() // Moved to MessageExecutionContext

    private var accumulatedInputTokenCount = 0
    private var accumulatedOutputTokenCount = 0

    // Callbacks
    private var currentResponseCallback: ((content: String, thinking: String?) -> Unit)? = null
    private var currentCompleteCallback: (() -> Unit)? = null

    // Package manager for handling tool packages
    private val packageManager = PackageManager.getInstance(context, toolHandler)

    // 存储最后的回复内容，用于通知
    private var lastReplyContent: String? = null

    init {
        com.kymjs.ai.custard.api.chat.library.ProblemLibrary.initialize(context)
        initScope.launch {
            runCatching {
                ensureInitialized()
            }.onFailure { e ->
                AppLogger.e(TAG, "MultiServiceManager初始化失败", e)
            }
        }
        initScope.launch {
            runCatching {
                toolHandler.registerDefaultTools()
            }.onFailure { e ->
                AppLogger.e(TAG, "注册默认工具失败", e)
            }
        }
    }

    /**
     * 获取指定功能类型的 AIService 实例
     * @param functionType 功能类型
     * @return AIService 实例
     */
    suspend fun getAIServiceForFunction(functionType: FunctionType): AIService {
        ensureInitialized()
        return multiServiceManager.getServiceForFunction(functionType)
    }

    /**
     * 获取指定功能类型的provider和model信息
     * @param functionType 功能类型
     * @return Pair<provider, modelName>，例如 Pair("DEEPSEEK", "deepseek-chat")
     */
    suspend fun getProviderAndModelForFunction(functionType: FunctionType): Pair<String, String> {
        val service = getAIServiceForFunction(functionType)
        val providerModel = service.providerModel
        // providerModel格式为"PROVIDER:modelName"，使用第一个冒号分割
        val colonIndex = providerModel.indexOf(":")
        return if (colonIndex > 0) {
            val provider = providerModel.substring(0, colonIndex)
            val modelName = providerModel.substring(colonIndex + 1)
            Pair(provider, modelName)
        } else {
            // 如果没有冒号，整个字符串作为provider，modelName为空
            Pair(providerModel, "")
        }
    }

    /**
     * 刷新指定功能类型的 AIService 实例 当配置发生更改时调用
     * @param functionType 功能类型
     */
    suspend fun refreshServiceForFunction(functionType: FunctionType) {
        ensureInitialized()
        multiServiceManager.refreshServiceForFunction(functionType)
    }

    /** 刷新所有 AIService 实例 当全局配置发生更改时调用 */
    suspend fun refreshAllServices() {
        ensureInitialized()
        multiServiceManager.refreshAllServices()
    }

    /** Process user input with a delay for UI feedback */
    suspend fun processUserInput(input: String): String {
        _inputProcessingState.value = InputProcessingState.Processing(context.getString(R.string.enhanced_processing_input))
        return InputProcessor.processUserInput(input)
    }

    /** Send a message to the AI service */
    suspend fun sendMessage(
        message: String,
        chatId: String? = null,
        chatHistory: List<Pair<String, String>> = emptyList(),
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        functionType: FunctionType = FunctionType.CHAT,
        promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        enableThinking: Boolean = false,
        thinkingGuidance: Boolean = false,
        enableMemoryQuery: Boolean = true,
        maxTokens: Int,
        tokenUsageThreshold: Double,
        onNonFatalError: suspend (error: String) -> Unit = {},
        onTokenLimitExceeded: (suspend () -> Unit)? = null,
        customSystemPromptTemplate: String? = null,
        isSubTask: Boolean = false,
        characterName: String? = null,
        avatarUri: String? = null,
        roleCardId: String? = null,
        proxySenderName: String? = null,
        onToolInvocation: (suspend (String) -> Unit)? = null,
        stream: Boolean = true
    ): Stream<String> {
        AppLogger.d(
                TAG,
                "sendMessage调用开始: 功能类型=$functionType, 提示词类型=$promptFunctionType, 思考引导=$thinkingGuidance"
        )
        accumulatedInputTokenCount = 0
        accumulatedOutputTokenCount = 0

        return stream {
            val execContext = MessageExecutionContext(conversationHistory = chatHistory.toMutableList())
            var hadFatalError = false
            try {
                // 确保所有操作都在IO线程上执行
                withContext(Dispatchers.IO) {
                    // 仅当会话首次启动时开启服务，并更新前台通知为“运行中”
                    if (!isSubTask) {
                        startAiService(characterName, avatarUri)
                    }

                    // Process the input message for any conversation markup (e.g., for AI planning)
                    val startTime = System.currentTimeMillis()
                    val processedInput = InputProcessor.processUserInput(message)
                    val tAfterProcessInput = System.currentTimeMillis()
                    AppLogger.d(TAG, "sendMessage本地耗时: processUserInput=${tAfterProcessInput - startTime}ms")
                

                    // Update state to show we're processing
                    if (!isSubTask) {
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value = InputProcessingState.Processing(context.getString(R.string.enhanced_processing_message))
                        }
                    }

                    // Prepare conversation history with system prompt
                    val preparedHistory =
                            prepareConversationHistory(
                                    execContext.conversationHistory, // 始终使用内部历史记录
                                    processedInput,
                                    workspacePath,
                                    workspaceEnv,
                                    promptFunctionType,
                                    thinkingGuidance,
                                    customSystemPromptTemplate,
                                    enableMemoryQuery,
                                    roleCardId,
                                    proxySenderName,
                                    isSubTask,
                                    functionType
                            )
                    val tAfterPrepareHistory = System.currentTimeMillis()
                    AppLogger.d(TAG, "sendMessage本地耗时: prepareConversationHistory=${tAfterPrepareHistory - tAfterProcessInput}ms")
                    
                    // 关键修复：用准备好的历史记录（包含了系统提示）去同步更新内部的 conversationHistory 状态
                    execContext.conversationHistory.clear()
                    execContext.conversationHistory.addAll(preparedHistory)

                    // Update UI state to connecting
                    if (!isSubTask) {
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value = InputProcessingState.Connecting(context.getString(R.string.enhanced_connecting_service))
                        }
                    }

                    // Get all model parameters from preferences (with enabled state)
                    val modelParameters = multiServiceManager.getModelParametersForFunction(functionType)
                    val tAfterModelParams = System.currentTimeMillis()
                    AppLogger.d(TAG, "sendMessage本地耗时: getModelParametersForFunction=${tAfterModelParams - tAfterPrepareHistory}ms")

                    // 获取对应功能类型的AIService实例
                    val serviceForFunction = getAIServiceForFunction(functionType)
                    val tAfterGetService = System.currentTimeMillis()
                    AppLogger.d(TAG, "sendMessage本地耗时: getAIServiceForFunction=${tAfterGetService - tAfterModelParams}ms")

                    // 清空之前的单次请求token计数
                    _perRequestTokenCounts.value = null

                    // 获取工具列表（如果启用Tool Call）
                    val availableTools = getAvailableToolsForFunction(functionType)
                    val tAfterGetTools = System.currentTimeMillis()
                    AppLogger.d(TAG, "sendMessage本地耗时: getAvailableToolsForFunction=${tAfterGetTools - tAfterGetService}ms")
                    
                    // 使用新的Stream API
                    AppLogger.d(TAG, "调用AI服务，处理时间: ${tAfterGetTools - startTime}ms, 流式输出: $stream")
                    val responseStream =
                            serviceForFunction.sendMessage(
                                    context = this@EnhancedAIService.context,
                                    message = processedInput,
                                    chatHistory = preparedHistory,
                                    modelParameters = modelParameters,
                                    enableThinking = enableThinking,
                                    stream = stream,
                                    availableTools = availableTools,
                                    onTokensUpdated = { input, cachedInput, output ->
                                        _perRequestTokenCounts.value = Pair(input, output)
                                    },
                                    onNonFatalError = onNonFatalError
                            )

                    // 收到第一个响应，更新状态
                    var isFirstChunk = true

                    // 创建一个新的轮次来管理内容
                    execContext.roundManager.startNewRound()
                    execContext.streamBuffer.clear()

                    // 从原始stream收集内容并处理
                    var chunkCount = 0
                    var totalChars = 0
                    var lastLogTime = System.currentTimeMillis()
                    val streamStartTime = System.currentTimeMillis()

                    responseStream.collect { content ->
                        // 第一次收到响应，更新状态
                        if (isFirstChunk) {
                            if (!isSubTask) {
                            withContext(Dispatchers.Main) {
                                _inputProcessingState.value =
                                        InputProcessingState.Receiving(context.getString(R.string.enhanced_receiving_response))
                                }
                            }
                            isFirstChunk = false
                            AppLogger.d(TAG, "首次响应耗时: ${System.currentTimeMillis() - streamStartTime}ms")
                        }

                        // 累计统计
                        chunkCount++
                        totalChars += content.length

                        // 周期性日志
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastLogTime > 5000) { // 每5秒记录一次
                            AppLogger.d(TAG, "已接收 $chunkCount 个内容块，总计 $totalChars 个字符")
                            lastLogTime = currentTime
                        }

                        // 更新streamBuffer，保持与原有逻辑一致
                        execContext.streamBuffer.append(content)

                        // 更新内容到轮次管理器
                        execContext.roundManager.updateContent(execContext.streamBuffer.toString())

                        // 发射当前内容片段
                        emit(content)
                    }

                    // 流收集完成后，添加用户消息到对话历史
                    // 只有在成功收到响应后，才将用户消息添加到历史记录中
                    execContext.conversationHistory.add(Pair("user", processedInput))

                    // Update accumulated token counts and persist them
                    val inputTokens = serviceForFunction.inputTokenCount
                    val cachedInputTokens = serviceForFunction.cachedInputTokenCount
                    val outputTokens = serviceForFunction.outputTokenCount
                    accumulatedInputTokenCount += inputTokens
                    accumulatedOutputTokenCount += outputTokens
                    apiPreferences.updateTokensForProviderModel(serviceForFunction.providerModel, inputTokens, outputTokens, cachedInputTokens)
                    
                    // Update request count
                    apiPreferences.incrementRequestCountForProviderModel(serviceForFunction.providerModel)

                    AppLogger.d(
                            TAG,
                            "Token count updated for $functionType. Input: $inputTokens, Output: $outputTokens. Turn Accumulated: $accumulatedInputTokenCount, $accumulatedOutputTokenCount"
                    )
                    AppLogger.d(
                            TAG,
                            "流收集完成，总计 $totalChars 字符，耗时: ${System.currentTimeMillis() - streamStartTime}ms"
                    )
                }
            } catch (e: Exception) {
                // 对于协程取消异常，这是正常流程，应当向上抛出以停止流
                if (e is kotlinx.coroutines.CancellationException) {
                    AppLogger.d(TAG, "sendMessage流被取消")
                    throw e
                }

                // 用户取消导致的 Socket closed 是预期行为，不应作为错误处理
                if (e.message?.contains("Socket closed", ignoreCase = true) == true) {
                    AppLogger.d(TAG, "Stream was cancelled by the user (Socket closed).")
                } else {
                    hadFatalError = true
                    // Handle any exceptions
                    AppLogger.e(TAG, "发送消息时发生错误: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value =
                                InputProcessingState.Error(message = context.getString(R.string.enhanced_error_with_message, e.message ?: ""))
                    }
                }

                // 发生无法处理的错误时，也应停止服务，但用户取消除外
                if (e.message?.contains("Socket closed", ignoreCase = true) != true) {
                    if (!isSubTask) stopAiService()
                }
            } finally {
                // 确保流处理完成后调用
                if (!hadFatalError) {
                    val collector = this
                    withContext(Dispatchers.IO) {
                        processStreamCompletion(
                            execContext,
                            functionType,
                            collector,
                            enableThinking,
                            enableMemoryQuery,
                            onNonFatalError,
                            onTokenLimitExceeded,
                            maxTokens,
                            tokenUsageThreshold,
                            isSubTask,
                            characterName,
                            avatarUri,
                            roleCardId,
                            chatId,
                            onToolInvocation,
                            stream
                        )
                    }
                }
            }
        }
    }

    /**
     * 使用流处理技术增强工具调用检测能力 这个方法通过流式XML解析来辅助识别工具调用，比单纯依赖正则表达式更可靠
     * @param content 需要检测工具调用的内容
     * @return 经过增强检测的内容，可能会修复格式问题
     */
    private suspend fun enhanceToolDetection(content: String): String {
        try {
            // 检查内容是否包含可能的工具调用标记
            if (!content.contains("<tool") && !content.contains("</tool>")) {
                return content
            }

            // 创建字符流以应用流处理，使用 stream() 替代 asCharStream()
            val charStream = content.stream()

            // 使用XML插件来拆分流
            val plugins = listOf(StreamXmlPlugin())

            // 保存增强后的内容
            val enhancedContent = StringBuilder()

            // 追踪是否发现了工具标签
            var foundToolTag = false

            // 处理拆分的结果
            charStream.splitBy(plugins).collect { group ->
                when (val tag = group.tag) {
                    // 匹配到XML标签
                    is StreamXmlPlugin -> {
                        val xmlContent = StringBuilder()
                        group.stream.collect { char -> xmlContent.append(char) }

                        val xml = xmlContent.toString()
                        // 检查是否是工具标签
                        if (xml.contains("<tool") && xml.contains("</tool>")) {
                            foundToolTag = true
                            // 格式标准化，使其符合工具调用的正则表达式预期格式
                            val normalizedXml = normalizeToolXml(xml)
                            enhancedContent.append(normalizedXml)
                            AppLogger.d(TAG, "工具调用XML被增强流处理检测到并标准化")
                        } else {
                            // 保留其他XML标签
                            enhancedContent.append(xml)
                        }
                    }
                    // 纯文本内容
                    null -> {
                        val textContent = StringBuilder()
                        group.stream.collect { char -> textContent.append(char) }
                        enhancedContent.append(textContent.toString())
                    }
                    // 添加必要的else分支
                    else -> {
                        val textContent = StringBuilder()
                        group.stream.collect { char -> textContent.append(char) }
                        enhancedContent.append(textContent.toString())
                        AppLogger.w(TAG, "未知标签类型: ${tag::class.java.simpleName}")
                    }
                }
            }

            // 如果找到了工具标签，返回增强的内容；否则返回原始内容
            return if (foundToolTag) {
                AppLogger.d(TAG, "增强的XML工具检测完成")
                enhancedContent.toString()
            } else {
                content
            }
        } catch (e: Exception) {
            // 如果流处理失败，返回原始内容并记录错误
            AppLogger.e(TAG, "增强工具检测失败: ${e.message}", e)
            return content
        }
    }

    /**
     * 规范化工具XML以符合正则表达式预期
     * @param xml 原始XML文本
     * @return 标准化后的XML
     */
    private fun normalizeToolXml(xml: String): String {
        var result = xml.trim()

        // 确保工具名称格式正确
        result = result.replace(Regex("<tool\\s+name\\s*="), "<tool name=")

        // 确保参数格式正确
        result = result.replace(Regex("<param\\s+name\\s*="), "<param name=")

        return result
    }

    /** 在处理完流后调用，使用增强的工具检测功能 */
    private suspend fun processStreamCompletion(
            context: MessageExecutionContext,
            functionType: FunctionType = FunctionType.CHAT,
            collector: StreamCollector<String>,
            enableThinking: Boolean = false,
            enableMemoryQuery: Boolean = true,
            onNonFatalError: suspend (error: String) -> Unit,
            onTokenLimitExceeded: (suspend () -> Unit)? = null,
            maxTokens: Int,
            tokenUsageThreshold: Double,
            isSubTask: Boolean,
            characterName: String? = null,
            avatarUri: String? = null,
            roleCardId: String? = null,
            chatId: String? = null,
            onToolInvocation: (suspend (String) -> Unit)? = null,
            stream: Boolean = true
    ) {
        try {
            val startTime = System.currentTimeMillis()
            // If conversation is no longer active, return immediately
            if (!context.isConversationActive.get()) {
                return
            }

            // Get response content
            val content = context.streamBuffer.toString().trim()

            // If content is empty, it means an error likely occurred or the model returned nothing.
            // We must still finalize the conversation to reset the state correctly.
            if (content.isEmpty()) {
                AppLogger.d(TAG, "Stream content is empty. Finalizing conversation state.")
                // We call handleTaskCompletion to properly set the conversation as inactive and update the UI state.
                // We pass enableMemoryQuery = false because there's no content to analyze or save.
                handleWaitForUserNeed(context, content, isSubTask)
                return
            }

            // If content is empty, finish immediately
            if (content.isEmpty()) {
                return
            }

            // 禁止“纯思考输出”：移除 thinking 后正文为空时，发出专用告警并回传给 AI 继续生成
            val contentWithoutThinking = ChatUtils.removeThinkingContent(content)
            if (contentWithoutThinking.isEmpty()) {
                val pureThinkingWarning =
                        ConversationMarkupManager.createWarningStatus(
                                this@EnhancedAIService.context.getString(
                                        R.string.enhanced_pure_thinking_only_warning
                                )
                        )
                context.roundManager.appendContent("\n$pureThinkingWarning")
                collector.emit(pureThinkingWarning)
                try {
                    context.conversationHistory.add(Pair("tool", pureThinkingWarning))
                } catch (e: Exception) {
                    AppLogger.e(TAG, "添加纯思考告警到历史记录失败", e)
                    return
                }
                AppLogger.w(TAG, "检测到纯思考输出（removeThinking后正文为空），已回传告警给AI继续生成")
                handleToolInvocation(
                        toolInvocations = emptyList(),
                        context = context,
                        functionType = functionType,
                        collector = collector,
                        enableThinking = enableThinking,
                        enableMemoryQuery = enableMemoryQuery,
                        onNonFatalError = onNonFatalError,
                        onTokenLimitExceeded = onTokenLimitExceeded,
                        maxTokens = maxTokens,
                        tokenUsageThreshold = tokenUsageThreshold,
                        isSubTask = isSubTask,
                        characterName = characterName,
                        avatarUri = avatarUri,
                        roleCardId = roleCardId,
                        chatId = chatId,
                        onToolInvocation = onToolInvocation,
                        stream = stream,
                        toolResultOverrideMessage = pureThinkingWarning
                )
                return
            }

            // 使用增强的工具检测功能处理内容
            val enhancedContent = enhanceToolDetection(content)
            // 如果内容被增强修改了，更新到streamBuffer
            if (enhancedContent != content) {
                context.streamBuffer.setLength(0)
                context.streamBuffer.append(enhancedContent)
                // 更新轮次管理器显示内容
                context.roundManager.updateContent(enhancedContent)
            }

            // 预先提取工具调用信息和完成标记，避免重复解析
            val extractedToolInvocations = ToolExecutionManager.extractToolInvocations(enhancedContent)
            val hasTaskCompletion = ConversationMarkupManager.containsTaskCompletion(enhancedContent)

            // 如果只有任务完成标记且没有工具调用，立即处理完成逻辑
            if (hasTaskCompletion && extractedToolInvocations.isEmpty()) {
                handleTaskCompletion(context, enhancedContent, enableMemoryQuery, isSubTask, characterName, avatarUri)
                return
            }

            // Check again if conversation is active
            if (!context.isConversationActive.get()) {
                return
            }

            // Add current assistant message to conversation history
            try {
                context.conversationHistory.add(Pair("assistant", context.roundManager.getCurrentRoundContent()))
            } catch (e: Exception) {
                AppLogger.e(TAG, "添加助手消息到历史记录失败", e)
                return
            }

            // Check again if conversation is active
            if (!context.isConversationActive.get()) {
                return
            }

            // Main flow: Detect and process tool invocations
            if (extractedToolInvocations.isNotEmpty()) {
                if (hasTaskCompletion) {
                    val warning =
                            ConversationMarkupManager.createToolsSkippedByCompletionWarning(
                                    this@EnhancedAIService.context,
                                    extractedToolInvocations.map { it.tool.name }
                            )
                    context.roundManager.appendContent(warning)
                    collector.emit(warning)
                    try {
                        context.conversationHistory.add(Pair("tool", warning))
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "添加任务完成跳过工具警告到历史记录失败", e)
                    }
                }

                // Handle wait for user need marker
                if (ConversationMarkupManager.containsWaitForUserNeed(enhancedContent)) {
                    val userNeedContent =
                            ConversationMarkupManager.createWarningStatus(
                                    this@EnhancedAIService.context.getString(R.string.enhanced_tool_warning),
                            )
                    context.roundManager.appendContent(userNeedContent)
                    collector.emit(userNeedContent)
                    try {
                        context.conversationHistory.add(Pair("tool", userNeedContent))
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "添加工具调用警告到历史记录失败", e)
                    }
                }

                // Add current assistant message to conversation history

                AppLogger.d(
                        TAG,
                        "检测到 ${extractedToolInvocations.size} 个工具调用，处理时间: ${System.currentTimeMillis() - startTime}ms"
                )
                handleToolInvocation(
                        extractedToolInvocations,
                        context,
                        functionType,
                        collector,
                        enableThinking,
                        enableMemoryQuery,
                        onNonFatalError,
                        onTokenLimitExceeded,
                        maxTokens,
                        tokenUsageThreshold,
                        isSubTask,
                        characterName,
                        avatarUri,
                        roleCardId,
                        chatId,
                        onToolInvocation,
                        stream = stream
                )
                return
            }

            // 修改默认行为：如果没有特殊标记或工具调用，默认等待用户输入
            // 而不是直接标记为完成

            // 创建等待用户输入的内容
            val userNeedContent =
                    ConversationMarkupManager.createWaitForUserNeedContent(
                            context.roundManager.getDisplayContent()
                    )

            // 处理为等待用户输入模式
            handleWaitForUserNeed(context, userNeedContent, isSubTask, characterName, avatarUri)
            AppLogger.d(TAG, "流完成处理耗时: ${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) {
            // Catch any exceptions in the processing flow
            AppLogger.e(TAG, "处理流完成时发生错误", e)
            withContext(Dispatchers.Main) {
                _inputProcessingState.value = InputProcessingState.Idle
            }
        }
    }

    /** Handle task completion logic - simplified version without callbacks */
    private suspend fun handleTaskCompletion(context: MessageExecutionContext, content: String, enableMemoryQuery: Boolean, isSubTask: Boolean, characterName: String? = null, avatarUri: String? = null) {
        // Mark conversation as complete
        context.isConversationActive.set(false)

        // 清除内容池
        // roundManager.clearContent()
        
        // 保存最后的回复内容用于通知
        lastReplyContent = context.roundManager.getDisplayContent()

        // Ensure input processing state is updated to completed
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.Completed
            }
        }

        if (enableMemoryQuery) {
            // 保存问题记录到库
            toolProcessingScope.launch {
                com.kymjs.ai.custard.api.chat.library.ProblemLibrary.saveProblemAsync(
                        this@EnhancedAIService.context,
                        toolHandler,
                        context.conversationHistory,
                        content,
                        multiServiceManager.getServiceForFunction(FunctionType.PROBLEM_LIBRARY)
                )
            }
        }

        if (!isSubTask) {
        // 在会话结束后停止服务（服务销毁时会自动发送通知）
        stopAiService(characterName, avatarUri)
        }
    }

    /** Handle wait for user need logic - simplified version without callbacks */
    private suspend fun handleWaitForUserNeed(context: MessageExecutionContext, content: String, isSubTask: Boolean, characterName: String? = null, avatarUri: String? = null) {
        // Mark conversation as complete
        context.isConversationActive.set(false)

        // 清除内容池
        // roundManager.clearContent()
        
        // 保存最后的回复内容用于通知
        lastReplyContent = context.roundManager.getDisplayContent()

        // Ensure input processing state is updated to completed
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.Completed
            }
        }

        AppLogger.d(TAG, "Wait for user need - skipping problem library analysis")
        if (!isSubTask) {
        // 在会话结束后停止服务（服务销毁时会自动发送通知）
        stopAiService(characterName, avatarUri)
        }
    }

    /** Handle tool invocation processing - simplified version without callbacks */
    private suspend fun handleToolInvocation(
        toolInvocations: List<ToolInvocation>,
        context: MessageExecutionContext,
        functionType: FunctionType = FunctionType.CHAT,
        collector: StreamCollector<String>,
        enableThinking: Boolean = false,
        enableMemoryQuery: Boolean = true,
        onNonFatalError: suspend (error: String) -> Unit,
        onTokenLimitExceeded: (suspend () -> Unit)? = null,
        maxTokens: Int,
        tokenUsageThreshold: Double,
        isSubTask: Boolean,
        characterName: String? = null,
        avatarUri: String? = null,
        roleCardId: String? = null,
        chatId: String? = null,
        onToolInvocation: (suspend (String) -> Unit)? = null,
        stream: Boolean = true,
        toolResultOverrideMessage: String? = null
    ) {
        val startTime = System.currentTimeMillis()

        toolInvocations.forEach { invocation ->
            onToolInvocation?.invoke(invocation.tool.name)
        }

        if (!isSubTask && toolInvocations.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                val toolNames = toolInvocations.joinToString(", ") { resolveToolDisplayName(it.tool) }
                _inputProcessingState.value = InputProcessingState.ExecutingTool(toolNames)
            }
        }

        val processToolJob = toolProcessingScope.launch {
            val allToolResults = ToolExecutionManager.executeInvocations(
                invocations = toolInvocations,
                toolHandler = toolHandler,
                packageManager = packageManager,
                collector = collector,
                callerName = characterName,
                callerChatId = chatId,
                callerCardId = roleCardId
            )

            if (allToolResults.isNotEmpty()) {
                AppLogger.d(TAG, "所有工具结果收集完毕，准备最终处理。")
                processToolResults(
                    allToolResults, context, functionType, collector, enableThinking,
                    enableMemoryQuery, onNonFatalError, onTokenLimitExceeded, maxTokens, tokenUsageThreshold, isSubTask,
                    characterName, avatarUri, roleCardId, chatId, onToolInvocation, stream
                )
            } else if (!toolResultOverrideMessage.isNullOrEmpty()) {
                AppLogger.d(TAG, "0工具路由命中，使用覆盖消息继续请求AI。")
                processToolResults(
                    results = emptyList(),
                    context = context,
                    functionType = functionType,
                    collector = collector,
                    enableThinking = enableThinking,
                    enableMemoryQuery = enableMemoryQuery,
                    onNonFatalError = onNonFatalError,
                    onTokenLimitExceeded = onTokenLimitExceeded,
                    maxTokens = maxTokens,
                    tokenUsageThreshold = tokenUsageThreshold,
                    isSubTask = isSubTask,
                    characterName = characterName,
                    avatarUri = avatarUri,
                    roleCardId = roleCardId,
                    chatId = chatId,
                    onToolInvocation = onToolInvocation,
                    stream = stream,
                    toolResultMessageOverride = toolResultOverrideMessage
                )
            }
        }

        val invocationId = java.util.UUID.randomUUID().toString()
        toolExecutionJobs[invocationId] = processToolJob

        try {
            processToolJob.join()
        } finally {
            toolExecutionJobs.remove(invocationId)
        }
    }


    /** Process tool execution result - simplified version without callbacks */
    private suspend fun processToolResults(
            results: List<ToolResult>,
            context: MessageExecutionContext,
            functionType: FunctionType = FunctionType.CHAT,
            collector: StreamCollector<String>,
            enableThinking: Boolean = false,
            enableMemoryQuery: Boolean = true,
            onNonFatalError: suspend (error: String) -> Unit,
            onTokenLimitExceeded: (suspend () -> Unit)? = null,
            maxTokens: Int,
            tokenUsageThreshold: Double,
            isSubTask: Boolean,
            characterName: String? = null,
            avatarUri: String? = null,
            roleCardId: String? = null,
            chatId: String? = null,
            onToolInvocation: (suspend (String) -> Unit)? = null,
            stream: Boolean = true,
            toolResultMessageOverride: String? = null
    ) {
        val startTime = System.currentTimeMillis()
        val toolNames = results.joinToString(", ") { it.toolName }
        val toolResultMessage = toolResultMessageOverride ?: results.joinToString("\n") {
            ConversationMarkupManager.formatToolResultForMessage(it)
        }

        if (toolResultMessage.isBlank()) {
            AppLogger.w(TAG, "工具结果消息为空，跳过后续AI请求")
            return
        }

        val displayToolNames = if (toolNames.isNotBlank()) toolNames else "warning"
        if (results.isNotEmpty()) {
            AppLogger.d(TAG, "开始处理工具结果: $toolNames, 成功: ${results.all { it.success }}")
        } else {
            AppLogger.d(TAG, "开始处理0工具覆盖消息，长度: ${toolResultMessage.length}")
        }

        // Add transition state
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.ProcessingToolResult(displayToolNames)
            }
        }

        // Check if conversation is still active
        if (!context.isConversationActive.get()) {
            return
        }

        // Add tool result to conversation history
        context.conversationHistory.add(Pair("tool", toolResultMessage))

        // Get current conversation history is now just the context's history
        val currentChatHistory = context.conversationHistory

        // 不再需要，因为结果在调用时已实时输出
        // context.roundManager.appendContent(toolResultMessage)
        // try { collector.emit(toolResultMessage) } catch (_: Exception) {}

        // Start new round - ensure tool execution response will be shown in a new message
        context.roundManager.startNewRound()
        context.streamBuffer.clear() // Clear buffer to ensure a new message will be created

        // Clearly show we're preparing to send tool result to AI
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.ProcessingToolResult(displayToolNames)
            }
        }

        // Add short delay to make state change more visible
        delay(300)

        // Get all model parameters from preferences (with enabled state)
        val modelParameters = multiServiceManager.getModelParametersForFunction(functionType)

        // 获取对应功能类型的AIService实例
        val serviceForFunction = getAIServiceForFunction(functionType)
        
        // 获取工具列表（如果启用Tool Call）- 提前获取，以便在token计算中使用
        val availableTools = getAvailableToolsForFunction(functionType)
 
        // After a tool call, check if token usage exceeds the threshold
        if (maxTokens > 0) {
            val currentTokens = serviceForFunction.calculateInputTokens("", currentChatHistory, availableTools)
            val usageRatio = currentTokens.toDouble() / maxTokens.toDouble()

            if (usageRatio >= tokenUsageThreshold) {
                AppLogger.w(TAG, "Token usage ($usageRatio) exceeds threshold ($tokenUsageThreshold) after tool call. Triggering summary.")
                onTokenLimitExceeded?.invoke()
                context.isConversationActive.set(false)
                if (!isSubTask) {
                    stopAiService(characterName, avatarUri)
                }
                // 关键修复：在触发总结后，直接返回，因为后续流程将由回调处理
                return
            }
        }

        // 清空之前的单次请求token计数
        _perRequestTokenCounts.value = null
        
        // 使用新的Stream API处理工具执行结果
        withContext(Dispatchers.IO) {
            try {
                // 发送消息并获取响应流
                val aiStartTime = System.currentTimeMillis()
                val responseStream =
                        serviceForFunction.sendMessage(
                                context = this@EnhancedAIService.context,
                                message = toolResultMessage,
                                chatHistory = currentChatHistory,
                                modelParameters = modelParameters,
                                enableThinking = enableThinking,
                                stream = stream,
                                availableTools = availableTools,
                                onTokensUpdated = { input, cachedInput, output ->
                                    _perRequestTokenCounts.value = Pair(input, output)
                                },
                                onNonFatalError = onNonFatalError
                        )

                // 更新状态为接收中
                if (!isSubTask) {
                withContext(Dispatchers.Main) {
                    _inputProcessingState.value =
                            InputProcessingState.Receiving(this@EnhancedAIService.context.getString(R.string.enhanced_receiving_tool_result))
                    }
                }

                // 处理流
                var chunkCount = 0
                var totalChars = 0
                var lastLogTime = System.currentTimeMillis()

                responseStream.collect { content ->
                    // 更新streamBuffer
                    context.streamBuffer.append(content)

                    // 更新内容到轮次管理器
                    context.roundManager.updateContent(context.streamBuffer.toString())

                    // 累计统计
                    chunkCount++
                    totalChars += content.length

                    // 定期记录日志
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime > 5000) { // 每5秒记录一次
                        lastLogTime = currentTime
                    }

                    // 通过收集器将内容发射出去，让UI可以接收到
                    collector.emit(content)
                }

                // Update accumulated token counts and persist them
                val inputTokens = serviceForFunction.inputTokenCount
                val cachedInputTokens = serviceForFunction.cachedInputTokenCount
                val outputTokens = serviceForFunction.outputTokenCount
                accumulatedInputTokenCount += inputTokens
                accumulatedOutputTokenCount += outputTokens
                apiPreferences.updateTokensForProviderModel(serviceForFunction.providerModel, inputTokens, outputTokens, cachedInputTokens)
                
                // Update request count
                apiPreferences.incrementRequestCountForProviderModel(serviceForFunction.providerModel)

                AppLogger.d(
                        TAG,
                        "Token count updated after tool result for $functionType. Input: $inputTokens, Output: $outputTokens."
                )

                val processingTime = System.currentTimeMillis() - aiStartTime
                AppLogger.d(TAG, "工具结果AI处理完成，收到 $totalChars 字符，耗时: ${processingTime}ms")

                // 流处理完成，处理完成逻辑
                processStreamCompletion(
                    context,
                    functionType,
                    collector,
                    enableThinking,
                    enableMemoryQuery,
                    onNonFatalError,
                    onTokenLimitExceeded,
                    maxTokens,
                    tokenUsageThreshold,
                    isSubTask,
                    characterName,
                    avatarUri,
                    roleCardId,
                    chatId,
                    onToolInvocation,
                    stream
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "处理工具执行结果时出错", e)
                withContext(Dispatchers.Main) {
                    _inputProcessingState.value =
                            InputProcessingState.Error(this@EnhancedAIService.context.getString(R.string.enhanced_process_tool_result_failed, e.message ?: ""))
                }
            }
        }
    }
    /**
     * Get the current input token count from the last API call
     * @return The number of input tokens used in the most recent request
     */
    fun getCurrentInputTokenCount(): Int {
        return accumulatedInputTokenCount
    }

    /**
     * Get the current output token count from the last API call
     * @return The number of output tokens generated in the most recent response
     */
    fun getCurrentOutputTokenCount(): Int {
        return accumulatedOutputTokenCount
    }

    /** Reset token counters to zero Use this when starting a new conversation */
    fun resetTokenCounters() {
        Companion.resetTokenCounters(context)
    }

    /**
     * 重置指定功能类型或所有功能类型的token计数器
     * @param functionType 功能类型，如果为null则重置所有功能类型
     */
    suspend fun resetTokenCountersForFunction(functionType: FunctionType? = null) {
        Companion.resetTokenCountersForFunction(context, functionType)
    }

    /**
     * 生成对话总结
     * @param messages 要总结的消息列表
     * @return 生成的总结文本
     */
    suspend fun generateSummary(messages: List<Pair<String, String>>): String {
        return generateSummary(messages, null)
    }

    /**
     * 生成对话总结，并且包含上一次的总结内容
     * @param messages 要总结的消息列表
     * @param previousSummary 上一次的总结内容，可以为null
     * @return 生成的总结文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            previousSummary: String?
    ): String {
        // 调用ConversationService中的方法
        return conversationService.generateSummary(messages, previousSummary, multiServiceManager)
    }

    /**
     * 获取指定功能类型的当前输入token计数
     * @param functionType 功能类型
     * @return 输入token计数
     */
    suspend fun getCurrentInputTokenCountForFunction(functionType: FunctionType): Int {
        return Companion.getCurrentInputTokenCountForFunction(context, functionType)
    }

    /**
     * 获取指定功能类型的当前输出token计数
     * @param functionType 功能类型
     * @return 输出token计数
     */
    suspend fun getCurrentOutputTokenCountForFunction(functionType: FunctionType): Int {
        return Companion.getCurrentOutputTokenCountForFunction(context, functionType)
    }

    private fun resolveToolDisplayName(tool: AITool): String {
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

    /** Prepare the conversation history with system prompt */
    private suspend fun prepareConversationHistory(
            chatHistory: List<Pair<String, String>>,
            processedInput: String,
            workspacePath: String?,
            workspaceEnv: String?,
            promptFunctionType: PromptFunctionType,
            thinkingGuidance: Boolean,
            customSystemPromptTemplate: String? = null,
            enableMemoryQuery: Boolean,
            roleCardId: String?,
            proxySenderName: String? = null,
            isSubTask: Boolean = false,
            functionType: FunctionType = FunctionType.CHAT
    ): List<Pair<String, String>> {
        // Check if backend image recognition service is configured (for intent-based vision)
        // For subtasks, always disable backend image recognition (only support OCR)
        val hasImageRecognition = if (isSubTask) false else multiServiceManager.hasImageRecognitionConfigured()
        val hasAudioRecognition = if (isSubTask) false else multiServiceManager.hasAudioRecognitionConfigured()
        val hasVideoRecognition = if (isSubTask) false else multiServiceManager.hasVideoRecognitionConfigured()

        // 获取当前功能类型（通常是聊天模型）的模型配置，用于判断聊天模型是否自带识图能力
        val config = multiServiceManager.getModelConfigForFunction(functionType)
        val useToolCallApi = config.enableToolCall
        val strictToolCall = config.strictToolCall
        val chatModelHasDirectImage = config.enableDirectImageProcessing
        val chatModelHasDirectAudio = config.enableDirectAudioProcessing
        val chatModelHasDirectVideo = config.enableDirectVideoProcessing

        return conversationService.prepareConversationHistory(
                chatHistory,
                processedInput,
                workspacePath,
                workspaceEnv,
                packageManager,
                promptFunctionType,
                thinkingGuidance,
                customSystemPromptTemplate,
                enableMemoryQuery,
                roleCardId,
                proxySenderName,
                hasImageRecognition,
                hasAudioRecognition,
                hasVideoRecognition,
                chatModelHasDirectAudio,
                chatModelHasDirectVideo,
                useToolCallApi,
                strictToolCall,
                chatModelHasDirectImage
        )
    }

    /** Cancel the current conversation */
    fun cancelConversation() {
        // Set conversation inactive
        // isConversationActive.set(false) // This is now per-context, can't set a global one

        // Cancel all underlying AIService streaming instances
        initScope.launch {
            runCatching {
                multiServiceManager.cancelAllStreaming()
            }.onFailure { e ->
                AppLogger.e(TAG, "取消AIService流式输出失败", e)
            }
        }

        // Cancel all tool executions
        cancelAllToolExecutions()

        // Clean up current conversation content
        // roundManager.clearContent() // This is now per-context, can't clear a global one
        AppLogger.d(TAG, "Conversation canceled")

        // Reset input processing state
        _inputProcessingState.value = InputProcessingState.Idle

        // Reset per-request token counts
        _perRequestTokenCounts.value = null

        // Clear callback references
        currentResponseCallback = null
        currentCompleteCallback = null

        // 停止AI服务并关闭屏幕常亮
        stopAiService()

        AppLogger.d(TAG, "Conversation cancellation complete")
    }

    /** Cancel all tool executions */
    private fun cancelAllToolExecutions() {
        toolProcessingScope.coroutineContext.cancelChildren()
    }

    /**
     * 获取可用工具列表（用于Tool Call API）
     * 如果模型配置启用了Tool Call，返回工具列表；否则返回null
     */
    private suspend fun getAvailableToolsForFunction(functionType: FunctionType): List<ToolPrompt>? {
        return try {
            // 先读取全局工具和记忆开关
            val enableTools = apiPreferences.enableToolsFlow.first()
            val enableMemoryQuery = apiPreferences.enableMemoryQueryFlow.first()

            // 如果同时关闭了普通工具和记忆相关工具，则完全不提供Tool Call工具
            if (!enableTools && !enableMemoryQuery) {
                AppLogger.d(TAG, "全局设置已禁用工具和记忆，本次调用不提供任何Tool Call工具")
                return null
            }

            // 获取对应功能类型的模型配置
            val config = multiServiceManager.getModelConfigForFunction(functionType)
            
            // 检查是否启用Tool Call
            if (!config.enableToolCall) {
                return null
            }
            
            // 获取所有工具分类
            val isEnglish = LocaleUtils.getCurrentLanguage(context) == "en"

            // 后端识图服务是否可用（IMAGE_RECOGNITION 功能），用于 intent-based 视觉模型
            val hasBackendImageRecognition = multiServiceManager.hasImageRecognitionConfigured()

            val hasBackendAudioRecognition = multiServiceManager.hasAudioRecognitionConfigured()
            val hasBackendVideoRecognition = multiServiceManager.hasVideoRecognitionConfigured()

            val safBookmarkNames = runCatching {
                apiPreferences.safBookmarksFlow.first().map { it.name }
            }.getOrElse { emptyList() }

            // 当前功能模型（通常是聊天模型）是否支持直接看图
            val chatModelHasDirectImage = config.enableDirectImageProcessing

            val chatModelHasDirectAudio = config.enableDirectAudioProcessing
            val chatModelHasDirectVideo = config.enableDirectVideoProcessing

            val categories = if (isEnglish) {
                SystemToolPrompts.getAIAllCategoriesEn(
                    hasBackendImageRecognition = hasBackendImageRecognition,
                    chatModelHasDirectImage = chatModelHasDirectImage,
                    hasBackendAudioRecognition = hasBackendAudioRecognition,
                    hasBackendVideoRecognition = hasBackendVideoRecognition,
                    chatModelHasDirectAudio = chatModelHasDirectAudio,
                    chatModelHasDirectVideo = chatModelHasDirectVideo,
                    safBookmarkNames = safBookmarkNames
                )
            } else {
                SystemToolPrompts.getAIAllCategoriesCn(
                    hasBackendImageRecognition = hasBackendImageRecognition,
                    chatModelHasDirectImage = chatModelHasDirectImage,
                    hasBackendAudioRecognition = hasBackendAudioRecognition,
                    hasBackendVideoRecognition = hasBackendVideoRecognition,
                    chatModelHasDirectAudio = chatModelHasDirectAudio,
                    chatModelHasDirectVideo = chatModelHasDirectVideo,
                    safBookmarkNames = safBookmarkNames
                )
            }

            // 按类别拆分记忆工具和非记忆工具，以与 SystemPromptConfig 中的语义保持一致
            val memoryCategoryName = context.getString(R.string.enhanced_memory_tools_category)

            val memoryTools = categories
                .firstOrNull { it.categoryName == memoryCategoryName }
                ?.tools
                ?: emptyList()

            val nonMemoryTools = categories
                .filter { it.categoryName != memoryCategoryName }
                .flatMap { it.tools }

            // 根据开关组合最终可用工具：
            // - enableTools && enableMemoryQuery      -> 所有工具
            // - enableTools && !enableMemoryQuery     -> 仅非记忆工具
            // - !enableTools && enableMemoryQuery     -> 仅记忆工具
            val selectedTools = mutableListOf<ToolPrompt>()
            if (enableTools) {
                selectedTools.addAll(nonMemoryTools)
            }
            if (enableMemoryQuery) {
                selectedTools.addAll(memoryTools)
            }

            if (config.strictToolCall) {
                selectedTools.add(
                    ToolPrompt(
                        name = "package_proxy",
                        description = "Proxy tool for package tools activated by use_package.",
                        parametersStructured = listOf(
                            ToolParameterSchema(
                                name = "tool_name",
                                type = "string",
                                description = "Target tool name from an activated package (for example: packageName:toolName)",
                                required = true
                            ),
                            ToolParameterSchema(
                                name = "params",
                                type = "object",
                                description = "JSON object of parameters to forward to the target tool",
                                required = true
                            )
                        )
                    )
                )
            }

            if (selectedTools.isEmpty()) {
                AppLogger.d(TAG, "根据当前工具/记忆开关，未选择任何Tool Call工具")
                return null
            }

            AppLogger.d(
                TAG,
                "Tool Call已启用，提供 ${selectedTools.size} 个工具 (enableTools=$enableTools, enableMemoryQuery=$enableMemoryQuery)"
            )
            selectedTools
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取工具列表失败", e)
            null
        }
    }

    // --- Service Lifecycle Management ---

    /** 启动或更新前台服务为“AI 正在运行”状态，以保持应用活跃 */
    private fun startAiService(characterName: String? = null, avatarUri: String? = null) {
        val refCount = FOREGROUND_REF_COUNT.incrementAndGet()
        val appInForeground = ActivityLifecycleManager.getCurrentActivity() != null
        val alwaysListeningEnabled = runCatching {
            runBlocking { WakeWordPreferences(context).alwaysListeningEnabledFlow.first() }
        }.getOrDefault(false)
        if (!appInForeground && !AIForegroundService.isRunning.get() && !alwaysListeningEnabled) {
            AppLogger.d(TAG, "应用不在前台，跳过启动 AIForegroundService")
            return
        }
        try {
            val updateIntent = Intent(context, AIForegroundService::class.java).apply {
                putExtra(AIForegroundService.EXTRA_STATE, AIForegroundService.STATE_RUNNING)
                if (characterName != null) {
                    putExtra(AIForegroundService.EXTRA_CHARACTER_NAME, characterName)
                }
                if (avatarUri != null) {
                    putExtra(AIForegroundService.EXTRA_AVATAR_URI, avatarUri)
                }
            }
            context.startService(updateIntent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "更新AI前台服务为运行中状态失败: ${e.message}", e)
        }

        if (refCount == 1) {
            ActivityLifecycleManager.checkAndApplyKeepScreenOn(true)
        }
    }

    /** 将前台服务更新为“空闲/已完成”状态，但不真正停止服务 */
    private fun stopAiService(characterName: String? = null, avatarUri: String? = null) {
        val remaining = run {
            var remainingValue = -1
            while (true) {
                val current = FOREGROUND_REF_COUNT.get()
                if (current <= 0) {
                    remainingValue = -1
                    break
                }
                val next = current - 1
                if (FOREGROUND_REF_COUNT.compareAndSet(current, next)) {
                    remainingValue = next
                    break
                }
            }
            remainingValue
        }
        if (remaining < 0) return
        if (remaining > 0) return
         if (AIForegroundService.isRunning.get()) {
             AppLogger.d(TAG, "更新AI前台服务为闲置状态...")

             // 准备通知数据并切换为 IDLE 状态
            try {
                val stopIntent = Intent(context, AIForegroundService::class.java).apply {
                    putExtra(AIForegroundService.EXTRA_CHARACTER_NAME, characterName)
                    putExtra(AIForegroundService.EXTRA_REPLY_CONTENT, lastReplyContent)
                    putExtra(AIForegroundService.EXTRA_AVATAR_URI, avatarUri)
                    putExtra(AIForegroundService.EXTRA_STATE, AIForegroundService.STATE_IDLE)
                }

                AppLogger.d(TAG, "传递通知数据(空闲) - 角色: $characterName, 内容长度: ${lastReplyContent?.length}, 头像: $avatarUri")

                // 仅发送更新，不再真正停止前台服务
                context.startService(stopIntent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新AI前台服务为闲置状态失败: ${e.message}", e)
            }
        } else {
            AppLogger.d(TAG, "AI前台服务未在运行，无需更新闲置状态。")
        }

        // 使用管理器来恢复屏幕常亮设置
        ActivityLifecycleManager.checkAndApplyKeepScreenOn(false)
    }

    /**
     * 处理文件绑定操作（实例方法）
     * @param originalContent 原始文件内容
     * @param aiGeneratedCode AI生成的代码（包含"//existing code"标记）
     * @return 混合后的文件内容
     */
    suspend fun applyFileBinding(
            originalContent: String,
            aiGeneratedCode: String
    ): Pair<String, String> {
        return fileBindingService.processFileBinding(
                originalContent,
                aiGeneratedCode
        )
    }

    /**
     * 翻译文本功能
     * @param text 要翻译的文本
     * @return 翻译后的文本
     */
    suspend fun translateText(text: String): String {
        return conversationService.translateText(text, multiServiceManager)
    }

    /**
     * 自动生成工具包描述
     * @param pluginName 工具包名称
     * @param toolDescriptions 工具描述列表
     * @return 生成的工具包描述
     */
    suspend fun generatePackageDescription(
        pluginName: String,
        toolDescriptions: List<String>
    ): String {
        return conversationService.generatePackageDescription(pluginName, toolDescriptions, multiServiceManager)
    }


    /**
     * Manually saves the current conversation to the problem library.
     * @param conversationHistory The history of the conversation to save.
     * @param lastContent The content of the last message in the conversation.
     */
    suspend fun saveConversationToMemory(
        conversationHistory: List<Pair<String, String>>,
        lastContent: String
    ) {
            AppLogger.d(TAG, "手动触发记忆更新...")
            withContext(Dispatchers.IO) { // Use withContext to wait for completion
                try {
                    com.kymjs.ai.custard.api.chat.library.ProblemLibrary.saveProblemAsync(
                        context,
                        toolHandler,
                        conversationHistory,
                        lastContent,
                        multiServiceManager.getServiceForFunction(FunctionType.PROBLEM_LIBRARY)
                    )
                    AppLogger.d(TAG, "手动记忆更新成功")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "手动记忆更新失败", e)
                    throw e
                }
        }
    }

    /**
     * 使用识图模型分析图片
     * @param imagePath 图片路径
     * @param userIntent 用户意图，例如"这个图片里面有什么"、"图片的题目公式是什么"等
     * @return AI分析结果
     */
    suspend fun analyzeImageWithIntent(imagePath: String, userIntent: String?): String {
        return conversationService.analyzeImageWithIntent(imagePath, userIntent, multiServiceManager)
    }

    suspend fun analyzeAudioWithIntent(audioPath: String, userIntent: String?): String {
        return conversationService.analyzeAudioWithIntent(audioPath, userIntent, multiServiceManager)
    }

    suspend fun analyzeVideoWithIntent(videoPath: String, userIntent: String?): String {
        return conversationService.analyzeVideoWithIntent(videoPath, userIntent, multiServiceManager)
    }
}
