package com.kymjs.ai.custard.ui.features.chat.screens

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.kymjs.ai.custard.util.AppLogger
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kymjs.ai.custard.ui.components.CustomScaffold
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.core.tools.AIToolHandler
import com.kymjs.ai.custard.data.model.AITool
import com.kymjs.ai.custard.data.model.ApiProviderType
import com.kymjs.ai.custard.data.model.AttachmentInfo
import com.kymjs.ai.custard.data.model.ToolParameter
import com.kymjs.ai.custard.data.preferences.ApiPreferences
import com.kymjs.ai.custard.data.preferences.UserPreferencesManager
import com.kymjs.ai.custard.ui.components.ErrorDialog
import com.kymjs.ai.custard.ui.features.chat.components.*
import com.kymjs.ai.custard.ui.features.chat.components.style.input.agent.AgentChatInputSection
import com.kymjs.ai.custard.ui.features.chat.components.style.input.classic.ClassicChatInputSection
import com.kymjs.ai.custard.ui.features.chat.components.style.input.classic.ClassicChatSettingsBar
import com.kymjs.ai.custard.ui.features.chat.components.AndroidExportDialog
import com.kymjs.ai.custard.ui.features.chat.components.ExportCompleteDialog
import com.kymjs.ai.custard.ui.features.chat.components.ExportPlatformDialog
import com.kymjs.ai.custard.ui.features.chat.components.ExportProgressDialog
import com.kymjs.ai.custard.ui.features.chat.components.WindowsExportDialog
import com.kymjs.ai.custard.ui.features.chat.webview.workspace.WorkspaceScreen
import com.kymjs.ai.custard.ui.features.chat.webview.WorkspaceFileSelector
import com.kymjs.ai.custard.ui.features.chat.webview.computer.ComputerScreen
import com.kymjs.ai.custard.ui.features.chat.util.ConfigurationStateHolder
import com.kymjs.ai.custard.ui.features.chat.viewmodel.ChatViewModel
import com.kymjs.ai.custard.ui.main.LocalTopBarActions
import com.kymjs.ai.custard.ui.main.components.LocalAppBarContentColor
import com.kymjs.ai.custard.ui.main.screens.GestureStateHolder
import com.kymjs.ai.custard.ui.main.SharedFileHandler
import java.io.File
import com.kymjs.ai.custard.data.preferences.CharacterCardManager
import com.kymjs.ai.custard.ui.main.components.LocalIsCurrentScreen
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue


