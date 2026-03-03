package com.kymjs.ai.custard.api.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import com.kymjs.ai.custard.util.AppLogger
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.drawable.IconCompat
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.api.speech.PersonalWakeListener
import com.kymjs.ai.custard.api.speech.SpeechPrerollStore
import com.kymjs.ai.custard.api.speech.SpeechService
import com.kymjs.ai.custard.api.speech.SpeechServiceFactory
import com.kymjs.ai.custard.core.chat.AIMessageManager
import com.kymjs.ai.custard.core.application.ActivityLifecycleManager
import com.kymjs.ai.custard.core.application.ForegroundServiceCompat
import com.kymjs.ai.custard.data.preferences.SpeechServicesPreferences
import com.kymjs.ai.custard.services.FloatingChatService
import com.kymjs.ai.custard.services.UIDebuggerService
import com.kymjs.ai.custard.data.preferences.DisplayPreferencesManager
import com.kymjs.ai.custard.data.preferences.WakeWordPreferences
import com.kymjs.ai.custard.data.repository.WorkflowRepository
import com.kymjs.ai.custard.util.WaifuMessageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.exitProcess
import java.io.FileInputStream
import java.io.InputStream

private fun AudioRecordingConfiguration.tryGetClientUid(): Int? {
    return try {
        val method =
            javaClass.methods.firstOrNull { it.name == "getClientUid" && it.parameterTypes.isEmpty() }
        val value = method?.invoke(this)
        value as? Int
    } catch (_: Exception) {
        null
    }
}

private fun AudioRecordingConfiguration.tryGetClientPackageName(): String? {
    return try {
        val method =
            javaClass.methods.firstOrNull { it.name == "getClientPackageName" && it.parameterTypes.isEmpty() }
        val value = method?.invoke(this)
        value as? String
    } catch (_: Exception) {
        null
    }
}

/** 前台服务，用于在AI进行长时间处理时保持应用活跃，防止被系统杀死。 该服务不执行实际工作，仅通过显示一个持久通知来提升应用的进程优先级。 */
class AIForegroundService : Service() {

    companion object {
        private const val TAG = "AIForegroundService"
        private const val NOTIFICATION_ID = 1
        private const val REPLY_NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "AI_SERVICE_CHANNEL"
        private const val REPLY_CHANNEL_ID = "AI_REPLY_CHANNEL"

        private const val ACTION_CANCEL_CURRENT_OPERATION = "com.kymjs.ai.custard.action.CANCEL_CURRENT_OPERATION"
        private const val REQUEST_CODE_CANCEL_CURRENT_OPERATION = 9002

        private const val ACTION_EXIT_APP = "com.kymjs.ai.custard.action.EXIT_APP"
        private const val REQUEST_CODE_EXIT_APP = 9003

        private const val ACTION_TOGGLE_WAKE_LISTENING = "com.kymjs.ai.custard.action.TOGGLE_WAKE_LISTENING"
        private const val REQUEST_CODE_TOGGLE_WAKE_LISTENING = 9006

        private const val ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_IME =
            "com.kymjs.ai.custard.action.SET_WAKE_LISTENING_SUSPENDED_FOR_IME"
        private const val EXTRA_IME_VISIBLE = "extra_ime_visible"

        private const val ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN =
            "com.kymjs.ai.custard.action.SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN"
        private const val EXTRA_FLOATING_FULLSCREEN_ACTIVE = "extra_floating_fullscreen_active"

        const val ACTION_PREPARE_WAKE_HANDOFF =
            "com.kymjs.ai.custard.action.PREPARE_WAKE_HANDOFF"

        private const val ACTION_ENSURE_MICROPHONE_FOREGROUND =
            "com.kymjs.ai.custard.action.ENSURE_MICROPHONE_FOREGROUND"

        @Volatile
        private var lastRequestedImeVisible: Boolean = false

        // 静态标志，用于从外部检查服务是否正在运行
        val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        
        // Intent extras keys
        const val EXTRA_CHARACTER_NAME = "extra_character_name"
        const val EXTRA_REPLY_CONTENT = "extra_reply_content"
        const val EXTRA_AVATAR_URI = "extra_avatar_uri"
        const val EXTRA_STATE = "extra_state"
        const val STATE_RUNNING = "running"
        const val STATE_IDLE = "idle"

        fun setWakeListeningSuspendedForIme(context: Context, imeVisible: Boolean) {
            lastRequestedImeVisible = imeVisible
            if (!isRunning.get()) return
            val intent = Intent(context, AIForegroundService::class.java).apply {
                action = ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_IME
                putExtra(EXTRA_IME_VISIBLE, imeVisible)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to request IME wake listening suspend: ${e.message}", e)
            }
        }

        fun setWakeListeningSuspendedForFloatingFullscreen(context: Context, active: Boolean) {
            if (!isRunning.get()) return
            val intent = Intent(context, AIForegroundService::class.java).apply {
                action = ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN
                putExtra(EXTRA_FLOATING_FULLSCREEN_ACTIVE, active)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Failed to request floating fullscreen wake listening suspend: ${e.message}",
                    e
                )
            }
        }

        fun ensureMicrophoneForeground(context: Context) {
            val intent = Intent(context, AIForegroundService::class.java).apply {
                action = ACTION_ENSURE_MICROPHONE_FOREGROUND
            }
            try {
                if (isRunning.get()) {
                    context.startService(intent)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to request microphone foreground", e)
            }
        }
    }

