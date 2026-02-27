package com.ai.assistance.custard.ui.features.packages.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter

import com.ai.assistance.custard.R
import com.ai.assistance.custard.data.api.GitHubIssue
import com.ai.assistance.custard.data.mcp.MCPRepository
import com.ai.assistance.custard.data.preferences.GitHubAuthPreferences
import com.ai.assistance.custard.data.preferences.GitHubUser
import com.ai.assistance.custard.ui.features.packages.screens.mcp.viewmodel.MCPMarketViewModel
import com.ai.assistance.custard.ui.features.packages.utils.MCPPluginParser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPMarketScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToPublish: () -> Unit = {},
    onNavigateToManage: () -> Unit = {},
    onNavigateToDetail: ((GitHubIssue) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mcpRepository = remember { MCPRepository(context.applicationContext) }
    val viewModel: MCPMarketViewModel = viewModel(
        factory = MCPMarketViewModel.Factory(context.applicationContext, mcpRepository)
    )

    // GitHub认证状态
    val githubAuth = remember { GitHubAuthPreferences.getInstance(context) }
    val isLoggedIn by githubAuth.isLoggedInFlow.collectAsState(initial = false)
    val currentUser by githubAuth.userInfoFlow.collectAsState(initial = null)

    // 市场数据状态
    val mcpIssues by viewModel.mcpIssues.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    // 安装状态
    val installingPlugins by viewModel.installingPlugins.collectAsState()
    val installProgress by viewModel.installProgress.collectAsState()
    val installedPluginIds by viewModel.installedPluginIds.collectAsState()

    // 搜索状态
    val searchQuery by viewModel.searchQuery.collectAsState()

    // UI状态
    var selectedTab by remember { mutableStateOf(0) }
    var showLoginDialog by remember { mutableStateOf(false) }

    // 在组件启动时加载数据
    LaunchedEffect(Unit) {
        viewModel.loadMCPMarketData()
    }

    // 错误处理
    errorMessage?.let { error ->
        LaunchedEffect(error) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 标签栏区域，包含登录状态
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Column {
                // 登录状态栏（如果未登录则显示）
                if (!isLoggedIn) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLoginDialog = true }
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.login_github_to_manage_mcp_plugins),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 标签栏
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.browse)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { 
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.my_tab))
                                if (isLoggedIn && currentUser != null) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Image(
                                        painter = rememberAsyncImagePainter(currentUser!!.avatarUrl),
                                        contentDescription = stringResource(R.string.user_avatar),
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        // 内容区域
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> MCPBrowseTab(
                    issues = mcpIssues,
                    isLoading = isLoading,
                    searchQuery = searchQuery,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    installingPlugins = installingPlugins,
                    installProgress = installProgress,
                    installedPluginIds = installedPluginIds,
                    onInstallMCP = { issue ->
                        viewModel.installMCPFromIssue(issue)
                    },
                    onRefresh = {
                        scope.launch {
                            viewModel.loadMCPMarketData()
                        }
                    },
                    onNavigateToDetail = onNavigateToDetail,
                    viewModel = viewModel
                )
                1 -> MCPMyTab(
                    isLoggedIn = isLoggedIn,
                    currentUser = currentUser,
                    onLogin = { showLoginDialog = true },
                    onLogout = {
                        scope.launch {
                            viewModel.logoutFromGitHub()
                        }
                    },
                    onNavigateToPublish = onNavigateToPublish,
                    onNavigateToManage = onNavigateToManage
                )
            }


        }
    }

    // GitHub登录对话框
    if (showLoginDialog) {
        GitHubLoginDialog(
            isLoggedIn = isLoggedIn,
            currentUser = currentUser,
            onDismiss = { showLoginDialog = false },
            onLogin = {
                scope.launch {
                    viewModel.initiateGitHubLogin(context)
                    showLoginDialog = false
                }
            },
            onLogout = {
                scope.launch {
                    viewModel.logoutFromGitHub()
                    showLoginDialog = false
                }
            }
        )
    }


}

