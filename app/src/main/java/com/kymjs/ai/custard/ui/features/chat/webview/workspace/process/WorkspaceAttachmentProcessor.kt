package com.kymjs.ai.custard.ui.features.chat.webview.workspace.process

import android.content.Context
import com.kymjs.ai.custard.util.AppLogger
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.core.tools.AIToolHandler
import com.kymjs.ai.custard.core.tools.DirectoryListingData
import com.kymjs.ai.custard.data.model.AITool
import com.kymjs.ai.custard.data.model.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区附着处理器
 * 负责生成包含工作区状态信息的XML附着内容
 */
object WorkspaceAttachmentProcessor {
    private const val TAG = "WorkspaceAttachmentProcessor"

    private const val DEFAULT_TIME_PATTERN_WITH_MS = "yyyy-MM-dd HH:mm:ss.SSS"
    private const val DEFAULT_TIME_PATTERN_NO_MS = "yyyy-MM-dd HH:mm:ss"

    // 用于缓存工作区状态
    private data class FileMetadata(val path: String, val size: Long, val lastModified: String, val isDirectory: Boolean)
    private val workspaceStateCache = mutableMapOf<String, List<FileMetadata>>()

    /**
     * 生成工作区附着XML内容
     * @param context 上下文
     * @param workspacePath 工作区路径
     * @return 包含工作区信息的XML字符串
     */
    suspend fun generateWorkspaceAttachment(
        context: Context,
        workspacePath: String?,
        workspaceEnv: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (workspacePath.isNullOrBlank()) {
            return@withContext generateEmptyWorkspaceXml(context)
        }

        try {
            val toolHandler = AIToolHandler.getInstance(context)

            if (!workspaceEnv.isNullOrBlank()) {
                val existsRes =
                    toolHandler.executeTool(
                        AITool(
                            name = "file_exists",
                            parameters = listOf(
                                ToolParameter("path", workspacePath),
                                ToolParameter("environment", workspaceEnv)
                            )
                        )
                    )
                val existsData = existsRes.result as? com.kymjs.ai.custard.core.tools.FileExistsData
                if (existsData == null || !existsData.exists || !existsData.isDirectory) {
                    AppLogger.w(TAG, context.getString(R.string.workspace_error_invalid_path, workspacePath))
                    workspaceStateCache.remove(makeCacheKey(workspacePath, workspaceEnv))
                    return@withContext generateEmptyWorkspaceXml(context)
                }
            } else {
                val workspaceDir = File(workspacePath)
                if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
                    AppLogger.w(TAG, context.getString(R.string.workspace_error_invalid_path, workspacePath))
                    // 清除无效路径的缓存
                    workspaceStateCache.remove(makeCacheKey(workspacePath, null))
                    return@withContext generateEmptyWorkspaceXml(context)
                }
            }

            // 获取工作区目录结构及其变化
            val directoryStructure = getWorkspaceStructureAndDiff(context, toolHandler, workspacePath, workspaceEnv)

            // 获取工作区错误信息
            val workspaceErrors = getWorkspaceErrors(context, toolHandler, workspacePath)

            // 获取用户改动记录
            val userChanges = getUserChanges(context, toolHandler, workspacePath, workspaceEnv)

            // 生成完整的XML
            buildWorkspaceXml(
                directoryStructure = directoryStructure,
                workspaceErrors = workspaceErrors,
                userChanges = userChanges
            )

        } catch (e: Exception) {
            AppLogger.e(TAG, context.getString(R.string.workspace_error_generate_attachment), e)
            generateErrorWorkspaceXml(context, e.message ?: context.getString(R.string.workspace_unknown_error))
        }
    }

    /**
     * 获取工作区建议
     */
    private suspend fun getWorkspaceSuggestions(
        context: Context,
        toolHandler: AIToolHandler,
        workspacePath: String,
        workspaceEnv: String?
    ): String {
        val suggestions = mutableListOf<String>()
        try {
            val ignoreRules = loadGitIgnoreRules(toolHandler, workspacePath, workspaceEnv)

            val hasHtmlFiles =
                if (!workspaceEnv.isNullOrBlank()) {
                    val entries = listRootEntries(toolHandler, workspacePath, workspaceEnv)
                    entries
                        .asSequence()
                        .filter { !it.isDirectory }
                        .filter { !GitIgnoreFilter.shouldIgnore(it.name, it.name, isDirectory = false, rules = ignoreRules) }
                        .any {
                            val ext = it.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                            ext == "html" || ext == "htm"
                        }
                } else {
                    val workspaceDir = File(workspacePath)
                    workspaceDir.listFiles()
                        ?.filter { !GitIgnoreFilter.shouldIgnore(it, workspaceDir, ignoreRules) }
                        ?.filter { it.isFile }
                        ?.any { it.extension.lowercase() == "html" || it.extension.lowercase() == "htm" }
                        ?: false
                }

            // 只有在有HTML文件时才显示H5相关建议
            if (hasHtmlFiles) {
                // 提醒AI分离文件
                suggestions.add(context.getString(R.string.workspace_suggestion_separate_files))

                // 建议创建子目录来组织文件（常驻建议）
                suggestions.add(context.getString(R.string.workspace_suggestion_create_folders))
            }

            return if (suggestions.isNotEmpty()) {
                suggestions.joinToString("\n")
            } else {
                context.getString(R.string.workspace_no_suggestions)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, context.getString(R.string.workspace_error_check_suggestions), e)
            return context.getString(R.string.workspace_error_get_suggestions, e.message ?: "")
        }
    }

    /**
     * 获取工作区目录结构，并与缓存进行比较以生成差异报告
     */
    private suspend fun getWorkspaceStructureAndDiff(
        context: Context,
        toolHandler: AIToolHandler,
        workspacePath: String,
        workspaceEnv: String?
    ): String {
        val cacheKey = makeCacheKey(workspacePath, workspaceEnv)
        val newFileMetadatas = getCurrentWorkspaceState(toolHandler, workspacePath, workspaceEnv)
        val oldFileMetadatas = workspaceStateCache[cacheKey]

        // 总是更新缓存
        workspaceStateCache[cacheKey] = newFileMetadatas

        val fullStructure = buildStructureStringFromMetadata(newFileMetadatas, workspacePath)

        if (oldFileMetadatas == null) {
            // 首次加载，只显示根目录
            val rootLevelStructure = buildRootLevelStructure(context, toolHandler, workspacePath, workspaceEnv)
            return context.getString(R.string.workspace_first_load, rootLevelStructure)
        }

        // --- 计算差异 ---
        val oldStateMap = oldFileMetadatas.associateBy { it.path }
        val newStateMap = newFileMetadatas.associateBy { it.path }

        val addedFiles = newFileMetadatas.filter { it.path !in oldStateMap }
        val deletedFiles = oldFileMetadatas.filter { it.path !in newStateMap }

        val modifiedFiles = newFileMetadatas.filter {
            val oldMeta = oldStateMap[it.path]
            // 文件存在于旧状态中，且不是目录，且大小或修改时间已改变
            oldMeta != null && !it.isDirectory && (it.size != oldMeta.size || it.lastModified != oldMeta.lastModified)
        }

        if (addedFiles.isEmpty() && deletedFiles.isEmpty() && modifiedFiles.isEmpty()) {
            return context.getString(R.string.workspace_no_changes, fullStructure)
        }

        // --- 构建差异报告字符串 ---
        val diffBuilder = StringBuilder()
        diffBuilder.append(context.getString(R.string.workspace_structure_changes))
        if (addedFiles.isNotEmpty()) {
            diffBuilder.append(context.getString(R.string.workspace_added_files))
            addedFiles.forEach { diffBuilder.append("    - ${it.path.replace('\\', '/')}\n") }
        }
        if (modifiedFiles.isNotEmpty()) {
            diffBuilder.append(context.getString(R.string.workspace_modified_files))
            modifiedFiles.forEach { diffBuilder.append("    - ${it.path.replace('\\', '/')}\n") }
        }
        if (deletedFiles.isNotEmpty()) {
            diffBuilder.append(context.getString(R.string.workspace_deleted_files))
            deletedFiles.forEach { diffBuilder.append("    - ${it.path.replace('\\', '/')}\n") }
        }

        diffBuilder.append(context.getString(R.string.workspace_current_structure))
        diffBuilder.append(fullStructure)

        return diffBuilder.toString()
    }

    /**
     * 获取根目录文件状态（仅扫描根目录，不深度遍历）
     */
    private suspend fun getCurrentWorkspaceState(
        toolHandler: AIToolHandler,
        workspacePath: String,
        workspaceEnv: String?
    ): List<FileMetadata> {
        val ignoreRules = loadGitIgnoreRules(toolHandler, workspacePath, workspaceEnv)
        if (!workspaceEnv.isNullOrBlank()) {
            return listRootEntries(toolHandler, workspacePath, workspaceEnv)
                .asSequence()
                .filterNot { GitIgnoreFilter.shouldIgnore(it.name, it.name, isDirectory = it.isDirectory, rules = ignoreRules) }
                .map { entry ->
                    FileMetadata(
                        path = entry.name,
                        size = if (entry.isDirectory) 0L else entry.size,
                        lastModified = entry.lastModified,
                        isDirectory = entry.isDirectory
                    )
                }
                .toList()
        }

        val workspaceDir = File(workspacePath)
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
            return emptyList()
        }

        val formatter = SimpleDateFormat(DEFAULT_TIME_PATTERN_WITH_MS, Locale.US)
        return workspaceDir.listFiles()
            ?.filter { !GitIgnoreFilter.shouldIgnore(it, workspaceDir, ignoreRules) }
            ?.map { file ->
                FileMetadata(
                    path = file.name,
                    size = if (file.isFile) file.length() else 0,
                    lastModified = formatter.format(Date(file.lastModified())),
                    isDirectory = file.isDirectory
                )
            }
            ?: emptyList()
    }

    /**
     * 构建根目录级别的结构（仅显示根目录下的直接子项）
     */
    private suspend fun buildRootLevelStructure(
        context: Context,
        toolHandler: AIToolHandler,
        workspacePath: String,
        workspaceEnv: String?
    ): String {
        val ignoreRules = loadGitIgnoreRules(toolHandler, workspacePath, workspaceEnv)
        if (!workspaceEnv.isNullOrBlank()) {
            val rootItems = listRootEntries(toolHandler, workspacePath, workspaceEnv)
                .asSequence()
                .filterNot { GitIgnoreFilter.shouldIgnore(it.name, it.name, isDirectory = it.isDirectory, rules = ignoreRules) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                .toList()

            if (rootItems.isEmpty()) {
                return context.getString(R.string.workspace_is_empty)
            }

            val builder = StringBuilder()
            rootItems.forEachIndexed { index, entry ->
                val isLast = index == rootItems.size - 1
                val prefix = if (isLast) "└── " else "├── "
                val icon = if (entry.isDirectory) "📁" else "📄"

                builder.append("$prefix$icon ${entry.name}")
                if (!entry.isDirectory && entry.size > 0) {
                    builder.append(" (${formatFileSize(entry.size)})")
                }
                builder.append("\n")
            }

            return builder.toString()
        }

        val workspaceDir = File(workspacePath)
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
            return context.getString(R.string.workspace_not_exists_or_not_dir)
        }

        val rootItems = workspaceDir.listFiles()
            ?.filter { !GitIgnoreFilter.shouldIgnore(it, workspaceDir, ignoreRules) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: emptyList()

        if (rootItems.isEmpty()) {
            return context.getString(R.string.workspace_is_empty)
        }

        val builder = StringBuilder()
        rootItems.forEachIndexed { index, file ->
            val isLast = index == rootItems.size - 1
            val prefix = if (isLast) "└── " else "├── "
            val icon = if (file.isDirectory) "📁" else "📄"

            builder.append("$prefix$icon ${file.name}")
            if (file.isFile && file.length() > 0) {
                builder.append(" (${formatFileSize(file.length())})")
            }
            builder.append("\n")
        }

        return builder.toString()
    }

    /**
     * 从文件元数据列表构建树形结构的字符串
     */
    private fun buildStructureStringFromMetadata(metadatas: List<FileMetadata>, _workspacePath: String): String {
        if (metadatas.isEmpty()) return "Workspace is empty"

        val root = Node(".")
        // 根据路径构建节点树
        metadatas.forEach { metadata ->
            var currentNode = root
            metadata.path
                .replace('\\', '/')
                .split('/')
                .filter { it.isNotBlank() }
                .forEach { component ->
                currentNode = currentNode.children.getOrPut(component) { Node(component) }
            }
            currentNode.metadata = metadata
        }

        val builder = StringBuilder()
        buildTreeString(root, "", true, builder)
        return builder.toString()
    }

    // 辅助节点类
    private data class Node(
        val name: String,
        val children: MutableMap<String, Node> = mutableMapOf(),
        var metadata: FileMetadata? = null
    )

    /**
     * 递归构建树形字符串
     */
    private fun buildTreeString(node: Node, indent: String, _isLast: Boolean, builder: StringBuilder) {
        // 排序：文件夹在前，文件在后，然后按名称排序
        val sortedChildren = node.children.values.sortedWith(
            compareBy({ it.metadata?.isDirectory == false }, { it.name })
        )

        sortedChildren.forEachIndexed { index, childNode ->
            val isCurrentLast = index == sortedChildren.size - 1
            val prefix = if (isCurrentLast) "└── " else "├── "
            val icon = if (childNode.metadata?.isDirectory == true) "📁" else "📄"

            builder.append("$indent$prefix$icon ${childNode.name}")
            if (childNode.metadata?.isDirectory == false && childNode.metadata!!.size > 0) {
                builder.append(" (${formatFileSize(childNode.metadata!!.size)})")
            }
            builder.append("\n")

            if (childNode.metadata?.isDirectory == true) {
                val newIndent = indent + if (isCurrentLast) "    " else "│   "
                buildTreeString(childNode, newIndent, isCurrentLast, builder)
            }
        }
    }

    /**
     * 获取工作区错误信息
     */
    private suspend fun getWorkspaceErrors(
        context: Context,
        toolHandler: AIToolHandler,
        workspacePath: String
    ): String {
        // TODO: 实现具体的错误检测逻辑
        // 这里可以检查文件语法错误、依赖问题等
        return try {
            // 检查常见错误文件类型
            val errorFiles = mutableListOf<String>()

            // 检查HTML文件
            checkHtmlErrors(toolHandler, workspacePath, errorFiles)

            // 检查CSS文件
            checkCssErrors(toolHandler, workspacePath, errorFiles)

            // 检查JavaScript文件
            checkJsErrors(toolHandler, workspacePath, errorFiles)

            if (errorFiles.isEmpty()) {
                context.getString(R.string.workspace_no_errors_found)
            } else {
                errorFiles.joinToString("\n")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, context.getString(R.string.workspace_error_check_errors), e)
            context.getString(R.string.workspace_error_get_errors, e.message ?: "")
        }
    }

    /**
     * 获取用户改动记录
     */
    private suspend fun getUserChanges(
        context: Context,
        _toolHandler: AIToolHandler,
        workspacePath: String,
        workspaceEnv: String?
    ): String {
        // TODO: 实现用户改动跟踪逻辑
        // 这里可以记录文件的修改时间、内容变化等
        return try {
            val recentFiles = mutableListOf<String>()

            if (!workspaceEnv.isNullOrBlank()) {
                val toolHandler = AIToolHandler.getInstance(context)
                val ignoreRules = loadGitIgnoreRules(toolHandler, workspacePath, workspaceEnv)
                val currentTime = System.currentTimeMillis()
                val oneDayAgo = currentTime - 24 * 60 * 60 * 1000
                val candidates = listRootEntries(toolHandler, workspacePath, workspaceEnv)
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .filterNot { GitIgnoreFilter.shouldIgnore(it.name, it.name, isDirectory = false, rules = ignoreRules) }
                    .mapNotNull { entry ->
                        val lastMs = parseLastModifiedToMillis(entry.lastModified) ?: return@mapNotNull null
                        if (lastMs <= oneDayAgo) return@mapNotNull null
                        entry.name to lastMs
                    }
                    .sortedByDescending { it.second }
                    .take(10)
                    .toList()

                candidates.forEach { (name, lastMs) ->
                    val timeAgo = formatTimeAgo(context, currentTime - lastMs)
                    recentFiles.add(context.getString(R.string.workspace_file_time_ago, name, timeAgo))
                }
            } else {
                val workspaceDir = File(workspacePath)
                getRecentlyModifiedFiles(context, workspaceDir, recentFiles)
            }

            if (recentFiles.isEmpty()) {
                context.getString(R.string.workspace_no_recent_changes)
            } else {
                context.getString(R.string.workspace_recently_modified_files, recentFiles.joinToString("\n"))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, context.getString(R.string.workspace_error_get_user_changes), e)
            context.getString(R.string.workspace_error_get_changes, e.message ?: "")
        }
    }

    /**
     * 检查HTML文件错误
     */
    private suspend fun checkHtmlErrors(
        _toolHandler: AIToolHandler,
        _workspacePath: String,
        _errorFiles: MutableList<String>
    ) {
        // TODO: 实现HTML语法检查
        // 可以检查标签闭合、属性格式等
    }

    /**
     * 检查CSS文件错误
     */
    private suspend fun checkCssErrors(
        _toolHandler: AIToolHandler,
        _workspacePath: String,
        _errorFiles: MutableList<String>
    ) {
        // TODO: 实现CSS语法检查
        // 可以检查选择器、属性值等
    }

    /**
     * 检查JavaScript文件错误
     */
    private suspend fun checkJsErrors(
        _toolHandler: AIToolHandler,
        _workspacePath: String,
        _errorFiles: MutableList<String>
    ) {
        // TODO: 实现JavaScript语法检查
        // 可以检查基本语法错误
    }

    /**
     * 获取最近修改的文件
     */
    private fun getRecentlyModifiedFiles(
        context: Context,
        workspaceDir: File,
        recentFiles: MutableList<String>
    ) {
        try {
            val currentTime = System.currentTimeMillis()
            val oneDayAgo = currentTime - 24 * 60 * 60 * 1000 // 24小时前

            // 加载 gitignore 规则
            val ignoreRules = GitIgnoreFilter.loadRules(workspaceDir)

            // 只监听根目录下的文件，与 buildSimpleStructure 保持一致
            workspaceDir.listFiles()
                ?.filter { it.isFile } // 只处理文件
                ?.filter { file ->
                    // 过滤应该被忽略的文件
                    !GitIgnoreFilter.shouldIgnore(file, workspaceDir, ignoreRules)
                }
                ?.filter { it.lastModified() > oneDayAgo }
                ?.sortedByDescending { it.lastModified() }
                ?.take(10) // 最多显示10个文件
                ?.forEach { file ->
                    val timeAgo = formatTimeAgo(context, currentTime - file.lastModified())
                    recentFiles.add(context.getString(R.string.workspace_file_time_ago, file.name, timeAgo))
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, context.getString(R.string.workspace_error_get_recent_files), e)
        }
    }

    /**
     * 构建完整的工作区XML
     */
    private fun buildWorkspaceXml(
        directoryStructure: String,
        workspaceErrors: String,
        userChanges: String
    ): String {
        return """
<workspace_context>
<directory_structure>
    $directoryStructure
</directory_structure>

<workspace_errors>
    $workspaceErrors
</workspace_errors>

<user_changes>
    $userChanges
</user_changes>

</workspace_context>""".trimIndent()
    }

    /**
     * 生成空工作区XML
     */
    private fun generateEmptyWorkspaceXml(context: Context): String {
        return """
            <workspace_context>
                <directory_structure>
                    ${context.getString(R.string.workspace_empty_description)}
                </directory_structure>

                <workspace_errors>
                    ${context.getString(R.string.workspace_empty_errors)}
                </workspace_errors>

                <user_changes>
                    ${context.getString(R.string.workspace_empty_changes)}
                </user_changes>
            </workspace_context>
        """.trimIndent()
    }

    /**
     * 生成错误工作区XML
     */
    private fun generateErrorWorkspaceXml(context: Context, errorMessage: String): String {
        return """
            <workspace_context>
                <directory_structure>
                    ${context.getString(R.string.workspace_error_fetch_description, errorMessage)}
                </directory_structure>

                <workspace_errors>
                    ${context.getString(R.string.workspace_error_fetch_errors, errorMessage)}
                </workspace_errors>

                <user_changes>
                    ${context.getString(R.string.workspace_error_fetch_changes, errorMessage)}
                </user_changes>
            </workspace_context>
        """.trimIndent()
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${bytes / (1024 * 1024)}MB"
        }
    }

    /**
     * 格式化时间差
     */
    private fun formatTimeAgo(context: Context, millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> context.getString(R.string.workspace_hours_ago, hours)
            minutes > 0 -> context.getString(R.string.workspace_minutes_ago, minutes)
            else -> context.getString(R.string.workspace_just_now)
        }
    }

    private fun makeCacheKey(workspacePath: String, workspaceEnv: String?): String {
        return if (workspaceEnv.isNullOrBlank()) workspacePath else "$workspaceEnv::$workspacePath"
    }

    private suspend fun loadGitIgnoreRules(
        toolHandler: AIToolHandler,
        workspacePath: String,
        workspaceEnv: String?
    ): List<String> {
        val rules = mutableListOf<String>()
        rules.addAll(listOf(".backup", ".custard"))

        if (workspaceEnv.isNullOrBlank()) {
            val workspaceDir = File(workspacePath)
            rules.addAll(GitIgnoreFilter.loadRules(workspaceDir))
            return rules.distinct()
        }

        val gitignorePath = workspacePath.trimEnd('/') + "/.gitignore"
        val res =
            toolHandler.executeTool(
                AITool(
                    name = "read_file_full",
                    parameters = listOf(
                        ToolParameter("path", gitignorePath),
                        ToolParameter("text_only", "true"),
                        ToolParameter("environment", workspaceEnv)
                    )
                )
            )
        val content = (res.result as? com.kymjs.ai.custard.core.tools.FileContentData)?.content
        if (res.success && !content.isNullOrBlank()) {
            content
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { rules.add(it) }
        }

        return rules.distinct()
    }

    private suspend fun listRootEntries(
        toolHandler: AIToolHandler,
        workspacePath: String,
        workspaceEnv: String
    ): List<com.kymjs.ai.custard.core.tools.DirectoryListingData.FileEntry> {
        val listRes =
            toolHandler.executeTool(
                AITool(
                    name = "list_files",
                    parameters = listOf(
                        ToolParameter("path", workspacePath),
                        ToolParameter("environment", workspaceEnv)
                    )
                )
            )
        val listing = listRes.result as? DirectoryListingData
        return listing?.entries.orEmpty()
    }

    private fun parseLastModifiedToMillis(lastModified: String): Long? {
        val raw = lastModified.trim()
        if (raw.isBlank()) return null

        val patterns = listOf(DEFAULT_TIME_PATTERN_WITH_MS, DEFAULT_TIME_PATTERN_NO_MS)
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                val date = fmt.parse(raw)
                if (date != null) return date.time
            } catch (_: ParseException) {
                // ignore
            } catch (_: Exception) {
                // ignore
            }
        }
        return null
    }
}