    private fun updateWakeListeningSuspendedForIme(imeVisible: Boolean) {
        if (wakeListeningSuspendedForIme == imeVisible) return
        wakeListeningSuspendedForIme = imeVisible
        AppLogger.d(TAG, "Wake listening suspended by IME: $wakeListeningSuspendedForIme")
        applyWakeListeningState()
    }

    private fun updateWakeListeningSuspendedForExternalRecording(externalRecording: Boolean) {
        if (wakeListeningSuspendedForExternalRecording == externalRecording) return
        wakeListeningSuspendedForExternalRecording = externalRecording
        AppLogger.d(TAG, "Wake listening suspended by external recording: $wakeListeningSuspendedForExternalRecording")
        applyWakeListeningState()
    }

    private fun updateWakeListeningSuspendedForFloatingFullscreen(active: Boolean) {
        if (wakeListeningSuspendedForFloatingFullscreen == active) return
        wakeListeningSuspendedForFloatingFullscreen = active
        AppLogger.d(TAG, "Wake listening suspended by floating fullscreen: $wakeListeningSuspendedForFloatingFullscreen")
        applyWakeListeningState()
    }
    
    private fun applyWakeListeningState() {
        wakeStateApplyJob?.cancel()
        wakeStateApplyJob =
            serviceScope.launch {
                wakeStateMutex.withLock {
                    applyWakeListeningStateLocked()
                }
            }
    }

    private suspend fun applyWakeListeningStateLocked() {
        val shouldListen =
            wakeListeningEnabled &&
                !wakeListeningSuspendedForIme &&
                !wakeListeningSuspendedForExternalRecording &&
                !wakeListeningSuspendedForFloatingFullscreen

        if (shouldListen) {
            startWakeListeningLocked()
        } else {
            val shouldRelease = !wakeListeningEnabled || wakeListeningSuspendedForFloatingFullscreen
            stopWakeListeningLocked(releaseProvider = shouldRelease)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun startRecordingStateMonitoring() {
        if (!wakeListeningEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (audioRecordingCallback != null) return

        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am

        val callback =
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    val isWakeListeningRunning =
                        wakeListeningMicActiveForRecordingDetection ||
                            wakeListeningJob?.isActive == true ||
                            personalWakeJob?.isActive == true
                    val myUid = Process.myUid()
                    val myPackageName = packageName

                    fun isExternalConfig(cfg: AudioRecordingConfiguration): Boolean {
                        val uid = cfg.tryGetClientUid()
                        if (uid != null && uid > 0) {
                            if (uid == myUid) return false
                            if (uid < Process.FIRST_APPLICATION_UID) {
                                return false
                            }

                            val pkg = cfg.tryGetClientPackageName()?.takeIf { it.isNotBlank() }
                            if (pkg != null) {
                                return pkg != myPackageName
                            }

                            return true
                        }

                        val pkg = cfg.tryGetClientPackageName()?.takeIf { it.isNotBlank() }
                        if (uid == null || uid <= 0) {
                            if (isWakeListeningRunning) return false
                            if (pkg != null) return pkg != myPackageName
                            return true
                        }

                        return false
                    }

                    val hasExternal = configs.any(::isExternalConfig)

                    if (hasExternal != wakeListeningSuspendedForExternalRecording) {
                        val summary =
                            configs.mapIndexed { idx, cfg ->
                                val uid = cfg.tryGetClientUid()
                                val pkg = cfg.tryGetClientPackageName()
                                val hasUidMethod = cfg.javaClass.methods.any { it.name == "getClientUid" && it.parameterTypes.isEmpty() }
                                val hasPkgMethod = cfg.javaClass.methods.any { it.name == "getClientPackageName" && it.parameterTypes.isEmpty() }
                                val raw = try {
                                    cfg.toString().replace('\n', ' ').replace('\r', ' ')
                                } catch (_: Exception) {
                                    ""
                                }
                                val rawShort = if (raw.length > 220) raw.substring(0, 220) else raw
                                "#$idx uid=${uid ?: "?"},pkg=${pkg ?: "?"},mUid=$hasUidMethod,mPkg=$hasPkgMethod cfg=$rawShort"
                            }.joinToString(" | ")
                        AppLogger.d(
                            TAG,
                            "Recording configs changed: wakeRunning=$isWakeListeningRunning micFlag=$wakeListeningMicActiveForRecordingDetection external=$hasExternal count=${configs.size} configs=[$summary]"
                        )
                    }

                    updateWakeListeningSuspendedForExternalRecording(hasExternal)
                }
            }
        audioRecordingCallback = callback

        try {
            am.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register recording callback: ${e.message}", e)
            audioRecordingCallback = null
            audioManager = null
            return
        }

        try {
            val configs = am.activeRecordingConfigurations
            val isWakeListeningRunning =
                wakeListeningMicActiveForRecordingDetection ||
                    wakeListeningJob?.isActive == true ||
                    personalWakeJob?.isActive == true
            val myUid = Process.myUid()
            val myPackageName = packageName

            fun isExternalConfig(cfg: AudioRecordingConfiguration): Boolean {
                val uid = cfg.tryGetClientUid()
                if (uid != null && uid > 0) {
                    if (uid == myUid) return false
                    if (uid < Process.FIRST_APPLICATION_UID) {
                        return false
                    }

                    val pkg = cfg.tryGetClientPackageName()?.takeIf { it.isNotBlank() }
                    if (pkg != null) {
                        return pkg != myPackageName
                    }

                    return true
                }

                val pkg = cfg.tryGetClientPackageName()?.takeIf { it.isNotBlank() }
                if (uid == null || uid <= 0) {
                    if (isWakeListeningRunning) return false
                    if (pkg != null) return pkg != myPackageName
                    return true
                }

                return false
            }

            val hasExternal = configs.any(::isExternalConfig)
            updateWakeListeningSuspendedForExternalRecording(hasExternal)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read active recording configs: ${e.message}", e)
        }
    }

    private fun stopRecordingStateMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val am = audioManager
        val callback = audioRecordingCallback

        if (am != null && callback != null) {
            try {
                am.unregisterAudioRecordingCallback(callback)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to unregister recording callback: ${e.message}", e)
            }
        }

        audioRecordingCallback = null
        audioManager = null
        wakeListeningSuspendedForExternalRecording = false
    }
    
