package com.kymjs.ai.custard.ui.features.packages.screens.mcp.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.data.api.GitHubApiService
import com.kymjs.ai.custard.data.api.GitHubIssue
import com.kymjs.ai.custard.data.api.GitHubComment

import com.kymjs.ai.custard.data.mcp.MCPRepository
import com.kymjs.ai.custard.data.mcp.MCPLocalServer
import com.kymjs.ai.custard.data.preferences.GitHubAuthPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.kymjs.ai.custard.util.AppLogger
import android.content.SharedPreferences

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.kymjs.ai.custard.ui.features.packages.utils.MCPPluginParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * MCP市场ViewModel
 * 处理GitHub认证、MCP浏览、安装和发布
 */

class MCPMarketViewModel(
    private val context: Context,
    private val mcpRepository: MCPRepository
) : ViewModel() {

    /**
     * MCP元数据的数据类
     * @param version 版本号，用于向前兼容
     */
    @Serializable
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private data class MCPMetadata(
        val description: String = "",
        val repositoryUrl: String,
        @JsonNames("installCommand")
        val installConfig: String,
        val category: String,
        val tags: String,
        val version: String
    )

    private val githubApiService = GitHubApiService(context)
    val githubAuth = GitHubAuthPreferences.getInstance(context)

    // UI状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var currentPage: Int = 1
    private var searchJob: Job? = null

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 新增：用于表示是否因为未登录而触发速率限制
    private val _isRateLimitError = MutableStateFlow(false)
    val isRateLimitError: StateFlow<Boolean> = _isRateLimitError.asStateFlow()

    // 安装进度状态
    private val _installingPlugins = MutableStateFlow<Set<String>>(emptySet())
    val installingPlugins: StateFlow<Set<String>> = _installingPlugins.asStateFlow()

    private val _installProgress = MutableStateFlow<Map<String, com.kymjs.ai.custard.data.mcp.InstallProgress>>(emptyMap())
    val installProgress: StateFlow<Map<String, com.kymjs.ai.custard.data.mcp.InstallProgress>> = _installProgress.asStateFlow()

    // 已安装插件
    val installedPluginIds: StateFlow<Set<String>> = mcpRepository.installedPluginIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // MCP市场数据
    private val _mcpIssues = MutableStateFlow<List<GitHubIssue>>(emptyList())
    private val _searchResultIssues = MutableStateFlow<List<GitHubIssue>>(emptyList())

    // 搜索查询
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val mcpIssues: StateFlow<List<GitHubIssue>> =
        combine(_mcpIssues, _searchQuery, _searchResultIssues) { issues, query, searchIssues ->
            if (query.isBlank()) {
                issues
            } else {
                searchIssues
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 用户已发布的插件
    private val _userPublishedPlugins = MutableStateFlow<List<GitHubIssue>>(emptyList())
    val userPublishedPlugins: StateFlow<List<GitHubIssue>> = _userPublishedPlugins.asStateFlow()

    // 评论相关状态
    private val _issueComments = MutableStateFlow<Map<Int, List<GitHubComment>>>(emptyMap())
    val issueComments: StateFlow<Map<Int, List<GitHubComment>>> = _issueComments.asStateFlow()

    private val _isLoadingComments = MutableStateFlow<Set<Int>>(emptySet())
    val isLoadingComments: StateFlow<Set<Int>> = _isLoadingComments.asStateFlow()

    private val _isPostingComment = MutableStateFlow<Set<Int>>(emptySet())
    val isPostingComment: StateFlow<Set<Int>> = _isPostingComment.asStateFlow()

    // 用户头像缓存
    private val _userAvatarCache = MutableStateFlow<Map<String, String>>(emptyMap())
    val userAvatarCache: StateFlow<Map<String, String>> = _userAvatarCache.asStateFlow()

    // Reactions相关状态
    private val _issueReactions = MutableStateFlow<Map<Int, List<com.kymjs.ai.custard.data.api.GitHubReaction>>>(emptyMap())
    val issueReactions: StateFlow<Map<Int, List<com.kymjs.ai.custard.data.api.GitHubReaction>>> = _issueReactions.asStateFlow()

    private val _isLoadingReactions = MutableStateFlow<Set<Int>>(emptySet())
    val isLoadingReactions: StateFlow<Set<Int>> = _isLoadingReactions.asStateFlow()

    private val _isReacting = MutableStateFlow<Set<Int>>(emptySet())
    val isReacting: StateFlow<Set<Int>> = _isReacting.asStateFlow()

    // 仓库信息缓存（包含星数）
    private val _repositoryCache = MutableStateFlow<Map<String, com.kymjs.ai.custard.data.api.GitHubRepository>>(emptyMap())
    val repositoryCache: StateFlow<Map<String, com.kymjs.ai.custard.data.api.GitHubRepository>> = _repositoryCache.asStateFlow()

    // 草稿保存
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("mcp_publish_draft", Context.MODE_PRIVATE)

    // 用户头像URL持久化缓存
    private val avatarCachePrefs: SharedPreferences = context.getSharedPreferences("github_avatar_cache", Context.MODE_PRIVATE)

    // 发布草稿数据类
    data class PublishDraft(
        val title: String = "",
        val description: String = "",
        val repositoryUrl: String = "",
        val tags: String = "",
        val installConfig: String = "",
        val category: String = ""
    )

    // 当前草稿
    val publishDraft: PublishDraft
        get() = PublishDraft(
            title = sharedPrefs.getString("title", "") ?: "",
            description = sharedPrefs.getString("description", "") ?: "",
            repositoryUrl = sharedPrefs.getString("repositoryUrl", "") ?: "",
            tags = sharedPrefs.getString("tags", "") ?: "",
            installConfig = sharedPrefs.getString("installConfig", "") ?: "",
            category = sharedPrefs.getString("category", "") ?: ""
        )

    init {
        // 加载持久化的头像缓存
        loadAvatarCacheFromPrefs()
    }

    class Factory(
        private val context: Context,
        private val mcpRepository: MCPRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MCPMarketViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MCPMarketViewModel(context, mcpRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        private const val TAG = "MCPMarketViewModel"
        private const val MARKET_REPO_OWNER = "kymjs"
        private const val MARKET_REPO_NAME = "CustardMCPMarket"
        private const val MCP_PLUGIN_LABEL = "mcp-plugin"
        private const val MARKET_PAGE_SIZE = 50
    }

    /**
     * 更新搜索查询
     */
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()

        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            _isLoading.value = false
            _searchResultIssues.value = emptyList()
            _errorMessage.value = null
            _isRateLimitError.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(350)
            searchMCPMarketIssues(trimmedQuery)
        }
    }

    private suspend fun searchMCPMarketIssues(rawQuery: String) {
        _isLoading.value = true
        _errorMessage.value = null
        _isRateLimitError.value = false

        val isLoggedIn = try {
            githubAuth.isLoggedIn()
        } catch (_: Exception) {
            false
        }

        val qualifiedQuery = buildString {
            append(rawQuery)
            append(" repo:")
            append(MARKET_REPO_OWNER)
            append("/")
            append(MARKET_REPO_NAME)
            append(" is:issue is:open label:")
            append(MCP_PLUGIN_LABEL)
        }

        try {
            val result = githubApiService.searchIssues(
                query = qualifiedQuery,
                sort = "updated",
                order = "desc",
                page = 1,
                perPage = MARKET_PAGE_SIZE
            )

            if (rawQuery != _searchQuery.value.trim()) return

            result.fold(
                onSuccess = { issues ->
                    _searchResultIssues.value = issues
                },
                onFailure = { error ->
                    val errorMessage = error.message ?: ""
                    if (errorMessage.contains("HTTP 403") && !isLoggedIn) {
                        _errorMessage.value = context.getString(R.string.mcp_market_api_rate_limited_login_required)
                        _isRateLimitError.value = true
                    } else {
                        _errorMessage.value = context.getString(R.string.mcp_market_load_failed_with_error, errorMessage)
                    }
                    _searchResultIssues.value = emptyList()
                    AppLogger.e(TAG, "Failed to search MCP market data", error)
                }
            )
        } catch (e: Exception) {
            if (rawQuery == _searchQuery.value.trim()) {
                _errorMessage.value = context.getString(R.string.mcp_market_network_error_with_error, e.message ?: "")
                _searchResultIssues.value = emptyList()
                AppLogger.e(TAG, "Network error while searching MCP market data", e)
            }
        } finally {
            if (rawQuery == _searchQuery.value.trim()) {
                _isLoading.value = false
            }
        }
    }

    /**
     * 加载MCP市场数据
     */
    fun loadMCPMarketData() {
        viewModelScope.launch {
            _isLoading.value = true
            _isLoadingMore.value = false
            _errorMessage.value = null
            _isRateLimitError.value = false // 重置状态
            _issueReactions.value = emptyMap() // 刷新时清除旧的Reactions缓存
            _hasMore.value = true
            currentPage = 1

            try {
                val result = githubApiService.getRepositoryIssues(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    state = "open",
                    labels = MCP_PLUGIN_LABEL,
                    page = 1,
                    perPage = MARKET_PAGE_SIZE
                )

                val isLoggedIn = try {
                    githubAuth.isLoggedIn()
                } catch (_: Exception) {
                    false
                }

                result.fold(
                    onSuccess = { issues ->
                        _mcpIssues.value = issues
                        _hasMore.value = issues.size >= MARKET_PAGE_SIZE
                    },
                    onFailure = { error ->
                        val errorMessage = error.message ?: ""
                        if (errorMessage.contains("HTTP 403") && !isLoggedIn) {
                            _errorMessage.value = context.getString(R.string.mcp_market_api_rate_limited_login_required)
                            _isRateLimitError.value = true
                        } else {
                            _errorMessage.value = context.getString(R.string.mcp_market_load_failed_with_error, errorMessage)
                        }
                        _hasMore.value = false
                        AppLogger.e(TAG, "Failed to load MCP market data", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.mcp_market_network_error_with_error, e.message ?: "")
                AppLogger.e(TAG, "Network error while loading MCP market data", e)
                _hasMore.value = false
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMoreMCPMarketData() {
        viewModelScope.launch {
            if (_isLoading.value || _isLoadingMore.value || !_hasMore.value) return@launch

            _isLoadingMore.value = true
            _errorMessage.value = null
            _isRateLimitError.value = false

            val isLoggedIn = try {
                githubAuth.isLoggedIn()
            } catch (_: Exception) {
                false
            }

            val nextPage = currentPage + 1

            try {
                val result = githubApiService.getRepositoryIssues(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    state = "open",
                    labels = MCP_PLUGIN_LABEL,
                    page = nextPage,
                    perPage = MARKET_PAGE_SIZE
                )

                result.fold(
                    onSuccess = { issues ->
                        if (issues.isEmpty()) {
                            _hasMore.value = false
                            return@fold
                        }

                        currentPage = nextPage
                        _mcpIssues.value = (_mcpIssues.value + issues).distinctBy { it.id }
                        _hasMore.value = issues.size >= MARKET_PAGE_SIZE
                    },
                    onFailure = { error ->
                        val errorMessage = error.message ?: ""
                        if (errorMessage.contains("HTTP 403") && !isLoggedIn) {
                            _errorMessage.value = context.getString(R.string.mcp_market_api_rate_limited_login_required)
                            _isRateLimitError.value = true
                        } else {
                            _errorMessage.value = context.getString(R.string.mcp_market_load_more_failed_with_error, errorMessage)
                        }

                        AppLogger.e(TAG, "Failed to load more MCP market data", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.mcp_market_network_error_with_error, e.message ?: "")
                AppLogger.e(TAG, "Network error while loading more MCP market data", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * 从Issue安装MCP
     */
    fun installMCPFromIssue(issue: GitHubIssue) {
        viewModelScope.launch {
            try {
                // 解析Issue中的安装信息
                val installInfo = parseInstallationInfo(issue)
                AppLogger.d(TAG, "Parsed installation info: $installInfo")

                if (installInfo != null) {
                    val pluginInfo = MCPPluginParser.parsePluginInfo(issue)
                    val pluginId = generateMCPId(issue)

                    // 标记插件开始安装
                    _installingPlugins.value = _installingPlugins.value + pluginId

                    // 如果提供了安装配置，检查是否需要物理安装
                    if (installInfo.installConfig != null && installInfo.installConfig.isNotBlank()) {
                        // 检查配置中的命令是否都不需要物理安装
                        val needsInstallation = mcpRepository.checkConfigNeedsPhysicalInstallation(installInfo.installConfig)

                        if (!needsInstallation) {
                            // 不需要物理安装，直接合并配置
                            AppLogger.d(TAG, "Using config merge installation for plugin $pluginId (no physical installation needed)")
                            val mcpLocalServer = MCPLocalServer.getInstance(context)
                            val mergeResult = mcpLocalServer.mergeConfigFromJson(installInfo.installConfig)

                            val count = mergeResult.getOrElse { error ->
                                _installingPlugins.value = _installingPlugins.value - pluginId
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.mcp_market_config_import_failed_with_error, error.message ?: ""),
                                    Toast.LENGTH_LONG
                                ).show()
                                AppLogger.e(TAG, "Config merge failed for plugin $pluginId", error)
                                return@launch
                            }

                            _installingPlugins.value = _installingPlugins.value - pluginId
                            Toast.makeText(
                                context,
                                context.getString(R.string.mcp_market_config_import_success_with_count, issue.title, count),
                                Toast.LENGTH_SHORT
                            ).show()
                            mcpRepository.refreshPluginList()
                            return@launch
                        } else {
                            AppLogger.d(TAG, "Config contains commands that need physical installation, proceeding with normal installation flow")
                            // 继续执行下面的物理安装流程
                        }
                    }

                    // 获取作者头像，如果缓存中没有，则使用分享者的头像作为备用
                    val authorAvatarUrl = _userAvatarCache.value[pluginInfo.repositoryOwner] ?: issue.user.avatarUrl

                    // 创建MCP服务器对象
                    val server = MCPLocalServer.PluginMetadata(
                        id = pluginId,
                        name = issue.title,
                        description = pluginInfo.description.ifBlank { issue.body?.take(200) ?: "" },
                        logoUrl = authorAvatarUrl,
                        author = pluginInfo.repositoryOwner.ifBlank { issue.user.login },
                        isInstalled = false,
                        version = "1.0.0",
                        updatedAt = issue.updated_at,
                        longDescription = issue.body ?: "",
                        repoUrl = installInfo.repoUrl ?: "",
                        type = "local",
                        marketConfig = installInfo.installConfig // 保存市场配置
                    )

                    // 安装MCP，带进度回调
                    val result = mcpRepository.installMCPServerWithObject(server) { progress ->
                        // 更新安装进度
                        _installProgress.value = _installProgress.value + (pluginId to progress)
                    }

                    // 清除安装状态
                    _installingPlugins.value = _installingPlugins.value - pluginId
                    _installProgress.value = _installProgress.value - pluginId

                    when (result) {
                        is com.kymjs.ai.custard.data.mcp.InstallResult.Success -> {
                            Toast.makeText(
                                context,
                                context.getString(R.string.mcp_market_install_success, issue.title),
                                Toast.LENGTH_SHORT
                            ).show()
                            AppLogger.i(TAG, "Successfully installed MCP: ${issue.title}")
                        }
                        is com.kymjs.ai.custard.data.mcp.InstallResult.Error -> {
                            _errorMessage.value = context.getString(R.string.mcp_market_install_failed_with_error, result.message)
                            AppLogger.e(TAG, "Failed to install MCP ${issue.title}: ${result.message}")
                        }
                    }
                } else {
                    _errorMessage.value = context.getString(R.string.mcp_market_parse_install_info_failed)
                    AppLogger.w(TAG, "Could not parse installation info from issue #${issue.number} ('${issue.title}'). URL: ${issue.html_url}")
                    AppLogger.d(TAG, "Issue body that failed to parse:\n${issue.body}")
                }
            } catch (e: Exception) {
                // 确保清除安装状态
                val pluginId = generateMCPId(issue)
                _installingPlugins.value = _installingPlugins.value - pluginId
                _installProgress.value = _installProgress.value - pluginId

                _errorMessage.value = context.getString(R.string.mcp_market_install_failed_with_error, e.message ?: "")
                AppLogger.e(TAG, "Failed to install MCP from issue #${issue.number}", e)
            }
        }
    }

    /**
     * 发布MCP到市场
     */
    fun publishMCP(
        title: String,
        description: String,
        repoUrl: String,
        labels: List<String>
    ) {
        viewModelScope.launch {
            try {
                if (!githubAuth.isLoggedIn()) {
                    _errorMessage.value = context.getString(R.string.mcp_market_github_login_required)
                    return@launch
                }

                _isLoading.value = true

                // 构建Issue内容
                val issueBody = buildMCPIssueBody(description, repoUrl)
                val issueLabels = (labels + MCP_PLUGIN_LABEL).distinct()

                val result = githubApiService.createIssue(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    title = title,
                    body = issueBody,
                    labels = issueLabels
                )

                result.fold(
                    onSuccess = { issue ->
                        AppLogger.d(TAG, "Successfully created issue #${issue.number}")
                        Toast.makeText(
                            context,
                            context.getString(R.string.mcp_market_publish_success_toast),
                            Toast.LENGTH_LONG
                        ).show()

                        // 刷新市场数据
                        loadMCPMarketData()

                        // 打开创建的Issue
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issue.html_url))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    onFailure = { error ->
                        AppLogger.e(TAG, "Failed to create issue", error)
                        _errorMessage.value = context.getString(R.string.publish_failed_with_error, error.message ?: "")
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.publish_failed_with_error, e.message ?: "")
                AppLogger.e(TAG, "Failed to publish MCP", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 启动GitHub登录流程
     */
    fun initiateGitHubLogin(context: Context) {
        try {
            val authUrl = githubAuth.getAuthorizationUrl()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            _errorMessage.value = context.getString(R.string.mcp_market_github_login_start_failed, e.message ?: "")
            AppLogger.e(TAG, "Failed to initiate GitHub login", e)
        }
    }

    /**
     * 处理GitHub OAuth回调
     */
    fun handleGitHubCallback(code: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                AppLogger.d(TAG, "Handling GitHub callback with code: $code")

                // 获取访问令牌
                val tokenResult = githubApiService.getAccessToken(code)

                val tokenResponse = tokenResult.getOrElse { error ->
                    AppLogger.e(TAG, "Failed to get access token", error)
                    _errorMessage.value = context.getString(R.string.main_github_login_failed, error.message ?: "")
                    return@launch
                }

                AppLogger.d(TAG, "Successfully obtained access token.")
                githubAuth.updateAccessToken(tokenResponse.access_token, tokenResponse.token_type)

                val userResult = githubApiService.getCurrentUser()
                val user = userResult.getOrElse { error ->
                    AppLogger.e(TAG, "Failed to get user info", error)
                    _errorMessage.value = context.getString(R.string.main_github_get_user_failed, error.message ?: "")
                    return@launch
                }

                AppLogger.d(TAG, "Successfully fetched user info for ${user.login}")
                githubAuth.saveAuthInfo(
                    accessToken = tokenResponse.access_token,
                    tokenType = tokenResponse.token_type,
                    userInfo = user
                )

                Toast.makeText(
                    context,
                    context.getString(R.string.main_github_login_success, user.login),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception during GitHub callback handling", e)
                _errorMessage.value = context.getString(R.string.main_github_login_error, e.message ?: "")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 退出GitHub登录
     */
    fun logoutFromGitHub() {
        viewModelScope.launch {
            try {
                githubAuth.logout()
                Toast.makeText(
                    context,
                    context.getString(R.string.mcp_market_github_logout_success),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.mcp_market_github_logout_failed_with_error, e.message ?: "")
                AppLogger.e(TAG, "Failed to logout from GitHub", e)
            }
        }
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 加载用户已发布的插件
     */
    fun loadUserPublishedPlugins() {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = context.getString(R.string.mcp_market_github_login_required)
                return@launch
            }

            _isLoading.value = true
            _errorMessage.value = null

            try {
                val userInfo = githubAuth.getCurrentUserInfo()
                if (userInfo == null) {
                    _errorMessage.value = context.getString(R.string.mcp_market_get_user_info_failed)
                    return@launch
                }

                val result = githubApiService.getRepositoryIssues(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    state = "all", // 获取所有状态的Issue
                    labels = MCP_PLUGIN_LABEL,
                    creator = userInfo.login, // 只获取当前用户创建的Issue
                    perPage = 100
                )

                result.fold(
                    onSuccess = { issues ->
                        _userPublishedPlugins.value = issues
                    },
                    onFailure = { error ->
                        _errorMessage.value = context.getString(R.string.mcp_market_load_published_failed_with_error, error.message ?: "")
                        AppLogger.e(TAG, "Failed to load user published plugins", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.mcp_market_network_error_with_error, e.message ?: "")
                AppLogger.e(TAG, "Network error while loading user published plugins", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 更新已发布的插件信息
     */
    fun updatePublishedPlugin(
        issueNumber: Int,
        title: String,
        description: String,
        repositoryUrl: String,
        category: String,
        tags: String,
        installConfig: String,
        version: String = "v1"
    ) {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = context.getString(R.string.mcp_market_github_login_required)
                return@launch
            }

            _isLoading.value = true
            _errorMessage.value = null

            try {
                val body = buildMCPPublishIssueBody(
                    description = description,
                    repositoryUrl = repositoryUrl,
                    category = category,
                    tags = tags,
                    installConfig = installConfig,
                    version = version
                )

                val result = githubApiService.updateIssue(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber,
                    title = title,
                    body = body
                )

                result.fold(
                    onSuccess = { updatedIssue ->
                        AppLogger.d(TAG, "Successfully updated issue #${issueNumber}")
                        Toast.makeText(
                            context,
                            context.getString(R.string.mcp_market_update_plugin_success_toast),
                            Toast.LENGTH_SHORT
                        ).show()

                        // 刷新用户发布的插件列表
                        loadUserPublishedPlugins()
                    },
                    onFailure = { error ->
                        AppLogger.e(TAG, "Failed to update issue #${issueNumber}", error)
                        _errorMessage.value = context.getString(R.string.update_failed_with_error, error.message ?: "")
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.update_failed_with_error, e.message ?: "")
                AppLogger.e(TAG, "Failed to update published plugin", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 删除已发布的插件（关闭Issue）
     */
    fun deletePublishedPlugin(issueNumber: Int, title: String) {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = context.getString(R.string.mcp_market_github_login_required)
                return@launch
            }

            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = githubApiService.updateIssue(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber,
                    state = "closed"
                )

                result.fold(
                    onSuccess = { _ ->
                        AppLogger.d(TAG, "Successfully closed issue #${issueNumber}")
                        Toast.makeText(
                            context,
                            context.getString(R.string.mcp_market_plugin_removed_from_market, title),
                            Toast.LENGTH_SHORT
                        ).show()

                        // 立即更新本地状态，不需要重新请求服务器
                        val currentPlugins = _userPublishedPlugins.value.toMutableList()
                        val pluginIndex = currentPlugins.indexOfFirst { it.number == issueNumber }
                        if (pluginIndex != -1) {
                            currentPlugins[pluginIndex] = currentPlugins[pluginIndex].copy(state = "closed")
                            _userPublishedPlugins.value = currentPlugins
                        }
                    },
                    onFailure = { error ->
                        AppLogger.e(TAG, "Failed to close issue #${issueNumber}", error)
                        _errorMessage.value = context.getString(R.string.delete_failed, error.message ?: "")
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.delete_failed, e.message ?: "")
                AppLogger.e(TAG, "Failed to delete published plugin", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 重新开放已关闭的插件
     */
    fun reopenPublishedPlugin(issueNumber: Int, title: String) {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = context.getString(R.string.mcp_market_github_login_required)
                return@launch
            }

            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = githubApiService.updateIssue(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber,
                    state = "open"
                )

                result.fold(
                    onSuccess = { _ ->
                        AppLogger.d(TAG, "Successfully reopened issue #${issueNumber}")
                        Toast.makeText(
                            context,
                            context.getString(R.string.mcp_market_plugin_republished_to_market, title),
                            Toast.LENGTH_SHORT
                        ).show()

                        // 立即更新本地状态，不需要重新请求服务器
                        val currentPlugins = _userPublishedPlugins.value.toMutableList()
                        val pluginIndex = currentPlugins.indexOfFirst { it.number == issueNumber }
                        if (pluginIndex != -1) {
                            currentPlugins[pluginIndex] = currentPlugins[pluginIndex].copy(state = "open")
                            _userPublishedPlugins.value = currentPlugins
                        }
                    },
                    onFailure = { error ->
                        AppLogger.e(TAG, "Failed to reopen issue #${issueNumber}", error)
                        _errorMessage.value = context.getString(
                            R.string.mcp_market_republish_failed_with_error,
                            error.message ?: ""
                        )
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(
                    R.string.mcp_market_republish_failed_with_error,
                    e.message ?: ""
                )
                AppLogger.e(TAG, "Failed to reopen published plugin", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 从Issue内容解析插件信息用于编辑
     */
    fun parsePluginInfoFromIssue(issue: GitHubIssue): PublishDraft {
        val body = issue.body ?: return PublishDraft(title = issue.title)

        // 优先尝试解析隐藏在评论中的JSON元数据
        parseMCPMetadata(body)?.let { metadata ->
            return PublishDraft(
                title = issue.title,
                description = metadata.description,
                repositoryUrl = metadata.repositoryUrl,
                tags = metadata.tags,
                installConfig = metadata.installConfig,
                category = metadata.category
            )
        }

        // 如果JSON不存在，说明是格式错误或非常旧的Issue，直接返回一个基础的草稿用于编辑
        AppLogger.w(TAG, "Could not parse plugin info from issue #${issue.number}. No valid JSON metadata found.")
        return PublishDraft(
            title = issue.title,
            description = context.getString(R.string.mcp_market_parse_plugin_desc_failed_fallback)
        )
    }

    /**
     * 保存发布草稿
     */
    fun saveDraft(
        title: String,
        description: String,
        repositoryUrl: String,
        tags: String,
        installConfig: String,
        category: String
    ) {
        sharedPrefs.edit().apply {
            putString("title", title)
            putString("description", description)
            putString("repositoryUrl", repositoryUrl)
            putString("tags", tags)
            putString("installConfig", installConfig)
            putString("category", category)
            apply()
        }
    }

    /**
     * 清空草稿
     */
    fun clearDraft() {
        sharedPrefs.edit().clear().apply()
    }

    /**
     * 发布MCP到市场
     */
    suspend fun publishMCP(
        title: String,
        description: String,
        repositoryUrl: String,
        category: String,
        tags: String,
        installConfig: String,
        version: String = "v1"
    ): Boolean {
        return try {
            val body = buildMCPPublishIssueBody(
                description = description,
                repositoryUrl = repositoryUrl,
                category = category,
                tags = tags,
                installConfig = installConfig,
                version = version
            )

            val result = githubApiService.createIssue(
                owner = MARKET_REPO_OWNER,
                repo = MARKET_REPO_NAME,
                title = title,
                body = body,
                labels = listOf(MCP_PLUGIN_LABEL)
            )

            result.isSuccess
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to publish MCP", e)
            false
        }
    }

    /**
     * 构建MCP发布Issue内容
     */
    private fun buildMCPPublishIssueBody(
        description: String,
        repositoryUrl: String,
        category: String,
        tags: String,
        installConfig: String,
        version: String = "v1"
    ): String {
        return buildString {
            // 嵌入包含所有机器可读信息的JSON数据块
            val metadata = MCPMetadata(
                description = description,
                repositoryUrl = repositoryUrl,
                installConfig = installConfig,
                category = category,
                tags = tags,
                version = version
            )
            try {
                val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
                val metadataJson = json.encodeToString(metadata)
                appendLine("<!-- custard-mcp-json: $metadataJson -->")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to serialize MCP metadata", e)
            }

            // 软件解析版本号标记
            appendLine("<!-- custard-parser-version: $version -->")
            appendLine()

            appendLine(context.getString(R.string.mcp_publish_body_section_plugin_info))
            appendLine()
            appendLine(context.getString(R.string.mcp_publish_body_label_description, description))
            appendLine()
            if (repositoryUrl.isNotBlank()) {
                appendLine(context.getString(R.string.mcp_publish_body_section_repo_info))
                appendLine()
                appendLine(context.getString(R.string.mcp_publish_body_label_repo_url, repositoryUrl))
                appendLine()
            }

            if (installConfig.isNotBlank()) {
                appendLine(context.getString(R.string.mcp_publish_body_section_quick_install))
                appendLine()
                appendLine("```json")
                appendLine(installConfig)
                appendLine("```")
                appendLine()
            }

            if (repositoryUrl.isNotBlank()) {
                appendLine(context.getString(R.string.mcp_publish_body_section_install_method))
                appendLine()
                appendLine(context.getString(R.string.mcp_publish_body_method_repo_import_title))
                appendLine(context.getString(R.string.mcp_publish_body_method_repo_import_step1))
                appendLine(context.getString(R.string.mcp_publish_body_method_repo_import_step2))
                appendLine(context.getString(R.string.mcp_publish_body_method_repo_import_step3, repositoryUrl))
                appendLine(context.getString(R.string.mcp_publish_body_method_repo_import_step4))
                appendLine()
            }

            if (installConfig.isNotBlank()) {
                appendLine(context.getString(R.string.mcp_publish_body_method_config_import_title))
                appendLine(context.getString(R.string.mcp_publish_body_method_config_import_step1))
                appendLine(context.getString(R.string.mcp_publish_body_method_config_import_step2))
                appendLine(context.getString(R.string.mcp_publish_body_method_config_import_step3))
                appendLine("```json")
                appendLine(installConfig)
                appendLine("```")
                appendLine()
            }

            appendLine(context.getString(R.string.mcp_publish_body_section_tech_info))
            appendLine()
            appendLine(context.getString(R.string.mcp_publish_body_table_header))
            appendLine(context.getString(R.string.mcp_publish_body_table_separator))
            appendLine(context.getString(R.string.mcp_publish_body_table_row_platform))
            appendLine(context.getString(R.string.mcp_publish_body_table_row_parser_version))
            appendLine(
                context.getString(
                    R.string.mcp_publish_body_table_row_publish_time,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                )
            )
            appendLine(context.getString(R.string.mcp_publish_body_table_row_status_pending))
            appendLine()
            appendLine("---")
            appendLine(context.getString(R.string.mcp_publish_body_footer_note))
        }
    }

    private fun parseInstallationInfo(issue: GitHubIssue): InstallationInfo? {
        val body = issue.body ?: return null

        // 优先尝试解析隐藏的JSON元数据
        val metadata = parseMCPMetadata(body)
        if (metadata != null) {
            val repoUrlValid = metadata.repositoryUrl.startsWith("http")
            // 校验安装配置，确保不为空且包含有效字符
            val installConfigValid = metadata.installConfig.isNotBlank() && metadata.installConfig.trim().startsWith("{")

            if (repoUrlValid || installConfigValid) {
                AppLogger.d(TAG, "Parsed installation info from JSON for issue #${issue.number}")
                return InstallationInfo(
                    repoUrl = if (repoUrlValid) metadata.repositoryUrl else null,
                    installConfig = if (installConfigValid) metadata.installConfig else null,
                    installationType = if (repoUrlValid) "github" else "config"
                )
            } else {
                AppLogger.w(TAG, "Found JSON metadata in issue #${issue.number}, but both repositoryUrl ('${metadata.repositoryUrl}') and installConfig ('${metadata.installConfig}') are invalid.")
                return null
            }
        }

        AppLogger.w(TAG, "Could not parse installation info from issue #${issue.number}. No valid JSON metadata found.")
        return null
    }

    /**
     * 解析隐藏在Issue Body中的MCP元数据JSON
     */
    private fun parseMCPMetadata(body: String): MCPMetadata? {
        val prefix = "<!-- custard-mcp-json: "
        val start = body.indexOf(prefix)
        if (start < 0) return null

        val jsonStart = start + prefix.length
        val end = body.indexOf(" -->", startIndex = jsonStart)
        if (end <= jsonStart) return null

        val jsonString = body.substring(jsonStart, end)
        return try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<MCPMetadata>(jsonString)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse MCP metadata JSON from issue body.", e)
            null
        }
    }

    /**
     * 生成MCP ID
     */
    private fun generateMCPId(issue: GitHubIssue): String {
        val pluginInfo = MCPPluginParser.parsePluginInfo(issue)
        return pluginInfo.title.replace("[^a-zA-Z0-9_]".toRegex(), "_")
    }

    /**
     * 构建MCP Issue内容
     */
    private fun buildMCPIssueBody(description: String, repoUrl: String): String {
        return buildString {
            appendLine(context.getString(R.string.mcp_issue_body_title_description))
            appendLine()
            appendLine(description)
            appendLine()

            if (repoUrl.isNotBlank()) {
                appendLine(context.getString(R.string.mcp_issue_body_title_install_info))
                appendLine()
                appendLine(context.getString(R.string.mcp_issue_body_repo_address, repoUrl))
                appendLine()
                appendLine(context.getString(R.string.mcp_issue_body_title_install_method))
                appendLine(context.getString(R.string.mcp_issue_body_install_step1))
                appendLine(context.getString(R.string.mcp_issue_body_install_step2))
                appendLine(context.getString(R.string.mcp_issue_body_install_step3, repoUrl))
                appendLine(context.getString(R.string.mcp_issue_body_install_step4))
                appendLine()
            }

            appendLine(context.getString(R.string.mcp_issue_body_title_tech_info))
            appendLine(context.getString(R.string.mcp_issue_body_tech_platform))
            appendLine(
                context.getString(
                    R.string.mcp_issue_body_tech_publish_time,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
                )
            )
            appendLine()
            appendLine("---")
            appendLine(context.getString(R.string.mcp_issue_body_footer_note))
        }
    }

    /**
     * 安装信息数据类
     */
    private data class InstallationInfo(
        val repoUrl: String? = null,
        val installConfig: String? = null,
        val installationType: String
    )

    /**
     * 加载Issue评论
     */
    fun loadIssueComments(issueNumber: Int) {
        viewModelScope.launch {
            try {
                _isLoadingComments.value = _isLoadingComments.value + issueNumber

                val result = githubApiService.getIssueComments(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber,
                    perPage = 100
                )

                result.fold(
                    onSuccess = { comments ->
                        val currentComments = _issueComments.value.toMutableMap()
                        currentComments[issueNumber] = comments
                        _issueComments.value = currentComments
                        AppLogger.d(TAG, "Successfully loaded ${comments.size} comments for issue #$issueNumber")
                    },
                    onFailure = { error ->
                        _errorMessage.value = context.getString(
                            R.string.mcp_market_load_comments_failed_with_error,
                            error.message ?: ""
                        )
                        AppLogger.e(TAG, "Failed to load comments for issue #$issueNumber", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(
                    R.string.mcp_market_load_comments_error_with_error,
                    e.message ?: ""
                )
                AppLogger.e(TAG, "Exception while loading comments for issue #$issueNumber", e)
            } finally {
                _isLoadingComments.value = _isLoadingComments.value - issueNumber
            }
        }
    }

    /**
     * 发布评论
     */
    fun postComment(issueNumber: Int, commentBody: String) {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = context.getString(R.string.mcp_market_github_login_required)
                return@launch
            }

            if (commentBody.isBlank()) {
                _errorMessage.value = context.getString(R.string.mcp_market_comment_empty)
                return@launch
            }

            try {
                _isPostingComment.value = _isPostingComment.value + issueNumber

                val result = githubApiService.createIssueComment(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber,
                    body = commentBody
                )

                result.fold(
                    onSuccess = { newComment ->
                        // 将新评论添加到现有评论列表
                        val currentComments = _issueComments.value.toMutableMap()
                        val existingComments = currentComments[issueNumber] ?: emptyList()
                        currentComments[issueNumber] = existingComments + newComment
                        _issueComments.value = currentComments

                        Toast.makeText(
                            context,
                            context.getString(R.string.mcp_market_comment_post_success),
                            Toast.LENGTH_SHORT
                        ).show()
                        AppLogger.d(TAG, "Successfully posted comment to issue #$issueNumber")
                    },
                    onFailure = { error ->
                        _errorMessage.value = context.getString(
                            R.string.mcp_market_comment_post_failed_with_error,
                            error.message ?: ""
                        )
                        AppLogger.e(TAG, "Failed to post comment to issue #$issueNumber", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(
                    R.string.mcp_market_comment_post_error_with_error,
                    e.message ?: ""
                )
                AppLogger.e(TAG, "Exception while posting comment to issue #$issueNumber", e)
            } finally {
                _isPostingComment.value = _isPostingComment.value - issueNumber
            }
        }
    }

    /**
     * 获取Issue的评论列表
     */
    fun getCommentsForIssue(issueNumber: Int): List<GitHubComment> {
        return _issueComments.value[issueNumber] ?: emptyList()
    }

    /**
     * 检查是否正在加载评论
     */
    fun isLoadingCommentsForIssue(issueNumber: Int): Boolean {
        return issueNumber in _isLoadingComments.value
    }

    /**
     * 检查是否正在发布评论
     */
    fun isPostingCommentForIssue(issueNumber: Int): Boolean {
        return issueNumber in _isPostingComment.value
    }

    /**
     * 获取用户头像URL
     */
    fun getUserAvatarUrl(username: String): String? {
        return _userAvatarCache.value[username]
    }

    /**
     * 从SharedPreferences加载头像缓存
     */
    private fun loadAvatarCacheFromPrefs() {
        try {
            val cachedAvatars = avatarCachePrefs.all.mapNotNull { (key, value) ->
                if (value is String) key to value else null
            }.toMap()

            if (cachedAvatars.isNotEmpty()) {
                _userAvatarCache.value = cachedAvatars
                AppLogger.d(TAG, "Loaded ${cachedAvatars.size} avatar URLs from persistent cache")
            }

            // 如果缓存过大（超过500个），清理一半
            if (cachedAvatars.size > 500) {
                cleanupAvatarCache()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load avatar cache from preferences", e)
        }
    }

    /**
     * 清理头像缓存（保留最近的一半）
     */
    private fun cleanupAvatarCache() {
        try {
            val allEntries = avatarCachePrefs.all
            if (allEntries.size > 500) {
                val editor = avatarCachePrefs.edit()
                // 简单策略：删除前一半的键
                allEntries.keys.take(allEntries.size / 2).forEach { key ->
                    editor.remove(key)
                }
                editor.apply()
                AppLogger.d(TAG, "Cleaned up avatar cache, removed ${allEntries.size / 2} entries")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to cleanup avatar cache", e)
        }
    }

    /**
     * 保存头像URL到持久化缓存
     */
    private fun saveAvatarToPrefs(username: String, avatarUrl: String) {
        try {
            avatarCachePrefs.edit().putString(username, avatarUrl).apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save avatar to preferences", e)
        }
    }

    /**
     * 缓存用户头像URL（带持久化）
     */
    fun fetchUserAvatar(username: String) {
        if (username.isBlank() || _userAvatarCache.value.containsKey(username)) {
            return // 已经缓存或用户名为空
        }

        viewModelScope.launch {
            try {
                val result = githubApiService.getUser(username)
                result.fold(
                    onSuccess = { user ->
                        val currentCache = _userAvatarCache.value.toMutableMap()
                        currentCache[username] = user.avatarUrl
                        _userAvatarCache.value = currentCache

                        // 持久化保存
                        saveAvatarToPrefs(username, user.avatarUrl)
                        AppLogger.d(TAG, "Cached and persisted avatar for user: $username")
                    },
                    onFailure = { error ->
                        AppLogger.w(TAG, "Failed to fetch avatar for user $username: ${error.message}")
                        // 可以设置一个默认头像URL或者不做任何操作
                    }
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "Exception while fetching avatar for user $username", e)
            }
        }
    }

    /**
     * 获取Issue的reactions
     */
    fun loadIssueReactions(issueNumber: Int, force: Boolean = false) {
        if (issueNumber in _isLoadingReactions.value) {
            return // 正在加载中，避免重复请求
        }

        // 如果不是强制刷新，并且缓存中已有数据，则直接返回
        if (!force && _issueReactions.value.containsKey(issueNumber)) {
            AppLogger.d(TAG, "Reactions for issue #$issueNumber already in cache.")
            return
        }

        viewModelScope.launch {
            try {
                _isLoadingReactions.value = _isLoadingReactions.value + issueNumber

                val result = githubApiService.getIssueReactions(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber
                )

                result.fold(
                    onSuccess = { reactions ->
                        val currentReactions = _issueReactions.value.toMutableMap()
                        currentReactions[issueNumber] = reactions
                        _issueReactions.value = currentReactions
                        AppLogger.d(TAG, "Successfully loaded reactions for issue #$issueNumber")
                    },
                    onFailure = { error ->
                        AppLogger.e(TAG, "Failed to load reactions for issue #$issueNumber", error)
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception while loading reactions for issue #$issueNumber", e)
            } finally {
                _isLoadingReactions.value = _isLoadingReactions.value - issueNumber
            }
        }
    }

    /**
     * 为Issue添加reaction
     */
    fun addReactionToIssue(issueNumber: Int, reactionType: String) {
        if (issueNumber in _isReacting.value) {
            return // 正在操作中，避免重复请求
        }

        viewModelScope.launch {
            try {
                _isReacting.value = _isReacting.value + issueNumber

                val result = githubApiService.createIssueReaction(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber,
                    content = reactionType
                )

                result.fold(
                    onSuccess = { newReaction ->
                        // 将新reaction添加到现有列表
                        val currentReactions = _issueReactions.value.toMutableMap()
                        val existingReactions = currentReactions[issueNumber] ?: emptyList()
                        currentReactions[issueNumber] = existingReactions + newReaction
                        _issueReactions.value = currentReactions

                        Toast.makeText(
                            context,
                            context.getString(R.string.mcp_market_reaction_success),
                            Toast.LENGTH_SHORT
                        ).show()
                        AppLogger.d(TAG, "Successfully added reaction to issue #$issueNumber")
                    },
                    onFailure = { error ->
                        _errorMessage.value = context.getString(
                            R.string.mcp_market_reaction_failed_with_error,
                            error.message ?: ""
                        )
                        AppLogger.e(TAG, "Failed to add reaction to issue #$issueNumber", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(
                    R.string.mcp_market_reaction_error_with_error,
                    e.message ?: ""
                )
                AppLogger.e(TAG, "Exception while adding reaction to issue #$issueNumber", e)
            } finally {
                _isReacting.value = _isReacting.value - issueNumber
            }
        }
    }

    /**
     * 获取仓库信息（包含星数）
     */
    fun fetchRepositoryInfo(repositoryUrl: String) {
        if (repositoryUrl.isBlank() || _repositoryCache.value.containsKey(repositoryUrl)) {
            return // 已经缓存或URL为空
        }

        // 从URL中提取owner和repo名称
        val repoPath = repositoryUrl.removePrefix("https://github.com/")
        val parts = repoPath.split("/")
        if (parts.size < 2) {
            AppLogger.w(TAG, "Invalid repository URL: $repositoryUrl")
            return
        }

        val owner = parts[0]
        val repo = parts[1]

        viewModelScope.launch {
            try {
                val result = githubApiService.getRepository(owner, repo)
                result.fold(
                    onSuccess = { repository ->
                        val currentCache = _repositoryCache.value.toMutableMap()
                        currentCache[repositoryUrl] = repository
                        _repositoryCache.value = currentCache
                        AppLogger.d(TAG, "Successfully fetched repository info for $repositoryUrl")
                    },
                    onFailure = { error ->
                        AppLogger.w(TAG, "Failed to fetch repository info for $repositoryUrl: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "Exception while fetching repository info for $repositoryUrl", e)
            }
        }
    }

    /**
     * 获取Issue的reactions列表
     */
    fun getReactionsForIssue(issueNumber: Int): List<com.kymjs.ai.custard.data.api.GitHubReaction> {
        return _issueReactions.value[issueNumber] ?: emptyList()
    }

    /**
     * 检查是否正在加载reactions
     */
    fun isLoadingReactionsForIssue(issueNumber: Int): Boolean {
        return issueNumber in _isLoadingReactions.value
    }

    /**
     * 检查是否正在添加reaction
     */
    fun isReactingToIssue(issueNumber: Int): Boolean {
        return issueNumber in _isReacting.value
    }

    /**
     * 获取仓库信息
     */
    fun getRepositoryInfo(repositoryUrl: String): com.kymjs.ai.custard.data.api.GitHubRepository? {
        return _repositoryCache.value[repositoryUrl]
    }

    /**
     * 统计特定类型的reaction数量
     */
    fun getReactionCount(issueNumber: Int, reactionType: String): Int {
        return getReactionsForIssue(issueNumber).count { it.content == reactionType }
    }

    /**
     * 检查当前用户是否已经对issue添加了特定类型的reaction
     */
    suspend fun hasUserReacted(issueNumber: Int, reactionType: String): Boolean {
        val currentUser = githubAuth.getCurrentUserInfo() ?: return false
        return getReactionsForIssue(issueNumber).any { 
            it.content == reactionType && it.user.login == currentUser.login 
        }
    }
} 
