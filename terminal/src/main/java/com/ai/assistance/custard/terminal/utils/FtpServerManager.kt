package com.ai.assistance.custard.terminal.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.listener.nio.NioListener
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.ftpserver.DataConnectionConfigurationFactory
import java.io.File
import java.net.NetworkInterface
import java.util.*

class FtpServerManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "FtpServerManager"
        private const val FTP_PORT = 2127
        private const val FTP_USERNAME = "ubuntu"
        private const val FTP_PASSWORD = "ubuntu123"
        
        @Volatile
        private var instance: FtpServerManager? = null
        
        fun getInstance(context: Context): FtpServerManager {
            return instance ?: synchronized(this) {
                instance ?: FtpServerManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private var ftpServer: FtpServer? = null
    private val filesDir = context.filesDir
    private val usrDir = File(filesDir, "usr")
    
    private fun getUbuntuRootPath(): String {
        val prootDistroPath = File(usrDir, "var/lib/proot-distro")
        val ubuntuPath = File(prootDistroPath, "installed-rootfs/ubuntu")
        return ubuntuPath.absolutePath
    }
    
    suspend fun startFtpServer(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (ftpServer?.isStopped == false) {
                Log.w(TAG, "FTP服务器已在运行")
                return@withContext true
            }
            
            val ubuntuRootPath = getUbuntuRootPath()
            val ubuntuRoot = File(ubuntuRootPath)
            
            if (!ubuntuRoot.exists()) {
                Log.e(TAG, "Ubuntu环境未初始化，无法启动FTP服务器")
                return@withContext false
            }
            
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            
            // 设置监听端口
            listenerFactory.port = FTP_PORT

            // 配置被动模式
            val dataConnectionConfigFactory = DataConnectionConfigurationFactory()
            dataConnectionConfigFactory.setPassivePorts("2128-2136")
            dataConnectionConfigFactory.setPassiveExternalAddress(getLocalIpAddress())
            listenerFactory.setDataConnectionConfiguration(dataConnectionConfigFactory.createDataConnectionConfiguration())

            // 配置监听器
            serverFactory.addListener("default", listenerFactory.createListener())
            
            // 创建用户管理器
            val userManagerFactory = PropertiesUserManagerFactory()
            val userManager = userManagerFactory.createUserManager()
            
            // 创建用户
            val user = BaseUser().apply {
                name = FTP_USERNAME
                password = FTP_PASSWORD
                homeDirectory = ubuntuRootPath
                authorities = listOf<Authority>(WritePermission())
            }
            
            userManager.save(user)
            serverFactory.userManager = userManager
            
            // 创建并启动FTP服务器
            ftpServer = serverFactory.createServer()
            ftpServer?.start()
            
            Log.i(TAG, "FTP服务器已启动")
            Log.i(TAG, "服务器地址: ${getLocalIpAddress()}:$FTP_PORT")
            Log.i(TAG, "用户名: $FTP_USERNAME")
            Log.i(TAG, "密码: $FTP_PASSWORD")
            Log.i(TAG, "根目录: $ubuntuRootPath")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动FTP服务器失败", e)
            false
        }
    }
    
    suspend fun stopFtpServer(): Boolean = withContext(Dispatchers.IO) {
        try {
            ftpServer?.stop()
            ftpServer = null
            Log.i(TAG, "FTP服务器已停止")
            true
        } catch (e: Exception) {
            Log.e(TAG, "停止FTP服务器失败", e)
            false
        }
    }
    
    fun isFtpServerRunning(): Boolean {
        return ftpServer?.isStopped == false
    }
    
    fun getFtpServerInfo(): String {
        return if (isFtpServerRunning()) {
            context.getString(
                com.ai.assistance.custard.terminal.R.string.ftp_server_running_info,
                getLocalIpAddress(),
                FTP_PORT.toString(),
                FTP_USERNAME,
                FTP_PASSWORD
            )
        } else {
            context.getString(com.ai.assistance.custard.terminal.R.string.ftp_server_not_running)
        }
    }
    
    private fun getLocalIpAddress(): String {
        return "127.0.0.1"
    }
} 