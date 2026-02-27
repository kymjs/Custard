package com.ai.assistance.custard.terminal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.assistance.custard.terminal.utils.CacheManager
import com.ai.assistance.custard.terminal.utils.UpdateChecker
import com.ai.assistance.custard.terminal.utils.FtpServerManager
import com.ai.assistance.custard.terminal.TerminalManager
import com.ai.assistance.custard.terminal.data.MirrorSource
import com.ai.assistance.custard.terminal.data.PackageManagerType
import com.ai.assistance.custard.terminal.data.SourceConfig
import com.ai.assistance.custard.terminal.data.SSHConfig
import com.ai.assistance.custard.terminal.utils.SourceManager
import com.ai.assistance.custard.terminal.utils.SSHConfigManager
import com.ai.assistance.custard.terminal.utils.VirtualKeyboardConfigManager
import com.ai.assistance.custard.terminal.utils.VirtualKeyboardLayoutConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File

class SettingsViewModel(
    application: Application,
    private val terminalManager: TerminalManager? = null
) : AndroidViewModel(application) {
    private val cacheManager = CacheManager(application)
    private val updateChecker = UpdateChecker(application)
    private val ftpServerManager = FtpServerManager.getInstance(application)
    private val sourceManager = SourceManager(application)
    private val sshConfigManager = SSHConfigManager(application)
    private val virtualKeyboardConfigManager = VirtualKeyboardConfigManager.getInstance(application)
    
    // SharedPreferences for shared tmp setting
    private val prefs = application.getSharedPreferences("terminal_settings", android.content.Context.MODE_PRIVATE)

    // 用于跟踪缓存计算任务的Job
    private var cacheSizeCalculationJob: Job? = null

    private val _cacheSize = MutableStateFlow(application.getString(com.ai.assistance.custard.terminal.R.string.cache_size_default))
    val cacheSize = _cacheSize.asStateFlow()

    private val _updateStatus = MutableStateFlow(application.getString(com.ai.assistance.custard.terminal.R.string.update_status_default))
    val updateStatus = _updateStatus.asStateFlow()
    
    private val _isCalculatingCache = MutableStateFlow(false)
    val isCalculatingCache = _isCalculatingCache.asStateFlow()
    
    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache = _isClearingCache.asStateFlow()

    // FTP服务器相关状态
    private val _ftpServerStatus = MutableStateFlow(application.getString(com.ai.assistance.custard.terminal.R.string.ftp_server_not_running))
    val ftpServerStatus = _ftpServerStatus.asStateFlow()
    
    private val _isFtpServerRunning = MutableStateFlow(false)
    val isFtpServerRunning = _isFtpServerRunning.asStateFlow()
    
    private val _isManagingFtpServer = MutableStateFlow(false)
    val isManagingFtpServer = _isManagingFtpServer.asStateFlow()

    // 更新相关状态
    private val _hasUpdateAvailable = MutableStateFlow(false)
    val hasUpdateAvailable = _hasUpdateAvailable.asStateFlow()
    
    // 源管理相关状态
    private val _sourceConfigs = MutableStateFlow<Map<PackageManagerType, SourceConfig>>(emptyMap())
    val sourceConfigs = _sourceConfigs.asStateFlow()
    
    // SSH配置相关状态（单一配置）
    private val _sshConfig = MutableStateFlow<SSHConfig?>(null)
    val sshConfig = _sshConfig.asStateFlow()
    
    private val _sshEnabled = MutableStateFlow(false)
    val sshEnabled = _sshEnabled.asStateFlow()

    private val _showSshToolsMissingDialog = MutableStateFlow(false)
    val showSshToolsMissingDialog = _showSshToolsMissingDialog.asStateFlow()
    
    private val _showOpensshMissingDialog = MutableStateFlow(false)
    val showOpensshMissingDialog = _showOpensshMissingDialog.asStateFlow()
    
    // Shared tmp setting state
    private val _sharedTmpEnabled = MutableStateFlow(true)
    val sharedTmpEnabled = _sharedTmpEnabled.asStateFlow()

    private val _chrootEnabled = MutableStateFlow(false)
    val chrootEnabled = _chrootEnabled.asStateFlow()

    private val _virtualKeyboardLayout = MutableStateFlow(virtualKeyboardConfigManager.loadLayout())
    val virtualKeyboardLayout = _virtualKeyboardLayout.asStateFlow()

    // 自动检测更新，但不自动计算缓存大小
    init {
        checkForUpdates()
        updateFtpServerStatus()
        loadSourceConfigs()
        loadSSHConfigs()
        loadSSHEnabled()
        loadSharedTmpSetting()
        loadChrootSetting()
        loadVirtualKeyboardLayout()
    }

    fun onSshToolsMissingDialogDismissed() {
        _showSshToolsMissingDialog.value = false
    }
    
    fun onOpensshMissingDialogDismissed() {
        _showOpensshMissingDialog.value = false
    }

    private fun areSshToolsInstalled(): Boolean {
        val filesDir = getApplication<Application>().filesDir
        val ubuntuRoot = File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
        
        val sshExecutable = File(ubuntuRoot, "usr/bin/ssh")
        val sshpassExecutable = File(ubuntuRoot, "usr/bin/sshpass")
        
        return sshExecutable.exists() && sshpassExecutable.exists()
    }
    
    private fun isOpensshServerInstalled(): Boolean {
        val filesDir = getApplication<Application>().filesDir
        val ubuntuRoot = File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
        val sshdExecutable = File(ubuntuRoot, "usr/sbin/sshd")
        return sshdExecutable.exists()
    }

    private fun loadSourceConfigs() {
        _sourceConfigs.value = mapOf(
            PackageManagerType.APT to SourceConfig(
                PackageManagerType.APT,
                sourceManager.getSelectedSourceId(PackageManagerType.APT),
                sourceManager.aptSources
            ),
            PackageManagerType.PIP to SourceConfig(
                PackageManagerType.PIP,
                sourceManager.getSelectedSourceId(PackageManagerType.PIP),
                sourceManager.pipSources
            ),
            PackageManagerType.NPM to SourceConfig(
                PackageManagerType.NPM,
                sourceManager.getSelectedSourceId(PackageManagerType.NPM),
                sourceManager.npmSources
            ),
            PackageManagerType.RUST to SourceConfig(
                PackageManagerType.RUST,
                sourceManager.getSelectedSourceId(PackageManagerType.RUST),
                sourceManager.rustSources
            )
        )
    }

    fun updateSource(pm: PackageManagerType, sourceId: String) {
        viewModelScope.launch {
            // 1. 保存设置
            sourceManager.setSelectedSourceId(pm, sourceId)
            
            // 2. 重新加载配置以更新UI
            loadSourceConfigs()

            // 3. 应用更改
            val source = when (pm) {
                PackageManagerType.APT -> sourceManager.aptSources.find { it.id == sourceId }
                PackageManagerType.PIP -> sourceManager.pipSources.find { it.id == sourceId }
                PackageManagerType.NPM -> sourceManager.npmSources.find { it.id == sourceId }
                PackageManagerType.RUST -> sourceManager.rustSources.find { it.id == sourceId }
            }
            source?.let {
                val command = when (pm) {
                    PackageManagerType.APT -> sourceManager.getAptSourceChangeCommand(it)
                    PackageManagerType.PIP -> sourceManager.getPipSourceChangeCommand(it)
                    PackageManagerType.NPM -> sourceManager.getNpmSourceChangeCommand(it)
                    PackageManagerType.RUST -> {
                        // Rust源的更改需要通过环境变量，这里只是提示用户
                        "echo 'Rust镜像源已更新为: ${it.name}. 下次安装Rust时将使用此源。'"
                    }
                }
                // 在默认会话中执行命令
                terminalManager?.sendCommandToSession("default", command)
            }
        }
    }
    
    fun addCustomSource(pm: PackageManagerType, name: String, url: String, isHttps: Boolean) {
        // 生成唯一ID：使用时间戳 + URL的哈希
        val id = "custom_${pm.name.lowercase()}_${System.currentTimeMillis()}"
        val source = MirrorSource(id, name, url, isHttps)
        sourceManager.saveCustomSource(pm, source)
        loadSourceConfigs()
    }
    
    fun deleteCustomSource(pm: PackageManagerType, sourceId: String) {
        sourceManager.deleteCustomSource(pm, sourceId)
        // 如果删除的是当前选中的源，切换到第一个内置源
        if (sourceManager.getSelectedSourceId(pm) == sourceId) {
            val firstBuiltInSource = when (pm) {
                PackageManagerType.APT -> "tuna_apt"
                PackageManagerType.PIP -> "tuna_pip"
                PackageManagerType.NPM -> "taobao_npm"
                PackageManagerType.RUST -> "ustc_rust"
            }
            updateSource(pm, firstBuiltInSource)
        }
        loadSourceConfigs()
    }

    fun getCacheSize() {
        // 如果已经有正在运行的计算任务，先取消它
        cacheSizeCalculationJob?.cancel()
        
        cacheSizeCalculationJob = viewModelScope.launch {
            _isCalculatingCache.value = true
            _cacheSize.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.cache_calculating)
            try {
                // 调用新的 getCacheSize，并传入一个更新UI的回调
                val size = cacheManager.getCacheSize { currentSize ->
                    // 在回调中，实时更新UI状态
                    _cacheSize.value = "${getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.cache_calculating)} (${cacheManager.formatSize(currentSize)})"
                }
                _cacheSize.value = cacheManager.formatSize(size)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _cacheSize.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.cache_calculation_cancelled)
                } else {
                    _cacheSize.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.cache_calculation_failed)
                }
            } finally {
                _isCalculatingCache.value = false
                cacheSizeCalculationJob = null
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _isClearingCache.value = true
            
            // 先停止正在进行的缓存计算
            val jobToCancel = cacheSizeCalculationJob
            if (jobToCancel?.isActive == true) {
                jobToCancel.cancel()
                try {
                    jobToCancel.join() // 等待任务完成
                } catch (e: Exception) {
                    // 忽略join可能抛出的异常，因为我们就是要取消它
                }
            }
            
            _cacheSize.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.environment_resetting)
            try {
                // 在清理缓存前停止FTP服务器
                if (ftpServerManager.isFtpServerRunning()) {
                    ftpServerManager.stopFtpServer()
                    updateFtpServerStatus()
                }
                
                cacheManager.clearCache(terminalManager)
                _cacheSize.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.environment_reset_complete)
            } catch (e: Exception) {
                _cacheSize.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.environment_reset_failed, e.message ?: "")
            } finally {
                _isClearingCache.value = false
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.checking_updates)
            when (val result = updateChecker.checkForUpdates(showToast = true)) {
                is UpdateChecker.UpdateResult.UpdateAvailable -> {
                    _updateStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.update_available, result.latestVersion, result.currentVersion)
                    _hasUpdateAvailable.value = true
                }
                is UpdateChecker.UpdateResult.UpToDate -> {
                    _updateStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.up_to_date, result.currentVersion)
                    _hasUpdateAvailable.value = false
                }
                is UpdateChecker.UpdateResult.Error -> {
                    _updateStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.update_check_failed, result.message)
                    _hasUpdateAvailable.value = false
                }
            }
        }
    }
    
    fun openGitHubRepo() {
        updateChecker.openGitHubRepo()
    }
    
    fun openGitHubReleases() {
        updateChecker.openGitHubReleases()
    }
    
    fun startFtpServer() {
        viewModelScope.launch {
            _isManagingFtpServer.value = true
            _ftpServerStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.ftp_server_starting)
            try {
                val success = ftpServerManager.startFtpServer()
                if (success) {
                    _isFtpServerRunning.value = true
                    _ftpServerStatus.value = ftpServerManager.getFtpServerInfo()
                } else {
                    _ftpServerStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.ftp_server_start_failed_env)
                }
            } catch (e: Exception) {
                _ftpServerStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.ftp_server_start_failed, e.message ?: "")
            } finally {
                _isManagingFtpServer.value = false
            }
        }
    }
    
    fun stopFtpServer() {
        viewModelScope.launch {
            _isManagingFtpServer.value = true
            _ftpServerStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.ftp_server_stopping_progress)
            try {
                val success = ftpServerManager.stopFtpServer()
                if (success) {
                    _isFtpServerRunning.value = false
                    _ftpServerStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.ftp_server_stopped)
                } else {
                    _ftpServerStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.ftp_server_stop_failed)
                }
            } catch (e: Exception) {
                _ftpServerStatus.value = getApplication<Application>().getString(com.ai.assistance.custard.terminal.R.string.ftp_server_stop_failed_with_error, e.message ?: "")
            } finally {
                _isManagingFtpServer.value = false
            }
        }
    }
    
    private fun updateFtpServerStatus() {
        _isFtpServerRunning.value = ftpServerManager.isFtpServerRunning()
        _ftpServerStatus.value = ftpServerManager.getFtpServerInfo()
    }
    
    // ==================== SSH 配置管理 ====================
    
    private fun loadSSHConfigs() {
        viewModelScope.launch {
            _sshConfig.value = sshConfigManager.getConfig()
        }
    }
    
    private fun loadSSHEnabled() {
        _sshEnabled.value = sshConfigManager.isEnabled()
    }
    
    fun saveSSHConfig(config: SSHConfig) {
        viewModelScope.launch {
            sshConfigManager.saveConfig(config)
            loadSSHConfigs()
        }
    }
    
    fun deleteSSHConfig() {
        viewModelScope.launch {
            sshConfigManager.deleteConfig()
            // 删除配置时也禁用 SSH
            sshConfigManager.setEnabled(false)
            loadSSHConfigs()
            loadSSHEnabled()
        }
    }
    
    fun setSSHEnabled(enabled: Boolean) {
        if (enabled) {
            if (!areSshToolsInstalled()) {
                _showSshToolsMissingDialog.value = true
                return
            }
            
            // 检查是否启用了反向隧道且是否安装了openssh-server
            val config = _sshConfig.value
            if (config != null && config.enableReverseTunnel && !isOpensshServerInstalled()) {
                _showOpensshMissingDialog.value = true
                return
            }
            
            sshConfigManager.setEnabled(true)
            loadSSHEnabled()
        } else {
            sshConfigManager.setEnabled(false)
            loadSSHEnabled()
        }
    }
    
    // ==================== Shared TMP 设置管理 ====================
    
    private fun loadSharedTmpSetting() {
        _sharedTmpEnabled.value = prefs.getBoolean("shared_tmp_enabled", true)
    }
    
    fun setSharedTmpEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("shared_tmp_enabled", enabled).apply()
        _sharedTmpEnabled.value = enabled
    }
    
    fun isSharedTmpEnabled(): Boolean {
        return prefs.getBoolean("shared_tmp_enabled", true)
    }

    private fun loadChrootSetting() {
        _chrootEnabled.value = prefs.getBoolean("chroot_enabled", false)
    }
    
    fun setChrootEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("chroot_enabled", enabled).apply()
        _chrootEnabled.value = enabled
    }
    
    fun isChrootEnabled(): Boolean {
        return prefs.getBoolean("chroot_enabled", false)
    }

    private fun loadVirtualKeyboardLayout() {
        _virtualKeyboardLayout.value = virtualKeyboardConfigManager.loadLayout()
    }

    fun saveVirtualKeyboardLayout(layout: VirtualKeyboardLayoutConfig) {
        virtualKeyboardConfigManager.saveLayout(layout)
        loadVirtualKeyboardLayout()
    }
} 
