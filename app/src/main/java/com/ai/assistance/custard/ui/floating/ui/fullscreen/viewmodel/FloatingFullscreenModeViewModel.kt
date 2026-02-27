package com.ai.assistance.custard.ui.floating.ui.fullscreen.viewmodel

import android.content.Context
import androidx.compose.runtime.*
import com.ai.assistance.custard.R
import com.ai.assistance.custard.data.model.ChatMessage
import com.ai.assistance.custard.data.model.InputProcessingState
import com.ai.assistance.custard.data.model.PromptFunctionType
import com.ai.assistance.custard.data.preferences.WakeWordPreferences
import com.ai.assistance.custard.ui.floating.FloatContext
import com.ai.assistance.custard.ui.floating.ui.fullscreen.XmlTextProcessor
import com.ai.assistance.custard.ui.floating.voice.SpeechInteractionManager
import com.ai.assistance.custard.util.stream.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "FloatingFullscreenViewModel"

class FloatingFullscreenModeViewModel(
    private val context: Context,
    private val floatContext: FloatContext,
    private val coroutineScope: CoroutineScope,
    initialWaveActive: Boolean
) {
    // ===== 状态定义 =====
    var aiMessage by mutableStateOf(context.getString(R.string.floating_hold_microphone_to_speak))
    
    // UI状态
    var isWaveActive by mutableStateOf(initialWaveActive)
    var showBottomControls by mutableStateOf(true)
    var isEditMode by mutableStateOf(false)
    var editableText by mutableStateOf("")
    var inputText by mutableStateOf("")
    var showDragHints by mutableStateOf(false)

    var attachScreenContent by mutableStateOf(false)
    var attachNotifications by mutableStateOf(false)
    var attachLocation by mutableStateOf(false)
    var hasOcrSelection by mutableStateOf(false)
    
    val isInitialLoad = mutableStateOf(true)

     private var aiStreamJob: Job? = null
     private var activeAiStreamIdentity: Int? = null
     private var activeAiMessageTimestamp: Long? = null

    private val wakePrefs by lazy { WakeWordPreferences(context.applicationContext) }
    private var inactivityTimeoutSeconds: Int = WakeWordPreferences.DEFAULT_VOICE_CALL_INACTIVITY_TIMEOUT_SECONDS
    private var prefsJob: Job? = null
    private var inactivityJob: Job? = null
    private var lastVoiceActivityAtMs: Long = 0L

    private var wakeEnterJob: Job? = null
    
    // ===== 语音交互管理器 =====
    val speechManager = SpeechInteractionManager(
        context = context,
        coroutineScope = coroutineScope,
        onSpeechResult = { text, _ -> 
            // 收到最终语音结果后直接发送，不再写入底部输入框
            val finalText = text.trim()
            if (finalText.isNotEmpty()) {
                aiMessage = context.getString(R.string.floating_thinking)
                coroutineScope.launch {
                    try {
                        maybeAutoAttachByKeyword(finalText)
                    } catch (_: Exception) {
                    }
                    floatContext.onSendMessage?.invoke(finalText, PromptFunctionType.VOICE)
                }
            }
        },
        onStateChange = { msg -> aiMessage = msg }
    )
    
    // 代理属性，方便 UI 访问
    val isRecording: Boolean get() = speechManager.isRecording
    val isProcessingSpeech: Boolean get() = speechManager.isProcessingSpeech
    val userMessage: String get() = speechManager.userMessage
    val hasFocus: Boolean get() = speechManager.hasFocus
    // UI 用到 volumeFlow 和 recognitionResultFlow
    val speechService get() = speechManager.speechService
    val volumeLevelFlow get() = speechManager.volumeLevelFlow
    val recognitionResultFlow get() = speechManager.recognitionResultFlow

    // ===== 业务逻辑 =====

    fun processAndSpeakAiMessage(lastMessage: ChatMessage?, ttsCleanerRegexs: List<String>) {
        val message = lastMessage ?: return

         // If we are switching to a new message, stop any previous stream collector.
         // This avoids duplicate collectors (and duplicated replay) when the upstream SharedStream replays history.
         if (activeAiMessageTimestamp != null && activeAiMessageTimestamp != message.timestamp) {
             aiStreamJob?.cancel()
             aiStreamJob = null
             activeAiStreamIdentity = null
         }
         activeAiMessageTimestamp = message.timestamp
        
        if (isInitialLoad.value) {
            isInitialLoad.value = false
            if (message.sender == "ai") aiMessage = message.content
            return
        }
        
        coroutineScope.launch { speechManager.voiceService.stop() }
        
        when (message.sender) {
            "think" -> {
                aiStreamJob?.cancel()
                aiStreamJob = null
                activeAiStreamIdentity = null
                aiMessage = context.getString(R.string.floating_thinking)
            }
            "ai" -> {
                val stream = message.contentStream
                if (stream != null) {
                    val streamIdentity = System.identityHashCode(stream)
                    if (aiStreamJob?.isActive == true && activeAiStreamIdentity == streamIdentity) {
                        return
                    }
                    aiStreamJob?.cancel()
                    aiStreamJob = null
                    activeAiStreamIdentity = streamIdentity

                    // 不要立即清空，等待流内容到达
                    aiStreamJob = coroutineScope.launch {
                        handleStreamResponse(stream, ttsCleanerRegexs)
                    }
                } else {
                    aiStreamJob?.cancel()
                    aiStreamJob = null
                    activeAiStreamIdentity = null
                    handleStaticResponse(message.content, ttsCleanerRegexs)
                }
            }
        }
    }

    private suspend fun handleStreamResponse(stream: Stream<String>, cleaners: List<String>) {
        val sb = StringBuilder()
        var isFirstSentence = true
        var isFirstChar = true
        val endChars = ".,!?;:，。！？；：\n"
        
        XmlTextProcessor.processStreamToText(stream).collect { char ->
            if (isFirstChar) {
                aiMessage = "" // 收到第一个字符时才清空等待提示
                isFirstChar = false
            }
            aiMessage += char
            sb.append(char)
            
            if (char in endChars || sb.length >= 50) {
                if (trySpeak(sb.toString(), isFirstSentence, cleaners)) {
                    isFirstSentence = false
                    sb.clear()
                }
            }
        }
        trySpeak(sb.toString(), isFirstSentence, cleaners)
    }

    private fun handleStaticResponse(content: String, cleaners: List<String>) {
        aiMessage = content
        trySpeak(content, false, cleaners)
    }

    private fun trySpeak(text: String, interrupt: Boolean, cleaners: List<String>): Boolean {
        val cleanText = speechManager.cleanTextForTts(text.trim(), cleaners)
        if (cleanText.isNotEmpty()) {
            speechManager.speak(cleanText, interrupt)
            return true
        }
        return false
    }

    // ===== 语音交互 =====

    fun startVoiceCapture() {
        // 如果AI正在生成，尝试取消
        val lastMessage = floatContext.messages.lastOrNull()
        val isAiWorking = lastMessage?.sender == "think" || 
                          (lastMessage?.sender == "ai" && lastMessage.contentStream != null)
        
        if (isAiWorking) {
            floatContext.onCancelMessage?.invoke()
        }
        
        speechManager.startListening { errorMsg ->
            aiMessage = errorMsg
        }
    }

    fun stopVoiceCapture(isCancel: Boolean) {
        speechManager.stopListening(isCancel)
    }

    fun enterWaveMode(wakeLaunched: Boolean = false) {
        wakeEnterJob?.cancel()
        wakeEnterJob = coroutineScope.launch {
            // 语音态 UI 先切换出来（唤醒场景更符合预期）
            isWaveActive = true

            playWakeGreetingIfNeeded(wakeLaunched)

            startVoiceCapture()
            if (speechManager.isRecording) {
                lastVoiceActivityAtMs = System.currentTimeMillis()
                startInactivityMonitor()
            } else {
                isWaveActive = false
                showBottomControls = true
            }
        }
    }
    
    fun exitWaveMode() {
        wakeEnterJob?.cancel()
        wakeEnterJob = null
        stopVoiceCapture(true)
        coroutineScope.launch { speechManager.voiceService.stop() }
        isWaveActive = false
        showBottomControls = true
        inactivityJob?.cancel()
        inactivityJob = null
    }

    private suspend fun playWakeGreetingIfNeeded(wakeLaunched: Boolean) {
        if (!wakeLaunched) return

        val enabled = wakePrefs.wakeGreetingEnabledFlow.first()
        if (!enabled) return

        val text =
            wakePrefs.wakeGreetingTextFlow.first().trim().ifBlank {
                WakeWordPreferences.DEFAULT_WAKE_GREETING_TEXT
            }
        if (text.isBlank()) return

        // 先朗读问候语，再开始录音；等待 TTS 结束，避免把 TTS 录进去。
        speechManager.speak(text, interrupt = true)

        val voiceService = speechManager.voiceService
        val estimatedMs = (600L + text.length * 220L).coerceIn(800L, 6000L)

        val started = withTimeoutOrNull(1500L) {
            voiceService.speakingStateFlow.filter { it }.first()
        }

        if (started != null) {
            withTimeoutOrNull(estimatedMs + 2500L) {
                voiceService.speakingStateFlow.filter { speaking -> !speaking }.first()
            }
        } else {
            // 某些实现可能不会及时发 speaking=true，这里用估时兜底
            delay(estimatedMs)
        }

        // 留一点间隔，减少回声/尾音被录入
        delay(250L)
    }

    fun handleRecognitionResult(resultText: String, isFinal: Boolean) {
        if (isWaveActive && resultText.isNotBlank()) {
            lastVoiceActivityAtMs = System.currentTimeMillis()
        }
        // 委托给 Manager 处理，波浪模式下启用自动静默发送
        speechManager.handleRecognitionResult(resultText, isFinal, autoSendSilence = isWaveActive)
    }

    // ===== 初始化与清理 =====

     suspend fun initialize(autoEnterVoiceChat: Boolean = false, wakeLaunched: Boolean = false) {
         speechManager.initialize()
         prefsJob?.cancel()
         prefsJob = coroutineScope.launch {
             wakePrefs.voiceCallInactivityTimeoutSecondsFlow.collectLatest { seconds ->
                 inactivityTimeoutSeconds = seconds.coerceIn(1, 600)
             }
         }
         isInitialLoad.value = true
         isWaveActive = autoEnterVoiceChat
         showBottomControls = true
         exitEditMode()

        // 获取焦点
        val view = floatContext.chatService?.getComposeView()
         if (!speechManager.requestFocus(view)) {
             aiMessage = context.getString(R.string.floating_cannot_get_input_service)
         } else {
             aiMessage = context.getString(R.string.floating_hold_microphone_to_speak)
         }

         if (autoEnterVoiceChat) {
             enterWaveMode(wakeLaunched = wakeLaunched)
         }
     }

    fun cleanup() {
        val view = floatContext.chatService?.getComposeView()
        speechManager.releaseFocus(view)
        speechManager.cleanup()

        prefsJob?.cancel()
        prefsJob = null
        inactivityJob?.cancel()
        inactivityJob = null

         aiStreamJob?.cancel()
         aiStreamJob = null
         activeAiStreamIdentity = null

        wakeEnterJob?.cancel()
        wakeEnterJob = null
    }

    private fun startInactivityMonitor() {
        inactivityJob?.cancel()
        inactivityJob = coroutineScope.launch {
            while (isActive && isWaveActive) {
                val timeoutMs = inactivityTimeoutSeconds.toLong() * 1000L
                val elapsed = System.currentTimeMillis() - lastVoiceActivityAtMs
                val remaining = timeoutMs - elapsed
                if (remaining <= 0L) {
                    // 如果 AI 正在朗读，不要在朗读过程中退出/关闭。
                    // 等朗读结束后再重新评估 remaining。
                    val voiceService = speechManager.voiceService
                    if (voiceService.isSpeaking) {
                        withTimeoutOrNull(20_000L) {
                            voiceService.speakingStateFlow.filter { speaking -> !speaking }.first()
                        }
                        // 朗读结束后重置计时，给用户一个完整的超时窗口
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                        continue
                    }

                    // AI 在工具调用/处理/生成过程中，也不要自动关闭。
                    // 否则会出现“工具调用耗时较长 -> 超时 -> 窗口自动关闭”。
                    if (isAiBusy()) {
                        while (isActive && isWaveActive && isAiBusy()) {
                            delay(250L)
                        }
                        // AI 忙完后重置计时，避免立刻触发关闭
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                        continue
                    }

                    exitWaveMode()
                    if (floatContext.chatService?.isWakeLaunched() == true) {
                        floatContext.onClose()
                    }
                    return@launch
                }
                delay(minOf(remaining, 500L))
            }
        }
    }

    private fun isAiBusy(): Boolean {
        val state = floatContext.inputProcessingState.value
        val stateBusy =
            state !is InputProcessingState.Idle &&
                state !is InputProcessingState.Completed &&
                state !is InputProcessingState.Error

        val lastMessage = floatContext.messages.lastOrNull()
        val streamBusy =
            lastMessage?.sender == "think" ||
                (lastMessage?.sender == "ai" && lastMessage.contentStream != null)

        return stateBusy || streamBusy
    }

    // ===== 编辑模式 =====

    fun enterEditMode(text: String) {
        coroutineScope.launch { speechManager.stopListening(isCancel = true) }
        editableText = text
        isEditMode = true
        aiMessage = context.getString(R.string.floating_edit_your_message)
    }
    
    fun exitEditMode() {
        isEditMode = false
        editableText = ""
        aiMessage = context.getString(R.string.floating_hold_microphone_to_speak)
    }
    
    fun sendEditedMessage() {
        if (editableText.isNotBlank()) {
            floatContext.onSendMessage?.invoke(editableText, PromptFunctionType.VOICE)
            isEditMode = false
            editableText = ""
            aiMessage = context.getString(R.string.floating_thinking)
        }
    }
    
    fun sendInputMessage() {
        val text = inputText.trim()
        if (text.isEmpty() && !attachScreenContent && !attachNotifications && !attachLocation && !hasOcrSelection) return

        // 立即清理UI状态，不等待协程
        val shouldCaptureScreen = attachScreenContent
        val shouldCaptureNotifications = attachNotifications
        val shouldCaptureLocation = attachLocation
        
        inputText = ""
        attachScreenContent = false
        attachNotifications = false
        attachLocation = false
        hasOcrSelection = false
        aiMessage = context.getString(R.string.floating_thinking)

        coroutineScope.launch {
            try {
                maybeAutoAttachByKeyword(text)
            } catch (_: Exception) {
            }
            try {
                val attachmentDelegate = floatContext.chatService?.getChatCore()?.getAttachmentDelegate()
                if (shouldCaptureScreen) {
                    attachmentDelegate?.captureScreenContent()
                }
                if (shouldCaptureNotifications) {
                    attachmentDelegate?.captureNotifications()
                }
                if (shouldCaptureLocation) {
                    attachmentDelegate?.captureLocation()
                }
                // hasOcrSelection 的附件已经在 FloatingScreenOcrScreen 中添加了
            } catch (_: Exception) {
            }

            floatContext.onSendMessage?.invoke(text, PromptFunctionType.VOICE)
        }
    }

    private suspend fun maybeAutoAttachByKeyword(text: String) {
        if (text.isBlank()) return

        wakePrefs.migrateVoiceAutoAttachItemsIfNeeded()

        val enabled = wakePrefs.voiceAutoAttachEnabledFlow.first()
        if (!enabled) return

        val attachmentDelegate = floatContext.chatService?.getChatCore()?.getAttachmentDelegate() ?: return

        val items = wakePrefs.voiceAutoAttachItemsFlow.first()
        items
            .asSequence()
            .filter { it.enabled }
            .forEach { item ->
                val keywordConfig = item.keywords.trim()
                if (keywordConfig.isBlank()) return@forEach
                if (!matchesAnyKeyword(text, keywordConfig)) return@forEach

                when (item.type) {
                    WakeWordPreferences.VoiceAutoAttachType.SCREEN_OCR -> {
                        attachmentDelegate.captureScreenContent()
                    }
                    WakeWordPreferences.VoiceAutoAttachType.NOTIFICATIONS -> {
                        attachmentDelegate.captureNotifications()
                    }
                    WakeWordPreferences.VoiceAutoAttachType.LOCATION -> {
                        attachmentDelegate.captureLocation()
                    }
                    WakeWordPreferences.VoiceAutoAttachType.TIME -> {
                        attachmentDelegate.captureCurrentTime()
                    }
                }
            }
    }

    private fun matchesAnyKeyword(text: String, keywordConfig: String): Boolean {
        val keywords =
            keywordConfig
                .split('|', ',', '，', ';', '；', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return false
        return keywords.any { k -> text.contains(k, ignoreCase = true) }
    }
}

@Composable
 fun rememberFloatingFullscreenModeViewModel(
     context: Context,
     floatContext: FloatContext,
     coroutineScope: CoroutineScope,
     initialWaveActive: Boolean
 ) = remember(context, initialWaveActive) {
     FloatingFullscreenModeViewModel(context, floatContext, coroutineScope, initialWaveActive)
 }
