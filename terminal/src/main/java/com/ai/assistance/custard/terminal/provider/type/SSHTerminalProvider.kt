package com.ai.assistance.custard.terminal.provider.type

import android.content.Context
import android.util.Log
import com.ai.assistance.custard.terminal.Pty
import com.ai.assistance.custard.terminal.TerminalManager
import com.ai.assistance.custard.terminal.TerminalSession
import com.ai.assistance.custard.terminal.data.SSHAuthType
import com.ai.assistance.custard.terminal.data.SSHConfig
import com.ai.assistance.custard.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.custard.terminal.utils.SSHFileConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 远程终端提供者
 * 
 * 通过启动一个本地终端，然后自动执行ssh命令来连接到远程服务器
 */
class SSHTerminalProvider(
    private val context: Context,
    private val sshConfig: SSHConfig,
    private val terminalManager: TerminalManager
) : TerminalProvider {
    
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    
    // 使用独立的SSH文件连接管理器
    private val sshFileManager = SSHFileConnectionManager.getInstance(context)
    
    // SSH连接ID
    private var sshConnectionId: String? = null
    
    companion object {
        private const val TAG = "SSHTerminalProvider"
    }
    
    override suspend fun isConnected(): Boolean {
        // 检查SSH文件连接是否存在
        return sshConnectionId?.let { id ->
            sshFileManager.getFileSystemProvider(id) != null
        } ?: false
    }
    
    override suspend fun connect(): Result<Unit> {
        return withContext<Result<Unit>>(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to SSH via SSHFileConnectionManager")
                
                // 构建连接参数
                val params = SSHFileConnectionManager.ConnectionParams(
                    host = sshConfig.host,
                    port = sshConfig.port,
                    username = sshConfig.username,
                    password = sshConfig.password,
                    privateKeyPath = sshConfig.privateKeyPath,
                    passphrase = sshConfig.passphrase,
                    enableKeepAlive = sshConfig.enableKeepAlive,
                    keepAliveInterval = sshConfig.keepAliveInterval,
                    enablePortForwarding = sshConfig.enablePortForwarding,
                    localForwardPort = sshConfig.localForwardPort,
                    remoteForwardPort = sshConfig.remoteForwardPort,
                    enableReverseTunnel = sshConfig.enableReverseTunnel,
                    remoteTunnelPort = sshConfig.remoteTunnelPort,
                    localSshPort = sshConfig.localSshPort,
                    localSshUsername = sshConfig.localSshUsername,
                    localSshPassword = sshConfig.localSshPassword,
                    connectionId = "terminal_${sshConfig.host}_${sshConfig.port}"
                )
                
                // 通过管理器建立连接
                val result = sshFileManager.connect(params)
                if (result.isSuccess) {
                    sshConnectionId = result.getOrThrow()
                    Log.d(TAG, "SSH connection established via manager: $sshConnectionId")
                    Result.success(Unit)
                } else {
                    Log.e(TAG, "Failed to connect SSH", result.exceptionOrNull())
                    Result.failure(result.exceptionOrNull() ?: Exception("Unknown SSH connection error"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to SSH server", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            // 关闭所有终端会话
            activeSessions.keys.toList().forEach { sessionId ->
                closeSession(sessionId)
            }
            
            // 通过管理器断开SSH连接
            sshConnectionId?.let { id ->
                sshFileManager.disconnect(id)
                sshConnectionId = null
            }
            
            Log.d(TAG, "SSH connection closed")
        }
    }
    
    override suspend fun startSession(sessionId: String): Result<Pair<TerminalSession, Pty>> {
        return withContext<Result<Pair<TerminalSession, Pty>>>(Dispatchers.IO) {
            try {
                // 确保SSH连接已建立
                if (!isConnected()) {
                    connect().getOrThrow()
                }

                val filesDir: File = context.filesDir
                val binDir: File = File(filesDir, "usr/bin")
                val bash = File(binDir, "bash").absolutePath
                val startScript = "source \$HOME/common.sh && ssh_shell"
                val command = arrayOf(bash, "-c", startScript)
                
                val env = buildEnvironment()
                
                Log.d(TAG, "Starting local terminal session for SSH with command: ${command.joinToString(" ")}")
                Log.d(TAG, "Environment: $env")
                
                val pty = Pty.start(command, env, filesDir)
                
                val terminalSession = TerminalSession(
                    process = pty.process,
                    stdout = pty.stdout,
                    stdin = pty.stdin
                )
                
                activeSessions[sessionId] = terminalSession
                
                // 如果启用了反向隧道，挂载存储（通过管理器）
                if (sshConfig.enableReverseTunnel) {
                    sshConnectionId?.let { id ->
                        sshFileManager.mountStorage(id)
                    }
                }
                
                Log.d(TAG, "SSH terminal session started via local pty: $sessionId")
                Result.success(Pair(terminalSession, pty))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SSH terminal session", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun closeSession(sessionId: String) {
        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed SSH terminal session (process): $sessionId")
        }
    }
    
    override fun getFileSystemProvider(): FileSystemProvider {
        // 从管理器获取文件系统提供者
        return sshConnectionId?.let { id ->
            sshFileManager.getFileSystemProvider(id)
        } ?: throw IllegalStateException("SSH connection not established")
    }
    
    override suspend fun getWorkingDirectory(): String {
        // SSH默认工作目录通常是用户主目录
        return "~"
    }
    
    override fun getEnvironment(): Map<String, String> {
        // SSH环境变量由远程服务器决定
        return mapOf(
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8"
        )
    }

    private fun buildSshCommand(): String {
        val cmd = StringBuilder()
        
        // 如果是密码认证，使用sshpass自动输入密码
        if (sshConfig.authType == SSHAuthType.PASSWORD && sshConfig.password != null) {
            cmd.append("sshpass -p '${sshConfig.password}' ")
        }
        
        cmd.append("ssh")
        cmd.append(" -p ${sshConfig.port}")
        
        // 注意：反向隧道现在通过JSch Session API配置（setupReverseTunnel），不再需要ssh命令参数
        
        if (sshConfig.authType == SSHAuthType.PUBLIC_KEY && sshConfig.privateKeyPath != null) {
            // 注意：这里的路径是Android文件系统中的路径。
            // proot已将/storage/emulated/0挂载为/sdcard，因此如果密钥在外部存储中，路径需要相应调整。
            // 为简单起见，我们假设用户提供的路径在proot环境中是可访问的。
            cmd.append(" -i \"${sshConfig.privateKeyPath}\"")
        }
        
        cmd.append(" -o StrictHostKeyChecking=no") // 避免首次连接时的主机密钥检查提示
        
        // 配置心跳包（Keep-Alive）
        if (sshConfig.enableKeepAlive) {
            cmd.append(" -o ServerAliveInterval=${sshConfig.keepAliveInterval}")
            cmd.append(" -o ServerAliveCountMax=3")
        }
        
        cmd.append(" ${sshConfig.username}@${sshConfig.host}")

        return cmd.toString()
    }

    private fun buildEnvironment(): Map<String, String> {
        val filesDir: File = context.filesDir
        val usrDir: File = File(filesDir, "usr")
        val binDir: File = File(usrDir, "bin")
        val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
        
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
        env["SSH_COMMAND"] = buildSshCommand()
        return env
    }
}
