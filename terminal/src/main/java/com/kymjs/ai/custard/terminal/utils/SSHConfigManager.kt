package com.kymjs.ai.custard.terminal.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kymjs.ai.custard.terminal.data.SSHAuthType
import com.kymjs.ai.custard.terminal.data.SSHConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SSH 配置管理器（单一配置）
 * 
 * 只管理一个 SSH 连接配置
 */
class SSHConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "ssh_config",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val TAG = "SSHConfigManager"
        private const val KEY_CONFIG = "config"
        private const val KEY_ENABLED = "ssh_enabled"
    }
    
    /**
     * 获取 SSH 配置
     */
    suspend fun getConfig(): SSHConfig? = withContext(Dispatchers.IO) {
        val configJson = prefs.getString(KEY_CONFIG, null)
        Log.d(TAG, "getConfig: configJson = $configJson")
        
        if (configJson == null) {
            Log.d(TAG, "getConfig: No config found")
            return@withContext null
        }
        
        try {
            val json = JSONObject(configJson)
            val config = parseConfig(json)
            Log.d(TAG, "getConfig: Successfully parsed config: ${config.username}@${config.host}")
            config
        } catch (e: Exception) {
            Log.e(TAG, "getConfig: Failed to parse config", e)
            null
        }
    }
    
    /**
     * 保存 SSH 配置
     */
    suspend fun saveConfig(config: SSHConfig) = withContext(Dispatchers.IO) {
        Log.d(TAG, "saveConfig: Saving config for ${config.username}@${config.host}:${config.port}")
        val json = toJson(config)
        val jsonString = json.toString()
        Log.d(TAG, "saveConfig: JSON = $jsonString")
        
        val success = prefs.edit().putString(KEY_CONFIG, jsonString).commit()
        Log.d(TAG, "saveConfig: Save result = $success")
        
        // 验证保存
        val savedJson = prefs.getString(KEY_CONFIG, null)
        Log.d(TAG, "saveConfig: Verification read = $savedJson")
    }
    
    /**
     * 删除 SSH 配置
     */
    suspend fun deleteConfig() = withContext(Dispatchers.IO) {
        Log.d(TAG, "deleteConfig: Deleting SSH config")
        val success = prefs.edit().remove(KEY_CONFIG).commit()
        Log.d(TAG, "deleteConfig: Delete result = $success")
    }
    
    /**
     * 检查是否有配置
     */
    suspend fun hasConfig(): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(KEY_CONFIG)
    }
    
    /**
     * 获取 SSH 是否启用
     */
    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false)
    }
    
    /**
     * 设置 SSH 是否启用
     */
    fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "setEnabled: $enabled")
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
    
    private fun parseConfig(json: JSONObject): SSHConfig {
        return SSHConfig(
            host = json.getString("host"),
            port = json.optInt("port", 22),
            username = json.getString("username"),
            authType = SSHAuthType.valueOf(json.getString("authType")),
            password = json.optString("password", "").takeIf { it.isNotEmpty() },
            privateKeyPath = json.optString("privateKeyPath", "").takeIf { it.isNotEmpty() },
            passphrase = json.optString("passphrase", "").takeIf { it.isNotEmpty() },
            // 反向隧道配置
            enableReverseTunnel = json.optBoolean("enableReverseTunnel", false),
            remoteTunnelPort = json.optInt("remoteTunnelPort", 8888),
            localSshPort = json.optInt("localSshPort", 8022),
            localSshUsername = json.optString("localSshUsername", "root"),
            localSshPassword = json.optString("localSshPassword", "")
        )
    }
    
    private fun toJson(config: SSHConfig): JSONObject {
        val json = JSONObject()
        json.put("host", config.host)
        json.put("port", config.port)
        json.put("username", config.username)
        json.put("authType", config.authType.name)
        config.password?.let { json.put("password", it) }
        config.privateKeyPath?.let { json.put("privateKeyPath", it) }
        config.passphrase?.let { json.put("passphrase", it) }
        // 反向隧道配置
        json.put("enableReverseTunnel", config.enableReverseTunnel)
        json.put("remoteTunnelPort", config.remoteTunnelPort)
        json.put("localSshPort", config.localSshPort)
        json.put("localSshUsername", config.localSshUsername)
        json.put("localSshPassword", config.localSshPassword)
        return json
    }
}

