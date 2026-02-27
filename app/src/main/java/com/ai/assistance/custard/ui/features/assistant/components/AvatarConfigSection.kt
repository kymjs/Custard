package com.ai.assistance.custard.ui.features.assistant.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.custard.R
import com.ai.assistance.custard.core.avatar.common.control.AvatarController
import com.ai.assistance.custard.core.avatar.common.control.AvatarSettingKeys
import com.ai.assistance.custard.core.avatar.common.model.AvatarType
import com.ai.assistance.custard.core.avatar.common.state.AvatarEmotion
import com.ai.assistance.custard.ui.features.assistant.viewmodel.AssistantConfigViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun AvatarConfigSection(
    viewModel: AssistantConfigViewModel,
    uiState: AssistantConfigViewModel.UiState,
    avatarController: AvatarController?,
    onImportClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.avatar_config),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(10.dp)
                )
                .padding(12.dp)
        ) {
            val currentAvatarConfig = uiState.currentAvatarConfig
            val currentAvatarModel = uiState.currentAvatarModel
            val currentSettings = uiState.config

            var availableAnimations by remember(currentAvatarModel?.id) {
                mutableStateOf(emptyList<String>())
            }

            LaunchedEffect(avatarController, currentAvatarModel?.id) {
                if (avatarController == null) {
                    availableAnimations = emptyList()
                    return@LaunchedEffect
                }

                while (isActive) {
                    val latestAnimations =
                        avatarController.availableAnimations
                            .map { animationName -> animationName.trim() }
                            .filter { animationName -> animationName.isNotBlank() }
                            .distinct()

                    if (latestAnimations != availableAnimations) {
                        availableAnimations = latestAnimations
                    }

                    delay(300)
                }
            }

            if (currentAvatarModel != null) {
                EmotionAnimationMappingSection(
                    availableAnimations = availableAnimations,
                    emotionAnimationMapping = uiState.emotionAnimationMapping,
                    onEmotionAnimationMappingChange = { emotion, animationName ->
                        viewModel.updateEmotionAnimationMapping(emotion, animationName)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    ModelSelector(
                        models = uiState.avatarConfigs,
                        currentModelId = uiState.currentAvatarConfig?.id,
                        onModelSelected = { viewModel.switchAvatar(it) },
                        onModelRename = { modelId, newName ->
                            viewModel.renameAvatar(modelId, newName)
                        },
                        onModelDelete = { viewModel.deleteAvatar(it) }
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onImportClick) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.import_model),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = stringResource(R.string.avatar_config_supported_formats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = stringResource(R.string.avatar_config_import_methods),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            if (currentAvatarConfig != null && currentSettings != null) {
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.scale, String.format("%.2f", currentSettings.scale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = currentSettings.scale,
                    onValueChange = { viewModel.updateScale(it) },
                    valueRange = 0.1f..2.0f
                )

                Text(
                    text = stringResource(R.string.x_translation, String.format("%.1f", currentSettings.translateX)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = currentSettings.translateX,
                    onValueChange = { viewModel.updateTranslateX(it) },
                    valueRange = -500f..500f
                )

                Text(
                    text = stringResource(R.string.y_translation, String.format("%.1f", currentSettings.translateY)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = currentSettings.translateY,
                    onValueChange = { viewModel.updateTranslateY(it) },
                    valueRange = -500f..500f
                )

                if (currentAvatarConfig.type == AvatarType.MMD) {
                    val cameraPitch =
                        currentSettings.customSettings[AvatarSettingKeys.MMD_INITIAL_ROTATION_X] ?: 0f
                    val initialRotationY =
                        currentSettings.customSettings[AvatarSettingKeys.MMD_INITIAL_ROTATION_Y] ?: 0f
                    val cameraDistanceScale =
                        currentSettings.customSettings[AvatarSettingKeys.MMD_CAMERA_DISTANCE_SCALE] ?: 1f
                    val cameraTargetHeight =
                        currentSettings.customSettings[AvatarSettingKeys.MMD_CAMERA_TARGET_HEIGHT] ?: 0f

                    Text(
                        text = "MMD Camera Pitch (${String.format("%.1f deg", cameraPitch)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = cameraPitch,
                        onValueChange = {
                            viewModel.updateCustomSetting(
                                AvatarSettingKeys.MMD_INITIAL_ROTATION_X,
                                it
                            )
                        },
                        valueRange = -90f..90f
                    )

                    Text(
                        text = "MMD Camera Yaw (${String.format("%.1f deg", initialRotationY)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = initialRotationY,
                        onValueChange = {
                            viewModel.updateCustomSetting(
                                AvatarSettingKeys.MMD_INITIAL_ROTATION_Y,
                                it
                            )
                        },
                        valueRange = -180f..180f
                    )

                    Text(
                        text = "MMD Camera Distance (${String.format("%.2fx", cameraDistanceScale)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = cameraDistanceScale,
                        onValueChange = {
                            viewModel.updateCustomSetting(
                                AvatarSettingKeys.MMD_CAMERA_DISTANCE_SCALE,
                                it
                            )
                        },
                        valueRange = 0.02f..12.0f
                    )

                    Text(
                        text = "MMD Orbit Pivot Height (${String.format("%.2f", cameraTargetHeight)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = cameraTargetHeight.coerceIn(-2.0f, 2.0f),
                        onValueChange = {
                            viewModel.updateCustomSetting(
                                AvatarSettingKeys.MMD_CAMERA_TARGET_HEIGHT,
                                it
                            )
                        },
                        valueRange = -2.0f..2.0f
                    )
                }

                if (currentAvatarConfig.type == AvatarType.GLTF) {
                    val cameraPitch =
                        currentSettings.customSettings[AvatarSettingKeys.GLTF_CAMERA_PITCH] ?: 8f
                    val cameraYaw =
                        currentSettings.customSettings[AvatarSettingKeys.GLTF_CAMERA_YAW] ?: 0f
                    val cameraDistanceScale =
                        currentSettings.customSettings[AvatarSettingKeys.GLTF_CAMERA_DISTANCE_SCALE] ?: 0.5f
                    val cameraTargetHeight =
                        currentSettings.customSettings[AvatarSettingKeys.GLTF_CAMERA_TARGET_HEIGHT] ?: 0f

                    Text(
                        text = "glTF Camera Pitch (${String.format("%.1f deg", cameraPitch)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = cameraPitch,
                        onValueChange = {
                            viewModel.updateCustomSetting(
                                AvatarSettingKeys.GLTF_CAMERA_PITCH,
                                it
                            )
                        },
                        valueRange = -89f..89f
                    )

                    Text(
                        text = "glTF Camera Yaw (${String.format("%.1f deg", cameraYaw)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = cameraYaw,
                        onValueChange = {
                            viewModel.updateCustomSetting(
                                AvatarSettingKeys.GLTF_CAMERA_YAW,
                                it
                            )
                        },
                        valueRange = -180f..180f
                    )

                    Text(
                        text = "glTF Camera Distance (${String.format("%.3fx", cameraDistanceScale)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = cameraDistanceScale.coerceIn(0.0f, 10.0f),
                        onValueChange = {
                            viewModel.updateCustomSetting(
                                AvatarSettingKeys.GLTF_CAMERA_DISTANCE_SCALE,
                                it
                            )
                        },
                        valueRange = 0.0f..10.0f
                    )

                    Text(
                        text = "glTF Orbit Pivot Height (${String.format("%.2f", cameraTargetHeight)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = cameraTargetHeight.coerceIn(-2.0f, 2.0f),
                        onValueChange = {
                            viewModel.updateCustomSetting(
                                AvatarSettingKeys.GLTF_CAMERA_TARGET_HEIGHT,
                                it
                            )
                        },
                        valueRange = -2.0f..2.0f
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HowToImportSection()
            Spacer(modifier = Modifier.height(8.dp))
            AllAvatarImportGuideSection()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EmotionAnimationMappingSection(
    availableAnimations: List<String>,
    emotionAnimationMapping: Map<AvatarEmotion, String>,
    onEmotionAnimationMappingChange: (AvatarEmotion, String?) -> Unit
) {
    val allEmotions = remember { AvatarEmotion.values().toList() }
    var selectedEmotion by remember { mutableStateOf(AvatarEmotion.IDLE) }
    var emotionDropdownExpanded by remember { mutableStateOf(false) }
    var animationDropdownExpanded by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Emotion Mapping",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )

    if (emotionAnimationMapping.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 2
        ) {
            allEmotions.forEach { emotion ->
                val mappedAnimation = emotionAnimationMapping[emotion] ?: return@forEach

                FilterChip(
                    selected = selectedEmotion == emotion,
                    onClick = { selectedEmotion = emotion },
                    label = {
                        Text(
                            text = "${emotion.toDisplayName()}: $mappedAnimation"
                        )
                    }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExposedDropdownMenuBox(
            modifier = Modifier.weight(0.4f),
            expanded = emotionDropdownExpanded,
            onExpandedChange = {
                emotionDropdownExpanded = !emotionDropdownExpanded
            }
        ) {
            OutlinedTextField(
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                value = selectedEmotion.toDisplayName(),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text(text = "Emotion") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = emotionDropdownExpanded
                    )
                }
            )

            ExposedDropdownMenu(
                expanded = emotionDropdownExpanded,
                onDismissRequest = { emotionDropdownExpanded = false }
            ) {
                allEmotions.forEach { emotion ->
                    DropdownMenuItem(
                        text = { Text(text = emotion.toDisplayName()) },
                        onClick = {
                            selectedEmotion = emotion
                            emotionDropdownExpanded = false
                        }
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            modifier = Modifier.weight(0.6f),
            expanded = animationDropdownExpanded,
            onExpandedChange = {
                animationDropdownExpanded = !animationDropdownExpanded
            }
        ) {
            OutlinedTextField(
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                value =
                    emotionAnimationMapping[selectedEmotion]
                        ?: "Unmapped",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                label = { Text(text = "Mapped Action") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = animationDropdownExpanded
                    )
                }
            )

            ExposedDropdownMenu(
                expanded = animationDropdownExpanded,
                onDismissRequest = { animationDropdownExpanded = false }
            ) {
                availableAnimations.forEach { animationName ->
                    DropdownMenuItem(
                        text = { Text(text = animationName) },
                        onClick = {
                            onEmotionAnimationMappingChange(
                                selectedEmotion,
                                animationName
                            )
                            animationDropdownExpanded = false
                        }
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(
            onClick = {
                onEmotionAnimationMappingChange(selectedEmotion, null)
            }
        ) {
            Text(text = "Clear Selected Mapping")
        }
    }

    if (availableAnimations.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 5
        ) {
            availableAnimations.forEach { animationName ->
                FilterChip(
                    selected = emotionAnimationMapping[selectedEmotion] == animationName,
                    onClick = {
                        onEmotionAnimationMappingChange(selectedEmotion, animationName)
                    },
                    label = { Text(animationName) }
                )
            }
        }
    } else {
        Text(
            text = "No model actions found for this avatar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AllAvatarImportGuideSection(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            text =
                if (expanded) {
                    stringResource(R.string.import_structure_guide_tap_to_collapse)
                } else {
                    stringResource(R.string.import_structure_guide_tap_to_expand)
                },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = !expanded }
        )

        if (expanded) {
            Text(
                text = stringResource(R.string.import_structure_guide_content),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelector(
    models: List<com.ai.assistance.custard.data.repository.AvatarConfig>,
    currentModelId: String?,
    onModelSelected: (String) -> Unit,
    onModelRename: (String, String) -> Unit,
    onModelDelete: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentModel = models.find { it.id == currentModelId }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.confirm_delete_model_title)) },
            text = { Text(stringResource(R.string.confirm_delete_model_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onModelDelete(showDeleteDialog!!)
                        showDeleteDialog = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showRenameDialog != null) {
        val canRename = renameInput.trim().isNotEmpty()
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text(stringResource(R.string.rename_avatar_model_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rename_avatar_model_message))
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.avatar_model_name_label)) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog?.let { modelId ->
                            onModelRename(modelId, renameInput.trim())
                        }
                        showRenameDialog = null
                    },
                    enabled = canRename
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentModel?.name ?: stringResource(R.string.select_model),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { Text(stringResource(R.string.current_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (model in models) {
                DropdownMenuItem(
                    text = { Text(model.name) },
                    onClick = {
                        onModelSelected(model.id)
                        expanded = false
                    },
                    trailingIcon = {
                        Row {
                            IconButton(
                                onClick = {
                                    showRenameDialog = model.id
                                    renameInput = model.name
                                    expanded = false
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.rename)
                                )
                            }
                            IconButton(
                                onClick = {
                                    showDeleteDialog = model.id
                                    expanded = false
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete)
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun AvatarEmotion.toDisplayName(): String {
    return when (this) {
        AvatarEmotion.IDLE -> "Idle"
        AvatarEmotion.LISTENING -> "Listening"
        AvatarEmotion.THINKING -> "Thinking"
        AvatarEmotion.HAPPY -> "Happy"
        AvatarEmotion.SAD -> "Sad"
        AvatarEmotion.CONFUSED -> "Confused"
        AvatarEmotion.SURPRISED -> "Surprised"
    }
}