@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview(showBackground = true)
fun AIChatScreen(
    viewModel: ChatViewModel? = null,
    onNavigateToTokenConfig: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToUserPreferences: () -> Unit = {},
    onNavigateToModelConfig: () -> Unit = {},
    onNavigateToModelPrompts: () -> Unit = {},
    onNavigateToPackageManager: () -> Unit = {},
    onGestureConsumed: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val actualViewModel: ChatViewModel = viewModel ?: viewModel { ChatViewModel(context.applicationContext) }

    // 设置权限系统的颜色方案
    LaunchedEffect(colorScheme) { actualViewModel.setPermissionSystemColorScheme(colorScheme) }

    // Monitor shared files from external apps
    val sharedFiles by SharedFileHandler.sharedFiles.collectAsState()
    val sharedLinks by SharedFileHandler.sharedLinks.collectAsState()
    LaunchedEffect(sharedFiles) {
        sharedFiles?.let { uris ->
            if (uris.isNotEmpty()) {
                actualViewModel.handleSharedFiles(uris)
                SharedFileHandler.clearSharedFiles()
            }
        }
    }

    LaunchedEffect(sharedLinks) {
        sharedLinks?.let { urls ->
            if (urls.isNotEmpty()) {
                actualViewModel.handleSharedLinks(urls)
                SharedFileHandler.clearSharedLinks()
            }
        }
    }

    // 添加麦克风权限请求启动器
    val requestMicrophonePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (!isGranted) {
                Toast.makeText(context, context.getString(R.string.microphone_permission_denied), Toast.LENGTH_SHORT)
                    .show()
            }
        }

    // Get background image state
    val preferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val useBackgroundImage by preferencesManager.useBackgroundImage.collectAsState(initial = false)
    val backgroundImageUri by preferencesManager.backgroundImageUri.collectAsState(initial = null)
    val chatHeaderTransparent by preferencesManager.chatHeaderTransparent.collectAsState(initial = false)
    val chatInputTransparent by preferencesManager.chatInputTransparent.collectAsState(initial = false)
    val chatHeaderHistoryIconColor by preferencesManager.chatHeaderHistoryIconColor.collectAsState(
        initial = null
    )
    val chatHeaderPipIconColor by preferencesManager.chatHeaderPipIconColor.collectAsState(initial = null)
    val chatHeaderOverlayMode by preferencesManager.chatHeaderOverlayMode.collectAsState(initial = false)
    val showInputProcessingStatus by preferencesManager.showInputProcessingStatus.collectAsState(initial = true)
    val hasBackgroundImage = useBackgroundImage && backgroundImageUri != null

    // Collect chat style from preferences
    val chatStyleSetting by preferencesManager.chatStyle.collectAsState(initial = UserPreferencesManager.CHAT_STYLE_CURSOR)
    val chatStyle = remember(chatStyleSetting) {
        when (chatStyleSetting) {
            UserPreferencesManager.CHAT_STYLE_BUBBLE -> ChatStyle.BUBBLE
            else -> ChatStyle.CURSOR
        }
    }
    val inputStyle by
    preferencesManager.inputStyle.collectAsState(
        initial = UserPreferencesManager.INPUT_STYLE_AGENT,
    )
    val hostActivity = remember(context) { context.findActivity() }

    // Collect chat area horizontal padding from preferences
    val chatAreaHorizontalPadding by preferencesManager.chatAreaHorizontalPadding.collectAsState(initial = 16f)

    // 添加编辑按钮和编辑状态
    val editingMessageIndex = remember { mutableStateOf<Int?>(null) }
    val editingMessageContent = remember { mutableStateOf("") }

    // Collect state from ViewModel
    val apiKey by actualViewModel.apiKey.collectAsState()
    val apiEndpoint by actualViewModel.apiEndpoint.collectAsState()
    val modelName by actualViewModel.modelName.collectAsState()
    val apiProviderType by actualViewModel.apiProviderType.collectAsState()
    val isConfigured by actualViewModel.isConfigured.collectAsState()
    val chatHistory by actualViewModel.chatHistory.collectAsState()
    val userMessage by actualViewModel.userMessage.collectAsState()
    // 仅对当前会话显示处理中状态（影响“停止/发送”按钮）
    val isLoading by actualViewModel.currentChatIsLoading.collectAsState()
    val errorMessage by actualViewModel.errorMessage.collectAsState()
    // 按会话隔离的输入处理状态（用于进度条文案）
    val inputProcessingState by actualViewModel.currentChatInputProcessingState.collectAsState()

    val enableAiPlanning by actualViewModel.enableAiPlanning.collectAsState()
    val enableThinkingMode by actualViewModel.enableThinkingMode.collectAsState() // 收集思考模式状态
    val enableThinkingGuidance by
    actualViewModel.enableThinkingGuidance.collectAsState() // 收集思考引导状态
    val thinkingQualityLevel by actualViewModel.thinkingQualityLevel.collectAsState()
    val enableMemoryQuery by actualViewModel.enableMemoryQuery.collectAsState()
    val enableMaxContextMode by actualViewModel.enableMaxContextMode.collectAsState()
    val enableTools by actualViewModel.enableTools.collectAsState()
    val toolPromptVisibility by actualViewModel.toolPromptVisibility.collectAsState()
    val disableStreamOutput by actualViewModel.disableStreamOutput.collectAsState()
    val disableUserPreferenceDescription by
    actualViewModel.disableUserPreferenceDescription.collectAsState()
    val disableLatexDescription by actualViewModel.disableLatexDescription.collectAsState()
    val summaryTokenThreshold by actualViewModel.summaryTokenThreshold.collectAsState()
    val isAutoReadEnabled by actualViewModel.isAutoReadEnabled.collectAsState()
    val showChatHistorySelector by actualViewModel.showChatHistorySelector.collectAsState()
    val chatHistories by actualViewModel.chatHistories.collectAsState()
    val currentChatId by actualViewModel.currentChatId.collectAsState()
    val popupMessage by actualViewModel.popupMessage.collectAsState()
    val attachments by actualViewModel.attachments.collectAsState()
    // 收集附件面板状态
    val attachmentPanelState by actualViewModel.attachmentPanelState.collectAsState()
    // 收集滚动事件
    val scrollToBottomEvent = actualViewModel.scrollToBottomEvent
    // 从ViewModel收集新的状态
    val shouldShowConfigDialog by actualViewModel.shouldShowConfigDialog.collectAsState()
    val isWorkspaceOpen by actualViewModel.isWorkspaceOpen.collectAsState()
    val showWorkspaceFileSelector by actualViewModel.showWorkspaceFileSelector.collectAsState()

    // 添加模型建议对话框状态
    var showModelSuggestionDialog by remember { mutableStateOf(false) }

    // 添加记忆文件夹选择对话框状态
    var showMemoryFolderDialog by remember { mutableStateOf(false) }

    // 当模型名称加载后，检查是否为建议更换的模型
    LaunchedEffect(modelName) {
        if (modelName.isNotBlank() && modelName.contains("deepseek-r1-0528-qwen3-8b:free", ignoreCase = true)) {
            showModelSuggestionDialog = true
        }
    }

    // 模型建议对话框
    if (showModelSuggestionDialog) {
        AlertDialog(
            onDismissRequest = { showModelSuggestionDialog = false },
            title = { Text(stringResource(R.string.model_suggestion_title)) },
            text = { Text(stringResource(R.string.model_suggestion_message)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showModelSuggestionDialog = false
                        // 如果用户已输入token，直接保存配置
                        actualViewModel.updateApiKey(ApiPreferences.DEFAULT_API_KEY)
                        actualViewModel.updateApiEndpoint(ApiPreferences.DEFAULT_API_ENDPOINT)
                        actualViewModel.updateModelName(ApiPreferences.DEFAULT_MODEL_NAME)
                        actualViewModel.updateApiProviderType(ApiProviderType.DEEPSEEK)
                        actualViewModel.saveApiSettings()

                        // 新增：重置状态以重新显示配置界面
                        ConfigurationStateHolder.hasConfirmedDefaultInSession = false
                        actualViewModel.showConfigurationScreen()

                    }) {
                        Text(stringResource(R.string.change_model))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelSuggestionDialog = false }) {
                    Text(stringResource(R.string.ignore))
                }
            }
        )
    }


    // 添加WebView刷新相关状态
    val webViewRefreshCounter by actualViewModel.webViewRefreshCounter.collectAsState()

    // Collect reply state
    val replyToMessage by actualViewModel.replyToMessage.collectAsState()

    // Floating window mode state
    val isFloatingMode by actualViewModel.isFloatingMode.collectAsState()
    val canDrawOverlays = remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // UI state
    val scrollState = rememberScrollState()
    val historyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val characterCardManager = remember { CharacterCardManager.getInstance(context) }


    // 确保每次应用启动时正确处理配置界面的显示逻辑
    LaunchedEffect(apiKey) {
        // 只有当apiKey有效值时才执行逻辑，防止初始化阶段的不正确判断
        if (apiKey.isNotBlank()) {
            // 如果使用的是自定义配置，标记为已确认，不显示配置界面
            if (apiKey != ApiPreferences.DEFAULT_API_KEY) {
                ConfigurationStateHolder.hasConfirmedDefaultInSession = true
            }
        }
    }

    // Modern chat UI colors - Cursor风格
    val userMessageColor = MaterialTheme.colorScheme.primaryContainer
    val aiMessageColor = MaterialTheme.colorScheme.surface
    val userTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val aiTextColor = MaterialTheme.colorScheme.onSurface
    val systemMessageColor = MaterialTheme.colorScheme.surfaceVariant
    val systemTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val thinkingBackgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val thinkingTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 滚动状态
    var autoScrollToBottom by remember { mutableStateOf(true) }
    val onAutoScrollToBottomChange = remember { { it: Boolean -> autoScrollToBottom = it } }
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // 处理来自ViewModel的滚动事件（流式输出时）
    LaunchedEffect(Unit) {
        scrollToBottomEvent.collect {
            if (autoScrollToBottom) {
                try {
                    scrollState.animateScrollTo(scrollState.maxValue)
                } catch (e: Exception) {
                    // AppLogger.e("AIChatScreen", "自动滚动失败", e)
                }
            }
        }
    }

    // 消息新增时的自动滚动
    LaunchedEffect(chatHistory.size) {
        if (autoScrollToBottom) {
            try {
                scrollState.animateScrollTo(scrollState.maxValue)
            } catch (e: Exception) {
                // AppLogger.e("AIChatScreen", "自动滚动失败", e)
            }
        }
    }

    // 移除原有的 snackbar 错误处理
    val snackbarHostState = remember { SnackbarHostState() }

    // 用新的错误弹窗替换原有的错误显示逻辑
    errorMessage?.let { message ->
        ErrorDialog(errorMessage = message, onDismiss = { actualViewModel.dismissErrorDialog() })
    }

    // 处理toast事件 (保留)
    val toastEvent by actualViewModel.toastEvent.collectAsState()

    toastEvent?.let { message ->
        LaunchedEffect(message) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG)
                .show()
            actualViewModel.clearToastEvent()
        }
    }

    // Save chat on app exit
    DisposableEffect(Unit) {
        onDispose {
            // This is handled by the ViewModel
        }
    }

    // 确定是否显示配置界面的最终逻辑
    val showConfig = shouldShowConfigDialog && !ConfigurationStateHolder.hasConfirmedDefaultInSession

    // 添加手势状态
    var chatScreenGestureConsumed by remember { mutableStateOf(false) }
    val onChatScreenGestureConsumedChange = remember {
        { it: Boolean -> chatScreenGestureConsumed = it }
    }

    // 添加累计滑动距离变量
    var currentDrag by remember { mutableStateOf(0f) }
    val onCurrentDragChange = remember { { it: Float -> currentDrag = it } }
    var verticalDrag by remember { mutableStateOf(0f) }
    val onVerticalDragChange = remember { { it: Float -> verticalDrag = it } }
    val dragThreshold = 40f // 与PhoneLayout保持一致

    // 收集WebView显示状态
    val showWebView by actualViewModel.showWebView.collectAsState()
    // 收集AI电脑显示状态
    val showAiComputer by actualViewModel.showAiComputer.collectAsState()
    val manifestSoftInputMode = remember(hostActivity) { hostActivity?.manifestSoftInputMode() }
    LaunchedEffect(inputStyle, showWebView, showAiComputer, hostActivity) {
        val window = hostActivity?.window
        if (window != null) {
            val shouldUseChatLocalImeHandling =
                inputStyle == UserPreferencesManager.INPUT_STYLE_AGENT &&
                        !showWebView &&
                        !showAiComputer
            val shouldUseWorkspaceImeResize = showWebView || showAiComputer
            val targetSoftInputMode =
                if (shouldUseChatLocalImeHandling) {
                    // 聊天输入页：由 Compose 局部位移处理输入法
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                } else if (shouldUseWorkspaceImeResize) {
                    // 工作区/终端等页面：使用系统 resize，避免输入框被键盘遮挡
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                } else {
                    // 常规页面保持 pan，与 Manifest 默认行为一致
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                }
            window.setSoftInputMode(targetSoftInputMode)
        }
    }
    DisposableEffect(hostActivity, manifestSoftInputMode) {
        onDispose {
            val window = hostActivity?.window
            if (window != null && manifestSoftInputMode != null) {
                window.setSoftInputMode(manifestSoftInputMode)
            }
        }
    }

    var hasEverShownWebView by remember { mutableStateOf(false) }
    LaunchedEffect(showWebView) {
        if (showWebView) {
            hasEverShownWebView = true
        }
    }

    // 当手势状态改变时，通知父组件
    LaunchedEffect(chatScreenGestureConsumed, showWebView) {
        val finalGestureState = chatScreenGestureConsumed
        // 同时更新全局状态持有者，确保PhoneLayout能够访问到状态
        GestureStateHolder.isChatScreenGestureConsumed = finalGestureState
        onGestureConsumed(finalGestureState)
    }

    // 处理文件选择器请求
    val fileChooserRequest by actualViewModel.uiStateDelegate.fileChooserRequest.collectAsState()
    val fileChooserLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // 处理文件选择结果
            actualViewModel.handleFileChooserResult(result.resultCode, result.data)
            // 清除请求
            actualViewModel.uiStateDelegate.clearFileChooserRequest()
        }

    // 启动文件选择器
    LaunchedEffect(fileChooserRequest) {
        fileChooserRequest?.let { fileChooserLauncher.launch(it) }
    }

    // 从CompositionLocal获取设置TopBar Actions的函数
    val setTopBarActions = LocalTopBarActions.current
    val appBarContentColor = LocalAppBarContentColor.current
    val isCurrentScreen = LocalIsCurrentScreen.current


    // 当showWebView或showAiComputer状态改变时，更新TopAppBar的actions
    // 使用DisposableEffect确保当AIChatScreen离开组合时，actions被清空
    LaunchedEffect(isCurrentScreen, showWebView, showAiComputer, appBarContentColor) {
        if (isCurrentScreen) {
            setTopBarActions {
                // AI电脑模式切换按钮
                IconButton(
                    onClick = {
                        actualViewModel.onAiComputerButtonClick()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = stringResource(R.string.ai_computer),
                        tint =
                            if (showAiComputer) MaterialTheme.colorScheme.primaryContainer
                            else appBarContentColor
                    )
                }

                // Web开发模式切换按钮
                IconButton(
                    onClick = {
                        actualViewModel.onWorkspaceButtonClick()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = stringResource(R.string.code_editor),
                        tint =
                            if (showWebView) MaterialTheme.colorScheme.primaryContainer
                            else appBarContentColor
                    )
                }
            }
        }
    }

    // 导出相关状态
    var showExportPlatformDialog by remember { mutableStateOf(false) }
    var showAndroidExportDialog by remember { mutableStateOf(false) }
    var showWindowsExportDialog by remember { mutableStateOf(false) }
    var showExportProgressDialog by remember { mutableStateOf(false) }
    var showExportCompleteDialog by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportStatus by remember { mutableStateOf("") }
    var exportSuccess by remember { mutableStateOf(false) }
    var exportFilePath by remember { mutableStateOf<String?>(null) }
    var exportErrorMessage by remember { mutableStateOf<String?>(null) }
    var webContentDir by remember { mutableStateOf<File?>(null) }

    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val inputSurfaceColor = when {
        chatInputTransparent -> colorScheme.surface.copy(alpha = 0f)
        hasBackgroundImage -> colorScheme.surface.copy(alpha = 0.85f)
        else -> colorScheme.surface
    }
    Box(modifier = Modifier.fillMaxSize()) {
        CustomScaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // 只在不显示配置界面时显示底部输入框
                if (!showConfig) {
                    // ChatInputSection is back in the bottomBar to reserve space
                    Box(
                        modifier = Modifier.onGloballyPositioned {
                            bottomBarHeightPx = it.size.height
                        }
                    ) {
                        if (inputStyle == UserPreferencesManager.INPUT_STYLE_AGENT) {
                            AgentChatInputSection(
                                actualViewModel = actualViewModel,
                                userMessage = userMessage,
                                onUserMessageChange = {
                                    actualViewModel.updateUserMessage(it)
                                },
                                onSendMessage = {
                                    if (currentChatId.isNullOrBlank()) {
                                        actualViewModel.createNewChat()
                                    }
                                    focusManager.clearFocus()
                                    actualViewModel.sendUserMessage()
                                    actualViewModel.resetAttachmentPanelState()
                                    autoScrollToBottom = true
                                },
                                onCancelMessage = { actualViewModel.cancelCurrentMessage() },
                                isLoading = isLoading,
                                inputState = inputProcessingState,
                                allowTextInputWhileProcessing = true,
                                onAttachmentRequest = { filePath ->
                                    actualViewModel.handleAttachment(filePath)
                                },
                                attachments = attachments,
                                onRemoveAttachment = { filePath ->
                                    actualViewModel.removeAttachment(filePath)
                                },
                                onInsertAttachment = { attachment: AttachmentInfo ->
                                    actualViewModel.insertAttachmentReference(attachment)
                                },
                                onAttachScreenContent = {
                                    actualViewModel.captureScreenContent()
                                },
                                onAttachNotifications = {
                                    actualViewModel.captureNotifications()
                                },
                                onAttachLocation = { actualViewModel.captureLocation() },
                                onAttachMemory = { showMemoryFolderDialog = true },
                                onTakePhoto = { uri ->
                                    actualViewModel.handleTakenPhoto(uri)
                                },
                                hasBackgroundImage = hasBackgroundImage,
                                chatInputTransparent = chatInputTransparent,
                                externalAttachmentPanelState = attachmentPanelState,
                                onAttachmentPanelStateChange = { newState ->
                                    actualViewModel.updateAttachmentPanelState(newState)
                                },
                                showInputProcessingStatus = showInputProcessingStatus,
                                enableTools = enableTools,
                                replyToMessage = replyToMessage,
                                onClearReply = {
                                    actualViewModel.clearReplyToMessage()
                                },
                                isWorkspaceOpen = isWorkspaceOpen,
                                enableThinkingMode = enableThinkingMode,
                                onToggleThinkingMode = {
                                    actualViewModel.toggleThinkingMode()
                                },
                                enableThinkingGuidance = enableThinkingGuidance,
                                onToggleThinkingGuidance = {
                                    actualViewModel.toggleThinkingGuidance()
                                },
                                enableMaxContextMode = enableMaxContextMode,
                                onToggleEnableMaxContextMode = {
                                    actualViewModel.toggleEnableMaxContextMode()
                                },
                                enableAiPlanning = enableAiPlanning,
                                onToggleAiPlanning = { actualViewModel.toggleAiPlanning() },
                                permissionLevel =
                                    actualViewModel.masterPermissionLevel
                                        .collectAsState()
                                        .value,
                                onTogglePermission = { actualViewModel.toggleMasterPermission() },
                                enableMemoryQuery = enableMemoryQuery,
                                onToggleMemoryQuery = { actualViewModel.toggleMemoryQuery() },
                                isAutoReadEnabled = isAutoReadEnabled,
                                onToggleAutoRead = { actualViewModel.toggleAutoRead() },
                                onToggleTools = { actualViewModel.toggleTools() },
                                disableStreamOutput = disableStreamOutput,
                                onToggleDisableStreamOutput = {
                                    actualViewModel.toggleDisableStreamOutput()
                                },
                                disableUserPreferenceDescription =
                                    disableUserPreferenceDescription,
                                onToggleDisableUserPreferenceDescription = {
                                    actualViewModel.toggleDisableUserPreferenceDescription()
                                },
                                disableLatexDescription = disableLatexDescription,
                                onToggleDisableLatexDescription = {
                                    actualViewModel.toggleDisableLatexDescription()
                                },
                                onNavigateToUserPreferences = onNavigateToUserPreferences,
                                onNavigateToPackageManager = onNavigateToPackageManager,
                                toolPromptVisibility = toolPromptVisibility,
                                onSaveToolPromptVisibilityMap = { visibilityMap ->
                                    actualViewModel.saveToolPromptVisibilityMap(visibilityMap)
                                },
                                onManualMemoryUpdate = {
                                    actualViewModel.manuallyUpdateMemory()
                                },
                                onNavigateToModelConfig = onNavigateToModelConfig,
                            )
                        } else {
                            ClassicChatInputSection(
                                actualViewModel = actualViewModel,
                                userMessage = userMessage,
                                onUserMessageChange = {
                                    actualViewModel.updateUserMessage(it)
                                },
                                onSendMessage = {
                                    if (currentChatId.isNullOrBlank()) {
                                        actualViewModel.createNewChat()
                                    }
                                    focusManager.clearFocus()
                                    actualViewModel.sendUserMessage()
                                    actualViewModel.resetAttachmentPanelState()
                                    autoScrollToBottom = true
                                },
                                onCancelMessage = { actualViewModel.cancelCurrentMessage() },
                                isLoading = isLoading,
                                inputState = inputProcessingState,
                                allowTextInputWhileProcessing = true,
                                onAttachmentRequest = { filePath ->
                                    actualViewModel.handleAttachment(filePath)
                                },
                                attachments = attachments,
                                onRemoveAttachment = { filePath ->
                                    actualViewModel.removeAttachment(filePath)
                                },
                                onInsertAttachment = { attachment: AttachmentInfo ->
                                    actualViewModel.insertAttachmentReference(attachment)
                                },
                                onAttachScreenContent = {
                                    actualViewModel.captureScreenContent()
                                },
                                onAttachNotifications = {
                                    actualViewModel.captureNotifications()
                                },
                                onAttachLocation = { actualViewModel.captureLocation() },
                                onAttachMemory = { showMemoryFolderDialog = true },
                                onTakePhoto = { uri ->
                                    actualViewModel.handleTakenPhoto(uri)
                                },
                                hasBackgroundImage = hasBackgroundImage,
                                chatInputTransparent = chatInputTransparent,
                                externalAttachmentPanelState = attachmentPanelState,
                                onAttachmentPanelStateChange = { newState ->
                                    actualViewModel.updateAttachmentPanelState(newState)
                                },
                                showInputProcessingStatus = showInputProcessingStatus,
                                enableTools = enableTools,
                                replyToMessage = replyToMessage,
                                onClearReply = {
                                    actualViewModel.clearReplyToMessage()
                                },
                                isWorkspaceOpen = isWorkspaceOpen
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            // 根据前面的逻辑条件决定是否显示配置界面
            if (showConfig) {
                ConfigurationScreen(
                    apiEndpoint = apiEndpoint,
                    apiKey = apiKey,
                    modelName = modelName,
                    onApiEndpointChange = { actualViewModel.updateApiEndpoint(it) },
                    onApiKeyChange = { actualViewModel.updateApiKey(it) },
                    onModelNameChange = { actualViewModel.updateModelName(it) },
                    onApiProviderTypeChange = { actualViewModel.updateApiProviderType(it) },
                    onSaveConfig = {
                        actualViewModel.saveApiSettings()
                        // 保存配置后导航到聊天界面
                        ConfigurationStateHolder.hasConfirmedDefaultInSession = true
                        actualViewModel.onConfigDialogConfirmed()
                    },
                    onError = { error -> actualViewModel.showErrorMessage(error) },
                    coroutineScope = coroutineScope,
                    // 新增：使用默认配置的回调
                    onUseDefault = {
                        actualViewModel.useDefaultConfig()
                        // 确认使用默认配置后导航到聊天界面
                        ConfigurationStateHolder.hasConfirmedDefaultInSession = true
                        actualViewModel.onConfigDialogConfirmed()
                    },
                    // 标识是否在使用默认配置
                    isUsingDefault = true, // 当显示此屏幕时，总是因为使用了默认值
                    // 添加导航到聊天界面的回调
                    onNavigateToChat = {
                        // 当用户设置了自己的配置后保存
                        actualViewModel.saveApiSettings()
                        // 确认后导航到聊天界面
                        ConfigurationStateHolder.hasConfirmedDefaultInSession = true
                        actualViewModel.onConfigDialogConfirmed()
                    },
                    // 添加导航到Token配置页面的回调
                    onNavigateToTokenConfig = onNavigateToTokenConfig,
                    // 添加导航到Settings页面的回调
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToModelConfig = onNavigateToModelConfig
                )
            } else {
                // The main content area is now a Box to allow overlaying.
                // It respects the padding from the Scaffold's bottomBar.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    val chatContentImeLiftModifier =
                        if (
                            inputStyle == UserPreferencesManager.INPUT_STYLE_AGENT &&
                            imeBottomPx > 0
                        ) {
                            Modifier.graphicsLayer { translationY = -imeBottomPx.toFloat() }
                        } else {
                            Modifier
                        }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(chatContentImeLiftModifier)
                    ) {
                        ChatScreenContent(
                            // modifier = Modifier.weight(1f), // This is no longer needed here
                            paddingValues =
                                PaddingValues(), // Padding is already handled by the parent Box
                            actualViewModel = actualViewModel,
                            showChatHistorySelector = showChatHistorySelector,
                            chatHistory = chatHistory,
                            enableAiPlanning = enableAiPlanning,
                            isLoading = isLoading,
                            userMessageColor = userMessageColor,
                            aiMessageColor = aiMessageColor,
                            userTextColor = userTextColor,
                            aiTextColor = aiTextColor,
                            systemMessageColor = systemMessageColor,
                            systemTextColor = systemTextColor,
                            thinkingBackgroundColor = thinkingBackgroundColor,
                            thinkingTextColor = thinkingTextColor,
                            hasBackgroundImage = hasBackgroundImage,
                            editingMessageIndex = editingMessageIndex,
                            editingMessageContent = editingMessageContent,
                            chatScreenGestureConsumed = chatScreenGestureConsumed,
                            onChatScreenGestureConsumed = onChatScreenGestureConsumedChange,
                            currentDrag = currentDrag,
                            onCurrentDragChange = onCurrentDragChange,
                            verticalDrag = verticalDrag,
                            onVerticalDragChange = onVerticalDragChange,
                            dragThreshold = dragThreshold,
                            scrollState = scrollState,
                            autoScrollToBottom = autoScrollToBottom,
                            onAutoScrollToBottomChange = onAutoScrollToBottomChange,
                            coroutineScope = coroutineScope,
                            chatHistories = chatHistories,
                            currentChatId = currentChatId ?: "",
                            chatHeaderTransparent = chatHeaderTransparent,
                            chatHeaderHistoryIconColor = chatHeaderHistoryIconColor,
                            chatHeaderPipIconColor = chatHeaderPipIconColor,
                            chatHeaderOverlayMode = chatHeaderOverlayMode,
                            chatStyle = chatStyle, // Pass chat style
                            historyListState = historyListState,
                            onSwitchCharacter = { characterId ->
                                coroutineScope.launch {
                                    characterCardManager.setActiveCharacterCard(characterId)
                                }
                            },
                            chatAreaHorizontalPadding = chatAreaHorizontalPadding
                        )

                        if (inputStyle == UserPreferencesManager.INPUT_STYLE_CLASSIC) {
                            ClassicChatSettingsBar(
                                modifier = Modifier.align(Alignment.BottomEnd),
                                enableAiPlanning = enableAiPlanning,
                                onToggleAiPlanning = { actualViewModel.toggleAiPlanning() },
                                permissionLevel =
                                    actualViewModel.masterPermissionLevel
                                        .collectAsState()
                                        .value,
                                onTogglePermission = { actualViewModel.toggleMasterPermission() },
                                enableThinkingMode = enableThinkingMode,
                                onToggleThinkingMode = { actualViewModel.toggleThinkingMode() },
                                enableThinkingGuidance = enableThinkingGuidance,
                                onToggleThinkingGuidance = {
                                    actualViewModel.toggleThinkingGuidance()
                                },
                                thinkingQualityLevel = thinkingQualityLevel,
                                onThinkingQualityLevelChange = {
                                    actualViewModel.updateThinkingQualityLevel(it)
                                },
                                maxWindowSizeInK =
                                    actualViewModel.maxWindowSizeInK.collectAsState().value,
                                baseContextLengthInK =
                                    actualViewModel.baseContextLengthInK.collectAsState().value,
                                maxContextLengthInK =
                                    actualViewModel.maxContextLengthInK.collectAsState().value,
                                onContextLengthChange = {
                                    actualViewModel.updateContextLength(it)
                                },
                                enableMemoryQuery = enableMemoryQuery,
                                onToggleMemoryQuery = {
                                    actualViewModel.toggleMemoryQuery()
                                },
                                enableMaxContextMode = enableMaxContextMode,
                                onToggleEnableMaxContextMode = {
                                    actualViewModel.toggleEnableMaxContextMode()
                                },
                                summaryTokenThreshold = summaryTokenThreshold,
                                onSummaryTokenThresholdChange = {
                                    actualViewModel.updateSummaryTokenThreshold(it)
                                },
                                onNavigateToUserPreferences = onNavigateToUserPreferences,
                                onNavigateToModelConfig = onNavigateToModelConfig,
                                onNavigateToModelPrompts = onNavigateToModelPrompts,
                                onNavigateToPackageManager = onNavigateToPackageManager,
                                isAutoReadEnabled = isAutoReadEnabled,
                                onToggleAutoRead = { actualViewModel.toggleAutoRead() },
                                enableTools = enableTools,
                                onToggleTools = { actualViewModel.toggleTools() },
                                toolPromptVisibility = toolPromptVisibility,
                                onSaveToolPromptVisibilityMap = { visibilityMap ->
                                    actualViewModel.saveToolPromptVisibilityMap(visibilityMap)
                                },
                                disableStreamOutput = disableStreamOutput,
                                onToggleDisableStreamOutput = {
                                    actualViewModel.toggleDisableStreamOutput()
                                },
                                disableUserPreferenceDescription =
                                    disableUserPreferenceDescription,
                                onToggleDisableUserPreferenceDescription = {
                                    actualViewModel.toggleDisableUserPreferenceDescription()
                                },
                                disableLatexDescription = disableLatexDescription,
                                onToggleDisableLatexDescription = {
                                    actualViewModel.toggleDisableLatexDescription()
                                },
                                onManualMemoryUpdate = {
                                    actualViewModel.manuallyUpdateMemory()
                                },
                                onManualSummarizeConversation = {
                                    actualViewModel.manuallySummarizeConversation()
                                }
                            )
                        }
                    }
                }
            }
        }

        // 文件选择器，现在位于Scaffold外部，作为独立的浮层
        val bottomPaddingForSelector = with(density) { bottomBarHeightPx.toDp() }
        AnimatedVisibility(
            visible = showWorkspaceFileSelector,
            enter = fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = fadeOut(animationSpec = tween(durationMillis = 150))
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.2f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { actualViewModel.hideWorkspaceFileSelector() }
                        )
                )
                WorkspaceFileSelector(
                    modifier = Modifier
                        .padding(bottom = bottomPaddingForSelector)
                        .animateEnterExit(
                            enter = slideInVertically(
                                animationSpec = tween(durationMillis = 240)
                            ) { it },
                            exit = slideOutVertically(
                                animationSpec = tween(durationMillis = 200)
                            ) { it }
                        ),
                    viewModel = actualViewModel,
                    onFileSelected = { filePath ->
                        val currentChat =
                            actualViewModel.chatHistories.value.find { it.id == actualViewModel.currentChatId.value }
                        val workspacePath = currentChat?.workspace
                        val relativePath = if (workspacePath != null) {
                            File(filePath).relativeTo(File(workspacePath)).path
                        } else {
                            filePath
                        }
                        val currentText = userMessage.text
                        val newText = currentText.replaceAfterLast('@', "$relativePath ")
                        val newTextFieldValue = TextFieldValue(
                            text = newText,
                            selection = TextRange(newText.length)
                        )
                        actualViewModel.updateUserMessage(newTextFieldValue)
                        actualViewModel.hideWorkspaceFileSelector()
                    },
                    onShouldHide = {
                        actualViewModel.hideWorkspaceFileSelector()
                    },
                    backgroundColor = inputSurfaceColor
                )
            }
        }

        // Web开发模式作为浮层，现在位于Scaffold外部，可以覆盖整个屏幕
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = if (showWebView) 1f else 0f)
                .clipToBounds(),
            content = {
                // The content is composed unconditionally, keeping it "alive"
                val currentChat = chatHistories.find { it.id == currentChatId }
                if (hasEverShownWebView && currentChat != null) {
                    WorkspaceScreen(
                        actualViewModel = actualViewModel,
                        currentChat = currentChat,
                        isVisible = showWebView, // Pass visibility state
                        onExportClick = { workDir ->
                            webContentDir = workDir
                            AppLogger.d(
                                "AIChatScreen",
                                "正在导出工作区: ${workDir.absolutePath}, 聊天ID: $currentChatId"
                            )
                            showExportPlatformDialog = true
                        }
                    )
                }
            }
        ) { measurables, constraints ->
            if (measurables.isEmpty()) {
                layout(0, 0) {}
            } else {
                if (showWebView) {
                    val placeable = measurables.first().measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                } else {
                    // 当不可见时，我们让布局大小为0，并且完全不测量或放置子项。
                    // 这可以跳过子项昂贵的测量/布局过程，从而解决性能问题，
                    // 同时由于它仍在组合中，因此可以保持其状态。
                    layout(0, 0) {}
                }
            }
        }

        // AI电脑模式作为浮层：关闭时完全移出组合，确保 SurfaceView 被释放，避免机型相关残影
        if (showAiComputer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                ComputerScreen()
            }
        }

        // 导出平台选择对话框
        if (showExportPlatformDialog) {
            ExportPlatformDialog(
                onDismiss = { showExportPlatformDialog = false },
                onSelectAndroid = {
                    showExportPlatformDialog = false
                    showAndroidExportDialog = true
                },
                onSelectWindows = {
                    showExportPlatformDialog = false
                    showWindowsExportDialog = true
                }
            )
        }

        // Android导出设置对话框
        if (showAndroidExportDialog && webContentDir != null) {
            AndroidExportDialog(
                workDir = webContentDir!!,
                onDismiss = { showAndroidExportDialog = false },
                onExport = { packageName, appName, iconUri, versionName, versionCode ->
                    showAndroidExportDialog = false
                    showExportProgressDialog = true
                    exportProgress = 0f
                    exportStatus = context.getString(R.string.export_starting)

                    // 启动导出过程
                    coroutineScope.launch {
                        exportAndroidApp(
                            context = context,
                            packageName = packageName,
                            appName = appName,
                            versionName = versionName,
                            versionCode = versionCode,
                            iconUri = iconUri,
                            webContentDir = webContentDir!!,
                            onProgress = { progress, status ->
                                exportProgress = progress
                                exportStatus = status
                            },
                            onComplete = { success, filePath, errorMessage ->
                                showExportProgressDialog = false
                                exportSuccess = success
                                exportFilePath = filePath
                                exportErrorMessage = errorMessage
                                showExportCompleteDialog = true
                            }
                        )
                    }
                }
            )
        }

        // Windows导出设置对话框
        if (showWindowsExportDialog && webContentDir != null) {
            WindowsExportDialog(
                workDir = webContentDir!!,
                onDismiss = { showWindowsExportDialog = false },
                onExport = { appName, iconUri ->
                    showWindowsExportDialog = false
                    showExportProgressDialog = true
                    exportProgress = 0f
                    exportStatus = context.getString(R.string.export_starting)

                    // 启动导出过程
                    coroutineScope.launch {
                        exportWindowsApp(
                            context = context,
                            appName = appName,
                            iconUri = iconUri,
                            webContentDir = webContentDir!!,
                            onProgress = { progress, status ->
                                exportProgress = progress
                                exportStatus = status
                            },
                            onComplete = { success, filePath, errorMessage ->
                                showExportProgressDialog = false
                                exportSuccess = success
                                exportFilePath = filePath
                                exportErrorMessage = errorMessage
                                showExportCompleteDialog = true
                            }
                        )
                    }
                }
            )
        }

        // 导出进度对话框
        if (showExportProgressDialog) {
            ExportProgressDialog(
                progress = exportProgress,
                status = exportStatus,
                onCancel = {
                    // TODO: 实现取消导出的逻辑
                    showExportProgressDialog = false
                }
            )
        }

        // 导出完成对话框
        if (showExportCompleteDialog) {
            ExportCompleteDialog(
                success = exportSuccess,
                filePath = exportFilePath,
                errorMessage = exportErrorMessage,
                onDismiss = { showExportCompleteDialog = false },
                onOpenFile = { path ->
                    val tool = AITool(
                        name = if (path.endsWith(".apk", ignoreCase = true)) "install_app" else "open_file",
                        parameters = listOf(ToolParameter("path", path))
                    )
                    AIToolHandler.getInstance(context).executeTool(tool)
                }
            )
        }
    }

    // Show popup message dialog when needed
    popupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { actualViewModel.clearPopupMessage() },
            title = { Text(stringResource(R.string.dialog_title_prompt)) },
            text = { Text(message ?: "") },
            confirmButton = {
                TextButton(onClick = { actualViewModel.clearPopupMessage() }) { Text(stringResource(R.string.ok)) }
            }
        )
    }

    // Check for overlay permission on resume
    LaunchedEffect(Unit) {
        canDrawOverlays.value = Settings.canDrawOverlays(context)

        // If floating mode is on but no permission, turn it off
        if (isFloatingMode && !canDrawOverlays.value) {
            actualViewModel.toggleFloatingMode()
            Toast.makeText(
                context,
                context.getString(R.string.floating_window_permission_denied),
                Toast.LENGTH_SHORT
            )
                .show()
        }
    }

    // 记忆文件夹选择对话框
    MemoryFolderSelectionDialog(
        visible = showMemoryFolderDialog,
        onDismiss = { showMemoryFolderDialog = false },
        onConfirm = { selectedFolders ->
            actualViewModel.captureMemoryFolders(selectedFolders)
        }
    )
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Activity.manifestSoftInputMode(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getActivityInfo(
            componentName,
            PackageManager.ComponentInfoFlags.of(0)
        ).softInputMode
    } else {
        @Suppress("DEPRECATION")
        packageManager.getActivityInfo(componentName, 0).softInputMode
    }
