package com.kymjs.ai.custard.terminal.provider.filesystem

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地文件系统提供者
 * 使用Java File API实现文件系统操作
 * 
 * @param context Android上下文，用于路径映射（可选）
 */
class LocalFileSystemProvider(
    private val context: Context? = null
) : FileSystemProvider {
    
    companion object {
        private const val TAG = "LocalFileSystemProvider"
    }
    
    /**
     * Ubuntu根目录，用于Linux路径映射
     * 位于 {filesDir}/usr/var/lib/proot-distro/installed-rootfs/ubuntu
     */
    private val ubuntuRoot: File? by lazy {
        context?.let { ctx ->
            File(ctx.filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
        }
    }

    /**
     * App files 目录（通常为 /data/user/0/<package>/files）
     */
    private val appFilesDir: String? by lazy {
        context?.filesDir?.absolutePath
    }

    /**
     * 将Linux路径映射到Android文件系统中的实际路径
     * 如果没有提供context，则直接返回原路径
     */
    private fun mapPath(linuxPath: String): String {
        val root = ubuntuRoot ?: return linuxPath
        val homeDir = appFilesDir ?: return linuxPath
        val expandedPath = expandHomePath(linuxPath)

        return PRootMountMapping.mapLinuxPathToHostPath(
            linuxPath = expandedPath,
            ubuntuRoot = root,
            homeDir = homeDir
        )
    }

    /**
     * 处理 ~ 展开（~/ 展开为 /root/）
     */
    private fun expandHomePath(path: String): String {
        return when {
            path.startsWith("~/") -> "/root/" + path.substring(2)
            path == "~" -> "/root"
            else -> path
        }
    }
    
    // ==================== 文件读取操作 ====================
    
    override suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val file = File(mappedPath)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "File does not exist or is not a file: $path (mapped to $mappedPath)")
                return@withContext null
            }
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file: $path", e)
            null
        }
    }
    
    override suspend fun readFileWithLimit(path: String, maxBytes: Int): String? = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val file = File(mappedPath)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "File does not exist or is not a file: $path (mapped to $mappedPath)")
                return@withContext null
            }
            
            file.bufferedReader().use { reader ->
                val buffer = CharArray(maxBytes)
                val charsRead = reader.read(buffer, 0, maxBytes)
                if (charsRead > 0) String(buffer, 0, charsRead) else ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file with limit: $path", e)
            null
        }
    }
    
    override suspend fun readFileLines(path: String, startLine: Int, endLine: Int): String? = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val file = File(mappedPath)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "File does not exist or is not a file: $path (mapped to $mappedPath)")
                return@withContext null
            }
            
            val lines = mutableListOf<String>()
            var currentLine = 0
            
            file.bufferedReader().useLines { sequence ->
                sequence.forEach { line ->
                    currentLine++
                    if (currentLine >= startLine && currentLine <= endLine) {
                        lines.add(line)
                    }
                    if (currentLine > endLine) {
                        return@forEach
                    }
                }
            }
            
            lines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file lines: $path", e)
            null
        }
    }
    
    override suspend fun readFileSample(path: String, sampleSize: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val file = File(mappedPath)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "File does not exist or is not a file: $path (mapped to $mappedPath)")
                return@withContext null
            }
            
            file.inputStream().use { input ->
                val buffer = ByteArray(sampleSize)
                val bytesRead = input.read(buffer, 0, sampleSize)
                if (bytesRead > 0) buffer.copyOf(bytesRead) else ByteArray(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file sample: $path", e)
            null
        }
    }
    
    override suspend fun readFileBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val file = File(mappedPath)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "File does not exist or is not a file: $path (mapped to $mappedPath)")
                return@withContext null
            }
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file bytes: $path", e)
            null
        }
    }
    
    // ==================== 文件写入操作 ====================
    
    override suspend fun writeFile(path: String, content: String, append: Boolean): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val file = File(mappedPath)
            
            // 创建父目录
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
            
            // 写入内容
            if (append && file.exists()) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
            
            // 验证写入
            if (!file.exists()) {
                return@withContext FileSystemProvider.OperationResult(
                    success = false,
                    message = "Write completed but file does not exist"
                )
            }
            
            if (file.length() == 0L && content.isNotEmpty()) {
                return@withContext FileSystemProvider.OperationResult(
                    success = false,
                    message = "File was created but appears to be empty"
                )
            }

            FileSystemProvider.OperationResult(
                success = true,
                message = if (append) "Content appended to $path" else "Content written to $path"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Error writing file: ${e.message}"
            )
        }
    }
    
    override suspend fun writeFileBytes(path: String, bytes: ByteArray): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val file = File(mappedPath)
            
            // 创建父目录
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
            
            file.writeBytes(bytes)
            
            // 验证写入
            if (!file.exists()) {
                return@withContext FileSystemProvider.OperationResult(
                    success = false,
                    message = "Write completed but file does not exist"
                )
            }

            FileSystemProvider.OperationResult(
                success = true,
                message = "Binary content written to $path"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file bytes: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Error writing binary file: ${e.message}"
            )
        }
    }
    
    // ==================== 文件/目录管理操作 ====================
    
    override suspend fun listDirectory(path: String): List<FileSystemProvider.FileInfo>? = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val directory = File(mappedPath)
            if (!directory.exists()) {
                Log.w(TAG, "Directory does not exist: $path")
                return@withContext null
            }
            
            if (!directory.isDirectory) {
                Log.w(TAG, "Path is not a directory: $path")
                return@withContext null
            }
            
            val files = directory.listFiles() ?: return@withContext emptyList()
            val dateFormat = SimpleDateFormat("MMM dd HH:mm", Locale.US)
            
            files.mapNotNull { file ->
                if (file.name == "." || file.name == "..") return@mapNotNull null

                FileSystemProvider.FileInfo(
                    name = file.name,
                    isDirectory = file.isDirectory,
                    size = file.length(),
                    permissions = getFilePermissionsString(file),
                    lastModified = dateFormat.format(Date(file.lastModified()))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing directory: $path", e)
            null
        }
    }
    
    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(mapPath(path)).exists()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking existence: $path", e)
            false
        }
    }
    
    override suspend fun isDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(mapPath(path))
            file.exists() && file.isDirectory
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if directory: $path", e)
            false
        }
    }
    
    override suspend fun isFile(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(mapPath(path))
            file.exists() && file.isFile
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if file: $path", e)
            false
        }
    }
    
    override suspend fun getFileSize(path: String): Long = withContext(Dispatchers.IO) {
        try {
            val file = File(mapPath(path))
            if (file.exists() && file.isFile) file.length() else 0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size: $path", e)
            0
        }
    }
    
    override suspend fun getLineCount(path: String): Int = withContext(Dispatchers.IO) {
        try {
            val file = File(mapPath(path))
            if (!file.exists() || !file.isFile) return@withContext 0
            
            var count = 0
            file.bufferedReader().useLines { lines ->
                lines.forEach { count++ }
            }
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error counting lines: $path", e)
            0
        }
    }
    
    override suspend fun createDirectory(path: String, createParents: Boolean): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val directory = File(mappedPath)
            
            if (directory.exists()) {
                return@withContext if (directory.isDirectory) {
                    FileSystemProvider.OperationResult(
                        success = true,
                        message = "Directory already exists: $path"
                    )
                } else {
                    FileSystemProvider.OperationResult(
                        success = false,
                        message = "Path exists but is not a directory: $path"
                    )
                }
            }
            
            val success = if (createParents) directory.mkdirs() else directory.mkdir()

            FileSystemProvider.OperationResult(
                success = success,
                message = if (success) "Successfully created directory $path"
                else "Failed to create directory: parent directory may not exist or permission denied"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating directory: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Error creating directory: ${e.message}"
            )
        }
    }
    
    override suspend fun delete(path: String, recursive: Boolean): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val file = File(mappedPath)
            
            if (!file.exists()) {
                return@withContext FileSystemProvider.OperationResult(
                    success = false,
                    message = "File or directory does not exist: $path"
                )
            }
            
            val success = if (file.isDirectory) {
                if (recursive) {
                    file.deleteRecursively()
                } else {
                    val files = file.listFiles() ?: emptyArray()
                    if (files.isEmpty()) {
                        file.delete()
                    } else {
                        return@withContext FileSystemProvider.OperationResult(
                            success = false,
                            message = "Directory is not empty and recursive flag is not set"
                        )
                    }
                }
            } else {
                file.delete()
            }

            FileSystemProvider.OperationResult(
                success = success,
                message = if (success) "Successfully deleted $path"
                else "Failed to delete: permission denied or file in use"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Error deleting file/directory: ${e.message}"
            )
        }
    }
    
    override suspend fun move(sourcePath: String, destPath: String): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val mappedSourcePath = mapPath(sourcePath)
            val mappedDestPath = mapPath(destPath)
            val sourceFile = File(mappedSourcePath)
            val destFile = File(mappedDestPath)
            
            if (!sourceFile.exists()) {
                return@withContext FileSystemProvider.OperationResult(
                    success = false,
                    message = "Source file does not exist: $sourcePath"
                )
            }
            
            // 创建目标父目录
            destFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
            
            // 尝试重命名
            if (sourceFile.renameTo(destFile)) {
                return@withContext FileSystemProvider.OperationResult(
                    success = true,
                    message = "Successfully moved $sourcePath to $destPath"
                )
            }
            
            // 如果重命名失败，尝试复制+删除
            if (sourceFile.isDirectory) {
                if (copyDirectoryRecursively(sourceFile, destFile) && sourceFile.deleteRecursively()) {
                    return@withContext FileSystemProvider.OperationResult(
                        success = true,
                        message = "Successfully moved $sourcePath to $destPath (via copy and delete)"
                    )
                }
            } else {
                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                if (destFile.exists() && destFile.length() == sourceFile.length() && sourceFile.delete()) {
                    return@withContext FileSystemProvider.OperationResult(
                        success = true,
                        message = "Successfully moved $sourcePath to $destPath (via copy and delete)"
                    )
                }
            }

            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to move file: possibly a permissions issue or destination already exists"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error moving file: $sourcePath to $destPath", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Error moving file: ${e.message}"
            )
        }
    }
    
    override suspend fun copy(sourcePath: String, destPath: String, recursive: Boolean): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val mappedSourcePath = mapPath(sourcePath)
            val mappedDestPath = mapPath(destPath)
            val sourceFile = File(mappedSourcePath)
            val destFile = File(mappedDestPath)
            
            if (!sourceFile.exists()) {
                return@withContext FileSystemProvider.OperationResult(
                    success = false,
                    message = "Source path does not exist: $sourcePath"
                )
            }
            
            // 创建目标父目录
            destFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
            
            if (sourceFile.isDirectory) {
                if (!recursive) {
                    return@withContext FileSystemProvider.OperationResult(
                        success = false,
                        message = "Cannot copy directory without recursive flag"
                    )
                }
                
                val success = copyDirectoryRecursively(sourceFile, destFile)
                return@withContext FileSystemProvider.OperationResult(
                    success = success,
                    message = if (success) "Successfully copied directory $sourcePath to $destPath"
                    else "Failed to copy directory: possible permission issue"
                )
            } else {
                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                if (destFile.exists() && destFile.length() == sourceFile.length()) {
                    return@withContext FileSystemProvider.OperationResult(
                        success = true,
                        message = "Successfully copied file $sourcePath to $destPath"
                    )
                } else {
                    return@withContext FileSystemProvider.OperationResult(
                        success = false,
                        message = "Copy operation completed but verification failed"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying: $sourcePath to $destPath", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Error copying file/directory: ${e.message}"
            )
        }
    }
    
    // ==================== 文件搜索操作 ====================
    
    override suspend fun findFiles(
        basePath: String,
        pattern: String,
        maxDepth: Int,
        caseInsensitive: Boolean
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val mappedBasePath = mapPath(basePath)
            val rootDir = File(mappedBasePath)
            if (!rootDir.exists() || !rootDir.isDirectory) {
                Log.w(TAG, "Base path does not exist or is not a directory: $basePath")
                return@withContext emptyList()
            }
            
            val regex = globToRegex(pattern, caseInsensitive)
            val results = mutableListOf<String>()
            
            findMatchingFiles(rootDir, regex, results, maxDepth, 0, rootDir.absolutePath)

            // Convert mapped absolute paths back to linux paths under basePath
            val linuxBase = basePath.trimEnd('/')
            results
                .asSequence()
                .mapNotNull { abs ->
                    if (!abs.startsWith(rootDir.absolutePath)) return@mapNotNull null
                    val rel = abs.removePrefix(rootDir.absolutePath).trimStart(File.separatorChar)
                    if (rel.isEmpty()) {
                        linuxBase
                    } else {
                        "$linuxBase/$rel"
                    }
                }
                .toList()
        } catch (e: Exception) {
            Log.e(TAG, "Error finding files in: $basePath", e)
            emptyList()
        }
    }
    
    // ==================== 文件信息操作 ====================
    
    override suspend fun getFileInfo(path: String): FileSystemProvider.FileInfo? = withContext(Dispatchers.IO) {
        try {
            val mappedPath = mapPath(path)
            val file = File(mappedPath)
            if (!file.exists()) {
                Log.w(TAG, "File does not exist: $path (mapped to $mappedPath)")
                return@withContext null
            }
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

            FileSystemProvider.FileInfo(
                name = file.name,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0,
                permissions = getFilePermissionsString(file),
                lastModified = dateFormat.format(Date(file.lastModified()))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file info: $path", e)
            null
        }
    }
    
    override suspend fun getPermissions(path: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(mapPath(path))
            if (file.exists()) getFilePermissionsString(file) else ""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting permissions: $path", e)
            ""
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 获取文件权限字符串
     */
    private fun getFilePermissionsString(file: File): String {
        val canRead = if (file.canRead()) 'r' else '-'
        val canWrite = if (file.canWrite()) 'w' else '-'
        val canExecute = if (file.canExecute()) 'x' else '-'
        
        // 简化版本：所有用户使用相同权限
        return "$canRead$canWrite$canExecute$canRead-$canExecute$canRead-$canExecute"
    }
    
    /**
     * 递归复制目录
     */
    private fun copyDirectoryRecursively(sourceDir: File, destDir: File): Boolean {
        try {
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            
            sourceDir.listFiles()?.forEach { file ->
                val destFile = File(destDir, file.name)
                if (file.isDirectory) {
                    copyDirectoryRecursively(file, destFile)
                } else {
                    file.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying directory recursively", e)
            return false
        }
    }
    
    /**
     * 将glob模式转换为正则表达式
     */
    private fun globToRegex(glob: String, caseInsensitive: Boolean): Regex {
        val regex = StringBuilder("^")
        
        for (c in glob) {
            when (c) {
                '*' -> regex.append(".*")
                '?' -> regex.append(".")
                '.' -> regex.append("\\.")
                '\\' -> regex.append("\\\\")
                '[' -> regex.append("[")
                ']' -> regex.append("]")
                '(' -> regex.append("\\(")
                ')' -> regex.append("\\)")
                '{' -> regex.append("(")
                '}' -> regex.append(")")
                ',' -> regex.append("|")
                else -> regex.append(c)
            }
        }
        
        regex.append("$")
        
        return if (caseInsensitive) {
            Regex(regex.toString(), RegexOption.IGNORE_CASE)
        } else {
            Regex(regex.toString())
        }
    }
    
    /**
     * 递归查找匹配的文件
     */
    private fun findMatchingFiles(
        dir: File,
        regex: Regex,
        results: MutableList<String>,
        maxDepth: Int,
        currentDepth: Int,
        rootPath: String
    ) {
        if (maxDepth >= 0 && currentDepth > maxDepth) {
            return
        }
        
        val files = dir.listFiles() ?: return
        
        for (file in files) {
            if (regex.matches(file.name)) {
                results.add(file.absolutePath)
            }
            
            if (file.isDirectory) {
                findMatchingFiles(file, regex, results, maxDepth, currentDepth + 1, rootPath)
            }
        }
    }
}

