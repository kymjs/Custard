package com.kymjs.ai.custard.ui.features.settings.screens

import android.annotation.SuppressLint
import androidx.compose.animation.*
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.kymjs.ai.custard.ui.components.CustomScaffold
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.api.chat.llmprovider.AIServiceFactory
import com.kymjs.ai.custard.api.chat.llmprovider.MediaLinkBuilder
import com.kymjs.ai.custard.data.model.FunctionType
import com.kymjs.ai.custard.data.model.ModelConfigData
import com.kymjs.ai.custard.data.model.getModelByIndex
import com.kymjs.ai.custard.data.model.ToolParameterSchema
import com.kymjs.ai.custard.data.model.ToolPrompt
import com.kymjs.ai.custard.data.preferences.ApiPreferences
import com.kymjs.ai.custard.data.preferences.FunctionalConfigManager
import com.kymjs.ai.custard.data.preferences.ModelConfigManager
import com.kymjs.ai.custard.ui.features.settings.sections.AdvancedSettingsSection
import com.kymjs.ai.custard.ui.features.settings.sections.ModelApiSettingsSection
import com.kymjs.ai.custard.ui.features.settings.sections.ModelParametersSection
import com.kymjs.ai.custard.ui.features.settings.sections.SettingsInfoBanner
import com.kymjs.ai.custard.ui.features.settings.sections.SettingsSectionHeader
import com.kymjs.ai.custard.ui.features.settings.sections.SettingsSwitchRow
import com.kymjs.ai.custard.ui.features.settings.sections.SettingsTextField
import com.kymjs.ai.custard.util.AssetCopyUtils
import com.kymjs.ai.custard.util.ImagePoolManager
import com.kymjs.ai.custard.util.MediaPoolManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelConfigScreen(
    onBackPressed: () -> Unit = {},
    navigateToMnnModelDownload: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val configManager = remember { ModelConfigManager(context) }
    val functionalConfigManager = remember { FunctionalConfigManager(context) }
    val apiPreferences = remember { ApiPreferences.getInstance(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 配置状态
    val configList = configManager.configListFlow.collectAsState(initial = listOf("default")).value
    // 进入页面时默认选中“对话功能”当前绑定的模型配置
    var selectedConfigId by remember { mutableStateOf(ModelConfigManager.DEFAULT_CONFIG_ID) }
    val selectedConfig = remember { mutableStateOf<ModelConfigData?>(null) }

    // 配置名称映射
    val configNameMap = remember { mutableStateMapOf<String, String>() }

    // UI状态
    var showAddConfigDialog by remember { mutableStateOf(false) }
    var showRenameConfigDialog by remember { mutableStateOf(false) }
    var showSaveSuccessMessage by remember { mutableStateOf(false) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var newConfigName by remember { mutableStateOf("") }
    var renameConfigName by remember { mutableStateOf("") }
    var confirmMessage by remember { mutableStateOf("") }

    // 连接测试状态
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<List<ConnectionTestItem>?>(null) }

    // 保存API设置的函数引用
    var saveApiSettings: (() -> Unit)? by remember { mutableStateOf(null) }

    // 初始化配置，并默认定位到“对话功能模型”所使用的配置
    LaunchedEffect(Unit) {
        configManager.initializeIfNeeded()
        functionalConfigManager.initializeIfNeeded()

        val chatConfigId = functionalConfigManager.getConfigIdForFunction(FunctionType.CHAT)
        val availableConfigIds = configManager.configListFlow.first()
        selectedConfigId =
            availableConfigIds.firstOrNull { it == chatConfigId }
                ?: availableConfigIds.firstOrNull()
                ?: ModelConfigManager.DEFAULT_CONFIG_ID
    }

    // 加载所有配置名称
    LaunchedEffect(configList) {
        configList.forEach { id ->
            val config = configManager.getModelConfigFlow(id).first()
            configNameMap[id] = config.name
        }
    }

    // 加载选中的配置
    LaunchedEffect(selectedConfigId) {
        testResults = null
        configManager.getModelConfigFlow(selectedConfigId).collect { config ->
            selectedConfig.value = config
        }
    }

    // 显示通知消息
    fun showNotification(message: String) {
        confirmMessage = message
        showSaveSuccessMessage = true
        scope.launch {
            kotlinx.coroutines.delay(3000)
            showSaveSuccessMessage = false
        }
    }

    // 主界面内容
    CustomScaffold() { paddingValues ->
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border =
                        BorderStroke(
                            0.7.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.select_model_config),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            OutlinedButton(
                                onClick = { showAddConfigDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp),
                                colors =
                                    ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    stringResource(R.string.new_action),
                                    fontSize = 12.sp,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        val selectedConfigName =
                            configNameMap[selectedConfigId]
                                ?: stringResource(R.string.default_profile)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isDropdownExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            tonalElevation = 0.5.dp,
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = selectedConfigName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                @OptIn(ExperimentalAnimationApi::class)
                                AnimatedContent(
                                    targetState = isDropdownExpanded,
                                    transitionSpec = {
                                        fadeIn() + scaleIn() with fadeOut() + scaleOut()
                                    }
                                ) { expanded ->
                                    Icon(
                                        if (expanded) Icons.Default.KeyboardArrowUp
                                        else Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.select_config),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        FlowRow(
                            modifier = Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (selectedConfigId != "default") {
                                TextButton(
                                    onClick = {
                                        renameConfigName = selectedConfig.value?.name ?: ""
                                        showRenameConfigDialog = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.rename_action), fontSize = 14.sp)
                                }

                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            configManager.deleteConfig(selectedConfigId)
                                            selectedConfigId = configList.firstOrNull() ?: "default"
                                            showNotification(context.getString(R.string.config_deleted))
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.delete_action), fontSize = 14.sp)
                                }
                            }

                            TextButton(
                                onClick = {
                                    scope.launch {
                                        saveApiSettings?.invoke()
                                        kotlinx.coroutines.delay(300)

                                        isTestingConnection = true
                                        val results = mutableListOf<ConnectionTestItem>()
                                        try {
                                            selectedConfig.value?.let { config ->
                                                val modelNameToTest =
                                                    getModelByIndex(config.modelName, 0)
                                                val configForTest =
                                                    config.copy(modelName = modelNameToTest)
                                                val customHeadersJson =
                                                    apiPreferences.getCustomHeaders()
                                                val service =
                                                    AIServiceFactory.createService(
                                                        config = configForTest,
                                                        customHeadersJson = customHeadersJson,
                                                        modelConfigManager = configManager,
                                                        context = context
                                                    )
                                                val parameters =
                                                    configManager.getModelParametersForConfig(configForTest.id)

                                                suspend fun runTest(
                                                    labelResId: Int,
                                                    block: suspend () -> Unit
                                                ) {
                                                    val result = runCatching { block() }
                                                    results.add(ConnectionTestItem(labelResId, result))
                                                }

                                                runTest(R.string.test_item_chat) {
                                                    service.sendMessage(
                                                        context,
                                                        "Hi",
                                                        emptyList(),
                                                        parameters,
                                                        stream = false
                                                    ).collect { }
                                                }

                                                if (configForTest.enableToolCall) {
                                                    runTest(R.string.test_item_toolcall) {
                                                        val availableTools =
                                                            listOf(
                                                                ToolPrompt(
                                                                    name = "echo",
                                                                    description = "Echoes the provided text.",
                                                                    parametersStructured =
                                                                        listOf(
                                                                            ToolParameterSchema(
                                                                                name = "text",
                                                                                type = "string",
                                                                                description = "Text to echo.",
                                                                                required = true
                                                                            )
                                                                        )
                                                                )
                                                            )

                                                        suspend fun runToolCallTest(toolName: String) {
                                                            val testHistory = mutableListOf(
                                                                "system" to "You are a helpful assistant."
                                                            )
                                                            testHistory.add(
                                                                "assistant" to
                                                                    "<tool name=\"$toolName\"><param name=\"text\">ping</param></tool>"
                                                            )
                                                            testHistory.add(
                                                                "user" to
                                                                    "<tool_result name=\"$toolName\" status=\"success\"><content>pong</content></tool_result>"
                                                            )
                                                            service.sendMessage(
                                                                context,
                                                                "Hi",
                                                                testHistory,
                                                                parameters,
                                                                stream = false,
                                                                availableTools = availableTools
                                                            ).collect { }
                                                        }

                                                        if (configForTest.strictToolCall) {
                                                            runToolCallTest("echo")
                                                        } else {
                                                            val strictProbeResult = runCatching {
                                                                runToolCallTest("strict_probe_unlisted_tool")
                                                            }
                                                            if (strictProbeResult.isFailure) {
                                                                showNotification(
                                                                    context.getString(
                                                                        R.string.strict_tool_call_required
                                                                    )
                                                                )
                                                                runToolCallTest("echo")
                                                            }
                                                        }
                                                    }
                                                }

                                                if (configForTest.enableDirectImageProcessing) {
                                                    runTest(R.string.test_item_image) {
                                                        val imageFile =
                                                            AssetCopyUtils.copyAssetToCache(context, "test/1.jpg")
                                                        val imageId =
                                                            ImagePoolManager.addImage(imageFile.absolutePath)
                                                        if (imageId == "error") {
                                                            throw IllegalStateException("Failed to create test image")
                                                        }
                                                        try {
                                                            val prompt =
                                                                buildString {
                                                                    append(
                                                                        MediaLinkBuilder.image(context, imageId)
                                                                    )
                                                                    append("\n")
                                                                    append(
                                                                        context.getString(
                                                                            R.string.conversation_analyze_image_prompt
                                                                        )
                                                                    )
                                                                }
                                                            service.sendMessage(
                                                                context,
                                                                prompt,
                                                                emptyList(),
                                                                parameters,
                                                                stream = false
                                                            ).collect { }
                                                        } finally {
                                                            ImagePoolManager.removeImage(imageId)
                                                            runCatching { imageFile.delete() }
                                                        }
                                                    }
                                                }

                                                if (configForTest.enableDirectAudioProcessing) {
                                                    runTest(R.string.test_item_audio) {
                                                        val audioFile =
                                                            AssetCopyUtils.copyAssetToCache(context, "test/1.mp3")
                                                        val audioId =
                                                            MediaPoolManager.addMedia(audioFile.absolutePath, "audio/mpeg")
                                                        if (audioId == "error") {
                                                            throw IllegalStateException("Failed to create test audio")
                                                        }
                                                        try {
                                                            val prompt =
                                                                buildString {
                                                                    append(
                                                                        MediaLinkBuilder.audio(context, audioId)
                                                                    )
                                                                    append("\n")
                                                                    append(
                                                                        context.getString(
                                                                            R.string.conversation_analyze_audio_prompt
                                                                        )
                                                                    )
                                                                }
                                                            service.sendMessage(
                                                                context,
                                                                prompt,
                                                                emptyList(),
                                                                parameters,
                                                                stream = false
                                                            ).collect { }
                                                        } finally {
                                                            MediaPoolManager.removeMedia(audioId)
                                                            runCatching { audioFile.delete() }
                                                        }
                                                    }
                                                }

                                                if (configForTest.enableDirectVideoProcessing) {
                                                    runTest(R.string.test_item_video) {
                                                        val videoFile =
                                                            AssetCopyUtils.copyAssetToCache(context, "test/1.mp4")
                                                        val videoId =
                                                            MediaPoolManager.addMedia(videoFile.absolutePath, "video/mp4")
                                                        if (videoId == "error") {
                                                            throw IllegalStateException("Failed to create test video")
                                                        }
                                                        try {
                                                            val prompt =
                                                                buildString {
                                                                    append(
                                                                        MediaLinkBuilder.video(context, videoId)
                                                                    )
                                                                    append("\n")
                                                                    append(
                                                                        context.getString(
                                                                            R.string.conversation_analyze_video_prompt
                                                                        )
                                                                    )
                                                                }
                                                            service.sendMessage(
                                                                context,
                                                                prompt,
                                                                emptyList(),
                                                                parameters,
                                                                stream = false
                                                            ).collect { }
                                                        } finally {
                                                            MediaPoolManager.removeMedia(videoId)
                                                            runCatching { videoFile.delete() }
                                                        }
                                                    }
                                                }
                                            } ?: run {
                                                results.add(
                                                    ConnectionTestItem(
                                                        R.string.test_item_chat,
                                                        Result.failure<Unit>(
                                                            Exception(
                                                                context.getString(
                                                                    R.string.no_config_selected
                                                                )
                                                            )
                                                        )
                                                    )
                                                )
                                            }
                                        } catch (e: Exception) {
                                            results.add(
                                                ConnectionTestItem(
                                                    R.string.test_item_chat,
                                                    Result.failure<Unit>(e)
                                                )
                                            )
                                        } finally {
                                            isTestingConnection = false
                                        }
                                        testResults = results
                                    }
                                },
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Dns,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.test_connection_desc),
                                    fontSize = 14.sp
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = testResults != null,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            testResults?.let { results ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        results.forEachIndexed { index, item ->
                                            val isSuccess = item.result.isSuccess
                                            val statusText =
                                                if (isSuccess) {
                                                    context.getString(R.string.test_connection_success)
                                                } else {
                                                    context.getString(
                                                        R.string.test_connection_failed,
                                                        item.result.exceptionOrNull()?.message ?: ""
                                                    )
                                                }
                                            val contentColor =
                                                if (isSuccess) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.error
                                            val icon =
                                                if (isSuccess) Icons.Default.CheckCircle
                                                else Icons.Default.Warning

                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = null,
                                                        tint = contentColor,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = stringResource(item.labelResId),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (isSuccess) {
                                                        Text(
                                                            text = statusText,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = contentColor
                                                        )
                                                    }
                                                }
                                                if (!isSuccess) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = statusText,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = contentColor
                                                    )
                                                }
                                            }

                                            if (index != results.lastIndex) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = isDropdownExpanded,
                        onDismissRequest = { isDropdownExpanded = false },
                        modifier = Modifier.width(280.dp),
                        properties = PopupProperties(focusable = true)
                    ) {
                        configList.forEach { configId ->
                            val configName =
                                configNameMap[configId] ?: stringResource(R.string.unnamed_profile)
                            val isSelected = configId == selectedConfigId

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = configName,
                                        fontWeight =
                                            if (isSelected) FontWeight.SemiBold
                                            else FontWeight.Normal,
                                        color =
                                            if (isSelected)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon =
                                    if (isSelected) {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = stringResource(R.string.selected_desc),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                onClick = {
                                    selectedConfigId = configId
                                    isDropdownExpanded = false
                                },
                                colors =
                                    MenuDefaults.itemColors(
                                        textColor =
                                            if (isSelected)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                    ),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )

                            if (configId != configList.last()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                }
            }

            selectedConfig.value?.let { config ->
                item {
                    ModelApiSettingsSection(
                        config = config,
                        configManager = configManager,
                        showNotification = { message -> showNotification(message) },
                        onSaveRequested = { saveFunction -> saveApiSettings = saveFunction },
                        navigateToMnnModelDownload = navigateToMnnModelDownload
                    )
                }

                item {
                    ContextSummarySettingsSection(
                        config = config,
                        configManager = configManager,
                        scope = scope,
                        showNotification = { message -> showNotification(message) }
                    )
                }

                item {
                    ModelParametersSection(
                        config = config,
                        configManager = configManager,
                        showNotification = { message -> showNotification(message) }
                    )
                }

                item {
                    AdvancedSettingsSection(
                        config = config,
                        configManager = configManager,
                        showNotification = { message -> showNotification(message) }
                    )
                }
            }

            if (showSaveSuccessMessage) {
                item {
                    AnimatedVisibility(
                        visible = showSaveSuccessMessage,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = confirmMessage,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }

        // 新建配置对话框
        if (showAddConfigDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddConfigDialog = false
                    newConfigName = ""
                },
                title = {
                    Text(
                        stringResource(R.string.new_model_config),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.new_model_config_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newConfigName,
                            onValueChange = { newConfigName = it },
                            label = {
                                Text(
                                    stringResource(R.string.model_config_name),
                                    fontSize = 12.sp
                                )
                            },
                            placeholder = {
                                Text(
                                    stringResource(R.string.model_config_name_placeholder),
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newConfigName.isNotBlank()) {
                                scope.launch {
                                    val configId = configManager.createConfig(newConfigName)
                                    selectedConfigId = configId
                                    showAddConfigDialog = false
                                    newConfigName = ""
                                    showNotification(context.getString(R.string.new_config_created))
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) { Text(stringResource(R.string.create_action), fontSize = 13.sp) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAddConfigDialog = false
                            newConfigName = ""
                        }
                    ) { Text(stringResource(R.string.cancel_action), fontSize = 13.sp) }
                },
                shape = RoundedCornerShape(12.dp)
            )
        }

        // 重命名配置对话框
        if (showRenameConfigDialog) {
            AlertDialog(
                onDismissRequest = {
                    showRenameConfigDialog = false
                    renameConfigName = ""
                },
                title = {
                    Text(
                        stringResource(R.string.rename_model_config),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.rename_model_config_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = renameConfigName,
                            onValueChange = { renameConfigName = it },
                            label = {
                                Text(
                                    stringResource(R.string.model_config_name),
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (renameConfigName.isNotBlank()) {
                                scope.launch {
                                    configManager.updateConfigBase(
                                        selectedConfigId,
                                        renameConfigName
                                    )
                                    configNameMap[selectedConfigId] = renameConfigName
                                    showRenameConfigDialog = false
                                    renameConfigName = ""
                                    showNotification(context.getString(R.string.config_renamed))
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) { Text(stringResource(R.string.confirm_rename), fontSize = 13.sp) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showRenameConfigDialog = false
                            renameConfigName = ""
                        }
                    ) { Text(stringResource(R.string.cancel_action), fontSize = 13.sp) }
                },
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
private fun ContextSummarySettingsSection(
    config: ModelConfigData,
    configManager: ModelConfigManager,
    scope: CoroutineScope,
    showNotification: (String) -> Unit
) {
    val latestConfig by rememberUpdatedState(config)
    var contextLengthInput by remember(config.id) { mutableStateOf(formatFloatValue(config.contextLength)) }
    var maxContextLengthInput by remember(config.id) { mutableStateOf(formatFloatValue(config.maxContextLength)) }
    var contextError by remember { mutableStateOf<String?>(null) }

    var enableSummary by remember(config.id) { mutableStateOf(config.enableSummary) }
    var summaryTokenThresholdInput by remember(config.id) { mutableStateOf(formatFloatValue(config.summaryTokenThreshold)) }
    var enableSummaryByMessageCount by remember(config.id) { mutableStateOf(config.enableSummaryByMessageCount) }
    var summaryMessageCountThresholdInput by remember(config.id) { mutableStateOf(config.summaryMessageCountThreshold.toString()) }
    var summaryError by remember { mutableStateOf<String?>(null) }

    var contextExpanded by rememberSaveable { mutableStateOf(false) }
    var summaryExpanded by rememberSaveable { mutableStateOf(false) }

    val errorValidContextLength = stringResource(id = R.string.model_config_error_valid_context_length)
    val errorValidMaxContextLength = stringResource(id = R.string.model_config_error_valid_max_context_length)
    val errorSaveFailed = stringResource(id = R.string.model_config_error_save_failed)
    val errorSummaryThresholdRange = stringResource(id = R.string.model_config_error_summary_threshold_range)
    val errorValidMessageCount = stringResource(id = R.string.model_config_error_valid_message_count)

    LaunchedEffect(config.id, config.contextLength) {
        contextLengthInput = formatFloatValue(config.contextLength)
    }
    LaunchedEffect(config.id, config.maxContextLength) {
        maxContextLengthInput = formatFloatValue(config.maxContextLength)
    }
    LaunchedEffect(config.id, config.enableSummary) {
        enableSummary = config.enableSummary
    }
    LaunchedEffect(config.id, config.summaryTokenThreshold) {
        summaryTokenThresholdInput = formatFloatValue(config.summaryTokenThreshold)
    }
    LaunchedEffect(config.id, config.enableSummaryByMessageCount) {
        enableSummaryByMessageCount = config.enableSummaryByMessageCount
    }
    LaunchedEffect(config.id, config.summaryMessageCountThreshold) {
        summaryMessageCountThresholdInput = config.summaryMessageCountThreshold.toString()
    }

    LaunchedEffect(config.id) {
        snapshotFlow { contextLengthInput to maxContextLengthInput }
            .drop(1)
            .debounce(700)
            .distinctUntilChanged()
            .collectLatest { (contextText, maxText) ->
                val contextValue = contextText.toFloatOrNull()
                val maxValue = maxText.toFloatOrNull()

                when {
                    contextValue == null || contextValue <= 0f -> {
                        contextError = errorValidContextLength
                    }

                    maxValue == null || maxValue <= 0f -> {
                        contextError = errorValidMaxContextLength
                    }

                    else -> {
                        val current = latestConfig
                        val isNoOp =
                            current.contextLength == contextValue &&
                                    current.maxContextLength == maxValue
                        if (isNoOp) {
                            contextError = null
                            return@collectLatest
                        }

                        try {
                            configManager.updateContextSettings(
                                configId = current.id,
                                contextLength = contextValue,
                                maxContextLength = maxValue,
                                enableMaxContextMode = current.enableMaxContextMode
                            )
                            contextError = null
                        } catch (e: Exception) {
                            contextError = e.message ?: errorSaveFailed
                        }
                    }
                }
            }
    }

    LaunchedEffect(config.id) {
        snapshotFlow {
            listOf(
                enableSummary,
                summaryTokenThresholdInput,
                enableSummaryByMessageCount,
                summaryMessageCountThresholdInput
            )
        }
            .drop(1)
            .debounce(700)
            .distinctUntilChanged()
            .collectLatest {
                val current = latestConfig
                if (!enableSummary) {
                    if (current.enableSummary) {
                        try {
                            configManager.updateSummarySettings(
                                configId = current.id,
                                enableSummary = false,
                                summaryTokenThreshold = current.summaryTokenThreshold,
                                enableSummaryByMessageCount = current.enableSummaryByMessageCount,
                                summaryMessageCountThreshold = current.summaryMessageCountThreshold
                            )
                            summaryError = null
                        } catch (e: Exception) {
                            summaryError = e.message ?: errorSaveFailed
                        }
                    }
                    return@collectLatest
                }

                val threshold = summaryTokenThresholdInput.toFloatOrNull()
                val messageCount = summaryMessageCountThresholdInput.toIntOrNull()

                when {
                    threshold == null || threshold <= 0f || threshold >= 1f -> {
                        summaryError = errorSummaryThresholdRange
                    }

                    enableSummaryByMessageCount && (messageCount == null || messageCount <= 0) -> {
                        summaryError = errorValidMessageCount
                    }

                    else -> {
                        val nextMessageCount =
                            if (enableSummaryByMessageCount) messageCount
                                ?: current.summaryMessageCountThreshold
                            else current.summaryMessageCountThreshold

                        val isNoOp =
                            current.enableSummary == enableSummary &&
                                    current.summaryTokenThreshold == threshold &&
                                    current.enableSummaryByMessageCount == enableSummaryByMessageCount &&
                                    current.summaryMessageCountThreshold == nextMessageCount
                        if (isNoOp) {
                            summaryError = null
                            return@collectLatest
                        }

                        try {
                            configManager.updateSummarySettings(
                                configId = current.id,
                                enableSummary = enableSummary,
                                summaryTokenThreshold = threshold,
                                enableSummaryByMessageCount = enableSummaryByMessageCount,
                                summaryMessageCountThreshold = nextMessageCount
                            )
                            summaryError = null
                        } catch (e: Exception) {
                            summaryError = e.message ?: errorSaveFailed
                        }
                    }
                }
            }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { contextExpanded = !contextExpanded }
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.settings_context_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (contextExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (contextExpanded) stringResource(id = R.string.model_config_collapse) else stringResource(id = R.string.model_config_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = contextExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsInfoBanner(text = stringResource(id = R.string.settings_context_card_content))

                        SettingsTextField(
                            title = stringResource(id = R.string.settings_context_length),
                            subtitle = stringResource(id = R.string.settings_context_length_subtitle),
                            value = contextLengthInput,
                            onValueChange = {
                                contextLengthInput = it
                                contextError = null
                            },
                            unitText = "K",
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Next
                            )
                        )

                        SettingsTextField(
                            title = stringResource(id = R.string.settings_max_context_length),
                            subtitle = stringResource(id = R.string.settings_max_context_length_subtitle),
                            value = maxContextLengthInput,
                            onValueChange = {
                                maxContextLengthInput = it
                                contextError = null
                            },
                            unitText = "K",
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            )
                        )

                        contextError?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { summaryExpanded = !summaryExpanded }
                ) {
                    Icon(
                        imageVector = Icons.Default.Summarize,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = R.string.settings_summary_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (summaryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (summaryExpanded) stringResource(id = R.string.model_config_collapse) else stringResource(id = R.string.model_config_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = summaryExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsSwitchRow(
                            title = stringResource(id = R.string.settings_enable_summary),
                            subtitle = stringResource(id = R.string.settings_enable_summary_desc),
                            checked = enableSummary,
                            onCheckedChange = { enableSummary = it }
                        )

                        SettingsTextField(
                            title = stringResource(id = R.string.settings_summary_threshold),
                            subtitle = stringResource(id = R.string.settings_summary_threshold_subtitle),
                            value = summaryTokenThresholdInput,
                            onValueChange = {
                                summaryTokenThresholdInput = it
                                summaryError = null
                            },
                            enabled = enableSummary,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Next
                            )
                        )

                        SettingsSwitchRow(
                            title = stringResource(id = R.string.settings_enable_summary_by_message_count),
                            subtitle = stringResource(id = R.string.settings_enable_summary_by_message_count_desc),
                            checked = enableSummaryByMessageCount,
                            onCheckedChange = { enableSummaryByMessageCount = it },
                            enabled = enableSummary
                        )

                        SettingsTextField(
                            title = stringResource(id = R.string.settings_summary_message_count_threshold),
                            subtitle = stringResource(id = R.string.settings_summary_message_count_threshold_subtitle),
                            value = summaryMessageCountThresholdInput,
                            onValueChange = {
                                summaryMessageCountThresholdInput = it
                                summaryError = null
                            },
                            unitText = stringResource(id = R.string.model_config_unit_items),
                            enabled = enableSummary && enableSummaryByMessageCount,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            )
                        )

                        summaryError?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class ConnectionTestItem(
    val labelResId: Int,
    val result: Result<Unit>
)

private fun formatFloatValue(value: Float): String {
    return if (value % 1f == 0f) value.toInt().toString() else String.format("%.2f", value)
}
