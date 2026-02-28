package com.kymjs.ai.custard.terminal.provider.type

import android.content.Context
import android.util.Log
import com.kymjs.ai.custard.terminal.Pty
import com.kymjs.ai.custard.terminal.TerminalSession
import com.kymjs.ai.custard.terminal.provider.filesystem.FileSystemProvider
import com.kymjs.ai.custard.terminal.provider.filesystem.LocalFileSystemProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地终端提供者
 * 
 * 使用 proot + Ubuntu 环境提供本地 Linux 终端
 */
class LocalTerminalProvider(
    private val context: Context
) : TerminalProvider {
    
    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    private val fileSystemProvider = LocalFileSystemProvider(context)
    
    companion object {
        private const val TAG = "LocalTerminalProvider"
    }
    
    override suspend fun isConnected(): Boolean {
        // 本地终端总是"连接"的
        return true
    }
    
    override suspend fun connect(): Result<Unit> {
        // 本地终端不需要连接操作
        return Result.success(Unit)
    }
    
    override suspend fun disconnect() {
        // 关闭所有会话
        activeSessions.keys.forEach { sessionId ->
            closeSession(sessionId)
        }
    }
    
    override suspend fun startSession(sessionId: String): Result<Pair<TerminalSession, Pty>> {
        return withContext(Dispatchers.IO) {
            try {
                val bash = File(binDir, "bash").absolutePath
                val startScript = "source \$HOME/common.sh && start_shell"
                val command = arrayOf(bash, "-c", startScript)
                
                val env = buildEnvironment()
                
                Log.d(TAG, "Starting local terminal session with command: ${command.joinToString(" ")}")
                Log.d(TAG, "Environment: $env")
                
                val pty = Pty.start(command, env, filesDir)
                
                val session = TerminalSession(
                    process = pty.process,
                    stdout = pty.stdout,
                    stdin = pty.stdin
                )
                
                activeSessions[sessionId] = session
                Result.success(Pair(session, pty))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local terminal session", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun closeSession(sessionId: String) {
        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed local terminal session: $sessionId")
        }
    }
    
    override fun getFileSystemProvider(): FileSystemProvider {
        return fileSystemProvider
    }
    
    override suspend fun getWorkingDirectory(): String {
        return filesDir.absolutePath
    }
    
    override fun getEnvironment(): Map<String, String> {
        return buildEnvironment()
    }
    
    private fun buildEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH")}"
        env["HOME"] = filesDir.absolutePath
        env["PREFIX"] = usrDir.absolutePath
        env["TERMUX_PREFIX"] = usrDir.absolutePath
        env["LD_LIBRARY_PATH"] = "${nativeLibDir}:${binDir.absolutePath}"
        env["PROOT_LOADER"] = File(binDir, "loader").absolutePath
        env["TMPDIR"] = File(filesDir, "tmp").absolutePath
        env["PROOT_TMP_DIR"] = File(filesDir, "tmp").absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        return env
    }
}