    // 存储通知信息
    private var characterName: String? = null
    private var replyContent: String? = null
    private var avatarUri: String? = null
    private var isAiBusy: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wakePrefs by lazy { WakeWordPreferences(applicationContext) }
    @Volatile
    private var wakeSpeechProvider: SpeechService? = null
    private val workflowRepository by lazy { WorkflowRepository(applicationContext) }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var keepAliveOverlayView: View? = null
    private var keepAliveOverlayPermissionLogged = false

    private var wakeMonitorJob: Job? = null
    private var wakeListeningJob: Job? = null
    private var wakeResumeJob: Job? = null

    private val wakeStateMutex = Mutex()
    private var wakeStateApplyJob: Job? = null
    private var wakeStateRetryJob: Job? = null

    private var personalWakeJob: Job? = null
    private var personalWakeListener: PersonalWakeListener? = null

    @Volatile
    private var currentWakePhrase: String = WakeWordPreferences.DEFAULT_WAKE_PHRASE

    @Volatile
    private var wakePhraseRegexEnabled: Boolean = WakeWordPreferences.DEFAULT_WAKE_PHRASE_REGEX_ENABLED

    @Volatile
    private var wakeRecognitionMode: WakeWordPreferences.WakeRecognitionMode = WakeWordPreferences.WakeRecognitionMode.STT

    @Volatile
    private var personalWakeTemplates: List<FloatArray> = emptyList()

    @Volatile
    private var wakeListeningEnabled: Boolean = false

    @Volatile
    private var wakeListeningMicActiveForRecordingDetection: Boolean = false

    @Volatile
    private var wakeListeningSuspendedForIme: Boolean = false

    @Volatile
    private var wakeListeningSuspendedForExternalRecording: Boolean = false

    @Volatile
    private var wakeListeningSuspendedForFloatingFullscreen: Boolean = false

    private var audioManager: AudioManager? = null
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null

    private var lastWakeTriggerAtMs: Long = 0L

    @Volatile
    private var pendingWakeTriggeredAtMs: Long = 0L

    @Volatile
    private var wakeHandoffPending: Boolean = false

    @Volatile
    private var wakeStopInProgress: Boolean = false

    private var lastSpeechWorkflowCheckAtMs: Long = 0L

    private fun ensureWakeSpeechProvider(): SpeechService {
        val existing = wakeSpeechProvider
        if (existing != null) return existing
        return SpeechServiceFactory.createWakeSpeechService(applicationContext).also {
            wakeSpeechProvider = it
        }
    }

