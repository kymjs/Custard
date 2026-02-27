package com.ai.assistance.custard.terminal.provider.filesystem

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Vector

/**
 * SSH远程文件系统提供者
 * 使用SFTP协议操作远程文件系统
 * 
 * @param sshSession JSch SSH会话实例，用于创建SFTP通道
 */
class SSHFileSystemProvider(
    private val sshSession: Session
) : FileSystemProvider {
    
    companion object {
        private const val TAG = "SSHFileSystemProvider"
        private const val BUFFER_SIZE = 32768 // 32KB buffer for file operations
    }
    
    // SFTP通道 - 使用懒加载，在第一次使用时创建
    private var sftpChannel: ChannelSftp? = null

    private val sftpChannelLock = Mutex()

    private val sftpOperationLock = Mutex()
    
    // 远程主目录缓存
    private var remoteHome: String? = null
    
    /**
     * 获取或创建SFTP通道
     */
    private suspend fun getSftpChannel(): ChannelSftp = withContext(Dispatchers.IO) {
        sftpChannelLock.withLock {
            // 如果通道已存在且连接，直接返回
            sftpChannel?.let { channel ->
                if (channel.isConnected) {
                    return@withLock channel
                }
            }

            // 创建新的SFTP通道
            try {
                if (!sshSession.isConnected) {
                    throw IllegalStateException("SSH session is not connected")
                }

                val channel = sshSession.openChannel("sftp") as ChannelSftp
                channel.connect(10000) // 10秒超时
                sftpChannel = channel
                Log.d(TAG, "SFTP channel created and connected")
                channel
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SFTP channel", e)
                throw e
            }
        }
    }
    
    /**
     * 关闭SFTP通道
     */
    fun close() {
        sftpChannel?.disconnect()
        sftpChannel = null
        Log.d(TAG, "SFTP channel closed")
    }
    
    /**
     * 获取远程用户主目录
     */
    private suspend fun getRemoteHome(): String = withContext(Dispatchers.IO) {
        remoteHome?.let { return@withContext it }
        
        try {
            val home =
                sftpOperationLock.withLock {
                    val channel = getSftpChannel()
                    channel.home
                }
            remoteHome = home
            Log.d(TAG, "Remote home directory: $home")
            home
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get remote home directory, using /root as fallback", e)
            "/root"
        }
    }
    
    /**
     * 展开路径中的 ~ 符号
     */
    private suspend fun expandPath(path: String): String {
        return when {
            path.startsWith("~/") -> {
                val home = getRemoteHome()
                "$home/${path.substring(2)}"
            }
            path == "~" -> getRemoteHome()
            else -> path
        }
    }
    
    // ==================== 文件读取操作 ====================
    
    override suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            val content =
                sftpOperationLock.withLock {
                    val channel = getSftpChannel()
                    val outputStream = ByteArrayOutputStream()
                    channel.get(expandedPath, outputStream)
                    outputStream.toString("UTF-8")
                }
            content
        } catch (e: SftpException) {
            Log.e(TAG, "[readFile] Failed to read file: $path", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "[readFile] Unexpected error reading file: $path", e)
            null
        }
    }
    
    override suspend fun readFileWithLimit(path: String, maxBytes: Int): String? = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                val inputStream = channel.get(expandedPath)
                val buffer = ByteArray(minOf(maxBytes, BUFFER_SIZE))
                val outputStream = ByteArrayOutputStream()

                var totalRead = 0
                while (totalRead < maxBytes) {
                    val toRead = minOf(buffer.size, maxBytes - totalRead)
                    val bytesRead = inputStream.read(buffer, 0, toRead)
                    if (bytesRead <= 0) break
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                }
                inputStream.close()

                outputStream.toString("UTF-8")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[readFileWithLimit] Failed to read file: $path", e)
            null
        }
    }
    
    override suspend fun readFileLines(path: String, startLine: Int, endLine: Int): String? = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                val inputStream = channel.get(expandedPath)
                val reader = inputStream.bufferedReader(Charsets.UTF_8)

                val result = StringBuilder()
                var currentLine = 1

                reader.use { r ->
                    while (currentLine < startLine) {
                        r.readLine() ?: return@withLock null
                        currentLine++
                    }

                    while (currentLine <= endLine) {
                        val line = r.readLine() ?: break
                        result.append(line).append("\n")
                        currentLine++
                    }
                }

                result.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[readFileLines] Failed to read file lines: $path", e)
            null
        }
    }
    
    override suspend fun readFileSample(path: String, sampleSize: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                val inputStream = channel.get(expandedPath)
                val buffer = ByteArray(sampleSize)
                val bytesRead = inputStream.read(buffer, 0, sampleSize)
                inputStream.close()

                if (bytesRead <= 0) ByteArray(0) else buffer.copyOf(bytesRead)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[readFileSample] Failed to read file sample: $path", e)
            null
        }
    }
    
    override suspend fun readFileBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                val outputStream = ByteArrayOutputStream()
                channel.get(expandedPath, outputStream)
                outputStream.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[readFileBytes] Failed to read file bytes: $path", e)
            null
        }
    }
    
    // ==================== 文件写入操作 ====================
    
    override suspend fun writeFile(path: String, content: String, append: Boolean): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "[writeFile] Path: $path, content length: ${content.length}, append: $append")
        
        try {
            val expandedPath = expandPath(path)
            val bytes = content.toByteArray(Charsets.UTF_8)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()

                // 先创建父目录
                val parentDir = expandedPath.substringBeforeLast('/')
                if (parentDir.isNotEmpty() && parentDir != expandedPath) {
                    createDirectoryRecursive(channel, parentDir)
                }

                val inputStream = ByteArrayInputStream(bytes)

                if (append) {
                    channel.put(inputStream, expandedPath, ChannelSftp.APPEND)
                } else {
                    channel.put(inputStream, expandedPath, ChannelSftp.OVERWRITE)
                }
            }

            Log.d(TAG, "[writeFile] Successfully wrote ${bytes.size} bytes to $path")
            FileSystemProvider.OperationResult(
                success = true,
                message = if (append) "Content appended to $path" else "Content written to $path"
            )
        } catch (e: SftpException) {
            Log.e(TAG, "[writeFile] SFTP error writing file: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to write file: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[writeFile] Unexpected error writing file: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to write file: ${e.message}"
            )
        }
    }
    
    override suspend fun writeFileBytes(path: String, bytes: ByteArray): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()

                // 先创建父目录
                val parentDir = expandedPath.substringBeforeLast('/')
                if (parentDir.isNotEmpty() && parentDir != expandedPath) {
                    createDirectoryRecursive(channel, parentDir)
                }

                val inputStream = ByteArrayInputStream(bytes)
                channel.put(inputStream, expandedPath, ChannelSftp.OVERWRITE)
            }

            Log.d(TAG, "[writeFileBytes] Successfully wrote ${bytes.size} bytes to $path")
            FileSystemProvider.OperationResult(
                success = true,
                message = "Binary content written to $path"
            )
        } catch (e: SftpException) {
            Log.e(TAG, "[writeFileBytes] SFTP error writing file: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to write binary file: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[writeFileBytes] Unexpected error writing file: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to write binary file: ${e.message}"
            )
        }
    }
    
    /**
     * 递归创建目录
     */
    private fun createDirectoryRecursive(channel: ChannelSftp, path: String) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val isAbsolute = path.startsWith('/')
        
        var currentPath = if (isAbsolute) "" else "."
        for (part in parts) {
            currentPath = if (currentPath.isEmpty() || currentPath == ".") {
                if (isAbsolute) "/$part" else part
            } else {
                "$currentPath/$part"
            }
            
            try {
                channel.stat(currentPath)
            } catch (e: SftpException) {
                // 目录不存在，创建它
                try {
                    channel.mkdir(currentPath)
                    Log.d(TAG, "[createDirectoryRecursive] Created directory: $currentPath")
                } catch (e2: SftpException) {
                    Log.w(TAG, "[createDirectoryRecursive] Failed to create directory: $currentPath", e2)
                }
            }
        }
    }
    
    // ==================== 文件/目录管理操作 ====================
    
    override suspend fun listDirectory(path: String): List<FileSystemProvider.FileInfo>? = withContext(Dispatchers.IO) {
        Log.d(TAG, "[listDirectory] Listing directory: $path")
        try {
            val expandedPath = expandPath(path)
            val fileInfoList =
                sftpOperationLock.withLock {
                    val channel = getSftpChannel()
                    @Suppress("UNCHECKED_CAST")
                    val entries = channel.ls(expandedPath) as Vector<ChannelSftp.LsEntry>

                    entries
                        .filter { it.filename != "." && it.filename != ".." }
                        .map { entry ->
                            val attrs = entry.attrs
                            FileSystemProvider.FileInfo(
                                name = entry.filename,
                                isDirectory = attrs.isDir,
                                size = attrs.size,
                                permissions = attrs.permissionsString,
                                lastModified = (attrs.mTime * 1000L).toString()
                            )
                        }
                }

            Log.d(TAG, "[listDirectory] Found ${fileInfoList.size} entries in $path")
            fileInfoList
        } catch (e: SftpException) {
            Log.e(TAG, "[listDirectory] Failed to list directory: $path", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "[listDirectory] Unexpected error listing directory: $path", e)
            null
        }
    }
    
    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                channel.stat(expandedPath)
                true
            }
        } catch (e: SftpException) {
            false
        } catch (e: Exception) {
            Log.e(TAG, "[exists] Unexpected error checking path: $path", e)
            false
        }
    }
    
    override suspend fun isDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                val attrs = channel.stat(expandedPath)
                attrs.isDir
            }
        } catch (e: Exception) {
            false
        }
    }
    
    override suspend fun isFile(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                val attrs = channel.stat(expandedPath)
                !attrs.isDir && !attrs.isLink
            }
        } catch (e: Exception) {
            false
        }
    }
    
    override suspend fun getFileSize(path: String): Long = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                val attrs = channel.stat(expandedPath)
                attrs.size
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    override suspend fun getLineCount(path: String): Int = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                val inputStream = channel.get(expandedPath)
                val reader = inputStream.bufferedReader(Charsets.UTF_8)
                var count = 0
                reader.use { r ->
                    while (r.readLine() != null) {
                        count++
                    }
                }
                count
            }
        } catch (e: Exception) {
            Log.e(TAG, "[getLineCount] Failed to count lines: $path", e)
            0
        }
    }
    
    override suspend fun createDirectory(path: String, createParents: Boolean): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "[createDirectory] Path: $path, createParents: $createParents")
        
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()

                // 检查目录是否已存在
                try {
                    val attrs = channel.stat(expandedPath)
                    if (attrs.isDir) {
                        return@withContext FileSystemProvider.OperationResult(
                            success = true,
                            message = "Directory already exists: $path"
                        )
                    }
                } catch (e: SftpException) {
                    // 目录不存在，继续创建
                }

                if (createParents) {
                    createDirectoryRecursive(channel, expandedPath)
                } else {
                    channel.mkdir(expandedPath)
                }
            }
            
            Log.d(TAG, "[createDirectory] Successfully created directory: $path")
            FileSystemProvider.OperationResult(
                success = true,
                message = "Successfully created directory $path"
            )
        } catch (e: SftpException) {
            Log.e(TAG, "[createDirectory] SFTP error creating directory: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to create directory: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[createDirectory] Unexpected error creating directory: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to create directory: ${e.message}"
            )
        }
    }
    
    override suspend fun delete(path: String, recursive: Boolean): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "[delete] Path: $path, recursive: $recursive")
        
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()

                val isDir =
                    if (!recursive) {
                        false
                    } else {
                        runCatching { channel.stat(expandedPath).isDir }.getOrDefault(false)
                    }

                if (recursive && isDir) {
                    deleteRecursive(channel, expandedPath)
                } else {
                    // 删除文件或空目录
                    try {
                        channel.rm(expandedPath)
                    } catch (e: SftpException) {
                        channel.rmdir(expandedPath)
                    }
                }
            }
            
            Log.d(TAG, "[delete] Successfully deleted: $path")
            FileSystemProvider.OperationResult(
                success = true,
                message = "Successfully deleted $path"
            )
        } catch (e: SftpException) {
            Log.e(TAG, "[delete] SFTP error deleting: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to delete: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[delete] Unexpected error deleting: $path", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to delete: ${e.message}"
            )
        }
    }
    
    /**
     * 递归删除目录
     */
    private fun deleteRecursive(channel: ChannelSftp, path: String) {
        @Suppress("UNCHECKED_CAST")
        val entries = channel.ls(path) as Vector<ChannelSftp.LsEntry>
        
        for (entry in entries) {
            if (entry.filename == "." || entry.filename == "..") continue
            
            val fullPath = "$path/${entry.filename}"
            if (entry.attrs.isDir) {
                deleteRecursive(channel, fullPath)
            } else {
                channel.rm(fullPath)
            }
        }
        
        channel.rmdir(path)
    }
    
    override suspend fun move(sourcePath: String, destPath: String): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val expandedSourcePath = expandPath(sourcePath)
            val expandedDestPath = expandPath(destPath)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                channel.rename(expandedSourcePath, expandedDestPath)
            }

            FileSystemProvider.OperationResult(
                success = true,
                message = "Successfully moved $sourcePath to $destPath"
            )
        } catch (e: SftpException) {
            Log.e(TAG, "[move] SFTP error moving file", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to move file: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[move] Unexpected error moving file", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to move file: ${e.message}"
            )
        }
    }
    
    override suspend fun copy(sourcePath: String, destPath: String, recursive: Boolean): FileSystemProvider.OperationResult = withContext(Dispatchers.IO) {
        try {
            val expandedSourcePath = expandPath(sourcePath)
            val expandedDestPath = expandPath(destPath)

            var isSourceDir = false
            sftpOperationLock.withLock {
                val channel = getSftpChannel()

                // 检查源路径是否存在
                val sourceAttrs = try {
                    channel.stat(expandedSourcePath)
                } catch (e: SftpException) {
                    return@withContext FileSystemProvider.OperationResult(
                        success = false,
                        message = "Source path does not exist: $sourcePath"
                    )
                }

                isSourceDir = sourceAttrs.isDir

                // 确保目标父目录存在
                val destParentDir = expandedDestPath.substringBeforeLast('/')
                if (destParentDir.isNotEmpty() && destParentDir != expandedDestPath) {
                    createDirectoryRecursive(channel, destParentDir)
                }

                if (sourceAttrs.isDir) {
                    if (!recursive) {
                        return@withContext FileSystemProvider.OperationResult(
                            success = false,
                            message = "Cannot copy directory without recursive flag"
                        )
                    }
                    copyDirectoryRecursive(channel, expandedSourcePath, expandedDestPath)
                } else {
                    // 复制单个文件
                    val inputStream = channel.get(expandedSourcePath)
                    val outputStream = ByteArrayOutputStream()
                    inputStream.copyTo(outputStream)
                    inputStream.close()

                    val bytes = outputStream.toByteArray()
                    val destInputStream = ByteArrayInputStream(bytes)
                    channel.put(destInputStream, expandedDestPath)
                }
            }

            FileSystemProvider.OperationResult(
                success = true,
                message = "Successfully copied ${if (isSourceDir) "directory" else "file"} $sourcePath to $destPath"
            )
        } catch (e: SftpException) {
            Log.e(TAG, "[copy] SFTP error copying", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to copy: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[copy] Unexpected error copying", e)
            FileSystemProvider.OperationResult(
                success = false,
                message = "Failed to copy: ${e.message}"
            )
        }
    }
    
    /**
     * 递归复制目录
     */
    private fun copyDirectoryRecursive(channel: ChannelSftp, sourcePath: String, destPath: String) {
        // 创建目标目录
        try {
            channel.mkdir(destPath)
        } catch (e: SftpException) {
            // 目录可能已存在
        }
        
        @Suppress("UNCHECKED_CAST")
        val entries = channel.ls(sourcePath) as Vector<ChannelSftp.LsEntry>
        
        for (entry in entries) {
            if (entry.filename == "." || entry.filename == "..") continue
            
            val sourceFullPath = "$sourcePath/${entry.filename}"
            val destFullPath = "$destPath/${entry.filename}"
            
            if (entry.attrs.isDir) {
                copyDirectoryRecursive(channel, sourceFullPath, destFullPath)
            } else {
                // 复制文件
                val inputStream = channel.get(sourceFullPath)
                val outputStream = ByteArrayOutputStream()
                inputStream.copyTo(outputStream)
                inputStream.close()
                
                val bytes = outputStream.toByteArray()
                val destInputStream = ByteArrayInputStream(bytes)
                channel.put(destInputStream, destFullPath)
            }
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
            val expandedBasePath = expandPath(basePath)
            val results = mutableListOf<String>()
            
            // 将glob模式转换为正则表达式
            val regex = globToRegex(pattern, caseInsensitive)

            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                searchFilesRecursive(channel, expandedBasePath, regex, maxDepth, 0, results)
            }
            
            results
        } catch (e: Exception) {
            Log.e(TAG, "[findFiles] Error searching files", e)
            emptyList()
        }
    }
    
    /**
     * 递归搜索文件
     */
    private fun searchFilesRecursive(
        channel: ChannelSftp,
        currentPath: String,
        pattern: Regex,
        maxDepth: Int,
        currentDepth: Int,
        results: MutableList<String>
    ) {
        if (maxDepth >= 0 && currentDepth > maxDepth) return
        
        try {
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(currentPath) as Vector<ChannelSftp.LsEntry>
            
            for (entry in entries) {
                if (entry.filename == "." || entry.filename == "..") continue
                
                val fullPath = "$currentPath/${entry.filename}"
                
                if (pattern.matches(entry.filename)) {
                    results.add(fullPath)
                }
                
                if (entry.attrs.isDir) {
                    searchFilesRecursive(channel, fullPath, pattern, maxDepth, currentDepth + 1, results)
                }
            }
        } catch (e: SftpException) {
            Log.w(TAG, "[searchFilesRecursive] Cannot access directory: $currentPath", e)
        }
    }
    
    /**
     * 将glob模式转换为正则表达式
     */
    private fun globToRegex(glob: String, caseInsensitive: Boolean): Regex {
        val regexPattern = buildString {
            append('^')
            for (char in glob) {
                when (char) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.' -> append("\\.")
                    '\\' -> append("\\\\")
                    else -> append(char)
                }
            }
            append('$')
        }
        
        return if (caseInsensitive) {
            Regex(regexPattern, RegexOption.IGNORE_CASE)
        } else {
            Regex(regexPattern)
        }
    }
    
    // ==================== 文件信息操作 ====================
    
    override suspend fun getFileInfo(path: String): FileSystemProvider.FileInfo? = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            val attrs =
                sftpOperationLock.withLock {
                    val channel = getSftpChannel()
                    channel.stat(expandedPath)
                }
            
            val name = path.substringAfterLast('/')

            FileSystemProvider.FileInfo(
                name = name,
                isDirectory = attrs.isDir,
                size = attrs.size,
                permissions = attrs.permissionsString,
                lastModified = (attrs.mTime * 1000L).toString()
            )
        } catch (e: SftpException) {
            Log.e(TAG, "[getFileInfo] File not found: $path", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "[getFileInfo] Unexpected error getting file info: $path", e)
            null
        }
    }
    
    override suspend fun getPermissions(path: String): String = withContext(Dispatchers.IO) {
        try {
            val expandedPath = expandPath(path)
            sftpOperationLock.withLock {
                val channel = getSftpChannel()
                val attrs = channel.stat(expandedPath)
                attrs.permissionsString
            }
        } catch (e: Exception) {
            ""
        }
    }
}

