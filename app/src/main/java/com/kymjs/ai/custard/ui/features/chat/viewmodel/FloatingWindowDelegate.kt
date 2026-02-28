package com.kymjs.ai.custard.ui.features.chat.viewmodel

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.kymjs.ai.custard.util.AppLogger
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import com.kymjs.ai.custard.data.model.ChatMessage
import com.kymjs.ai.custard.data.model.InputProcessingState
import com.kymjs.ai.custard.data.model.toSerializable
import com.kymjs.ai.custard.services.FloatingChatService
import com.kymjs.ai.custard.services.core.ChatHistoryDelegate
import com.kymjs.ai.custard.ui.floating.FloatingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 委托类，负责管理悬浮窗交互 */
class FloatingWindowDelegate(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val inputProcessingState: StateFlow<InputProcessingState>,
    private val chatHistoryFlow: StateFlow<List<ChatMessage>>? = null,
    private val chatHistoryDelegate: ChatHistoryDelegate? = null,
    private val onChatStatsUpdate: ((chatId: String?, inputTokens: Int, outputTokens: Int, windowSize: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "FloatingWindowDelegate"
    }

    // 悬浮窗状态
    private val _isFloatingMode = MutableStateFlow(false)
    val isFloatingMode: StateFlow<Boolean> = _isFloatingMode.asStateFlow()

    // 悬浮窗服务
    private var floatingService: FloatingChatService? = null

    private var floatingBinder: FloatingChatService.LocalBinder? = null

    private var isBoundToService: Boolean = false

    private val serviceLifecycleReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    FloatingChatService.ACTION_FLOATING_CHAT_SERVICE_STARTED -> {
                        tryBindToRunningService()
                    }
                    FloatingChatService.ACTION_FLOATING_CHAT_SERVICE_STOPPED -> {
                        disconnectFromService(updateFloatingMode = false)
                    }
                }
            }
        }

    // 服务连接
    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as FloatingChatService.LocalBinder
                floatingService = binder.getService()
                floatingBinder = binder
                // 设置回调，允许服务通知委托关闭
                binder.setCloseCallback {
                    closeFloatingWindow()
                }
                // 设置反向通信回调，允许悬浮窗通知应用重新加载消息
                binder.setReloadCallback {
                    coroutineScope.launch {
                        try {
                            val chatId = chatHistoryDelegate?.currentChatId?.value
                            if (chatId != null) {
                                AppLogger.d(TAG, "收到悬浮窗重新加载请求，chatId: $chatId")
                                chatHistoryDelegate?.reloadChatMessagesSmart(chatId)
                            } else {
                                AppLogger.w(TAG, "当前没有活跃对话，无法重新加载消息")
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "重新加载消息失败", e)
                        }
                    }
                }

                binder.setChatSyncCallback { chatId, messages ->
                    coroutineScope.launch {
                        try {
                            if (chatId == null) {
                                return@launch
                            }

                            val currentId = chatHistoryDelegate?.currentChatId?.value
                            if (currentId == chatId) {
                                AppLogger.d(TAG, "收到悬浮窗消息同步(改为DB重新加载): chatId=$chatId, messages=${messages.size}")
                                chatHistoryDelegate?.reloadChatMessagesSmart(chatId)
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "处理悬浮窗消息同步失败", e)
                        }
                    }
                }

                binder.setChatStatsCallback { chatId, inputTokens, outputTokens, windowSize ->
                    onChatStatsUpdate?.invoke(chatId, inputTokens, outputTokens, windowSize)
                }
                // 订阅聊天历史更新
                setupChatHistoryCollection()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                try {
                    floatingBinder?.clearCallbacks()
                } catch (_: Exception) {
                }
                floatingBinder = null
                floatingService = null
                isBoundToService = false
            }
        }

    init {
        try {
            val filter = IntentFilter().apply {
                addAction(FloatingChatService.ACTION_FLOATING_CHAT_SERVICE_STARTED)
                addAction(FloatingChatService.ACTION_FLOATING_CHAT_SERVICE_STOPPED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(serviceLifecycleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(serviceLifecycleReceiver, filter)
            }
        } catch (_: Exception) {
        }

        // If the service is already running (started by wake/workflow/widget), bind to it.
        tryBindToRunningService()
        setupInputStateCollection()
    }

    private fun tryBindToRunningService() {
        if (isBoundToService) return
        if (FloatingChatService.getInstance() == null) return
        try {
            val intent = Intent(context, FloatingChatService::class.java)
            // Bind without auto-create: only succeed if service is already running.
            val ok = context.bindService(intent, serviceConnection, 0)
            if (ok) {
                isBoundToService = true
                AppLogger.d(TAG, "已绑定到已运行的悬浮窗服务")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "绑定到已运行的悬浮窗服务失败", e)
        }
    }

    /** 切换悬浮窗模式 */
    fun toggleFloatingMode(colorScheme: ColorScheme? = null, typography: Typography? = null) {
        val newMode = !_isFloatingMode.value

        if (newMode) {
            _isFloatingMode.value = true

            // 先启动并绑定服务
            val intent = Intent(context, FloatingChatService::class.java)
            colorScheme?.let {
                intent.putExtra("COLOR_SCHEME", it.toSerializable())
            }
            typography?.let {
                intent.putExtra("TYPOGRAPHY", it.toSerializable())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            // 统一调用关闭逻辑，确保服务被正确关闭
            floatingService?.onClose()
        }
    }

    /**
     * 启动悬浮窗并指定一个初始模式
     */
    fun launchInMode(
        mode: FloatingMode,
        colorScheme: ColorScheme? = null,
        typography: Typography? = null
    ) {
        if (_isFloatingMode.value && floatingService != null) {
            // 如果服务已在运行，直接切换模式
            floatingService?.switchToMode(mode)
            AppLogger.d(TAG, "悬浮窗已在运行，直接切换到模式: $mode")
            return
        }

        _isFloatingMode.value = true

        // 先启动并绑定服务
        val intent = Intent(context, FloatingChatService::class.java)
        // 添加初始模式参数
        intent.putExtra("INITIAL_MODE", mode.name)

        colorScheme?.let {
            intent.putExtra("COLOR_SCHEME", it.toSerializable())
        }
        typography?.let {
            intent.putExtra("TYPOGRAPHY", it.toSerializable())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 由服务回调或用户操作调用，用于关闭悬浮窗并更新状态
     */
    private fun closeFloatingWindow() {
        disconnectFromService(updateFloatingMode = true)
    }

    private fun disconnectFromService(updateFloatingMode: Boolean) {
        if (updateFloatingMode && _isFloatingMode.value) {
            _isFloatingMode.value = false
        }
        if (isBoundToService) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {
            }
        }
        try {
            floatingBinder?.clearCallbacks()
        } catch (_: Exception) {
        }
        floatingBinder = null
        floatingService = null
        isBoundToService = false
    }

    private fun setupInputStateCollection() {
        coroutineScope.launch {
            inputProcessingState.collect { state ->
                val isUiToolExecuting = state is InputProcessingState.ExecutingTool

                // Update UI busy state directly on the window state
                // floatingService?.windowState?.isUiBusy?.value = isUiToolExecuting
            }
        }
    }

    /** 设置聊天历史收集 - 订阅ChatHistoryDelegate的chatHistory流 */
    private fun setupChatHistoryCollection() {
        chatHistoryFlow?.let { flow ->
            coroutineScope.launch {
                try {
                    // 先立即同步当前的消息历史（服务刚连接时）
                    val currentMessages = flow.value
                    if (currentMessages.isNotEmpty()) {
                        AppLogger.d(TAG, "悬浮窗服务连接，立即同步当前消息: ${currentMessages.size} 条")
                        floatingService?.updateChatMessages(currentMessages)
                    }

                    // 然后订阅后续的更新
                    flow.collect { messages ->
                        // 只在悬浮窗模式激活时同步消息
                        if (_isFloatingMode.value) {
                            AppLogger.d(TAG, "从ChatHistoryDelegate收到消息更新: ${messages.size} 条")
                            floatingService?.updateChatMessages(messages)
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "收集聊天历史时出错", e)
                }
            }
        } ?: AppLogger.w(TAG, "chatHistoryFlow为空，无法订阅聊天历史更新")
    }

    /** 通知悬浮窗服务重新加载消息 */
    fun notifyFloatingServiceReload() {
        if (_isFloatingMode.value && floatingService != null) {
            AppLogger.d(TAG, "通知悬浮窗服务重新加载消息")
            floatingService?.reloadChatMessages()
        }
    }

    /** 清理资源 */
    fun cleanup() {
        try {
            context.unregisterReceiver(serviceLifecycleReceiver)
        } catch (_: Exception) {
        }
        // 解绑服务
        disconnectFromService(updateFloatingMode = false)
    }
}
