package com.ai.assistance.custard.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.custard.terminal.R
import com.ai.assistance.custard.terminal.utils.VirtualKeyAction
import com.ai.assistance.custard.terminal.utils.VirtualKeyboardButtonConfig
import com.ai.assistance.custard.terminal.utils.VirtualKeyboardConfigManager
import com.ai.assistance.custard.terminal.utils.VirtualKeyboardLayoutConfig

@Composable
fun VirtualKeyboardCustomizationDialog(
    initialLayout: VirtualKeyboardLayoutConfig,
    onDismiss: () -> Unit,
    onConfirm: (VirtualKeyboardLayoutConfig) -> Unit
) {
    val context = LocalContext.current
    var draftButtons by remember(initialLayout) { mutableStateOf(initialLayout.rows.flatten()) }

    fun updateButton(index: Int, block: (VirtualKeyboardButtonConfig) -> VirtualKeyboardButtonConfig) {
        val current = draftButtons.toMutableList()
        current[index] = block(current[index])
        draftButtons = current
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = context.getString(R.string.virtual_keyboard_dialog_title),
                    color = SettingsTheme.onSurfaceColor,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = {
                        draftButtons = VirtualKeyboardConfigManager.defaultLayout().rows.flatten()
                    }
                ) {
                    Text(context.getString(R.string.virtual_keyboard_reset_default), color = SettingsTheme.primaryColor)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = context.getString(R.string.virtual_keyboard_value_hint),
                    color = SettingsTheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                repeat(VirtualKeyboardLayoutConfig.ROW_COUNT) { rowIndex ->
                    val rowTitle = if (rowIndex == 0) {
                        context.getString(R.string.virtual_keyboard_row_one)
                    } else {
                        context.getString(R.string.virtual_keyboard_row_two)
                    }

                    Text(
                        text = rowTitle,
                        color = SettingsTheme.onSurfaceColor,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    repeat(VirtualKeyboardLayoutConfig.COLUMN_COUNT) { columnIndex ->
                        val index = rowIndex * VirtualKeyboardLayoutConfig.COLUMN_COUNT + columnIndex
                        val key = draftButtons[index]
                        VirtualKeyboardKeyEditor(
                            keyIndex = columnIndex + 1,
                            keyConfig = key,
                            onLabelChange = { value ->
                                updateButton(index) { it.copy(label = value) }
                            },
                            onValueChange = { value ->
                                updateButton(index) { it.copy(value = value) }
                            },
                            onActionChange = { action ->
                                updateButton(index) { current ->
                                    current.copy(
                                        action = action,
                                        value = if (action == VirtualKeyAction.SEND_TEXT) current.value else ""
                                    )
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        VirtualKeyboardLayoutConfig(
                            rows = draftButtons.chunked(VirtualKeyboardLayoutConfig.COLUMN_COUNT)
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.primaryColor)
            ) {
                Text(context.getString(R.string.confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        },
        containerColor = SettingsTheme.surfaceColor
    )
}

@Composable
private fun VirtualKeyboardKeyEditor(
    keyIndex: Int,
    keyConfig: VirtualKeyboardButtonConfig,
    onLabelChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onActionChange: (VirtualKeyAction) -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "$keyIndex",
            color = SettingsTheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = keyConfig.label,
                onValueChange = onLabelChange,
                modifier = Modifier.weight(1f),
                label = { Text(context.getString(R.string.virtual_keyboard_key_label)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SettingsTheme.onSurfaceColor,
                    unfocusedTextColor = SettingsTheme.onSurfaceColor,
                    focusedBorderColor = SettingsTheme.primaryColor,
                    unfocusedBorderColor = SettingsTheme.onSurfaceVariant
                )
            )
            OutlinedButton(
                onClick = { onActionChange(keyConfig.action.next()) },
                modifier = Modifier.width(102.dp)
            ) {
                Text(
                    text = actionLabel(context, keyConfig.action),
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = keyConfig.value,
            onValueChange = onValueChange,
            enabled = keyConfig.action == VirtualKeyAction.SEND_TEXT,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(context.getString(R.string.virtual_keyboard_key_value)) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SettingsTheme.onSurfaceColor,
                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                focusedBorderColor = SettingsTheme.primaryColor,
                unfocusedBorderColor = SettingsTheme.onSurfaceVariant
            )
        )
    }
}

private fun actionLabel(context: android.content.Context, action: VirtualKeyAction): String {
    return when (action) {
        VirtualKeyAction.SEND_TEXT -> context.getString(R.string.virtual_keyboard_action_send)
        VirtualKeyAction.TOGGLE_CTRL -> context.getString(R.string.virtual_keyboard_action_ctrl)
        VirtualKeyAction.TOGGLE_ALT -> context.getString(R.string.virtual_keyboard_action_alt)
    }
}

private fun VirtualKeyAction.next(): VirtualKeyAction {
    return when (this) {
        VirtualKeyAction.SEND_TEXT -> VirtualKeyAction.TOGGLE_CTRL
        VirtualKeyAction.TOGGLE_CTRL -> VirtualKeyAction.TOGGLE_ALT
        VirtualKeyAction.TOGGLE_ALT -> VirtualKeyAction.SEND_TEXT
    }
}
