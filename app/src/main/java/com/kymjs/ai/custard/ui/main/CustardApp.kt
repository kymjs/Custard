package com.kymjs.ai.custard.ui.main

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.rememberNavController
import com.kymjs.ai.custard.core.tools.AIToolHandler
import com.kymjs.ai.custard.data.announcement.RemoteAnnouncementDisplay
import com.kymjs.ai.custard.data.announcement.RemoteAnnouncementRepository
import com.kymjs.ai.custard.data.mcp.MCPRepository
import com.kymjs.ai.custard.data.preferences.ApiPreferences
import com.kymjs.ai.custard.data.preferences.DisplayPreferencesManager
import com.kymjs.ai.custard.data.preferences.RemoteAnnouncementPreferences
import com.kymjs.ai.custard.data.preferences.UserPreferencesManager
import com.kymjs.ai.custard.ui.common.NavItem
import com.kymjs.ai.custard.ui.features.announcement.RemoteAnnouncementDialog
import com.kymjs.ai.custard.ui.main.layout.PhoneLayout
import com.kymjs.ai.custard.ui.main.layout.TabletLayout
import com.kymjs.ai.custard.ui.main.screens.CustardRouter
import com.kymjs.ai.custard.ui.main.screens.Screen
import com.kymjs.ai.custard.util.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.ui.features.update.screens.UpdateScreen

// 为TopAppBar的actions提供CompositionLocal
// 它允许子组件（如AIChatScreen）向上提供它们的action Composable
val LocalTopBarActions = compositionLocalOf<(@Composable (RowScope.() -> Unit)) -> Unit> { {} }

data class NavGroup(@StringRes val titleResId: Int, val items: List<NavItem>)

