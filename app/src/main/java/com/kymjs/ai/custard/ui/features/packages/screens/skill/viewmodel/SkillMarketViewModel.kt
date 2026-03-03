package com.kymjs.ai.custard.ui.features.packages.screens.skill.viewmodel

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kymjs.ai.custard.data.api.GitHubApiService
import com.kymjs.ai.custard.data.api.GitHubComment
import com.kymjs.ai.custard.data.api.GitHubIssue
import com.kymjs.ai.custard.data.api.GitHubReaction
import com.kymjs.ai.custard.data.api.GitHubRepository
import com.kymjs.ai.custard.data.preferences.GitHubAuthPreferences
import com.kymjs.ai.custard.data.skill.SkillRepository
import com.kymjs.ai.custard.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.kymjs.ai.custard.R

class SkillMarketViewModel(
    private val context: Context,
    private val skillRepository: SkillRepository
) : ViewModel() {

    private val githubApiService = GitHubApiService(context)
    val githubAuth = GitHubAuthPreferences.getInstance(context)

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

    private val _isRateLimitError = MutableStateFlow(false)
    val isRateLimitError: StateFlow<Boolean> = _isRateLimitError.asStateFlow()

    private val _skillIssues = MutableStateFlow<List<GitHubIssue>>(emptyList())
    private val _searchResultIssues = MutableStateFlow<List<GitHubIssue>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val skillIssues: StateFlow<List<GitHubIssue>> =
        combine(_skillIssues, _searchQuery, _searchResultIssues) { issues, query, searchIssues ->
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

    private val _installingSkills = MutableStateFlow<Set<String>>(emptySet())
    val installingSkills: StateFlow<Set<String>> = _installingSkills.asStateFlow()

    private val _installedSkillRepoUrls = MutableStateFlow<Set<String>>(emptySet())
    val installedSkillRepoUrls: StateFlow<Set<String>> = _installedSkillRepoUrls.asStateFlow()

    private val _installedSkillNames = MutableStateFlow<Set<String>>(emptySet())
    val installedSkillNames: StateFlow<Set<String>> = _installedSkillNames.asStateFlow()

    private val _userPublishedSkills = MutableStateFlow<List<GitHubIssue>>(emptyList())
    val userPublishedSkills: StateFlow<List<GitHubIssue>> = _userPublishedSkills.asStateFlow()

    private val _issueComments = MutableStateFlow<Map<Int, List<GitHubComment>>>(emptyMap())
    val issueComments: StateFlow<Map<Int, List<GitHubComment>>> = _issueComments.asStateFlow()

    private val _isLoadingComments = MutableStateFlow<Set<Int>>(emptySet())
    val isLoadingComments: StateFlow<Set<Int>> = _isLoadingComments.asStateFlow()

    private val _isPostingComment = MutableStateFlow<Set<Int>>(emptySet())
    val isPostingComment: StateFlow<Set<Int>> = _isPostingComment.asStateFlow()

    private val _userAvatarCache = MutableStateFlow<Map<String, String>>(emptyMap())
    val userAvatarCache: StateFlow<Map<String, String>> = _userAvatarCache.asStateFlow()

    private val _issueReactions = MutableStateFlow<Map<Int, List<GitHubReaction>>>(emptyMap())
    val issueReactions: StateFlow<Map<Int, List<GitHubReaction>>> = _issueReactions.asStateFlow()

    private val _isLoadingReactions = MutableStateFlow<Set<Int>>(emptySet())
    val isLoadingReactions: StateFlow<Set<Int>> = _isLoadingReactions.asStateFlow()

    private val _isReacting = MutableStateFlow<Set<Int>>(emptySet())
    val isReacting: StateFlow<Set<Int>> = _isReacting.asStateFlow()

    private val _repositoryCache = MutableStateFlow<Map<String, GitHubRepository>>(emptyMap())
    val repositoryCache: StateFlow<Map<String, GitHubRepository>> = _repositoryCache.asStateFlow()

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("skill_publish_draft", Context.MODE_PRIVATE)

    data class PublishDraft(
        val title: String = "",
        val description: String = "",
        val repositoryUrl: String = ""
    )

    val publishDraft: PublishDraft
        get() = PublishDraft(
            title = sharedPrefs.getString("title", "") ?: "",
            description = sharedPrefs.getString("description", "") ?: "",
            repositoryUrl = sharedPrefs.getString("repositoryUrl", "") ?: ""
        )

    @Serializable
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private data class SkillMetadata(
        val description: String = "",
        val repositoryUrl: String,
        val category: String = "",
        val tags: String = "",
        val version: String = ""
    )

    class Factory(
        private val context: Context,
        private val skillRepository: SkillRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SkillMarketViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return SkillMarketViewModel(context, skillRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        private const val TAG = "SkillMarketViewModel"
        private const val MARKET_REPO_OWNER = "kymjs"
        private const val MARKET_REPO_NAME = "CustardSkillMarket"
        private const val SKILL_LABEL = "skill-plugin"
        private const val MARKET_PAGE_SIZE = 50
    }

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
            searchSkillMarketIssues(trimmedQuery)
        }
    }

    private suspend fun searchSkillMarketIssues(rawQuery: String) {
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
            append(SKILL_LABEL)
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
                    val msg = error.message ?: "Unknown error"
                    _errorMessage.value = context.getString(R.string.skillmarket_load_failed, msg)
                    _searchResultIssues.value = emptyList()

                    if ((msg.contains("403") || msg.contains("rate") || msg.contains("Rate")) && !isLoggedIn) {
                        _isRateLimitError.value = true
                    }

                    AppLogger.e(TAG, "Failed to search skill market data", error)
                }
            )
        } catch (e: Exception) {
            if (rawQuery == _searchQuery.value.trim()) {
                _errorMessage.value = context.getString(R.string.skillmarket_network_error, e.message ?: "")
                _searchResultIssues.value = emptyList()
                AppLogger.e(TAG, "Exception while searching skill market data", e)
            }
        } finally {
            if (rawQuery == _searchQuery.value.trim()) {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun saveDraft(title: String, description: String, repositoryUrl: String) {
        sharedPrefs.edit().apply {
            putString("title", title)
            putString("description", description)
            putString("repositoryUrl", repositoryUrl)
            apply()
        }
    }

    fun clearDraft() {
        sharedPrefs.edit().clear().apply()
    }

    fun parseSkillInfoFromIssue(issue: GitHubIssue): PublishDraft {
        val body = issue.body ?: return PublishDraft(title = issue.title)
        val metadata = parseSkillMetadata(body)
        return if (metadata != null) {
            PublishDraft(
                title = issue.title,
                description = metadata.description,
                repositoryUrl = metadata.repositoryUrl
            )
        } else {
            PublishDraft(
                title = issue.title,
                description = "Unable to parse Skill description, please fill manually.",
                repositoryUrl = ""
            )
        }
    }

    fun initiateGitHubLogin(context: Context) {
        try {
            val authUrl = githubAuth.getAuthorizationUrl()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            _errorMessage.value = context.getString(R.string.skillmarket_login_failed, e.message ?: "")
            AppLogger.e(TAG, "Failed to initiate GitHub login", e)
        }
    }

    fun logoutFromGitHub() {
        viewModelScope.launch {
            try {
                githubAuth.logout()
                Toast.makeText(context, context.getString(R.string.skillmarket_logged_out), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.skillmarket_logout_failed, e.message ?: "")
                AppLogger.e(TAG, "Failed to logout from GitHub", e)
            }
        }
    }

    fun loadSkillMarketData() {
        viewModelScope.launch {
            _isLoading.value = true
            _isLoadingMore.value = false
            _errorMessage.value = null
            _isRateLimitError.value = false
            _hasMore.value = true
            currentPage = 1

            val isLoggedIn = try {
                githubAuth.isLoggedIn()
            } catch (_: Exception) {
                false
            }

            try {
                val result = githubApiService.getRepositoryIssues(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    state = "open",
                    labels = SKILL_LABEL,
                    page = 1,
                    perPage = MARKET_PAGE_SIZE
                )

                result.fold(
                    onSuccess = { issues ->
                        _skillIssues.value = issues
                        _hasMore.value = issues.size >= MARKET_PAGE_SIZE
                    },
                    onFailure = { error ->
                        val msg = error.message ?: "Unknown error"
                        _errorMessage.value = context.getString(R.string.skillmarket_load_failed, msg)
                        _skillIssues.value = emptyList()
                        _hasMore.value = false

                        if (msg.contains("403") || msg.contains("rate") || msg.contains("Rate")) {
                            _isRateLimitError.value = !isLoggedIn
                        }

                        AppLogger.e(TAG, "Failed to load skill market data", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.skillmarket_network_error, e.message ?: "")
                _skillIssues.value = emptyList()
                AppLogger.e(TAG, "Exception while loading skill market data", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMoreSkillMarketData() {
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
                    labels = SKILL_LABEL,
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
                        _skillIssues.value = (_skillIssues.value + issues).distinctBy { it.id }
                        _hasMore.value = issues.size >= MARKET_PAGE_SIZE
                    },
                    onFailure = { error ->
                        val msg = error.message ?: "Unknown error"
                        _errorMessage.value = context.getString(R.string.skillmarket_load_more_failed, msg)

                        if (msg.contains("403") || msg.contains("rate") || msg.contains("Rate")) {
                            _isRateLimitError.value = !isLoggedIn
                        }

                        AppLogger.e(TAG, "Failed to load more skill market data", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.skillmarket_network_error, e.message ?: "")
                AppLogger.e(TAG, "Exception while loading more skill market data", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun refreshInstalledSkills() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) {
                try {
                    skillRepository.getAvailableSkillPackages()
                } catch (_: Exception) {
                    emptyMap()
                }
            }

            val installedNames = installed.keys.toSet()
            val installedRepoUrls = installed.values.mapNotNull { pkg ->
                try {
                    val marker = pkg.directory.resolve(".custard_repo_url")
                    if (marker.exists() && marker.isFile) {
                        marker.readText().trim().ifBlank { null }
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }.toSet()

            _installedSkillNames.value = installedNames
            _installedSkillRepoUrls.value = installedRepoUrls
        }
    }

    fun loadUserPublishedSkills() {
        viewModelScope.launch {
            if (!githubAuth.isLoggedIn()) {
                _errorMessage.value = context.getString(R.string.skillmarket_github_login_required)
                return@launch
            }

            _isLoading.value = true
            _errorMessage.value = null

            try {
                val userInfo = githubAuth.getCurrentUserInfo()
                if (userInfo == null) {
                    _errorMessage.value = context.getString(R.string.skillmarket_unable_get_user_info)
                    return@launch
                }

                val resultWithLabel = githubApiService.getRepositoryIssues(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    state = "all",
                    labels = SKILL_LABEL,
                    creator = userInfo.login,
                    perPage = 100
                )

                val finalResult = resultWithLabel.fold(
                    onSuccess = { issues ->
                        if (issues.isNotEmpty()) {
                            Result.success(issues)
                        } else {
                            githubApiService.getRepositoryIssues(
                                owner = MARKET_REPO_OWNER,
                                repo = MARKET_REPO_NAME,
                                state = "all",
                                labels = null,
                                creator = userInfo.login,
                                perPage = 100
                            )
                        }
                    },
                    onFailure = { Result.failure(it) }
                )

                finalResult.fold(
                    onSuccess = { issues ->
                        _userPublishedSkills.value = issues
                    },
                    onFailure = { error ->
                        _errorMessage.value = context.getString(R.string.skillmarket_load_published_failed, error.message ?: "")
                        AppLogger.e(TAG, "Failed to load user published skills", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.skillmarket_network_error, e.message ?: "")
                AppLogger.e(TAG, "Network error while loading user published skills", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeSkillFromMarket(issueNumber: Int) {
        viewModelScope.launch {
            try {
                if (!githubAuth.isLoggedIn()) {
                    _errorMessage.value = context.getString(R.string.skillmarket_github_login_required)
                    return@launch
                }

                _isLoading.value = true
                val result = githubApiService.updateIssue(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber,
                    state = "closed"
                )

                result.fold(
                    onSuccess = {
                        Toast.makeText(context, context.getString(R.string.skillmarket_removed_from_market), Toast.LENGTH_SHORT).show()
                        loadUserPublishedSkills()
                        loadSkillMarketData()
                    },
                    onFailure = { error ->
                        _errorMessage.value = context.getString(R.string.skillmarket_remove_failed, error.message ?: "")
                        AppLogger.e(TAG, "Failed to remove skill from market", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.skillmarket_remove_failed, e.message ?: "")
                AppLogger.e(TAG, "Failed to remove skill from market", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadIssueComments(issueNumber: Int) {
        viewModelScope.launch {
            _isLoadingComments.value = _isLoadingComments.value + issueNumber
            try {
                val result = githubApiService.getIssueComments(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber,
                    perPage = 50
                )

                result.fold(
                    onSuccess = { comments ->
                        _issueComments.value = _issueComments.value + (issueNumber to comments)
                    },
                    onFailure = { error ->
                        _errorMessage.value = context.getString(R.string.skillmarket_load_comments_failed, error.message ?: "")
                        AppLogger.e(TAG, "Failed to load issue comments", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.skillmarket_load_comments_failed, e.message ?: "")
                AppLogger.e(TAG, "Failed to load issue comments", e)
            } finally {
                _isLoadingComments.value = _isLoadingComments.value - issueNumber
            }
        }
    }

    fun postIssueComment(issueNumber: Int, body: String) {
        val text = body.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            _isPostingComment.value = _isPostingComment.value + issueNumber
            try {
                if (!githubAuth.isLoggedIn()) {
                    _errorMessage.value = context.getString(R.string.skillmarket_github_login_required)
                    return@launch
                }

                val result = githubApiService.createIssueComment(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    issueNumber = issueNumber,
                    body = text
                )

                result.fold(
                    onSuccess = {
                        loadIssueComments(issueNumber)
                    },
                    onFailure = { error ->
                        _errorMessage.value = context.getString(R.string.skillmarket_post_comment_failed, error.message ?: "")
                        AppLogger.e(TAG, "Failed to post issue comment", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.skillmarket_post_comment_failed, e.message ?: "")
                AppLogger.e(TAG, "Failed to post issue comment", e)
            } finally {
                _isPostingComment.value = _isPostingComment.value - issueNumber
            }
        }
    }

    suspend fun publishSkill(
        title: String,
        description: String,
        repositoryUrl: String,
        version: String = "v1"
    ): Boolean {
        return try {
            if (!githubAuth.isLoggedIn()) return false

            val body = buildSkillPublishIssueBody(
                description = description,
                repositoryUrl = repositoryUrl,
                version = version
            )

            val result = githubApiService.createIssue(
                owner = MARKET_REPO_OWNER,
                repo = MARKET_REPO_NAME,
                title = title,
                body = body,
                labels = listOf(SKILL_LABEL)
            )

            if (result.isSuccess) return true

            val errMsg = result.exceptionOrNull()?.message.orEmpty()
            if (errMsg.contains("422")) {
                val retry = githubApiService.createIssue(
                    owner = MARKET_REPO_OWNER,
                    repo = MARKET_REPO_NAME,
                    title = title,
                    body = body,
                    labels = emptyList()
                )
                retry.isSuccess
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to publish skill", e)
            false
        }
    }

    suspend fun updatePublishedSkill(
        issueNumber: Int,
        title: String,
        description: String,
        repositoryUrl: String,
        version: String = "v1"
    ): Boolean {
        return try {
            if (!githubAuth.isLoggedIn()) return false

            val body = buildSkillPublishIssueBody(
                description = description,
                repositoryUrl = repositoryUrl,
                version = version
            )

            val result = githubApiService.updateIssue(
                owner = MARKET_REPO_OWNER,
                repo = MARKET_REPO_NAME,
                issueNumber = issueNumber,
                title = title,
                body = body
            )

            result.isSuccess
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update skill", e)
            false
        }
    }

    private fun buildSkillPublishIssueBody(
        description: String,
        repositoryUrl: String,
        version: String = "v1"
    ): String {
        return buildString {
            try {
                val metadata = SkillMetadata(
                    description = description,
                    repositoryUrl = repositoryUrl,
                    version = version
                )
                val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
                val metadataJson = json.encodeToString(metadata)
                appendLine("<!-- custard-skill-json: $metadataJson -->")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to serialize skill metadata", e)
            }

            appendLine("<!-- custard-parser-version: $version -->")
            appendLine()

            appendLine(context.getString(R.string.skill_publish_body_section_skill_info))
            appendLine()
            appendLine(description)
            appendLine()

            if (repositoryUrl.isNotBlank()) {
                appendLine(context.getString(R.string.skill_publish_body_section_repo_info))
                appendLine()
                appendLine(context.getString(R.string.skill_publish_body_label_repo_url, repositoryUrl))
                appendLine()
            }

            if (repositoryUrl.isNotBlank()) {
                appendLine(context.getString(R.string.skill_publish_body_section_install_method))
                appendLine()
                appendLine(context.getString(R.string.skill_publish_body_install_step1))
                appendLine(context.getString(R.string.skill_publish_body_install_step2))
                appendLine(context.getString(R.string.skill_publish_body_install_step3, repositoryUrl))
                appendLine(context.getString(R.string.skill_publish_body_install_step4))
                appendLine()
            }

            appendLine(context.getString(R.string.skill_publish_body_section_tech_info))
            appendLine()
            appendLine(context.getString(R.string.skill_publish_body_table_header))
            appendLine(context.getString(R.string.skill_publish_body_table_separator))
            appendLine(context.getString(R.string.skill_publish_body_table_row_platform))
            appendLine(context.getString(R.string.skill_publish_body_table_row_parser_version))
            appendLine(
                context.getString(
                    R.string.skill_publish_body_table_row_publish_time,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                )
            )
            appendLine(context.getString(R.string.skill_publish_body_table_row_status_pending))
            appendLine()
        }
    }

    private fun parseSkillMetadata(body: String): SkillMetadata? {
        val prefix = "<!-- custard-skill-json: "
        val start = body.indexOf(prefix)
        if (start < 0) return null

        val jsonStart = start + prefix.length
        val end = body.indexOf(" -->", startIndex = jsonStart)
        if (end <= jsonStart) return null

        val jsonString = body.substring(jsonStart, end)
        return try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<SkillMetadata>(jsonString)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse skill metadata JSON from issue body.", e)
            null
        }
    }

    fun installSkillFromRepoUrl(repoUrl: String) {
        val key = repoUrl.trim()
        if (key.isBlank()) {
            _errorMessage.value = context.getString(R.string.skillmarket_invalid_repo_url)
            return
        }

        viewModelScope.launch {
            _installingSkills.value = _installingSkills.value + key
            try {
                val result = skillRepository.importSkillFromGitHubRepo(key)
                Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                _installedSkillRepoUrls.value = _installedSkillRepoUrls.value + key
                refreshInstalledSkills()
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.skillmarket_install_failed, e.message ?: "")
                AppLogger.e(TAG, "Failed to install skill from repo", e)
            } finally {
                _installingSkills.value = _installingSkills.value - key
            }
        }
    }

    fun fetchUserAvatar(username: String) {
        if (username.isBlank() || _userAvatarCache.value.containsKey(username)) {
            return
        }

        viewModelScope.launch {
            try {
                val result = githubApiService.getUser(username)
                result.fold(
                    onSuccess = { user ->
                        val currentCache = _userAvatarCache.value.toMutableMap()
                        currentCache[username] = user.avatarUrl
                        _userAvatarCache.value = currentCache
                    },
                    onFailure = { error ->
                        AppLogger.w(TAG, "Failed to fetch user avatar for $username: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "Exception while fetching user avatar for $username", e)
            }
        }
    }

    fun fetchRepositoryInfo(repositoryUrl: String) {
        if (repositoryUrl.isBlank() || _repositoryCache.value.containsKey(repositoryUrl)) {
            return
        }

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

    fun loadIssueReactions(issueNumber: Int, force: Boolean = false) {
        if (issueNumber in _isLoadingReactions.value) {
            return
        }

        if (!force && _issueReactions.value.containsKey(issueNumber)) {
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

    fun addReactionToIssue(issueNumber: Int, reactionType: String) {
        if (issueNumber in _isReacting.value) {
            return
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
                        val currentReactions = _issueReactions.value.toMutableMap()
                        val existingReactions = currentReactions[issueNumber] ?: emptyList()
                        currentReactions[issueNumber] = existingReactions + newReaction
                        _issueReactions.value = currentReactions

                        Toast.makeText(
                            context,
                            "Liked successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { error ->
                        _errorMessage.value = context.getString(R.string.skillmarket_like_failed, error.message ?: "")
                        AppLogger.e(TAG, "Failed to add reaction to issue #$issueNumber", error)
                    }
                )
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.skillmarket_like_error, e.message ?: "")
                AppLogger.e(TAG, "Exception while adding reaction to issue #$issueNumber", e)
            } finally {
                _isReacting.value = _isReacting.value - issueNumber
            }
        }
    }
}
