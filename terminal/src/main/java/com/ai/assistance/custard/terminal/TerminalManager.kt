package com.ai.assistance.custard.terminal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import com.ai.assistance.custard.terminal.data.TerminalState
import com.ai.assistance.custard.terminal.data.CommandHistoryItem
import com.ai.assistance.custard.terminal.data.QueuedCommand
import com.ai.assistance.custard.terminal.view.domain.OutputProcessor
import java.util.UUID
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import com.ai.assistance.custard.terminal.data.PackageManagerType
import com.ai.assistance.custard.terminal.data.SessionInitState
import com.ai.assistance.custard.terminal.utils.SourceManager
import com.ai.assistance.custard.terminal.utils.SSHConfigManager
import com.ai.assistance.custard.terminal.utils.SSHDServerManager
import com.ai.assistance.custard.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.custard.terminal.provider.filesystem.LocalFileSystemProvider
import com.ai.assistance.custard.terminal.provider.type.TerminalProvider
import com.ai.assistance.custard.terminal.provider.type.TerminalType
import com.ai.assistance.custard.terminal.provider.type.LocalTerminalProvider
import com.ai.assistance.custard.terminal.provider.type.SSHTerminalProvider
import com.ai.assistance.custard.terminal.data.TerminalSessionData
import com.ai.assistance.custard.terminal.view.domain.ansi.AnsiTerminalEmulator