@Composable
fun CustardApp(initialNavItem: NavItem = NavItem.AiChat, toolHandler: AIToolHandler? = null) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val remoteAnnouncementRepository = remember { RemoteAnnouncementRepository() }
    val remoteAnnouncementPreferences = remember { RemoteAnnouncementPreferences(context) }

    // Navigation state - using a custom back stack
    var selectedItem by remember { mutableStateOf(initialNavItem) }
    var currentScreen by remember {
        mutableStateOf(CustardRouter.getScreenForNavItem(initialNavItem))
    }
    val backStack = remember { mutableStateListOf<Screen>() }

    // 跟踪是否是返回操作
    var isNavigatingBack by remember { mutableStateOf(false) }

    // 用于存储由子屏幕提供的TopAppBar Actions
    var topBarActions by remember { mutableStateOf<@Composable RowScope.() -> Unit>({}) }

    // 当currentScreen改变时，检查是否需要清空TopBarActions
    // 这是为了解决从有action的屏幕导航到无action的屏幕时，action残留的问题
    LaunchedEffect(currentScreen) {
        if (currentScreen !is Screen.AiChat && currentScreen !is Screen.TokenConfig) {
            topBarActions = {}
        }
    }

    // Navigation functions
    fun navigateTo(newScreen: Screen, fromDrawer: Boolean = false) {
        if (newScreen == currentScreen) return

        // 设置为前进导航
        isNavigatingBack = false

        if (fromDrawer) {
            // 从抽屉导航时，清除整个返回栈
            backStack.clear()
        } else {
            // 检查新屏幕是否为一级路由
            if (!newScreen.isSecondaryScreen) {
                // 如果是一级路由，清除栈中除了AI对话以外的所有内容
                if (backStack.isNotEmpty()) {
                    // 保留栈底的AI对话（如果存在）
                    val aiChatScreen = backStack.find { it is Screen.AiChat }
                    backStack.clear()
                    if (aiChatScreen != null && currentScreen !is Screen.AiChat) {
                        backStack.add(aiChatScreen)
                    }
                }
                // 如果当前是AI对话，并且要导航到其他一级路由，将AI对话加入栈底
                if (currentScreen is Screen.AiChat) {
                    backStack.add(currentScreen)
                }
            } else {
                // 二级路由导航，正常将当前屏幕加入栈
                backStack.add(currentScreen)
            }
        }
        currentScreen = newScreen
        // Update the selected NavItem if the new screen has one.
        newScreen.navItem?.let { navItem -> selectedItem = navItem }
    }

    fun goBack() {
        if (backStack.isNotEmpty()) {
            // 设置为返回导航
            isNavigatingBack = true

            val previousScreen = backStack.removeLast()
            currentScreen = previousScreen
            // Update the selected NavItem if the previous screen has one.
            previousScreen.navItem?.let { navItem -> selectedItem = navItem }
        } else if (currentScreen !is Screen.AiChat) {
            // 一级页面（如设置）在无返回栈时，返回到聊天首页而不是直接退出应用
            isNavigatingBack = true
            currentScreen = Screen.AiChat
            selectedItem = NavItem.AiChat
        }
    }

    // Function to navigate to TokenConfig, treated as sub-navigation.
    fun navigateToTokenConfig() {
        navigateTo(Screen.TokenConfig)
    }

    // Register system back handler to use our custom back stack.
    // 只要不在AI对话页，统一由应用内处理返回逻辑
    BackHandler(enabled = currentScreen !is Screen.AiChat, onBack = { goBack() })

    // 修改canGoBack的判断逻辑，只有当前屏幕是二级屏幕时才显示返回键
    val canGoBack = currentScreen.isSecondaryScreen

    var isLoading by remember { mutableStateOf(false) }

    // Tablet mode sidebar state
    var isTabletSidebarExpanded by remember { mutableStateOf(true) }
    var tabletSidebarWidth by remember { mutableStateOf(280.dp) } // 侧边栏默认宽度
    val collapsedTabletSidebarWidth = 64.dp // 收起时的宽度

    // Device screen size calculation
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    // Determine if using tablet layout based on screen width
    // Using Material Design 3 guidelines:
    // - Less than 600dp: phone
    // - 600dp and above: tablet
    val useTabletLayout = screenWidthDp >= 600

    var remoteAnnouncement by remember { mutableStateOf<RemoteAnnouncementDisplay?>(null) }

    fun dismissRemoteAnnouncement() {
        remoteAnnouncement?.let { announcement ->
            remoteAnnouncementPreferences.setAcknowledgedVersion(announcement.version)
        }
        remoteAnnouncement = null
    }

    // Navigation items grouped by category
    val navGroups = listOf(
        NavGroup(
            R.string.nav_group_ai_features,
            listOf(
                NavItem.AiChat,
                NavItem.AssistantConfig,
                NavItem.Packages,
                NavItem.MemoryBase
            )
        ),
        NavGroup(
            R.string.nav_group_tools,
            listOf(
                NavItem.Toolbox,
                NavItem.ShizukuCommands,
                NavItem.Workflow,
            )
        ),
        NavGroup(
            R.string.nav_group_system,
            listOfNotNull(
                NavItem.Settings,
                NavItem.Help,
                NavItem.About,
                NavItem.UpdateHistory
            )
        )
    )

    // Flattened list for components that need it
    val navItems = navGroups.flatMap { it.items }

    // Network state monitoring
    var isNetworkAvailable by remember { mutableStateOf(NetworkUtils.isNetworkAvailable(context)) }
    var networkType by remember { mutableStateOf(NetworkUtils.getNetworkType(context)) }

    // Periodically check network status
    LaunchedEffect(Unit) {
        while (true) {
            isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)
            networkType = NetworkUtils.getNetworkType(context)
            delay(10000) // Check every 10 seconds
        }
    }

    // Fetch remote announcement when network becomes available.
    LaunchedEffect(isNetworkAvailable) {
        if (!isNetworkAvailable || remoteAnnouncement != null) return@LaunchedEffect

        val announcement = remoteAnnouncementRepository.fetchDisplayableAnnouncement()
        if (announcement != null && remoteAnnouncementPreferences.shouldShow(announcement.version)) {
            remoteAnnouncement = announcement
        }
    }

    // Get FPS counter display setting
    val displayPreferencesManager = remember { DisplayPreferencesManager.getInstance(context) }
    val showFpsCounter = displayPreferencesManager.showFpsCounter.collectAsState(initial = false).value

    // Create an instance of MCPRepository
    val mcpRepository = remember { MCPRepository(context) }

    // Initialize MCP plugin status
    LaunchedEffect(Unit) {
        launch {
            // First scan local installed plugins
            mcpRepository.syncInstalledStatus()
        }
    }

    // Calculate drawer width for phone mode
    val drawerWidth = (screenWidthDp * 0.75).dp // Drawer width is 3/4 of screen width

    // Main app container
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        CompositionLocalProvider(LocalTopBarActions provides { actions: @Composable RowScope.() -> Unit ->
            topBarActions = actions
        }) {
            if (useTabletLayout) {
                // Tablet layout
                TabletLayout(
                    currentScreen = currentScreen,
                    selectedItem = selectedItem,
                    isTabletSidebarExpanded = isTabletSidebarExpanded,
                    isLoading = isLoading,
                    navGroups = navGroups,
                    navItems = navItems,
                    isNetworkAvailable = isNetworkAvailable,
                    networkType = networkType,
                    navController = navController,
                    scope = scope,
                    drawerState = drawerState,
                    showFpsCounter = showFpsCounter,
                    tabletSidebarWidth = tabletSidebarWidth,
                    collapsedTabletSidebarWidth = collapsedTabletSidebarWidth,
                    onScreenChange = { screen -> navigateTo(screen) },
                    onNavItemChange = { item ->
                        selectedItem = item
                    },
                    onDrawerItemSelected = { screen, _ ->
                        navigateTo(screen, fromDrawer = true)
                    },
                    onToggleSidebar = {
                        isTabletSidebarExpanded = !isTabletSidebarExpanded
                    },
                    navigateToTokenConfig = ::navigateToTokenConfig,
                    canGoBack = canGoBack,
                    onGoBack = ::goBack,
                    isNavigatingBack = isNavigatingBack,
                    topBarActions = { topBarActions() }
                )
            } else {
                // Phone layout
                PhoneLayout(
                    currentScreen = currentScreen,
                    selectedItem = selectedItem,
                    isLoading = isLoading,
                    navGroups = navGroups,
                    isNetworkAvailable = isNetworkAvailable,
                    networkType = networkType,
                    drawerWidth = drawerWidth,
                    navController = navController,
                    scope = scope,
                    drawerState = drawerState,
                    showFpsCounter = showFpsCounter,
                    onScreenChange = { screen -> navigateTo(screen) },
                    onNavItemChange = { item ->
                        selectedItem = item
                    },
                    onDrawerItemSelected = { screen, _ ->
                        navigateTo(screen, fromDrawer = true)
                    },
                    navigateToTokenConfig = ::navigateToTokenConfig,
                    canGoBack = canGoBack,
                    onGoBack = ::goBack,
                    isNavigatingBack = isNavigatingBack,
                    topBarActions = { topBarActions() }
                )
            }
        }

        remoteAnnouncement?.let { announcement ->
            RemoteAnnouncementDialog(
                title = announcement.title,
                body = announcement.body,
                acknowledgeText = announcement.acknowledgeText,
                countdownSeconds = announcement.countdownSec,
                onAcknowledge = { dismissRemoteAnnouncement() }
            )
        }
    }
}
