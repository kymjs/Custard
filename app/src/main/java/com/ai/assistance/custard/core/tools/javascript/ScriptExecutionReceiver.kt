package com.ai.assistance.custard.core.tools.javascript

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.custard.util.AppLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 接收通过ADB发送的广播命令，用于执行JavaScript文件 */
class ScriptExecutionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScriptExecutionReceiver"
        const val ACTION_EXECUTE_JS = "com.ai.assistance.custard.EXECUTE_JS"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FUNCTION_NAME = "function_name"
        const val EXTRA_PARAMS = "params"
        const val EXTRA_TEMP_FILE = "temp_file"
        const val EXTRA_ENV_FILE_PATH = "env_file_path"
        const val EXTRA_TEMP_ENV_FILE = "temp_env_file"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_EXECUTE_JS) {
            val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
            val functionName = intent.getStringExtra(EXTRA_FUNCTION_NAME)
            val paramsJson = intent.getStringExtra(EXTRA_PARAMS) ?: "{}"
            val isTempFile = intent.getBooleanExtra(EXTRA_TEMP_FILE, false)

            val envFilePath = intent.getStringExtra(EXTRA_ENV_FILE_PATH)
            val isTempEnvFile = intent.getBooleanExtra(EXTRA_TEMP_ENV_FILE, false)

            if (filePath == null || functionName == null) {
                AppLogger.e(
                        TAG,
                        "Missing required parameters: filePath=$filePath, functionName=$functionName"
                )
                return
            }

            AppLogger.d(TAG, "Received request to execute JS file: $filePath, function: $functionName")
            executeJavaScript(context, filePath, functionName, paramsJson, isTempFile, envFilePath, isTempEnvFile)
        }
    }

    private fun executeJavaScript(
            context: Context,
            filePath: String,
            functionName: String,
            paramsJson: String,
            isTempFile: Boolean,
            envFilePath: String?,
            isTempEnvFile: Boolean
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    AppLogger.e(TAG, "JavaScript file not found: $filePath")
                    return@launch
                }

                // 读取文件内容
                val scriptContent = file.readText()
                AppLogger.d(TAG, "Loaded JavaScript file, size: ${scriptContent.length} bytes")

                // 获取JsEngine实例
                val jsEngine = JsEngine(context)

                // 解析参数
                val params =
                        try {
                            val jsonObject = JSONObject(paramsJson)
                            val paramsMap = mutableMapOf<String, String>()
                            jsonObject.keys().forEach { key ->
                                paramsMap[key] = jsonObject.opt(key)?.toString() ?: ""
                            }
                            paramsMap
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Error parsing params: $paramsJson", e)
                            mapOf<String, String>()
                        }

                val envOverrides: Map<String, String> =
                        try {
                            if (envFilePath.isNullOrBlank()) {
                                emptyMap()
                            } else {
                                val envFile = File(envFilePath)
                                if (!envFile.exists()) {
                                    AppLogger.w(TAG, "Env file not found: $envFilePath")
                                    emptyMap()
                                } else {
                                    val map = mutableMapOf<String, String>()
                                    envFile.readLines().forEach { rawLine ->
                                        val line = rawLine.trim()
                                        if (line.isEmpty()) return@forEach
                                        if (line.startsWith("#")) return@forEach
                                        val idx = line.indexOf('=')
                                        if (idx <= 0) return@forEach
                                        val k = line.substring(0, idx).trim()
                                        if (k.isEmpty()) return@forEach
                                        var v = line.substring(idx + 1).trim()
                                        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                                            if (v.length >= 2) {
                                                v = v.substring(1, v.length - 1)
                                            }
                                        }
                                        map[k] = v
                                    }
                                    map
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Error parsing env file: ${envFilePath ?: ""}", e)
                            emptyMap()
                        }

                // 执行JavaScript
                val result =
                        jsEngine.executeScriptFunction(
                                scriptContent,
                                functionName,
                                params,
                                envOverrides = envOverrides
                        )

                AppLogger.d(TAG, "JavaScript execution result: $result")

                // 如果是临时文件，执行完成后删除
                if (isTempFile) {
                    try {
                        file.delete()
                        AppLogger.d(TAG, "Deleted temporary file: $filePath")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error deleting temporary file: $filePath", e)
                    }
                }

                if (isTempEnvFile && !envFilePath.isNullOrBlank()) {
                    try {
                        File(envFilePath).delete()
                        AppLogger.d(TAG, "Deleted temporary env file: $envFilePath")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error deleting temporary env file: $envFilePath", e)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error executing JavaScript: ${e.message}", e)
            }
        }
    }
}