class TerminalManager private constructor(
    private val context: Context
) {
    internal val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val envInitMutex = Mutex()
    private var isEnvInitialized = false

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    
    // SharedPreferences for reading settings
    private val prefs = context.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)

    // 核心组件
    private val sessionManager = SessionManager(this)
    private val outputProcessor = OutputProcessor(
        onCommandExecutionEvent = { event ->
            coroutineScope.launch {
                _commandExecutionEvents.emit(event)
            }
        },
        onDirectoryChangeEvent = { event ->
            coroutineScope.launch {
                _directoryChangeEvents.emit(event)
            }
        },
        onCommandCompleted = { sessionId ->
            coroutineScope.launch {
                processNextQueuedCommand(sessionId)
            }
        }
    )
    private val sourceManager = SourceManager(context)
    private val sshConfigManager = SSHConfigManager(context)
    private val sshdServerManager = SSHDServerManager.getInstance(context)
    
    // 单例的 TerminalProvider
    private var terminalProvider: TerminalProvider? = null
    private val providerMutex = Mutex()

    // 状态和事件流
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>()
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()

    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>()
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()

    // 暴露会话管理器的状态
    val terminalState: StateFlow<TerminalState> = sessionManager.state

    // 为了向后兼容，提供单独的状态流
    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "$ " }
    val isInteractiveMode = terminalState.map { it.currentSession?.isInteractiveMode ?: false }
    val interactivePrompt = terminalState.map { it.currentSession?.interactivePrompt ?: "" }
    val isFullscreen = terminalState.map { it.currentSession?.isFullscreen ?: false }
    val terminalEmulator = terminalState.map { it.currentSession?.ansiParser ?: AnsiTerminalEmulator() }

    companion object {
        @Volatile
        private var INSTANCE: TerminalManager? = null

        fun getInstance(context: Context): TerminalManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "TerminalManager"
        private const val UBUNTU_FILENAME = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        private const val MAX_HISTORY_ITEMS = 500
        private const val MAX_OUTPUT_LINES_PER_ITEM = 1000
    }

    init {
        // 在初始化时异步创建默认session
        coroutineScope.launch {
            try {
                Log.d(TAG, "Creating default session...")
                // 自动检测：如果有SSH配置则创建SSH会话，否则创建本地会话
                val sshConfig = sshConfigManager.getConfig()
                val isEnabled = sshConfigManager.isEnabled()
                if (sshConfig != null && isEnabled) {
                    Log.d(TAG, "Found SSH config, creating SSH session")
                    createNewSession("SSH")
                } else {
                    Log.d(TAG, "No SSH config, creating local session")
                    createNewSession("Local")
                }
                Log.d(TAG, "Default session created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create default session", e)
            }
        }
    }

    /**
     * 创建新会话 - 同步等待初始化完成
     * 自动检测终端类型：如果配置了SSH则使用SSH，否则使用本地终端
     * 
     * @param title 会话标题
     */
    suspend fun createNewSession(
        title: String? = null
    ): TerminalSessionData {
        // 自动检测终端类型
        val terminalType = if (sshConfigManager.getConfig() != null && sshConfigManager.isEnabled()) {
            TerminalType.SSH
        } else {
            TerminalType.LOCAL
        }
        
        val newSession = sessionManager.createNewSession(title, terminalType)

        // 异步初始化会话
        coroutineScope.launch {
            initializeSession(newSession.id)
        }

        // 等待会话初始化完成
        val success = withTimeoutOrNull(30000) { // 30秒超时
            terminalState.first { state ->
                val session = state.sessions.find { it.id == newSession.id }
                session?.initState == com.ai.assistance.custard.terminal.data.SessionInitState.READY
            }
        }

        if (success == null) {
            Log.e(TAG, "Session initialization timeout for session: ${newSession.id}")
            // 初始化失败，移除会话
            sessionManager.closeSession(newSession.id)
            throw Exception("Session initialization timeout")
        }

        Log.d(TAG, "Session ${newSession.id} initialized successfully")
        return sessionManager.getSession(newSession.id) ?: newSession
    }

    /**
     * 切换到会话
     */
    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }

    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    /**
     * 会话关闭后的运行态清理（非持久化状态）。
     */
    fun onSessionClosed(sessionId: String) {
        outputProcessor.clearSessionState(sessionId)
    }
    
    /**
     * 保存会话的滚动位置
     */
    fun saveScrollOffset(sessionId: String, scrollOffset: Float) {
        sessionManager.saveScrollOffset(sessionId, scrollOffset)
    }
    
    /**
     * 获取会话的滚动位置
     */
    fun getScrollOffset(sessionId: String): Float {
        return sessionManager.getScrollOffset(sessionId)
    }

    private fun createBusyboxSymlinks() {
        val links = listOf(
            "awk", "ash", "basename", "bzip2", "curl", "cp", "chmod", "cut", "cat", "du", "dd",
            "find", "grep", "gzip", "hexdump", "head", "id", "lscpu", "mkdir", "realpath", "rm",
            "sed", "stat", "sh", "tr", "tar", "uname", "xargs", "xz", "xxd"
        )
        val busybox = File(binDir, "busybox")
        for (linkName in links) {
            try {
                createSymbolicLink(busybox, linkName, binDir, true)
                Log.d(TAG, "Created busybox link for '$linkName'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create link for '$linkName'", e)
            }
        }
        try {
            val fileLink = File(binDir, "file")
            if (!fileLink.exists()) {
                Files.createSymbolicLink(fileLink.toPath(), File("/system/bin/file").toPath())
                Log.d(TAG, "Created symlink for 'file'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create symlink for 'file'", e)
        }
    }

    /**
     * 发送命令
     */
    suspend fun sendCommand(command: String, commandId: String? = null): String {
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getCurrentSession() ?: return actualCommandId

        // Allow input during initialization (e.g. password prompt) or interactive mode
        val isInitializing = session.initState != SessionInitState.READY
        
        if (session.isInteractiveMode || isInitializing) {
            Log.d(TAG, "Session in interactive mode or initializing, sending as input: $command")
            sendInput(command + "\n")
            return actualCommandId
        }

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                // 有命令正在执行，将新命令加入队列
                session.commandQueue.add(QueuedCommand(actualCommandId, command))
                Log.d(TAG, "Command queued: $command (id: $actualCommandId). Queue size: ${session.commandQueue.size}")
            } else {
                // 没有命令在执行，直接执行
                executeCommandInternal(command, session, actualCommandId)
            }
        }
        return actualCommandId
    }

    /**
     * 向指定会话发送命令（不切换当前会话）
     */
    suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String? = null): String {
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getSession(sessionId) ?: return actualCommandId

        // 如果会话在交互模式，直接发送输入（不创建命令历史）
        if (session.isInteractiveMode) {
            Log.d(TAG, "Session $sessionId in interactive mode, sending as input: $command")
            try {
                session.sessionWriter?.write(command + "\n")
                session.sessionWriter?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input to session $sessionId", e)
            }
            return actualCommandId
        }

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                // 有命令正在执行，将新命令加入队列
                session.commandQueue.add(QueuedCommand(actualCommandId, command))
                Log.d(TAG, "Command queued for session $sessionId: $command (id: $actualCommandId). Queue size: ${session.commandQueue.size}")
            } else {
                // 没有命令在执行，直接执行
                executeCommandInternal(command, session, actualCommandId)
            }
        }
        return actualCommandId
    }

    /**
     * 处理队列中的下一个命令
     */
    private suspend fun processNextQueuedCommand(sessionId: String) {
        val session = sessionManager.getSession(sessionId) ?: return

        session.commandMutex.withLock {
            if (session.currentExecutingCommand?.isExecuting == true) {
                Log.w(TAG, "processNextQueuedCommand called, but a command is still executing. This should not happen.")
                return@withLock
            }

            if (session.commandQueue.isNotEmpty()) {
                val nextCommand = session.commandQueue.removeAt(0)
                Log.d(TAG, "Processing next queued command: ${nextCommand.command} (id: ${nextCommand.id}). Queue size: ${session.commandQueue.size}")
                executeCommandInternal(nextCommand.command, session, nextCommand.id)
            }
        }
    }

    /**
     * 内部执行命令的函数, 必须在 commandMutex 锁内部调用
     */
    private suspend fun executeCommandInternal(command: String, session: TerminalSessionData, commandId: String) {
        if (command.trim() == "clear") {
            try {
                session.sessionWriter?.write("clear\n")
                session.sessionWriter?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending 'clear' command", e)
            }
        } else {
            handleRegularCommand(command, session, commandId)
            try {
                val fullInput = "$command\n"
                session.sessionWriter?.write(fullInput)
                session.sessionWriter?.flush()
                Log.d(TAG, "Sent command to PTY: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending command", e)
            }
        }
    }

    /**
     * 发送输入
     */
    fun sendInput(input: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val session = sessionManager.getCurrentSession() ?: return@launch

            try {
                session.sessionWriter?.write(input)
                session.sessionWriter?.flush()
                Log.d(TAG, "Sent input: '$input'")

                // 如果用户提供了交互式输入，则重置等待状态
                if (session.isWaitingForInteractiveInput) {
                    sessionManager.updateSession(session.id) {
                        it.copy(isWaitingForInteractiveInput = false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input", e)
            }
        }
    }

    /**
     * 发送中断信号
     */
    fun sendInterruptSignal() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val currentSession = sessionManager.getCurrentSession()
                currentSession?.sessionWriter?.apply {
                    write(3) // ETX character (Ctrl+C)
                    flush()
                    Log.d(TAG, "Sent interrupt signal (Ctrl+C) to session ${currentSession.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending interrupt signal", e)
                val currentSession = sessionManager.getCurrentSession()
            }
        }
    }

    private fun initializeSession(sessionId: String) {
        coroutineScope.launch {
            val success = initializeEnvironment()
            if (success) {
                startSession(sessionId)
            }
        }
    }

    private fun startSession(sessionId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting session...")

                // 获取单例的终端提供者
                val provider = getTerminalProvider()

                // 启动终端会话
                val result = provider.startSession(sessionId)
                val (terminalSession, pty) = result.getOrThrow()
                val sessionWriter = terminalSession.stdin.writer()

                // 启动读取协程
                val readJob = launch {
                    try {
                        terminalSession.stdout.use { inputStream ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                val chunk = String(buffer, 0, bytesRead)
                                Log.d(TAG, "Read chunk: '$chunk'")
                                outputProcessor.processOutput(sessionId, chunk, sessionManager)
                            }
                        }
                    } catch (e: java.io.InterruptedIOException) {
                        Log.i(TAG, "Read job interrupted for session $sessionId.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in read job for session $sessionId", e)
                    }
                }

                // 更新会话信息
                sessionManager.updateSession(sessionId) { session ->
                    session.copy(
                        terminalSession = terminalSession,
                        pty = pty,
                        sessionWriter = sessionWriter,
                        readJob = readJob
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting session", e)
            }
        }
    }
    
    /**
     * 获取或创建单例的终端提供者
     */
    private suspend fun getTerminalProvider(): TerminalProvider {
        providerMutex.withLock {
            if (terminalProvider == null) {
                val sshConfig = sshConfigManager.getConfig()
                val provider = if (sshConfig != null && sshConfigManager.isEnabled()) {
                    Log.d(TAG, "Creating singleton SSH terminal provider")
                    SSHTerminalProvider(context, sshConfig, this)
                } else {
                    Log.d(TAG, "Creating singleton local terminal provider")
                    LocalTerminalProvider(context)
                }
                provider.connect().getOrThrow()
                terminalProvider = provider
            }
        }
        return terminalProvider!!
    }

    suspend fun initializeEnvironment(): Boolean {
        if (isEnvInitialized) {
            return withContext(Dispatchers.IO) {
                try {
                    val startScript = generateStartScript()
                    File(filesDir, "common.sh").writeText(startScript.replace("\r\n", "\n").replace("\r", "\n"))
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Environment script refresh failed", e)
                    false
                }
            }
        }

        envInitMutex.lock()
        try {
            if (isEnvInitialized) {
                return true
            }

            val success = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Starting environment initialization...")

                    // 1. Create necessary directories
                    createDirectories()

                    // 2. Link native libraries
                    linkNativeLibs()
                    createBusyboxSymlinks()

                    // 3. Extract assets
                    extractAssets()

                    // 4. Generate and write startup script
                    val startScript = generateStartScript()
                    File(filesDir, "common.sh").writeText(startScript.replace("\r\n", "\n").replace("\r", "\n"))


                    Log.d(TAG, "Environment initialization completed successfully.")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Environment initialization failed", e)
                    false
                }
            }
            if (success) {
                isEnvInitialized = true
            }
            return success
        } finally {
            envInitMutex.unlock()
        }
    }

    private fun createDirectories() {
        if (!usrDir.exists()) {
            usrDir.mkdirs()
        }
        if (!binDir.exists()) {
            binDir.mkdirs()
            Log.d(TAG, "Created bin directory at: ${binDir.absolutePath}")
        }
        File(filesDir, "tmp").mkdirs()
    }

    private fun linkNativeLibs() {
        Log.d(TAG, "Linking native libraries from: $nativeLibDir")

        val nativeLibDirFile = File(nativeLibDir)
        if (!nativeLibDirFile.exists() || !nativeLibDirFile.isDirectory) {
            Log.e(TAG, "Native library directory not found or is not a directory.")
            return
        }

        Log.d(TAG, "Native lib directory contents:")
        nativeLibDirFile.listFiles()?.forEach { file ->
            Log.d(TAG, "  - ${file.name} (file ${file.length()} bytes)")
        }

        val busybox = File(binDir, "busybox")

        // First, we need to link busybox itself so we can use it.
        val busyboxSo = File(nativeLibDir, "libbusybox.so")
        Log.d(TAG, "Checking busybox: libbusybox.so exists = ${busyboxSo.exists()}, busybox exists = ${busybox.exists()}")

        if (!busyboxSo.exists()) {
            Log.e(TAG, "libbusybox.so not found, cannot create busybox link")
            return
        }

        // Always ensure proper busybox link - remove any existing file/broken link first
        try {
            val link = busybox.toPath()
            val target = busyboxSo.toPath()

            // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
            Files.deleteIfExists(link)

            // CRITICAL: Set execute permission on the target .so file before creating symlink
            busyboxSo.setExecutable(true, false)

            // Create the symbolic link
            Files.createSymbolicLink(link, target)
            Log.d(TAG, "Created busybox symbolic link using Java NIO")

            // Verify the link was created successfully and is functional
            if (busybox.exists() && busybox.canExecute()) {
                Log.d(TAG, "Verification: busybox link exists and is executable at ${busybox.absolutePath}")
            } else {
                Log.e(TAG, "Verification failed: busybox link not functional after creation")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create busybox link using Java NIO", e)
            return
        }

        // Symlink other binaries
        val libraries = mapOf(
            "libproot.so" to "proot",
            "libloader.so" to "loader",
            "liblibtalloc.so.2.so" to "libtalloc.so.2", // Keep .so extension for libs
            "libbash.so" to "bash",
            "libsudo.so" to "sudo"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(nativeLibDir, libName)
            val linkFile = File(binDir, linkName)

            Log.d(TAG, "Checking $libName at ${libFile.absolutePath}, exists: ${libFile.exists()}")

            if (!libFile.exists()) {
                Log.w(TAG, "Native library not found: $libName")
                return@forEach
            }

            // Always ensure proper link - remove any existing file/broken link first
            try {
                val link = linkFile.toPath()
                val target = libFile.toPath()

                // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
                Files.deleteIfExists(link)

                // CRITICAL: Set execute permission on the target .so file before creating symlink
                libFile.setExecutable(true, false)

                // Create the symbolic link
                Files.createSymbolicLink(link, target)
                Log.d(TAG, "Created $linkName symbolic link using Java NIO")

                // Verify the link was created successfully and is executable
                if (linkFile.exists() && linkFile.canExecute()) {
                    Log.d(TAG, "Verification: $linkName link exists and is executable at ${linkFile.absolutePath}")
                } else {
                    Log.w(TAG, "Verification failed: $linkName link not executable after creation")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create $linkName link using Java NIO", e)
            }
        }
    }

    @Throws(IOException::class)
    private fun createSymbolicLink(target: File, linkName: String, linkDir: File, force: Boolean) {
        val linkFile = File(linkDir, linkName)

        // Use relative path for target if it's in the same directory
        val targetPath = if (target.parentFile == linkDir) {
            Paths.get(target.name)
        } else {
            target.toPath()
        }

        if (force) {
            Files.deleteIfExists(linkFile.toPath())
        }
        Files.createSymbolicLink(linkFile.toPath(), targetPath)
    }

    private fun extractAssets() {
        try {
            val assets = listOf(
                UBUNTU_FILENAME,
                "setup_fake_sysdata.sh"
            )
            assets.forEach { assetName ->
                val assetFile = File(filesDir, assetName)
                // 强制更新脚本文件，大文件只在不存在时提取
                val shouldExtract = !assetFile.exists() || assetName == "setup_fake_sysdata.sh"

                if (shouldExtract) {
                    if (assetName.endsWith(".sh")) {
                        context.assets.open(assetName).use { input ->
                            val raw = input.readBytes()
                            val text = raw.toString(Charsets.UTF_8)
                            val normalized =
                                text
                                    .removePrefix("\uFEFF")
                                    .replace("\r\n", "\n")
                                    .replace("\r", "\n")
                            assetFile.writeText(normalized, Charsets.UTF_8)
                        }
                    } else {
                        context.assets.open(assetName).use { input ->
                            assetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    Log.d(TAG, "Extracted $assetName")
                } else {
                    Log.d(TAG, "Asset $assetName already exists.")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract assets", e)
            throw e
        }
    }

    private fun generateStartScript(): String {
        val ubuntuName = UBUNTU_FILENAME.replace(Regex("-pd.*"), "")
        val tmpDir = File(filesDir, "tmp").absolutePath
        val binDir = binDir.absolutePath
        val homeDir = filesDir.absolutePath
        val usrDir = usrDir.absolutePath
        val prootDistroPath = "$usrDir/var/lib/proot-distro"
        val ubuntuPath = "$prootDistroPath/installed-rootfs/ubuntu"

        // 获取当前选择的源
        val aptSource = sourceManager.getSelectedSource(PackageManagerType.APT)
        val pipSource = sourceManager.getSelectedSource(PackageManagerType.PIP)
        val npmSource = sourceManager.getSelectedSource(PackageManagerType.NPM)
        val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)

        val common = """
        export TMPDIR=$tmpDir
        export BIN=$binDir
        export HOME=$homeDir
        export UBUNTU_PATH=$ubuntuPath
        export UBUNTU=$UBUNTU_FILENAME
        export UBUNTU_NAME=$ubuntuName
        export USE_CHROOT=${if (prefs.getBoolean("chroot_enabled", false)) "1" else "0"}
        export OPERIT_UID=$(id -u)
        export OPERIT_GID=$(id -g)
        export OPERIT_GROUPS=$(id -G | tr ' ' ',')
        export L_NOT_INSTALLED="not installed"
        export L_INSTALLING="installing"
        export L_INSTALLED="installed"
        clear_lines(){
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
        }
        progress_echo(){
          echo -e "\\033[31m- ${'$'}@\\033[0m"
          echo "${'$'}@" > "${'$'}TMPDIR/progress_des"
        }
        bump_progress(){
          current=0
          if [ -f "${'$'}TMPDIR/progress" ]; then
            current=${'$'}(cat "${'$'}TMPDIR/progress" 2>/dev/null || echo 0)
          fi
          next=${'$'}((current + 1))
          printf "${'$'}next" > "${'$'}TMPDIR/progress"
        }
        """.trimIndent()

        val installUbuntu = """
        install_ubuntu(){
          OK_FILE="${'$'}UBUNTU_PATH/.operit_installed_ok"
          LOCK_DIR="${'$'}UBUNTU_PATH.install.lock"
          LOCK_PID_FILE="${'$'}LOCK_DIR/pid"
          TMP_DIR="${'$'}UBUNTU_PATH.install.tmp"

          UBUNTU_PARENT="${'$'}{UBUNTU_PATH%/*}"
          mkdir -p "${'$'}UBUNTU_PARENT" 2>/dev/null

          attempt=0
          while true; do
            if mkdir "${'$'}LOCK_DIR" 2>/dev/null; then
              echo "${'$'}${'$'}" > "${'$'}LOCK_PID_FILE" 2>/dev/null || true
              break
            fi

            if [ -f "${'$'}LOCK_PID_FILE" ]; then
              lock_pid=${'$'}(cat "${'$'}LOCK_PID_FILE" 2>/dev/null)
              if [ -z "${'$'}lock_pid" ]; then
                if [ "${'$'}attempt" -gt 2 ]; then
                  rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                  continue
                fi
              elif ! kill -0 "${'$'}lock_pid" 2>/dev/null; then
                rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                continue
              fi
            else
              if [ "${'$'}attempt" -gt 2 ]; then
                rm -rf "${'$'}LOCK_DIR" 2>/dev/null
                continue
              fi
            fi

            attempt=${'$'}((attempt + 1))
            if [ "${'$'}attempt" -gt 120 ]; then
              progress_echo "Ubuntu install lock timeout"
              return 1
            fi
            sleep 1
          done

          cleanup_install(){
            rm -rf "${'$'}TMP_DIR" 2>/dev/null
            rm -rf "${'$'}LOCK_DIR" 2>/dev/null
          }
          trap 'cleanup_install' EXIT INT TERM

          if [ -f "${'$'}OK_FILE" ]; then
            VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
            progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
          else
            if [ -f "${'$'}UBUNTU_PATH/etc/issue.net" ]; then
              echo "ok" > "${'$'}OK_FILE" 2>/dev/null || true
              VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
              progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
            else
              progress_echo "Ubuntu ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
              if [ ! -f "${'$'}HOME/${'$'}UBUNTU" ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              rm -rf "${'$'}TMP_DIR" 2>/dev/null
              mkdir -p "${'$'}TMP_DIR" 2>/dev/null
              progress_echo "Extracting Ubuntu rootfs..."
              busybox tar xf "${'$'}HOME/${'$'}UBUNTU" -C "${'$'}TMP_DIR"/ >/dev/null 2>&1
              if [ ${'$'}? -ne 0 ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              echo "Extraction complete"
              if [ -d "${'$'}TMP_DIR/${'$'}UBUNTU_NAME" ]; then
                mv "${'$'}TMP_DIR/${'$'}UBUNTU_NAME"/* "${'$'}TMP_DIR"/ 2>/dev/null
                rm -rf "${'$'}TMP_DIR/${'$'}UBUNTU_NAME" 2>/dev/null
              fi

              mkdir -p "${'$'}TMP_DIR/root" 2>/dev/null
              echo 'export ANDROID_DATA=/home/' >> "${'$'}TMP_DIR/root/.bashrc"
              mkdir -p "${'$'}TMP_DIR/etc" 2>/dev/null
              echo 'nameserver 8.8.8.8' > "${'$'}TMP_DIR/etc/resolv.conf"
              echo "ok" > "${'$'}TMP_DIR/.operit_installed_ok" 2>/dev/null || true

              rm -rf "${'$'}UBUNTU_PATH" 2>/dev/null
              mv "${'$'}TMP_DIR" "${'$'}UBUNTU_PATH" 2>/dev/null
              if [ ${'$'}? -ne 0 ]; then
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
              rm -f "${'$'}HOME/${'$'}UBUNTU" 2>/dev/null
            fi
          fi

          mkdir -p ${'$'}UBUNTU_PATH/etc 2>/dev/null
          echo 'nameserver 8.8.8.8' > ${'$'}UBUNTU_PATH/etc/resolv.conf

          rm -rf "${'$'}LOCK_DIR" 2>/dev/null
          trap - EXIT INT TERM
        }
        """.trimIndent()

        val configureSources = """
        configure_sources(){
          # 配置APT源
          cat <<'EOF' > ${'$'}UBUNTU_PATH/etc/apt/sources.list
        # From Operit Settings - ${aptSource.name}
        deb ${aptSource.url} noble main restricted universe multiverse
        deb ${aptSource.url} noble-updates main restricted universe multiverse
        deb ${aptSource.url} noble-backports main restricted universe multiverse
        EOF
          
          # 配置Pip/Uv源
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/pip 2>/dev/null
          echo '[global]' > ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          echo 'index-url = ${pipSource.url}' >> ${'$'}UBUNTU_PATH/root/.config/pip/pip.conf
          
          mkdir -p ${'$'}UBUNTU_PATH/root/.config/uv 2>/dev/null
          echo 'index-url = "${pipSource.url}"' > ${'$'}UBUNTU_PATH/root/.config/uv/uv.toml
          
          # 配置NPM源
          mkdir -p ${'$'}UBUNTU_PATH/root 2>/dev/null
          echo 'registry=${npmSource.url}' > ${'$'}UBUNTU_PATH/root/.npmrc
        }
        """.trimIndent()

        val fixPermissions = """
        fix_permissions(){
          echo "Fixing permissions..."
          # Fix "cannot find name for group ID" warnings
          # Append Android groups to Ubuntu /etc/group
          current_groups=$(id -G)
          for gid in ${'$'}current_groups; do
            if ! grep -q ":${'$'}gid:" ${'$'}UBUNTU_PATH/etc/group; then
              echo "android_group_${'$'}gid:x:${'$'}gid:" >> ${'$'}UBUNTU_PATH/etc/group
            fi
          done
          echo "Permissions fixed."
        }
        """.trimIndent()

        // 读取共享tmp设置
        val sharedTmpEnabled = prefs.getBoolean("shared_tmp_enabled", true)
        val tmpBindingLine = if (sharedTmpEnabled) {
            """-b "${'$'}TMPDIR":/dev/shm \"""
        } else {
            ""
        }
        
        val loginUbuntu = """
        login_ubuntu(){
          COMMAND_TO_EXEC="$1"
          if [ -z "${'$'}COMMAND_TO_EXEC" ]; then
            COMMAND_TO_EXEC="/bin/bash -il"
          fi

          # Setup fake sysdata
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            export INSTALLED_ROOTFS_DIR=$(dirname "${'$'}UBUNTU_PATH")
            export distro_name=$(basename "${'$'}UBUNTU_PATH")
            
            if [ -f "${'$'}HOME/setup_fake_sysdata.sh" ]; then
                source "${'$'}HOME/setup_fake_sysdata.sh"
                setup_fake_sysdata
            fi
          fi

          # Prepare extra bindings for missing proc files
          PROOT_EXTRA_BINDINGS=""
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            if [ ! -e /proc/stat ]; then PROOT_EXTRA_BINDINGS="${'$'}PROOT_EXTRA_BINDINGS -b ${'$'}UBUNTU_PATH/proc/.stat:/proc/stat"; fi
            if [ ! -e /proc/loadavg ]; then PROOT_EXTRA_BINDINGS="${'$'}PROOT_EXTRA_BINDINGS -b ${'$'}UBUNTU_PATH/proc/.loadavg:/proc/loadavg"; fi
            if [ ! -e /proc/uptime ]; then PROOT_EXTRA_BINDINGS="${'$'}PROOT_EXTRA_BINDINGS -b ${'$'}UBUNTU_PATH/proc/.uptime:/proc/uptime"; fi
            if [ ! -e /proc/version ]; then PROOT_EXTRA_BINDINGS="${'$'}PROOT_EXTRA_BINDINGS -b ${'$'}UBUNTU_PATH/proc/.version:/proc/version"; fi
            if [ ! -e /proc/vmstat ]; then PROOT_EXTRA_BINDINGS="${'$'}PROOT_EXTRA_BINDINGS -b ${'$'}UBUNTU_PATH/proc/.vmstat:/proc/vmstat"; fi
            if [ ! -e /proc/sys/kernel/cap_last_cap ]; then PROOT_EXTRA_BINDINGS="${'$'}PROOT_EXTRA_BINDINGS -b ${'$'}UBUNTU_PATH/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap"; fi
            if [ ! -e /proc/sys/fs/inotify/max_user_watches ]; then PROOT_EXTRA_BINDINGS="${'$'}PROOT_EXTRA_BINDINGS -b ${'$'}UBUNTU_PATH/proc/.sysctl_inotify_max_user_watches:/proc/sys/fs/inotify/max_user_watches"; fi
          fi

          # 使用 proot 直接进入解压的 Ubuntu 根文件系统。
          # - 清理并设置 PATH，避免继承宿主 PATH 造成命令找不到或混用 busybox。
          # - 绑定常见伪文件系统与外部存储，保障交互和软件包管理工作正常。
          # 在 proot 环境中创建必要的目录
          mkdir -p "${'$'}UBUNTU_PATH/storage/emulated" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$homeDir" 2>/dev/null

          if [ "${'$'}USE_CHROOT" = "1" ]; then
            CMD_FILE="${'$'}TMPDIR/command_to_exec"
            printf "%s" "${'$'}COMMAND_TO_EXEC" > "${'$'}CMD_FILE" 2>/dev/null || true
            CHROOT_WRAPPER="${'$'}TMPDIR/operit_chroot_wrapper.sh"
            cat > "${'$'}CHROOT_WRAPPER" <<'EOF'
        BIN="$1"
        UBUNTU_PATH="$2"
        CMD_FILE="$3"
        HOME_DIR="$4"
        OPERIT_UID="$5"
        OPERIT_GID="$6"
        OPERIT_GROUPS="$7"
        cleanup_mounts(){
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev/pts" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/sys" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/sdcard" 2>/dev/null || true
        }
        cleanup_mounts

        "${'$'}BIN/busybox" mkdir -p "${'$'}UBUNTU_PATH/proc" "${'$'}UBUNTU_PATH/sys" "${'$'}UBUNTU_PATH/dev" "${'$'}UBUNTU_PATH/dev/pts" "${'$'}UBUNTU_PATH/sdcard" "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null
        "${'$'}BIN/busybox" mount -t proc proc "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /dev "${'$'}UBUNTU_PATH/dev" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /sys "${'$'}UBUNTU_PATH/sys" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /dev/pts "${'$'}UBUNTU_PATH/dev/pts" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /storage/emulated/0 "${'$'}UBUNTU_PATH/sdcard" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind "${'$'}HOME_DIR" "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null || true
        COMMAND_TO_EXEC="$(cat "${'$'}CMD_FILE" 2>/dev/null)"
        "${'$'}BIN/busybox" chroot "${'$'}UBUNTU_PATH" /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "COMMAND_TO_EXEC=${'$'}COMMAND_TO_EXEC" "OPERIT_UID=${'$'}OPERIT_UID" "OPERIT_GID=${'$'}OPERIT_GID" "OPERIT_GROUPS=${'$'}OPERIT_GROUPS" /bin/bash -lc 'echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; umask 0002; if [ -n "${'$'}OPERIT_GID" ]; then chown 0:"${'$'}OPERIT_GID" /root 2>/dev/null || true; chmod 2775 /root 2>/dev/null || true; fi; eval "${'$'}COMMAND_TO_EXEC"'
        ret=${'$'}?
        cleanup_mounts
        exit ${'$'}ret
        EOF
            chmod 700 "${'$'}CHROOT_WRAPPER" 2>/dev/null || true
            exec su -c "sh \"${'$'}CHROOT_WRAPPER\" \"${'$'}BIN\" \"${'$'}UBUNTU_PATH\" \"${'$'}CMD_FILE\" \"${homeDir}\" \"${'$'}OPERIT_UID\" \"${'$'}OPERIT_GID\" \"${'$'}OPERIT_GROUPS\""
          fi
          exec ${'$'}BIN/proot \
            -0 \
            -r "${'$'}UBUNTU_PATH" \
            --link2symlink \
            -b /dev \
            -b /proc \
            -b /sys \
            -b /dev/pts \
            $tmpBindingLine
            -b /proc/self/fd:/dev/fd \
            -b /proc/self/fd/0:/dev/stdin \
            -b /proc/self/fd/1:/dev/stdout \
            -b /proc/self/fd/2:/dev/stderr \
            -b /storage/emulated/0:/sdcard \
            -b /storage/emulated/0:/storage/emulated/0 \
            -b $homeDir:$homeDir \
            ${'$'}PROOT_EXTRA_BINDINGS \
            -w /root \
            /usr/bin/env -i \
              HOME=/root \
              TERM=xterm-256color \
              LANG=en_US.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              COMMAND_TO_EXEC="${'$'}COMMAND_TO_EXEC" \
              /bin/bash -lc 'echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; eval "${'$'}COMMAND_TO_EXEC"'
        }
        """.trimIndent()

        val sshShell = """
        ssh_shell(){
          set -x
          install_ubuntu
          configure_sources
          fix_permissions
          sleep 1
          bump_progress
          
          # 先进入Ubuntu环境，然后连接SSH
          # 当SSH退出时，用户会回到本地Ubuntu shell
          login_ubuntu 'echo "Connecting to SSH..."; '"${'$'}SSH_COMMAND"'; echo "SSH connection closed. You are now in local Ubuntu terminal."; /bin/bash -il'
        }
        """.trimIndent()

        return """
        $common
        $installUbuntu
        $configureSources
        $fixPermissions
        $loginUbuntu
        $sshShell
        clear_lines
        start_shell(){
          install_ubuntu
          configure_sources
          fix_permissions
          sleep 1
          bump_progress
          login_ubuntu
        }
        """.trimIndent()
    }

    fun closeTerminalSession(sessionId: String) {
        // Delegate to provider to ensure underlying process is killed
        coroutineScope.launch {
            try {
                terminalProvider?.closeSession(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing session via provider", e)
            }
        }

        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed and removed session: $sessionId")
        }
    }

    private fun handleRegularCommand(command: String, session: com.ai.assistance.custard.terminal.data.TerminalSessionData, commandId: String) {
        session.currentCommandOutput.clear()
        session.currentOutputLineCount = 0

        val newCommandItem = CommandHistoryItem(
            id = commandId,
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )

        // Set the current executing command reference for efficient access
        session.currentExecutingCommand = newCommandItem

        // 发出命令开始执行事件
        coroutineScope.launch {
            _commandExecutionEvents.emit(CommandExecutionEvent(
                commandId = newCommandItem.id,
                sessionId = session.id,
                outputChunk = "",
                isCompleted = false
            ))
        }
    }

    fun cleanup() {
        // 释放 provider 连接
        kotlinx.coroutines.runBlocking {
            terminalProvider?.disconnect()
            Log.d(TAG, "Disconnected terminal provider")
            
            // 停止SSHD服务器
            sshdServerManager.stopServer()
            Log.d(TAG, "Stopped SSHD server")
        }
        terminalProvider = null
        
        activeSessions.keys.forEach { sessionId ->
            closeTerminalSession(sessionId)
        }
        sessionManager.cleanup()
        coroutineScope.cancel()
        Log.d(TAG, "All active sessions cleaned up.")
    }

    /**
     * 获取文件系统提供者
     * 
     * 根据配置返回对应的提供者（本地或SSH）
     * 如果配置了SSH且已连接，返回SSH的文件系统提供者（共享SFTP连接）
     * 否则返回本地文件系统提供者
     */
    fun getFileSystemProvider(): FileSystemProvider = kotlinx.coroutines.runBlocking {
        getTerminalProvider().getFileSystemProvider()
    }
    
    /**
     * 获取SSHD服务器管理器
     * 
     * 用于管理本地SSHD服务器（反向SSH隧道场景）
     */
    fun getSSHDServerManager(): SSHDServerManager = sshdServerManager
}