@Composable
private fun MCPBrowseTab(
    issues: List<GitHubIssue>,
    isLoading: Boolean,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    installingPlugins: Set<String>,
    installProgress: Map<String, com.ai.assistance.custard.data.mcp.InstallProgress>,
    installedPluginIds: Set<String>,
    onInstallMCP: (GitHubIssue) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToDetail: ((GitHubIssue) -> Unit)? = null,
    viewModel: MCPMarketViewModel
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()

    LaunchedEffect(listState, issues.size, searchQuery, hasMore, isLoadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisibleIndex ->
                if (searchQuery.isNotBlank()) return@collect
                val headerCount = if (searchQuery.isBlank()) 1 else 0
                val lastIssueIndex = headerCount + issues.size - 1
                if (
                    hasMore &&
                    !isLoadingMore &&
                    issues.isNotEmpty() &&
                    lastVisibleIndex >= (lastIssueIndex - 2)
                ) {
                    viewModel.loadMoreMCPMarketData()
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.mcp_market_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.mcp_market_search)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.mcp_market_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 仅当没有搜索时显示标题
                    if (searchQuery.isBlank()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.available_mcp_plugins),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = onRefresh) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.mcp_market_refresh))
                                }
                            }
                        }
                    }

                    items(issues, key = { it.id }) { issue ->
                        val pluginInfo = remember(issue) {
                            MCPPluginParser.parsePluginInfo(issue)
                        }
                        // 使用issue的title作为插件ID，过滤非法字符
                        val pluginId = remember(issue) {
                            pluginInfo.title.replace("[^a-zA-Z0-9_]".toRegex(), "_")
                        }
                        val isInstalling = pluginId in installingPlugins
                        val isInstalled = pluginId in installedPluginIds
                        val currentProgress = installProgress[pluginId]
                        
                        MCPIssueCard(
                            issue = issue,
                            pluginInfo = pluginInfo,
                            onInstall = { onInstallMCP(issue) },
                            onViewDetails = {
                                // 优先使用内部详情页面，如果没有则在浏览器中打开
                                if (onNavigateToDetail != null) {
                                    onNavigateToDetail(issue)
                                } else {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issue.html_url))
                                    context.startActivity(intent)
                                }
                            },
                            isInstalling = isInstalling,
                            isInstalled = isInstalled,
                            installProgress = currentProgress,
                            onNavigateToDetail = onNavigateToDetail,
                            viewModel = viewModel
                        )
                    }

                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    if (issues.isEmpty() && !isLoading) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.Store,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        if (searchQuery.isNotBlank()) stringResource(R.string.no_matching_plugins_found) else stringResource(R.string.no_mcp_plugins_available),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        if (searchQuery.isNotBlank()) stringResource(R.string.try_changing_keywords) else stringResource(R.string.refresh_or_try_again_later),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MCPRecommendedTab() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Stars,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource(R.string.recommended_features),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.coming_soon_for_mcp_plugins),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MCPMyTab(
    isLoggedIn: Boolean,
    currentUser: GitHubUser?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToPublish: () -> Unit,
    onNavigateToManage: () -> Unit
) {
    if (!isLoggedIn) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.please_login_github_first),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.after_logging_in_you_can_view_and_manage_your_mcp_plugins),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onLogin) {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.login_github))
                }
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (currentUser != null) {
                    Image(
                        painter = rememberAsyncImagePainter(currentUser.avatarUrl),
                        contentDescription = stringResource(R.string.user_avatar),
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        currentUser.name ?: currentUser.login,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "@${currentUser.login}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onNavigateToPublish,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.publish_new_plugin))
                }
                OutlinedButton(
                    onClick = onNavigateToManage,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.manage_my_plugins))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(0.8f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.logout))
                }
            }
        }
    }
}

