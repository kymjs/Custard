package com.ai.assistance.custard.terminal.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.ai.assistance.custard.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.custard.terminal.provider.type.TerminalType
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * 队列中的命令项
 */
data class QueuedCommand(
    val id: String,
    val command: String
)

/**
 * 命令历史项数据类
 */
class CommandHistoryItem(
    val id: String,
    prompt: String,
    command: String,
    output: String,
    isExecuting: Boolean = false
) {
    private var _prompt by mutableStateOf(prompt)
    private var _command by mutableStateOf(command)
    private var _output by mutableStateOf(output)
    private var _isExecuting by mutableStateOf(isExecuting)
    
    val outputPages = mutableStateListOf<String>()
    
    // 为AIDL序列化提供稳定的getter
    val prompt: String get() = _prompt
    val command: String get() = _command
    val output: String get() = _output
    val isExecuting: Boolean get() = _isExecuting
    
    // 为UI更新提供setter方法
    fun setPrompt(value: String) { _prompt = value }
    fun setCommand(value: String) { _command = value }
    fun setOutput(value: String) { _output = value }
    fun setExecuting(value: Boolean) { _isExecuting = value }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CommandHistoryItem
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "CommandHistoryItem(id='$id', prompt='$prompt', command='$command', output='${output.take(20)}...', isExecuting=$isExecuting)"
    }
}

/**
 * 会话初始化状态枚举
 */
enum class SessionInitState {
    INITIALIZING,
    LOGGED_IN,
    AWAITING_FIRST_PROMPT,
    READY
}

/**
 * 终端会话数据类
 */
data class TerminalSessionData(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val terminalType: TerminalType = TerminalType.LOCAL,
    val terminalSession: com.ai.assistance.custard.terminal.TerminalSession? = null,
    val pty: com.ai.assistance.custard.terminal.Pty? = null, // PTY 对象，用于获取终端模式
    val sessionWriter: OutputStreamWriter? = null,
    val currentDirectory: String = "$ ",
    @Transient val currentCommandOutput: StringBuilder = StringBuilder(),
    @Transient val rawBuffer: StringBuilder = StringBuilder(),
    val isWaitingForInteractiveInput: Boolean = false,
    val lastInteractivePrompt: String = "",
    val isInteractiveMode: Boolean = false,
    val interactivePrompt: String = "",
    val initState: SessionInitState = SessionInitState.INITIALIZING,
    val readJob: Job? = null,
    val isFullscreen: Boolean = false,
    @Transient val ansiParser: AnsiTerminalEmulator = AnsiTerminalEmulator(),
    @Transient var currentExecutingCommand: CommandHistoryItem? = null,
    @Transient var currentOutputLineCount: Int = 0,
    @Transient val commandQueue: MutableList<QueuedCommand> = mutableListOf(),
    @Transient val commandMutex: Mutex = Mutex(),
    // 保存每个会话的滚动位置
    var scrollOffsetY: Float = 0f
) {
    val isInitializing: Boolean
        get() = initState != SessionInitState.READY
}

/**
 * 终端状态数据类
 */
data class TerminalState(
    val sessions: List<TerminalSessionData> = emptyList(),
    val currentSessionId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val currentSession: TerminalSessionData?
        get() = currentSessionId?.let { sessionId ->
            sessions.find { it.id == sessionId }
        }
}

/**
 * 包管理器类型
 */
enum class PackageManagerType(val displayName: String) {
    APT("APT"),
    PIP("Pip/Uv"),
    NPM("NPM"),
    RUST("Rust/Cargo")
}

/**
 * 镜像源信息
 */
@Serializable
data class MirrorSource(
    val id: String,
    val name: String,
    val url: String,
    val isHttps: Boolean = false
)

/**
 * 源配置
 */
data class SourceConfig(
    val packageManager: PackageManagerType,
    val selectedSourceId: String,
    val sources: List<MirrorSource>
) 