package com.ai.assistance.custard.terminal.utils

import android.content.Context
import android.util.Log
import com.ai.assistance.custard.terminal.data.SSHConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.nio.file.Paths
import java.security.Security

/**
 * SSHD 服务器管理器
 * 
 * 用于反向SSH隧道场景，允许远程服务器通过sshfs挂载本地Android存储
 * 不占用终端，独立运行
 */
class SSHDServerManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SSHDServerManager"
        
        @Volatile
        private var instance: SSHDServerManager? = null
        
        fun getInstance(context: Context): SSHDServerManager {
            return instance ?: synchronized(this) {
                instance ?: SSHDServerManager(context.applicationContext).also { 
                    instance = it
                    initializeForAndroid(context.applicationContext)
                }
            }
        }
        
        /**
         * Initialize system properties and security providers for Android compatibility
         */
        private fun initializeForAndroid(context: Context) {
            // Set system properties for Android compatibility
            // SSHD tries to access user.home which doesn't exist on Android
            System.setProperty("user.home", context.filesDir.absolutePath)
            System.setProperty("user.dir", context.filesDir.absolutePath)
            
            // Register BouncyCastle provider to avoid JMX issues on Android
            // see: https://issues.apache.org/jira/browse/SSHD-1236
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }
    
    private var sshServer: SshServer? = null
    private var currentConfig: SSHConfig? = null
    
    /**
     * 启动SSHD服务器
     * 
     * @param sshConfig SSH配置，包含端口、用户名、密码等信息
     * @return 是否成功启动
     */
    suspend fun startServer(sshConfig: SSHConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            if (sshServer?.isStarted == true) {
                Log.w(TAG, "SSHD服务器已在运行")
                return@withContext true
            }
            
            // 确保 .ssh 目录存在
            val sshDir = File(context.filesDir, ".ssh")
            if (!sshDir.exists()) {
                sshDir.mkdirs()
            }
            
            // 创建SSHD服务器实例
            val server = SshServer.setUpDefaultServer()
            
            // 配置端口
            server.port = sshConfig.localSshPort
            Log.d(TAG, "Setting SSHD port to: ${sshConfig.localSshPort}")
            
            // 配置主机密钥（自动生成并保存）
            val hostKeyPath = File(context.filesDir, "hostkey.ser")
            server.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyPath.toPath())
            
            // 配置密码认证
            server.passwordAuthenticator = PasswordAuthenticator { username, password, session ->
                val authenticated = username == sshConfig.localSshUsername && 
                                  password == sshConfig.localSshPassword
                Log.d(TAG, "Authentication attempt - username: $username, success: $authenticated")
                authenticated
            }
            
            // 配置SFTP子系统
            server.subsystemFactories = listOf(SftpSubsystemFactory())
            
            // 配置文件系统工厂 - 设置根目录为外部存储
            val sdcardPath = Paths.get("/storage/emulated/0")
            server.fileSystemFactory = VirtualFileSystemFactory(sdcardPath)
            
            // 启动服务器
            server.start()
            sshServer = server
            currentConfig = sshConfig
            
            Log.i(TAG, "SSHD服务器已启动")
            Log.i(TAG, "端口: ${sshConfig.localSshPort}")
            Log.i(TAG, "用户名: ${sshConfig.localSshUsername}")
            Log.i(TAG, "根目录: /storage/emulated/0 (/sdcard)")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动SSHD服务器失败", e)
            false
        }
    }
    
    /**
     * 停止SSHD服务器
     * 
     * @return 是否成功停止
     */
    suspend fun stopServer(): Boolean = withContext(Dispatchers.IO) {
        try {
            sshServer?.stop()
            sshServer = null
            currentConfig = null
            Log.i(TAG, "SSHD服务器已停止")
            true
        } catch (e: Exception) {
            Log.e(TAG, "停止SSHD服务器失败", e)
            false
        }
    }
    
    /**
     * 检查SSHD服务器是否正在运行
     * 
     * @return 是否正在运行
     */
    fun isServerRunning(): Boolean {
        return sshServer?.isStarted == true
    }
    
    /**
     * 获取SSHD服务器信息
     * 
     * @return 服务器状态信息字符串
     */
    fun getServerInfo(): String {
        return if (isServerRunning() && currentConfig != null) {
            """
            SSHD服务器运行中
            端口: ${currentConfig!!.localSshPort}
            用户名: ${currentConfig!!.localSshUsername}
            根目录: /storage/emulated/0 (/sdcard)
            """.trimIndent()
        } else {
            "SSHD服务器未运行"
        }
    }
}

