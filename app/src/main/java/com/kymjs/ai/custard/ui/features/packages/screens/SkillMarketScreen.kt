package com.kymjs.ai.custard.ui.features.packages.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.data.api.GitHubIssue
import com.kymjs.ai.custard.data.preferences.GitHubAuthPreferences
import com.kymjs.ai.custard.data.preferences.GitHubUser
import com.kymjs.ai.custard.data.skill.SkillRepository
import com.kymjs.ai.custard.ui.features.packages.screens.skill.viewmodel.SkillMarketViewModel
import com.kymjs.ai.custard.ui.features.packages.utils.SkillIssueParser
import kotlinx.coroutines.launch

@Composable
fun SkillMarketScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToPublish: () -> Unit = {},
    onNavigateToManage: () -> Unit = {},
    onNavigateToDetail: ((GitHubIssue) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val skillRepository = remember { SkillRepository.getInstance(context.applicationContext) }
    val viewModel: SkillMarketViewModel = viewModel(
        factory = SkillMarketViewModel.Factory(context.applicationContext, skillRepository)
    )

    val githubAuth = remember { GitHubAuthPreferences.getInstance(context) }
    val isLoggedIn by githubAuth.isLoggedInFlow.collectAsState(initial = false)
    val currentUser by githubAuth.userInfoFlow.collectAsState(initial = null)

    val issues by viewModel.skillIssues.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val searchQuery by viewModel.searchQuery.collectAsState()
    val installingSkills by viewModel.installingSkills.collectAsState()
    val installedSkillNames by viewModel.installedSkillNames.collectAsState()
    val installedSkillRepoUrls by viewModel.installedSkillRepoUrls.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var showLoginDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadSkillMarketData()
        viewModel.refreshInstalledSkills()
    }

    errorMessage?.let { error ->
        LaunchedEffect(error) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Column {
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
                                stringResource(R.string.login_github_for_skill_market),
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

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> SkillBrowseTab(
                    issues = issues,
                    isLoading = isLoading,
                    searchQuery = searchQuery,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    viewModel = viewModel,
                    installingSkills = installingSkills,
                    installedSkillRepoUrls = installedSkillRepoUrls,
                    installedSkillNames = installedSkillNames,
                    onInstall = { repoUrl ->
                        viewModel.installSkillFromRepoUrl(repoUrl)
                    },
                    onRefresh = {
                        scope.launch {
                            viewModel.loadSkillMarketData()
                            viewModel.refreshInstalledSkills()
                        }
                    },
                    onViewDetails = { issue ->
                        if (onNavigateToDetail != null) {
                            onNavigateToDetail(issue)
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issue.html_url))
                            context.startActivity(intent)
                        }
                    }
                )
                1 -> SkillMyTab(
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
private fun SkillBrowseTab(
    issues: List<GitHubIssue>,
    isLoading: Boolean,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    viewModel: SkillMarketViewModel,
    installingSkills: Set<String>,
    installedSkillRepoUrls: Set<String>,
    installedSkillNames: Set<String>,
    onInstall: (String) -> Unit,
    onRefresh: () -> Unit,
    onViewDetails: (GitHubIssue) -> Unit
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
                    viewModel.loadMoreSkillMarketData()
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.skill_market_search_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (searchQuery.isBlank()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.available_skills_market),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = onRefresh) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                }
                            }
                        }
                    }

                    items(issues, key = { it.id }) { issue ->
                        val skillInfo = remember(issue) { SkillIssueParser.parseSkillInfo(issue) }
                        val repoUrl = skillInfo.repositoryUrl
                        val isInstalling = repoUrl.isNotBlank() && repoUrl in installingSkills
                        val isInstalled =
                            (repoUrl.isNotBlank() && repoUrl in installedSkillRepoUrls) ||
                                issue.title in installedSkillNames

                        SkillIssueCard(
                            issue = issue,
                            skillInfo = skillInfo,
                            viewModel = viewModel,
                            isInstalling = isInstalling,
                            isInstalled = isInstalled,
                            onInstall = {
                                if (repoUrl.isBlank()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.skill_repo_url_not_found_cannot_install),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    onInstall(repoUrl)
                                }
                            },
                            onViewDetails = {
                                onViewDetails(issue)
                            }
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
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
                                        if (searchQuery.isNotBlank()) stringResource(R.string.no_matching_skills_found) else stringResource(R.string.no_skills_available),
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
private fun SkillMyTab(
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
                    stringResource(R.string.need_login_github_manage_skills),
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
                    Text(stringResource(R.string.publish_new_skill))
                }
                OutlinedButton(
                    onClick = onNavigateToManage,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.manage_my_skills))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(0.8f),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
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
private fun SkillIssueCard(
    issue: GitHubIssue,
    skillInfo: SkillIssueParser.ParsedSkillInfo,
    viewModel: SkillMarketViewModel,
    onInstall: () -> Unit,
    onViewDetails: () -> Unit,
    isInstalling: Boolean,
    isInstalled: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onViewDetails() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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

                if (skillInfo.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = skillInfo.description.take(100) + if (skillInfo.description.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val avatarUrl by viewModel.userAvatarCache.collectAsState()
                LaunchedEffect(skillInfo.repositoryOwner) {
                    if (skillInfo.repositoryOwner.isNotBlank()) {
                        viewModel.fetchUserAvatar(skillInfo.repositoryOwner)
                    }
                }

                val thumbsUpCount = issue.reactions?.thumbs_up ?: 0
                val heartCount = issue.reactions?.heart ?: 0

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (skillInfo.repositoryOwner.isNotBlank()) {
                        val userAvatarUrl = avatarUrl[skillInfo.repositoryOwner]
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

            Surface(
                shape = CircleShape,
                color = containerColor
            ) {
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = contentColor
                            )
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
    androidx.compose.material3.AlertDialog(
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
                            .clip(CircleShape),
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
                Text(stringResource(R.string.after_logging_in_you_can))
            }
        },
        confirmButton = {
            if (isLoggedIn) {
                Button(onClick = onLogout) {
                    Text(stringResource(R.string.logout))
                }
            } else {
                Button(onClick = onLogin) {
                    Text(stringResource(R.string.login_github))
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
