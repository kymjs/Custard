package com.ai.assistance.custard.ui.features.toolbox.screens.logcat

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.assistance.custard.R
import com.ai.assistance.custard.util.AppLogger
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志查看器ViewModel - 使用AppLogger文件
 */
class LogcatViewModel(private val context: Context) : ViewModel() {
    private val logcatManager = LogcatManager(context)


    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult: StateFlow<String?> = _saveResult.asStateFlow()



    fun clearLogs() {
        logcatManager.clearLogs()
    }

    fun saveLogsToFile() {
        if (_isSaving.value) return

        _isSaving.value = true
        _saveResult.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "custard_log_$timestamp.txt"

                val logsToSave = logcatManager.loadInitialLogs()

                if (logsToSave.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _saveResult.value = context.getString(R.string.logcat_no_logs_to_save)
                        delay(3000)
                        _saveResult.value = null
                    }
                    _isSaving.value = false
                    return@launch
                }

                val logContent = StringBuilder()
                logContent.append(context.getString(R.string.logcat_header) + "\n")
                logContent.append(context.getString(R.string.logcat_date, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())) + "\n")
                logContent.append(context.getString(R.string.logcat_total_count, logsToSave.size) + "\n")
                logContent.append("===================================\n\n")

                logsToSave.forEach { record ->
                    val recordTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(record.timestamp))
                    val tag = record.tag ?: ""
                    val level = record.level.symbol
                    logContent.append("$recordTimestamp $level/$tag: ${record.message}\n")
                }

                val filePath = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        saveUsingMediaStore(fileName, logContent.toString())
                    } else {
                        saveUsingFileSystem(fileName, logContent.toString())
                    }
                } catch (e: Exception) {
                    context.getString(R.string.logcat_save_failed, e.message ?: context.getString(R.string.logcat_unknown_error))
                }

                withContext(Dispatchers.Main) {
                    if (filePath.startsWith(context.getString(R.string.logcat_save_failed, ""))) {
                        _saveResult.value = filePath
                    } else {
                        _saveResult.value = context.getString(R.string.logcat_saved_to, filePath)
                    }
                    delay(3000)
                    _saveResult.value = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _saveResult.value = context.getString(R.string.logcat_save_failed, e.message ?: context.getString(R.string.logcat_unknown_error))
                    delay(3000)
                    _saveResult.value = null
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun saveUsingMediaStore(fileName: String, content: String): String {
        try {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/custard")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                return context.getString(R.string.logcat_cannot_create_file)
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: throw Exception(context.getString(R.string.logcat_cannot_open_output_stream))
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return "${downloadsDir.absolutePath}/custard/$fileName"
        } catch (e: Exception) {
            throw Exception(context.getString(R.string.logcat_mediestore_save_failed, e.message ?: ""))
        }
    }

    private fun saveUsingFileSystem(fileName: String, content: String): String {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir == null || !downloadsDir.exists() && !downloadsDir.mkdirs()) {
                throw Exception(context.getString(R.string.logcat_cannot_create_download_dir))
            }
            val custardDir = File(downloadsDir, "custard")
            if (!custardDir.exists() && !custardDir.mkdirs()) {
                throw Exception(context.getString(R.string.logcat_cannot_create_custard_dir))
            }
            val file = File(custardDir, fileName)
            FileWriter(file).use { it.write(content) }
            if (!file.exists() || file.length() == 0L) {
                throw Exception(context.getString(R.string.logcat_file_create_failed))
            }
            return file.absolutePath
        } catch (e: Exception) {
            throw Exception(context.getString(R.string.logcat_filesystem_save_failed, e.message ?: ""))
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LogcatViewModel::class.java)) {
                return LogcatViewModel(context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    override fun onCleared() {
        super.onCleared()
        // No-op, no more monitoring to stop
    }
}