    private fun releaseWakeSpeechProvider() {
        val provider = wakeSpeechProvider ?: return
        wakeSpeechProvider = null
        try {
            provider.shutdown()
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        wakeListeningSuspendedForIme = lastRequestedImeVisible
        AppLogger.d(TAG, "AI 前台服务创建。")
        createNotificationChannel()
        val notification = createNotification()
        ForegroundServiceCompat.startForeground(
            service = this,
            notificationId = NOTIFICATION_ID,
            notification = notification,
            types = ForegroundServiceCompat.buildTypes(dataSync = true)
        )
        startWakeMonitoring()
        AppLogger.d(TAG, "AI 前台服务已启动。")
    }

    private fun hasRecordAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isAlwaysListeningEnabledNow(): Boolean {
        return try {
            runBlocking { wakePrefs.alwaysListeningEnabledFlow.first() }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tryPromoteToMicrophoneForeground(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!hasRecordAudioPermission()) return false

        var waitedMs = 0L
        while (waitedMs < 3500L && ActivityLifecycleManager.getCurrentActivity() == null) {
            delay(150)
            waitedMs += 150
        }

        if (ActivityLifecycleManager.getCurrentActivity() == null) {
            AppLogger.w(TAG, "promote microphone foreground skipped: app not in foreground")
            return false
        }

        val types = ForegroundServiceCompat.buildTypes(dataSync = true, microphone = true)
        return try {
            ForegroundServiceCompat.startForeground(
                service = this,
                notificationId = NOTIFICATION_ID,
                notification = createNotification(),
                types = types
            )
            true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "promote microphone foreground failed: ${e.message}", e)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT_APP) {
            isRunning.set(false)
            isAiBusy = false

            try {
                AIMessageManager.cancelCurrentOperation()
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时取消当前AI任务失败: ${e.message}", e)
            }

            try {
                stopService(Intent(this, FloatingChatService::class.java))
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时停止 FloatingChatService 失败: ${e.message}", e)
            }

            try {
                stopService(Intent(this, UIDebuggerService::class.java))
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时停止 UIDebuggerService 失败: ${e.message}", e)
            }

            try {
                val activity = ActivityLifecycleManager.getCurrentActivity()
                activity?.runOnUiThread {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        activity.finishAndRemoveTask()
                    } else {
                        activity.finish()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时关闭前台界面失败: ${e.message}", e)
            }

            try {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
                manager.cancel(REPLY_NOTIFICATION_ID)
            } catch (e: Exception) {
                AppLogger.e(TAG, "退出时取消通知失败: ${e.message}", e)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }

            stopSelf()
            Process.killProcess(Process.myPid())
            exitProcess(0)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_ENSURE_MICROPHONE_FOREGROUND) {
            serviceScope.launch {
                try {
                    tryPromoteToMicrophoneForeground()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "ensure microphone foreground failed", e)
                }
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TOGGLE_WAKE_LISTENING) {
            AppLogger.d(TAG, "收到 ACTION_TOGGLE_WAKE_LISTENING")
            serviceScope.launch {
                try {
                    val current = wakePrefs.alwaysListeningEnabledFlow.first()
                    AppLogger.d(TAG, "切换唤醒监听: $current -> ${!current}")
                    wakePrefs.saveAlwaysListeningEnabled(!current)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "切换唤醒监听失败: ${e.message}", e)
                }

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, createNotification())
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_IME) {
            val imeVisible = intent.getBooleanExtra(EXTRA_IME_VISIBLE, false)
            updateWakeListeningSuspendedForIme(imeVisible)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN) {
            val active = intent.getBooleanExtra(EXTRA_FLOATING_FULLSCREEN_ACTIVE, false)
            updateWakeListeningSuspendedForFloatingFullscreen(active)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_PREPARE_WAKE_HANDOFF) {
            val now = System.currentTimeMillis()
            val triggeredAt = pendingWakeTriggeredAtMs
            if (triggeredAt > 0L && wakeHandoffPending) {
                val elapsedMs = (now - triggeredAt).coerceAtLeast(0L)
                val dynamicMarginMs = (elapsedMs / 4L).coerceIn(150L, 650L)
                val windowMs = (elapsedMs + dynamicMarginMs).coerceIn(200L, 2500L)
                AppLogger.d(
                    TAG,
                    "Wake handoff prepare: elapsedMs=$elapsedMs, marginMs=$dynamicMarginMs, captureWindowMs=$windowMs"
                )
                SpeechPrerollStore.capturePending(windowMs = windowMs.toInt())
                SpeechPrerollStore.armPending()

                if (!wakeStopInProgress) {
                    wakeStopInProgress = true
                    serviceScope.launch {
                        try {
                            stopWakeListening(releaseProvider = true)
                        } finally {
                            wakeStopInProgress = false
                            wakeHandoffPending = false
                            pendingWakeTriggeredAtMs = 0L
                            SpeechPrerollStore.clearPendingWakePhrase()
                        }
                    }
                }
            } else {
                AppLogger.d(TAG, "Wake handoff prepare ignored: pending=$wakeHandoffPending, triggeredAt=$triggeredAt")
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CANCEL_CURRENT_OPERATION) {
            try {
                AIMessageManager.cancelCurrentOperation()
                // 立即刷新通知状态（真正的状态重置由 EnhancedAIService.cancelConversation/stopAiService 完成）
                isAiBusy = false
            } catch (e: Exception) {
                AppLogger.e(TAG, "取消当前AI任务失败: ${e.message}", e)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification())
            return START_NOT_STICKY
        }

        // 从Intent中提取通知信息
        intent?.let {
            characterName = it.getStringExtra(EXTRA_CHARACTER_NAME)
            replyContent = it.getStringExtra(EXTRA_REPLY_CONTENT)
            avatarUri = it.getStringExtra(EXTRA_AVATAR_URI)
            AppLogger.d(TAG, "收到通知数据 - 角色: $characterName, 内容长度: ${replyContent?.length}, 头像: $avatarUri")

            val state = it.getStringExtra(EXTRA_STATE)
            if (state != null) {
                isAiBusy = state == STATE_RUNNING
                val alwaysListeningEnabled = isAlwaysListeningEnabledNow()
                if (!isAiBusy && !alwaysListeningEnabled && ActivityLifecycleManager.getCurrentActivity() == null) {
                    AppLogger.d(TAG, "服务进入空闲且应用不在前台，停止前台服务并移除通知")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        @Suppress("DEPRECATION")
                        stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, createNotification())
                if (!isAiBusy) {
                    sendReplyNotificationIfEnabled()
                }
            }
        }
        
        // 返回 START_NOT_STICKY 表示如果服务被杀死，系统不需要尝试重启它。
        // 因为服务的生命周期由 EnhancedAIService 精确控制。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        hideKeepAliveOverlay()
        stopWakeMonitoring()
        AppLogger.d(TAG, "AI 前台服务已销毁。")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 该服务是启动服务，不提供绑定功能。
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = getString(R.string.service_custard_running)
            val replyChannelName = getString(R.string.service_chat_complete_reminder)
            val serviceChannel =
                    NotificationChannel(
                            CHANNEL_ID,
                            channelName,
                            NotificationManager.IMPORTANCE_LOW // 低重要性，避免打扰用户
                    )
                    .apply {
                        description = getString(R.string.service_keep_background)
                    }

            val replyChannel =
                    NotificationChannel(
                            REPLY_CHANNEL_ID,
                            replyChannelName,
                            NotificationManager.IMPORTANCE_HIGH
                    )
                    .apply {
                        description = getString(R.string.service_notify_when_complete)
                    }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(replyChannel)
        }
    }

    private fun startWakeMonitoring() {
        if (wakeMonitorJob?.isActive == true) return
        AppLogger.d(TAG, "startWakeMonitoring")
        wakeMonitorJob =
            serviceScope.launch {
                launch {
                    wakePrefs.wakePhraseFlow.collectLatest { phrase ->
                        currentWakePhrase = phrase.ifBlank { WakeWordPreferences.DEFAULT_WAKE_PHRASE }
                        AppLogger.d(TAG, "唤醒词更新: '$currentWakePhrase'")
                    }
                }

                launch {
                    wakePrefs.wakePhraseRegexEnabledFlow.collectLatest { enabled ->
                        wakePhraseRegexEnabled = enabled
                        AppLogger.d(TAG, "唤醒词正则开关更新: enabled=$enabled")
                    }
                }

                launch {
                    wakePrefs.wakeRecognitionModeFlow.collectLatest { mode ->
                        wakeRecognitionMode = mode
                        AppLogger.d(TAG, "唤醒识别模式更新: $mode")
                        applyWakeListeningState()
                    }
                }

                launch {
                    wakePrefs.personalWakeTemplatesFlow.collectLatest { templates ->
                        personalWakeTemplates = templates.mapNotNull { t ->
                            val feats = t.features
                            if (feats.isEmpty()) null else feats.toFloatArray()
                        }
                        AppLogger.d(TAG, "个人化唤醒模板更新: count=${personalWakeTemplates.size}")
                        applyWakeListeningState()
                    }
                }

                wakePrefs.alwaysListeningEnabledFlow.collectLatest { enabled ->
                    wakeListeningEnabled = enabled
                    AppLogger.d(TAG, "唤醒监听开关更新: enabled=$enabled")

                    if (enabled) {
                        showKeepAliveOverlayIfPossible()
                    } else {
                        hideKeepAliveOverlay()
                    }

                    if (enabled) {
                        startRecordingStateMonitoring()
                    } else {
                        stopRecordingStateMonitoring()
                    }

                    applyWakeListeningState()

                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, createNotification())
                }
            }
    }