@Composable
private fun MCPIssueCard(
    issue: GitHubIssue,
    pluginInfo: MCPPluginParser.ParsedPluginInfo,
    onInstall: () -> Unit,
    onViewDetails: () -> Unit,
    isInstalling: Boolean = false,
    isInstalled: Boolean = false,
    installProgress: com.ai.assistance.custard.data.mcp.InstallProgress? = null,
    onNavigateToDetail: ((GitHubIssue) -> Unit)? = null,
    viewModel: MCPMarketViewModel
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onViewDetails() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = issue.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (pluginInfo.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pluginInfo.description.take(100) + if (pluginInfo.description.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val avatarUrl by viewModel.userAvatarCache.collectAsState()
                LaunchedEffect(pluginInfo.repositoryOwner) {
                    if (pluginInfo.repositoryOwner.isNotBlank()) {
                        viewModel.fetchUserAvatar(pluginInfo.repositoryOwner)
                    }
                }

                // Reactions（合并到同一行，避免额外高度）
                val thumbsUpCount = issue.reactions?.thumbs_up ?: 0
                val heartCount = issue.reactions?.heart ?: 0

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (pluginInfo.repositoryOwner.isNotBlank()) {
                        val userAvatarUrl = avatarUrl[pluginInfo.repositoryOwner]
                        if (userAvatarUrl != null) {
                            Image(
                                painter = rememberAsyncImagePainter(userAvatarUrl),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Image(
                        painter = rememberAsyncImagePainter(issue.user.avatarUrl),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )

                    if (thumbsUpCount > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ThumbUp,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = thumbsUpCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (heartCount > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color(0xFFE91E63)
                        )
                        Text(
                            text = heartCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE91E63)
                        )
                    }
                }
            }

            val circleSize = 34.dp
            val containerColor = when {
                isInstalled -> MaterialTheme.colorScheme.secondaryContainer
                isInstalling -> MaterialTheme.colorScheme.primaryContainer
                issue.state == "open" -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }

            val contentColor = when {
                isInstalled -> MaterialTheme.colorScheme.onSecondaryContainer
                isInstalling -> MaterialTheme.colorScheme.onPrimaryContainer
                issue.state == "open" -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(shape = CircleShape, color = containerColor) {
                IconButton(
                    onClick = {
                        if (issue.state == "open" && !isInstalled && !isInstalling) {
                            onInstall()
                        }
                    },
                    modifier = Modifier.size(circleSize)
                ) {
                    when {
                        isInstalling -> {

                            when (installProgress) {
                                is com.ai.assistance.custard.data.mcp.InstallProgress.Downloading -> {
                                    val p = installProgress.progress
                                    if (p in 0..100) {
                                        CircularProgressIndicator(
                                            progress = { p / 100f },
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = contentColor
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = contentColor
                                        )
                                    }
                                }
                                else -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = contentColor
                                    )
                                }
                            }
                        }
                        isInstalled -> {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        issue.state == "open" -> {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHubLoginDialog(
    isLoggedIn: Boolean,
    currentUser: GitHubUser?,
    onDismiss: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(if (isLoggedIn) stringResource(R.string.github_account) else stringResource(R.string.login_github)) 
        },
        text = {
            if (isLoggedIn && currentUser != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(currentUser.avatarUrl),
                        contentDescription = stringResource(R.string.user_avatar),
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        text = currentUser.name ?: currentUser.login,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "@${currentUser.login}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (currentUser.bio != null) {
                        Text(
                            text = currentUser.bio,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column {
                    Text(stringResource(R.string.after_logging_in_you_can))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• " + stringResource(R.string.publish_your_mcp_plugin_to_the_market))
                    Text("• " + stringResource(R.string.manage_your_published_plugins))
                    Text("• " + stringResource(R.string.better_installation_experience))
                }
            }
        },
        confirmButton = {
            if (isLoggedIn) {
                Button(onClick = onLogout) {
                    Text(stringResource(R.string.logout))
                }
            } else {
                Button(onClick = onLogin) {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.login_github))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}