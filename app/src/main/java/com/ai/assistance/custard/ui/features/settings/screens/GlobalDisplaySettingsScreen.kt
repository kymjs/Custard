package com.ai.assistance.custard.ui.features.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.custard.R
import com.ai.assistance.custard.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.custard.data.preferences.AndroidPermissionPreferences
import com.ai.assistance.custard.data.preferences.ApiPreferences
import com.ai.assistance.custard.data.preferences.DisplayPreferencesManager
import com.ai.assistance.custard.data.preferences.RootCommandExecutionMode
import com.ai.assistance.custard.data.preferences.ToolCollapseMode
import com.ai.assistance.custard.data.preferences.UserPreferencesManager
import com.ai.assistance.custard.data.preferences.androidPermissionPreferences
import com.ai.assistance.custard.services.floating.StatusIndicatorStyle
import com.ai.assistance.custard.ui.components.CustomScaffold
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GlobalDisplaySettingsScreen(
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val displayPreferencesManager = remember { DisplayPreferencesManager.getInstance(context) }
    val apiPreferences = remember { ApiPreferences.getInstance(context) }
    val userPreferences = remember { UserPreferencesManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 收集显示设置状态
    val showModelProvider by displayPreferencesManager.showModelProvider.collectAsState(initial = false)
    val showModelName by displayPreferencesManager.showModelName.collectAsState(initial = false)
    val showRoleName by displayPreferencesManager.showRoleName.collectAsState(initial = false)
    val showUserName by displayPreferencesManager.showUserName.collectAsState(initial = false)
    val toolCollapseMode by displayPreferencesManager.toolCollapseMode.collectAsState(initial = ToolCollapseMode.ALL)
    val showFpsCounter by displayPreferencesManager.showFpsCounter.collectAsState(initial = false)
    val enableReplyNotification by displayPreferencesManager.enableReplyNotification.collectAsState(initial = true)
    val enableExperimentalVirtualDisplay by displayPreferencesManager.enableExperimentalVirtualDisplay.collectAsState(initial = true)
    val globalUserName by displayPreferencesManager.globalUserName.collectAsState(initial = null)
    val globalUserAvatarUri by displayPreferencesManager.globalUserAvatarUri.collectAsState(initial = null)
    val screenshotFormat by displayPreferencesManager.screenshotFormat.collectAsState(initial = "PNG")
    val screenshotQuality by displayPreferencesManager.screenshotQuality.collectAsState(initial = 90)
    val screenshotScalePercent by displayPreferencesManager.screenshotScalePercent.collectAsState(initial = 100)
    val virtualDisplayBitrateKbps by displayPreferencesManager.virtualDisplayBitrateKbps.collectAsState(initial = 3000)
    val keepScreenOn by apiPreferences.keepScreenOnFlow.collectAsState(initial = true)

    val hasBackgroundImage by userPreferences.useBackgroundImage.collectAsState(initial = false)
    val uiAccessibilityMode by userPreferences.uiAccessibilityMode.collectAsState(initial = false)
    val preferredPermissionLevel by androidPermissionPreferences.preferredPermissionLevelFlow.collectAsState(initial = null)
    val rootExecutionMode by androidPermissionPreferences.rootExecutionModeFlow.collectAsState(initial = RootCommandExecutionMode.AUTO)
    val customSuCommand by androidPermissionPreferences.customSuCommandFlow.collectAsState(initial = AndroidPermissionPreferences.DEFAULT_SU_COMMAND)

    var showSaveSuccessMessage by remember { mutableStateOf(false) }
    var userNameInput by remember { mutableStateOf(globalUserName ?: "") }
    var customSuCommandInput by remember { mutableStateOf(customSuCommand) }

    // 自动化状态指示样式（使用与 FloatingChatService 相同的 SharedPreferences）
    val statusIndicatorPrefs = remember {
        context.getSharedPreferences("floating_chat_prefs", android.content.Context.MODE_PRIVATE)
    }
    var statusIndicatorStyle by remember {
        mutableStateOf(
            run {
                val defaultName = StatusIndicatorStyle.FULLSCREEN_RAINBOW.name
                val stored = statusIndicatorPrefs.getString("status_indicator_style", defaultName)
                try {
                    StatusIndicatorStyle.valueOf(stored ?: defaultName)
                } catch (_: IllegalArgumentException) {
                    StatusIndicatorStyle.FULLSCREEN_RAINBOW
                }
            }
        )
    }

    // 同步全局用户名状态
    LaunchedEffect(globalUserName) {
        userNameInput = globalUserName ?: ""
    }

    LaunchedEffect(customSuCommand) {
        customSuCommandInput = customSuCommand
    }

    val componentBackgroundColor = if (hasBackgroundImage) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    }

    CustomScaffold() { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(scrollState)
        ) {
            // ======= 消息显示设置 =======
            SectionTitle(
                text = stringResource(R.string.message_display_settings),
                icon = Icons.Default.Message
            )

            DisplayToggleItem(
                title = stringResource(R.string.show_model_provider),
                subtitle = stringResource(R.string.show_model_provider_description),
                checked = showModelProvider,
                onCheckedChange = {
                    scope.launch {
                        displayPreferencesManager.saveDisplaySettings(showModelProvider = it)
                        showSaveSuccessMessage = true
                    }
                },
                backgroundColor = componentBackgroundColor
            )

            DisplayToggleItem(
                title = stringResource(R.string.show_model_name),
                subtitle = stringResource(R.string.show_model_name_description),
                checked = showModelName,
                onCheckedChange = {
                    scope.launch {
                        displayPreferencesManager.saveDisplaySettings(showModelName = it)
                        showSaveSuccessMessage = true
                    }
                },
                backgroundColor = componentBackgroundColor
            )

            DisplayToggleItem(
                title = stringResource(R.string.show_role_name),
                subtitle = stringResource(R.string.show_role_name_description),
                checked = showRoleName,
                onCheckedChange = {
                    scope.launch {
                        displayPreferencesManager.saveDisplaySettings(showRoleName = it)
                        showSaveSuccessMessage = true
                    }
                },
                backgroundColor = componentBackgroundColor
            )

            DisplayToggleItem(
                title = stringResource(R.string.show_user_name),
                subtitle = stringResource(R.string.show_user_name_description),
                checked = showUserName,
                onCheckedChange = {
                    scope.launch {
                        displayPreferencesManager.saveDisplaySettings(showUserName = it)
                        showSaveSuccessMessage = true
                    }
                },
                backgroundColor = componentBackgroundColor
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(componentBackgroundColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.tool_collapse_mode_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(id = R.string.tool_collapse_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = toolCollapseMode == ToolCollapseMode.READ_ONLY,
                        onClick = {
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(toolCollapseMode = ToolCollapseMode.READ_ONLY)
                                showSaveSuccessMessage = true
                            }
                        },
                        label = { Text(stringResource(R.string.tool_collapse_mode_read_only)) }
                    )
                    FilterChip(
                        selected = toolCollapseMode == ToolCollapseMode.ALL,
                        onClick = {
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(toolCollapseMode = ToolCollapseMode.ALL)
                                showSaveSuccessMessage = true
                            }
                        },
                        label = { Text(stringResource(R.string.tool_collapse_mode_all)) }
                    )
                }
            }

            // 用户名字输入框
            if (showUserName) {
                OutlinedTextField(
                    value = userNameInput,
                    onValueChange = { userNameInput = it },
                    label = { Text(stringResource(R.string.global_user_name)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    singleLine = true,
                    trailingIcon = {
                        if (userNameInput != globalUserName) {
                            IconButton(onClick = {
                                scope.launch {
                                    displayPreferencesManager.saveDisplaySettings(globalUserName = userNameInput)
                                    showSaveSuccessMessage = true
                                }
                            }) {
                                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ======= 系统显示设置 =======
            SectionTitle(
                text = stringResource(R.string.system_display_settings),
                icon = Icons.Default.Settings
            )

            DisplayToggleItem(
                title = stringResource(R.string.show_fps_counter),
                subtitle = stringResource(R.string.show_fps_counter_description),
                checked = showFpsCounter,
                onCheckedChange = {
                    scope.launch {
                        displayPreferencesManager.saveDisplaySettings(showFpsCounter = it)
                        showSaveSuccessMessage = true
                    }
                },
                backgroundColor = componentBackgroundColor
            )

            DisplayToggleItem(
                title = stringResource(R.string.enable_reply_notification),
                subtitle = stringResource(R.string.enable_reply_notification_description),
                checked = enableReplyNotification,
                onCheckedChange = {
                    scope.launch {
                        displayPreferencesManager.saveDisplaySettings(enableReplyNotification = it)
                        showSaveSuccessMessage = true
                    }
                },
                backgroundColor = componentBackgroundColor
            )

            DisplayToggleItem(
                title = stringResource(R.string.keep_screen_on),
                subtitle = stringResource(R.string.keep_screen_on_description),
                checked = keepScreenOn,
                onCheckedChange = {
                    scope.launch {
                        apiPreferences.saveKeepScreenOn(it)
                        showSaveSuccessMessage = true
                    }
                },
                backgroundColor = componentBackgroundColor
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ======= 自动化状态指示样式 =======
            SectionTitle(
                text = stringResource(id = R.string.global_display_automation_behavior),
                icon = Icons.Default.AutoAwesome
            )

            DisplayToggleItem(
                title = stringResource(R.string.ui_accessibility_mode),
                subtitle = stringResource(R.string.ui_accessibility_mode_description),
                checked = uiAccessibilityMode,
                onCheckedChange = {
                    scope.launch {
                        userPreferences.saveUiAccessibilityMode(it)
                        showSaveSuccessMessage = true
                    }
                },
                backgroundColor = componentBackgroundColor
            )

            DisplayToggleItem(
                title = stringResource(R.string.experimental_virtual_display),
                subtitle = stringResource(R.string.experimental_virtual_display_description),
                checked = enableExperimentalVirtualDisplay,
                onCheckedChange = {
                    scope.launch {
                        displayPreferencesManager.saveDisplaySettings(
                            enableExperimentalVirtualDisplay = it
                        )
                        showSaveSuccessMessage = true
                    }
                },
                backgroundColor = componentBackgroundColor
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(componentBackgroundColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.global_display_virtual_screen_bitrate),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = virtualDisplayBitrateKbps == 1500,
                        onClick = {
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(virtualDisplayBitrateKbps = 1500)
                                showSaveSuccessMessage = true
                            }
                        },
                        label = { Text("1.5 Mbps") }
                    )
                    FilterChip(
                        selected = virtualDisplayBitrateKbps == 3000,
                        onClick = {
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(virtualDisplayBitrateKbps = 3000)
                                showSaveSuccessMessage = true
                            }
                        },
                        label = { Text("3 Mbps") }
                    )
                    FilterChip(
                        selected = virtualDisplayBitrateKbps == 5000,
                        onClick = {
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(virtualDisplayBitrateKbps = 5000)
                                showSaveSuccessMessage = true
                            }
                        },
                        label = { Text("5 Mbps") }
                    )
                    FilterChip(
                        selected = virtualDisplayBitrateKbps == 10000,
                        onClick = {
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(virtualDisplayBitrateKbps = 10000)
                                showSaveSuccessMessage = true
                            }
                        },
                        label = { Text("10 Mbps") }
                    )
                    FilterChip(
                        selected = virtualDisplayBitrateKbps == 20000,
                        onClick = {
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(virtualDisplayBitrateKbps = 20000)
                                showSaveSuccessMessage = true
                            }
                        },
                        label = { Text("20 Mbps") }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(componentBackgroundColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.global_display_status_indicator_style),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = statusIndicatorStyle == StatusIndicatorStyle.FULLSCREEN_RAINBOW,
                        onClick = {
                            statusIndicatorStyle = StatusIndicatorStyle.FULLSCREEN_RAINBOW
                            statusIndicatorPrefs.edit()
                                .putString(
                                    "status_indicator_style",
                                    StatusIndicatorStyle.FULLSCREEN_RAINBOW.name
                                )
                                .apply()
                            showSaveSuccessMessage = true
                        },
                        label = { Text(stringResource(R.string.display_rainbow_border)) }
                    )
                    FilterChip(
                        selected = statusIndicatorStyle == StatusIndicatorStyle.TOP_BAR,
                        onClick = {
                            statusIndicatorStyle = StatusIndicatorStyle.TOP_BAR
                            statusIndicatorPrefs.edit()
                                .putString(
                                    "status_indicator_style",
                                    StatusIndicatorStyle.TOP_BAR.name
                                )
                                .apply()
                            showSaveSuccessMessage = true
                        },
                        label = { Text(stringResource(R.string.display_top_hint)) }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(componentBackgroundColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.global_display_screenshot_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(id = R.string.global_display_image_format),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = screenshotFormat.equals("PNG", ignoreCase = true),
                        onClick = {
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(screenshotFormat = "PNG")
                                showSaveSuccessMessage = true
                            }
                        },
                        label = { Text(stringResource(R.string.display_png_default)) }
                    )
                    FilterChip(
                        selected = screenshotFormat.equals("JPG", ignoreCase = true) ||
                                screenshotFormat.equals("JPEG", ignoreCase = true),
                        onClick = {
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(screenshotFormat = "JPG")
                                showSaveSuccessMessage = true
                            }
                        },
                        label = { Text(stringResource(R.string.display_jpg_smaller)) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                var qualitySliderValue by remember { mutableStateOf(screenshotQuality.toFloat()) }
                LaunchedEffect(screenshotQuality) {
                    qualitySliderValue = screenshotQuality.toFloat()
                }

                Text(
                    text = stringResource(id = R.string.global_display_image_quality),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = qualitySliderValue,
                        onValueChange = { qualitySliderValue = it },
                        valueRange = 50f..100f,
                        modifier = Modifier.weight(1f),
                        onValueChangeFinished = {
                            val q = qualitySliderValue.roundToInt().coerceIn(50, 100)
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(screenshotQuality = q)
                                showSaveSuccessMessage = true
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${qualitySliderValue.roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                var scaleSliderValue by remember { mutableStateOf(screenshotScalePercent.toFloat()) }
                LaunchedEffect(screenshotScalePercent) {
                    scaleSliderValue = screenshotScalePercent.toFloat()
                }

                Text(
                    text = stringResource(id = R.string.global_display_resolution_scale),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = scaleSliderValue,
                        onValueChange = { scaleSliderValue = it },
                        valueRange = 50f..100f,
                        modifier = Modifier.weight(1f),
                        onValueChangeFinished = {
                            val s = scaleSliderValue.roundToInt().coerceIn(50, 100)
                            scope.launch {
                                displayPreferencesManager.saveDisplaySettings(screenshotScalePercent = s)
                                showSaveSuccessMessage = true
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${scaleSliderValue.roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (preferredPermissionLevel == AndroidPermissionLevel.ROOT) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(componentBackgroundColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.root_execution_mode_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(id = R.string.root_execution_mode_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = rootExecutionMode == RootCommandExecutionMode.AUTO,
                            onClick = {
                                scope.launch {
                                    androidPermissionPreferences.saveRootExecutionMode(RootCommandExecutionMode.AUTO)
                                    showSaveSuccessMessage = true
                                }
                            },
                            label = { Text(stringResource(R.string.root_execution_mode_auto)) }
                        )
                        FilterChip(
                            selected = rootExecutionMode == RootCommandExecutionMode.FORCE_LIBSU,
                            onClick = {
                                scope.launch {
                                    androidPermissionPreferences.saveRootExecutionMode(RootCommandExecutionMode.FORCE_LIBSU)
                                    showSaveSuccessMessage = true
                                }
                            },
                            label = { Text(stringResource(R.string.root_execution_mode_force_libsu)) }
                        )
                        FilterChip(
                            selected = rootExecutionMode == RootCommandExecutionMode.FORCE_EXEC,
                            onClick = {
                                scope.launch {
                                    androidPermissionPreferences.saveRootExecutionMode(RootCommandExecutionMode.FORCE_EXEC)
                                    showSaveSuccessMessage = true
                                }
                            },
                            label = { Text(stringResource(R.string.root_execution_mode_force_exec)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customSuCommandInput,
                        onValueChange = { customSuCommandInput = it },
                        label = { Text(stringResource(id = R.string.root_custom_su_command)) },
                        supportingText = {
                            Text(stringResource(id = R.string.root_custom_su_command_description))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (customSuCommandInput.trim() != customSuCommand.trim()) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            androidPermissionPreferences.saveCustomSuCommand(customSuCommandInput)
                                            showSaveSuccessMessage = true
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Save,
                                        contentDescription = stringResource(id = R.string.save)
                                    )
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ======= 重置按钮 =======
            Button(
                onClick = {
                    scope.launch {
                        displayPreferencesManager.resetDisplaySettings()
                        androidPermissionPreferences.resetRootExecutionSettings()
                        showSaveSuccessMessage = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.reset_all_display_settings),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // 底部间距
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 保存成功提示
        if (showSaveSuccessMessage) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500)
                showSaveSuccessMessage = false
            }
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { showSaveSuccessMessage = false }) {
                        Text(stringResource(id = android.R.string.ok))
                    }
                }
            ) {
                Text(stringResource(R.string.settings_saved))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun DisplayToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backgroundColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