    private fun stopWakeMonitoring() {
        wakeMonitorJob?.cancel()
        wakeMonitorJob = null
        wakeResumeJob?.cancel()
        wakeResumeJob = null
        wakeListeningJob?.cancel()
        wakeListeningJob = null
        personalWakeListener?.stop()
        personalWakeListener = null
        personalWakeJob?.cancel()
        personalWakeJob = null
        stopRecordingStateMonitoring()
        hideKeepAliveOverlay()
        try {
            serviceScope.cancel()
        } catch (_: Exception) {
        }
        releaseWakeSpeechProvider()
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post {
                try {
                    action()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error on main thread", e)
                }
            }
        }
    }

    private fun showKeepAliveOverlayIfPossible() {
        if (keepAliveOverlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            if (!keepAliveOverlayPermissionLogged) {
                keepAliveOverlayPermissionLogged = true
                AppLogger.w(TAG, "Keep-alive overlay skipped: missing overlay permission")
            }
            return
        }

        runOnMainThread {
            if (keepAliveOverlayView != null) return@runOnMainThread
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = View(this)
                ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                    val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                    updateWakeListeningSuspendedForIme(imeVisible)
                    insets
                }
                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }

                wm.addView(view, params)
                keepAliveOverlayView = view
                ViewCompat.requestApplyInsets(view)
                AppLogger.d(TAG, "Keep-alive overlay shown")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to show keep-alive overlay: ${e.message}", e)
                keepAliveOverlayView = null
            }
        }
    }

    private fun hideKeepAliveOverlay() {
        val view = keepAliveOverlayView ?: return
        runOnMainThread {
            val current = keepAliveOverlayView ?: return@runOnMainThread
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(current)
                AppLogger.d(TAG, "Keep-alive overlay hidden")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to hide keep-alive overlay: ${e.message}", e)
            } finally {
                if (keepAliveOverlayView === view) {
                    keepAliveOverlayView = null
                }
            }
        }
    }

    private suspend fun startWakeListening() {
        wakeStateMutex.withLock {
            startWakeListeningLocked()
        }
    }

    private suspend fun startWakeListeningLocked() {
        if (!wakeListeningEnabled) return
        if (wakeRecognitionMode == WakeWordPreferences.WakeRecognitionMode.STT) {
            if (personalWakeJob?.isActive == true) {
                AppLogger.d(TAG, "Switching wake listener: stopping personal wake before starting STT")
                stopWakeListeningLocked(releaseProvider = true)
            }
            if (wakeListeningJob?.isActive == true) return
        } else {
            if (wakeListeningJob?.isActive == true) {
                AppLogger.d(TAG, "Switching wake listener: stopping STT wake before starting personal")
                stopWakeListeningLocked(releaseProvider = true)
            }
            if (personalWakeJob?.isActive == true) return
        }

        if (wakeRecognitionMode == WakeWordPreferences.WakeRecognitionMode.PERSONAL_TEMPLATE) {
            startPersonalWakeListening()
            return
        }

        AppLogger.d(TAG, "startWakeListening: phrase='$currentWakePhrase'")

        if (wakeHandoffPending && !wakeStopInProgress && FloatingChatService.getInstance() == null) {
            AppLogger.d(TAG, "Clearing stale wake handoff pending state before starting wake listening")
            wakeHandoffPending = false
            wakeStopInProgress = false
            pendingWakeTriggeredAtMs = 0L
            SpeechPrerollStore.clearPendingWakePhrase()
        }

        val micGranted =
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            AppLogger.e(TAG, "启动唤醒监听失败: 未授予 RECORD_AUDIO（请在系统设置中允许麦克风权限）")
            wakeListeningEnabled = false
            try {
                wakePrefs.saveAlwaysListeningEnabled(false)
            } catch (_: Exception) {
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification())
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            tryPromoteToMicrophoneForeground()
        }

        wakeResumeJob?.cancel()
        wakeResumeJob = null

        try {
            val provider = ensureWakeSpeechProvider()
            val initOk = provider.initialize()
            AppLogger.d(TAG, "唤醒识别器 initialize: ok=$initOk")
            wakeListeningMicActiveForRecordingDetection = true
            val startOk = provider.startRecognition(
                languageCode = "zh-CN",
                continuousMode = true,
                partialResults = true
            )
            AppLogger.d(TAG, "唤醒识别器 startRecognition: ok=$startOk")
            if (!startOk) {
                val alreadyRunning =
                    provider.isRecognizing ||
                        provider.currentState == SpeechService.RecognitionState.PREPARING ||
                        provider.currentState == SpeechService.RecognitionState.PROCESSING ||
                        provider.currentState == SpeechService.RecognitionState.RECOGNIZING
                if (!alreadyRunning) {
                    AppLogger.w(TAG, "唤醒识别器 startRecognition failed (will retry)")
                    wakeListeningMicActiveForRecordingDetection = false
                    wakeStateRetryJob?.cancel()
                    wakeStateRetryJob =
                        serviceScope.launch {
                            delay(650)
                            wakeStateMutex.withLock {
                                applyWakeListeningStateLocked()
                            }
                        }
                    return
                }
            }
        } catch (e: Exception) {
            wakeListeningMicActiveForRecordingDetection = false
            AppLogger.e(TAG, "启动唤醒监听失败: ${e.message}", e)
            return
        }

        if (wakeListeningJob?.isActive == true) return

        wakeListeningJob =
            serviceScope.launch {
                var lastText = ""
                var lastIsFinal = false
                val provider = ensureWakeSpeechProvider()
                provider.recognitionResultFlow.collectLatest { result ->
                    val text = result.text
                    if (text.isBlank()) return@collectLatest
                    if (text == lastText && result.isFinal == lastIsFinal) return@collectLatest
                    lastText = text
                    lastIsFinal = result.isFinal

                    AppLogger.d(
                        TAG,
                        "唤醒识别输出(${if (result.isFinal) "final" else "partial"}): '$text'"
                    )

                    if (wakeHandoffPending) {
                        val floatingAlive = FloatingChatService.getInstance() != null
                        if (!wakeStopInProgress && !floatingAlive) {
                            AppLogger.d(TAG, "Clearing stale wake handoff pending state (no floating instance)")
                            wakeHandoffPending = false
                            wakeStopInProgress = false
                            pendingWakeTriggeredAtMs = 0L
                            SpeechPrerollStore.clearPendingWakePhrase()
                        } else {
                            return@collectLatest
                        }
                    }

                    try {
                        val now = System.currentTimeMillis()
                        val shouldCheckWorkflows = result.isFinal || now - lastSpeechWorkflowCheckAtMs >= 350L
                        if (shouldCheckWorkflows) {
                            lastSpeechWorkflowCheckAtMs = now
                            workflowRepository.triggerWorkflowsBySpeechEvent(text = text, isFinal = result.isFinal)
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Speech trigger processing failed: ${e.message}", e)
                    }

                    if (matchWakePhrase(text, currentWakePhrase, wakePhraseRegexEnabled)) {
                        val now = System.currentTimeMillis()
                        if (now - lastWakeTriggerAtMs < 3000L) return@collectLatest
                        lastWakeTriggerAtMs = now
                        pendingWakeTriggeredAtMs = now
                        wakeHandoffPending = true
                        wakeStopInProgress = false

                        AppLogger.d(TAG, "命中唤醒词: '$currentWakePhrase' in '$text'")
                        SpeechPrerollStore.setPendingWakePhrase(
                            phrase = currentWakePhrase,
                            regexEnabled = wakePhraseRegexEnabled,
                        )
                        triggerWakeLaunch()
                        scheduleWakeResume()
                    }
                }
            }
    }

    private suspend fun startPersonalWakeListening() {
        if (!wakeListeningEnabled) return
        if (personalWakeJob?.isActive == true) return

        AppLogger.d(TAG, "startPersonalWakeListening: templates=${personalWakeTemplates.size}")
        if (personalWakeTemplates.isEmpty()) {
            AppLogger.w(TAG, "Personal wake listening skipped: no templates")
            return
        }

        wakeListeningMicActiveForRecordingDetection = true

        val listener =
            PersonalWakeListener(
                context = applicationContext,
                templatesProvider = { personalWakeTemplates },
                onTriggered = onTriggered@{ similarity ->
                    val now = System.currentTimeMillis()
                    if (now - lastWakeTriggerAtMs < 3000L) return@onTriggered
                    lastWakeTriggerAtMs = now
                    pendingWakeTriggeredAtMs = now
                    wakeHandoffPending = true
                    wakeStopInProgress = false

                    AppLogger.d(TAG, "命中个人化唤醒: similarity=$similarity")
                    SpeechPrerollStore.setPendingWakePhrase(
                        phrase = currentWakePhrase,
                        regexEnabled = wakePhraseRegexEnabled,
                    )
                    triggerWakeLaunch()
                    scheduleWakeResume()
                }
            )
        personalWakeListener = listener

        personalWakeJob =
            serviceScope.launch {
                try {
                    listener.runLoop()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Personal wake loop failed: ${e.message}", e)
                } finally {
                    wakeListeningMicActiveForRecordingDetection = false
                }
            }
    }

    private suspend fun stopWakeListening(releaseProvider: Boolean = false) {
        wakeStateMutex.withLock {
            stopWakeListeningLocked(releaseProvider = releaseProvider)
        }
    }

    private suspend fun stopWakeListeningLocked(releaseProvider: Boolean = false) {
        AppLogger.d(TAG, "stopWakeListening")
        wakeListeningMicActiveForRecordingDetection = false
        wakeResumeJob?.cancel()
        wakeResumeJob = null

        wakeStateRetryJob?.cancel()
        wakeStateRetryJob = null

        wakeListeningJob?.cancel()
        wakeListeningJob = null

        personalWakeListener?.stop()
        personalWakeListener = null
        personalWakeJob?.cancel()
        personalWakeJob = null

        try {
            wakeSpeechProvider?.cancelRecognition()
        } catch (_: Exception) {
        }

        if (releaseProvider) {
            AppLogger.d(TAG, "Releasing wake speech provider")
            releaseWakeSpeechProvider()
        }
    }

    private fun scheduleWakeResume() {
        AppLogger.d(TAG, "scheduleWakeResume")
        wakeResumeJob?.cancel()
        wakeResumeJob =
            serviceScope.launch {
                var waitedMs = 0L
                while (isActive && waitedMs < 5000L) {
                    if (!wakeListeningEnabled) return@launch
                    if (FloatingChatService.getInstance() != null) break
                    delay(250)
                    waitedMs += 250
                }

                AppLogger.d(TAG, "等待悬浮窗启动: waitedMs=$waitedMs, instance=${FloatingChatService.getInstance() != null}")

                while (isActive) {
                    if (!wakeListeningEnabled) return@launch
                    if (FloatingChatService.getInstance() == null) break
                    delay(500)
                }

                AppLogger.d(TAG, "检测到悬浮窗已关闭，准备恢复唤醒监听")

                if (wakeHandoffPending) {
                    AppLogger.d(TAG, "Wake handoff aborted, clearing pending state")
                    wakeHandoffPending = false
                    wakeStopInProgress = false
                    pendingWakeTriggeredAtMs = 0L
                    SpeechPrerollStore.clearPendingWakePhrase()
                }

                wakeStateMutex.withLock {
                    applyWakeListeningStateLocked()
                }
            }
    }

    private fun triggerWakeLaunch() {
        AppLogger.d(TAG, "triggerWakeLaunch: 打开全屏悬浮窗并进入语音")
        try {
            val floatingIntent = Intent(this, FloatingChatService::class.java).apply {
                putExtra("INITIAL_MODE", com.kymjs.ai.custard.ui.floating.FloatingMode.FULLSCREEN.name)
                putExtra(FloatingChatService.EXTRA_AUTO_ENTER_VOICE_CHAT, true)
                putExtra(FloatingChatService.EXTRA_WAKE_LAUNCHED, true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(floatingIntent)
            } else {
                startService(floatingIntent)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "唤醒打开悬浮窗失败: ${e.message}", e)
        }
    }

    private fun matchWakePhrase(recognized: String, phrase: String, regexEnabled: Boolean): Boolean {
        if (regexEnabled) {
            if (phrase.isBlank()) return false
            return try {
                Regex(phrase).containsMatchIn(recognized)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Invalid wake phrase regex: '$phrase' (${e.message})")
                false
            }
        }

        val target = normalizeWakeText(phrase)
        if (target.isBlank()) return false
        val text = normalizeWakeText(recognized)
        return text.contains(target)
    }

    private fun normalizeWakeText(text: String): String {
        val cleaned =
            text
                .lowercase()
                .replace(
                    Regex("[\\s\\p{Punct}，。！？；：、“”‘’【】（）()\\[\\]{}<>《》]+"),
                    ""
                )
        return cleaned
    }

    private fun createNotification(): Notification {
        // 为了简单起见，使用一个安卓内置图标。
        // 在实际项目中，应替换为应用的自定义图标。
        val wakeListeningEnabledSnapshot = wakeListeningEnabled
        val wakeListeningSuspendedSnapshot = wakeListeningSuspendedForIme || wakeListeningSuspendedForExternalRecording || wakeListeningSuspendedForFloatingFullscreen
        val title =
            if (isAiBusy) {
                characterName ?: getString(R.string.service_custard_running)
            } else {
                if (wakeListeningEnabledSnapshot) {
                    if (wakeListeningSuspendedSnapshot) {
                        getString(R.string.service_running_wake_pause)
                    } else {
                        getString(R.string.service_running_wake_listening)
                    }
                } else {
                    getString(R.string.service_custard_running)
                }
            }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.service_custard_running))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // 使通知不可被用户清除

        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (contentIntent != null) {
            val contentPendingIntent = PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
            builder.setContentIntent(contentPendingIntent)
        }

        val floatingIntent = Intent(this, FloatingChatService::class.java).apply {
            putExtra("INITIAL_MODE", com.kymjs.ai.custard.ui.floating.FloatingMode.FULLSCREEN.name)
            putExtra(FloatingChatService.EXTRA_AUTO_ENTER_VOICE_CHAT, true)
        }
        val floatingPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                9005,
                floatingIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this,
                9005,
                floatingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        builder.addAction(
            android.R.drawable.ic_btn_speak_now,
            getString(R.string.service_voice_floating_window),
            floatingPendingIntent
        )

        val toggleWakeIntent = Intent(this, AIForegroundService::class.java).apply {
            action = ACTION_TOGGLE_WAKE_LISTENING
        }
        val toggleWakePendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_TOGGLE_WAKE_LISTENING,
            toggleWakeIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        builder.addAction(
            android.R.drawable.ic_lock_silent_mode_off,
            if (wakeListeningEnabledSnapshot) {
                getString(R.string.service_turn_off_wake)
            } else {
                getString(R.string.service_turn_on_wake)
            },
            toggleWakePendingIntent
        )

        val exitIntent = Intent(this, AIForegroundService::class.java).apply {
            action = ACTION_EXIT_APP
        }
        val exitPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_EXIT_APP,
            exitIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.service_exit),
            exitPendingIntent
        )

        if (isAiBusy) {
            val cancelIntent = Intent(this, AIForegroundService::class.java).apply {
                action = ACTION_CANCEL_CURRENT_OPERATION
            }
            val pendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_CANCEL_CURRENT_OPERATION,
                cancelIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.service_stop),
                pendingIntent
            )
        }

        return builder.build()
    }
    
    /**
     * 如果用户启用了回复通知，则发送AI回复完成通知
     */
    private fun sendReplyNotificationIfEnabled() {
        try {
            AppLogger.d(TAG, "检查是否需要发送回复通知...")
            
            // 检查应用是否在前台
            val isAppInForeground = ActivityLifecycleManager.getCurrentActivity() != null
            if (isAppInForeground) {
                AppLogger.d(TAG, "应用在前台，无需发送通知")
                return
            }
            
            // 检查用户是否启用了回复通知
            val displayPreferences = DisplayPreferencesManager.getInstance(applicationContext)
            val enableReplyNotification = runBlocking {
                displayPreferences.enableReplyNotification.first()
            }
            
            if (!enableReplyNotification) {
                AppLogger.d(TAG, "回复通知已禁用，跳过发送")
                return
            }
            
            val rawReplyContent = replyContent
            if (rawReplyContent.isNullOrBlank()) {
                AppLogger.d(TAG, "回复内容为空，跳过发送回复通知")
                return
            }

            AppLogger.d(TAG, "准备发送AI回复通知...")
            
            // 清理回复内容，移除思考内容等
            val cleanedReplyContent = WaifuMessageProcessor.cleanContentForWaifu(rawReplyContent)
            
            // 创建点击通知后打开应用的Intent
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
            
            // 构建通知
            val notificationBuilder = NotificationCompat.Builder(this, REPLY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(characterName ?: getString(R.string.notification_ai_reply_title))
                .setContentText(cleanedReplyContent.take(100).ifEmpty { getString(R.string.notification_ai_reply_content) })
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true) // 点击后自动消失
            
            // 如果有完整内容，使用BigTextStyle显示更多文本
            if (cleanedReplyContent.isNotEmpty()) {
                notificationBuilder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(cleanedReplyContent)
                        .setBigContentTitle(characterName ?: getString(R.string.notification_ai_reply_title))
                )
            }
            
            // 如果有头像，设置大图标
            val avatarUriString = avatarUri
            if (!avatarUriString.isNullOrEmpty()) {
                try {
                    val bitmap = loadBitmapFromUri(avatarUriString)
                    if (bitmap != null) {
                        notificationBuilder.setLargeIcon(bitmap)
                        AppLogger.d(TAG, "成功加载头像到通知")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "加载头像失败: ${e.message}", e)
                }
            }
            
            val notification = notificationBuilder.build()
            
            // 发送通知
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(REPLY_NOTIFICATION_ID, notification)
            AppLogger.d(TAG, "AI回复通知已发送 (ID: $REPLY_NOTIFICATION_ID)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "发送AI回复通知失败: ${e.message}", e)
        }
    }
    
    /**
     * 从URI加载Bitmap
     */
    private fun loadBitmapFromUri(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            val inputStream: InputStream? =
                when (uri.scheme) {
                    "file" -> {
                        val path = uri.path
                        if (path != null && path.startsWith("/android_asset/")) {
                            assets.open(path.removePrefix("/android_asset/"))
                        } else if (!path.isNullOrEmpty()) {
                            FileInputStream(path)
                        } else {
                            null
                        }
                    }
                    null -> {
                        if (uriString.startsWith("/android_asset/")) {
                            assets.open(uriString.removePrefix("/android_asset/"))
                        } else {
                            try {
                                FileInputStream(uriString)
                            } catch (_: Exception) {
                                contentResolver.openInputStream(uri)
                            }
                        }
                    }
                    else -> contentResolver.openInputStream(uri)
                }
            inputStream?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "从URI加载Bitmap失败: ${e.message}", e)
            null
        }
    }
}
