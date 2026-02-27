package com.ai.assistance.custard.ui.features.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.custard.R
import com.ai.assistance.custard.data.preferences.ApiPreferences
import com.ai.assistance.custard.data.preferences.UserPreferencesManager
import com.ai.assistance.custard.ui.components.CustomScaffold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSummarySettingsScreen(
        onBackPressed: () -> Unit
) {
        val context = LocalContext.current
        val apiPreferences = remember { ApiPreferences.getInstance(context) }
        val userPreferences = remember { UserPreferencesManager.getInstance(context) }
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        val maxFileSizeLabel = stringResource(id = R.string.context_field_max_file_size)
        val partSizeLabel = stringResource(id = R.string.context_field_part_size)
        val maxTextResultLengthLabel = stringResource(id = R.string.context_field_max_text_result_length)
        val maxImageHistoryTurnsLabel = stringResource(id = R.string.context_field_max_image_history_turns)
        val maxMediaHistoryTurnsLabel = stringResource(id = R.string.context_field_max_media_history_turns)

        // State for UI components - using String to hold input
        var maxFileSizeBytesInput by remember { mutableStateOf("") }
        var partSizeInput by remember { mutableStateOf("") }
        var maxTextResultLengthInput by remember { mutableStateOf("") }
        var maxImageHistoryUserTurnsInput by remember { mutableStateOf("") }
        var maxMediaHistoryUserTurnsInput by remember { mutableStateOf("") }

        val hasBackgroundImage by userPreferences.useBackgroundImage.collectAsState(initial = false)

        // Load initial values once and convert to String
        LaunchedEffect(Unit) {
            maxFileSizeBytesInput = (apiPreferences.maxFileSizeBytesFlow.first() / 1000).toString() // Display as KB
            partSizeInput = (apiPreferences.partSizeFlow.first()).toString()
            maxTextResultLengthInput = (apiPreferences.maxTextResultLengthFlow.first() / 1000).toString() // Display as KB
            maxImageHistoryUserTurnsInput = apiPreferences.maxImageHistoryUserTurnsFlow.first().toString()
            maxMediaHistoryUserTurnsInput = apiPreferences.maxMediaHistoryUserTurnsFlow.first().toString()
        }

        var showSaveSuccessMessage by remember { mutableStateOf(false) }
        var showValidationError by remember { mutableStateOf(false) }
        var validationErrorMessage by remember { mutableStateOf("") }

        // 验证输入是否为有效数字
        fun validateInputs(): Boolean {
            fun validateFloat(value: String, name: String): Boolean {
                val floatVal = value.toFloatOrNull()
                if (floatVal == null || floatVal <= 0f) {
                    validationErrorMessage = context.getString(R.string.context_validation_error_positive_number, name)
                    return false
                }
                return true
            }

            fun validateInt(value: String, name: String): Boolean {
                val intVal = value.toIntOrNull()
                if (intVal == null || intVal <= 0) {
                    validationErrorMessage = context.getString(R.string.context_validation_error_positive_integer, name)
                    return false
                }
                return true
            }

            fun validateNonNegativeInt(value: String, name: String): Boolean {
                val intVal = value.toIntOrNull()
                if (intVal == null || intVal < 0) {
                    validationErrorMessage = context.getString(R.string.context_validation_error_non_negative_integer, name)
                    return false
                }
                return true
            }

            if (!validateInt(maxFileSizeBytesInput, maxFileSizeLabel)) return false
            if (!validateInt(partSizeInput, partSizeLabel)) return false
            if (!validateInt(maxTextResultLengthInput, maxTextResultLengthLabel)) return false
            if (!validateNonNegativeInt(maxImageHistoryUserTurnsInput, maxImageHistoryTurnsLabel)) return false
            if (!validateNonNegativeInt(maxMediaHistoryUserTurnsInput, maxMediaHistoryTurnsLabel)) return false

            showValidationError = false
            return true
        }

        // 保存所有设置的函数
        fun saveAllSettings() {
            if (!validateInputs()) {
                showValidationError = true
                return
            }

            scope.launch {
                // All values are already validated, so toFloat/toInt is safe
                apiPreferences.saveMaxFileSizeBytes(maxFileSizeBytesInput.toInt() * 1000) // Convert KB to Bytes
                apiPreferences.savePartSize(partSizeInput.toInt())
                apiPreferences.saveMaxTextResultLength(maxTextResultLengthInput.toInt() * 1000) // Convert KB to Bytes
                apiPreferences.saveMaxImageHistoryUserTurns(maxImageHistoryUserTurnsInput.toInt())
                apiPreferences.saveMaxMediaHistoryUserTurns(maxMediaHistoryUserTurnsInput.toInt())
                showSaveSuccessMessage = true
            }
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

                        Text(
                                text = stringResource(id = R.string.context_summary_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // ======= 截断设置 =======
                        SectionTitle(
                                text = stringResource(id = R.string.settings_truncation_title),
                                icon = Icons.Default.ContentCut
                        )

                        // 文件读取最大字节数滑块
                        SettingsInputField(
                            title = stringResource(id = R.string.settings_max_file_size),
                            subtitle = stringResource(id = R.string.settings_max_file_size_subtitle),
                            value = maxFileSizeBytesInput,
                            onValueChange = { maxFileSizeBytesInput = it },
                            unitText = "k",
                            backgroundColor = componentBackgroundColor
                        )

                        // 分段读取行数滑块
                        SettingsInputField(
                            title = stringResource(id = R.string.settings_part_size),
                            subtitle = stringResource(id = R.string.settings_part_size_subtitle),
                            value = partSizeInput,
                            onValueChange = { partSizeInput = it },
                            unitText = stringResource(id = R.string.context_unit_lines),
                            backgroundColor = componentBackgroundColor
                        )

                        // 文本结果最大长度滑块
                        SettingsInputField(
                            title = stringResource(id = R.string.settings_max_text_result),
                            subtitle = stringResource(id = R.string.settings_max_text_result_subtitle),
                            value = maxTextResultLengthInput,
                            onValueChange = { maxTextResultLengthInput = it },
                            unitText = "k",
                            backgroundColor = componentBackgroundColor
                        )

                        SettingsInputField(
                            title = stringResource(id = R.string.settings_max_image_history_user_turns),
                            subtitle = stringResource(id = R.string.settings_max_image_history_user_turns_subtitle),
                            value = maxImageHistoryUserTurnsInput,
                            onValueChange = { maxImageHistoryUserTurnsInput = it },
                            unitText = stringResource(id = R.string.context_unit_times),
                            backgroundColor = componentBackgroundColor
                        )

                        SettingsInputField(
                            title = stringResource(id = R.string.settings_max_media_history_user_turns),
                            subtitle = stringResource(id = R.string.settings_max_media_history_user_turns_subtitle),
                            value = maxMediaHistoryUserTurnsInput,
                            onValueChange = { maxMediaHistoryUserTurnsInput = it },
                            unitText = stringResource(id = R.string.context_unit_times),
                            backgroundColor = componentBackgroundColor
                        )

                        // 重置按钮
                        Button(
                                onClick = {
                                        scope.launch {
                                                apiPreferences.resetTruncationSettings()
                                                maxFileSizeBytesInput = (apiPreferences.maxFileSizeBytesFlow.first() / 1000).toString()
                                                partSizeInput = apiPreferences.partSizeFlow.first().toString()
                                                maxTextResultLengthInput = (apiPreferences.maxTextResultLengthFlow.first() / 1000).toString()
                                                maxImageHistoryUserTurnsInput = apiPreferences.maxImageHistoryUserTurnsFlow.first().toString()
                                                maxMediaHistoryUserTurnsInput = apiPreferences.maxMediaHistoryUserTurnsFlow.first().toString()
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
                                        text = stringResource(id = R.string.context_reset_all_settings),
                                        style = MaterialTheme.typography.bodyLarge
                                )
                        }

                        // ======= 保存按钮 =======
                        Button(
                                onClick = { saveAllSettings() },
                                modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                )
                        ) {
                                Icon(
                                        imageVector = Icons.Default.Save,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                        text = stringResource(id = R.string.settings_save),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                )
                        }

                        // 底部间距
                        Spacer(modifier = Modifier.height(16.dp))
                }

                // 验证错误提示
                if (showValidationError) {
                        AlertDialog(
                                onDismissRequest = { showValidationError = false },
                                title = { Text(stringResource(R.string.context_validation_failed)) },
                                text = { Text(validationErrorMessage) },
                                confirmButton = {
                                        TextButton(onClick = { showValidationError = false }) {
                                                Text(stringResource(id = android.R.string.ok))
                                        }
                                }
                        )
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
                                Text(stringResource(id = R.string.settings_saved))
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
private fun SettingsInputField(
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    unitText: String? = null,
    backgroundColor: Color,
    enabled: Boolean = true
) {
    val focusManager = LocalFocusManager.current
    val contentAlpha = if (enabled) 1f else 0.38f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(8.dp)
            .alpha(contentAlpha)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = { newText ->
                        if (enabled) {
                            onValueChange(newText.filter { it.isDigit() || it == '.' })
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .width(50.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (enabled) {
                                focusManager.clearFocus()
                            }
                        }
                    ),
                    singleLine = true
                )

                if (unitText != null) {
                    Text(
                        text = unitText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

