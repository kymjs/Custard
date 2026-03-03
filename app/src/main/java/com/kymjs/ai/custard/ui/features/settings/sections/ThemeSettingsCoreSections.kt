package com.kymjs.ai.custard.ui.features.settings.sections

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.data.preferences.UserPreferencesManager
import com.kymjs.ai.custard.ui.features.settings.components.ChatStyleOption
import com.kymjs.ai.custard.ui.features.settings.components.ThemeModeOption

internal typealias SaveThemeSettingsAction = (suspend () -> Unit) -> Unit

@Composable
internal fun ThemeSettingsCharacterBindingInfoCard(
    aiAvatarUri: String?,
    activeCharacterName: String,
    cardColors: CardColors,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = cardColors,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (aiAvatarUri != null) {
                    Image(
                        painter = rememberAsyncImagePainter(Uri.parse(aiAvatarUri)),
                        contentDescription = stringResource(R.string.character_avatar),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        contentDescription =
                            stringResource(R.string.character_card_default_avatar),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.current_character, activeCharacterName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.theme_auto_bind_character_card),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                Icons.Default.Link,
                contentDescription = stringResource(R.string.bind),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun ThemeSettingsThemeModeSection(
    cardColors: CardColors,
    useSystemThemeInput: Boolean,
    onUseSystemThemeInputChange: (Boolean) -> Unit,
    themeModeInput: String,
    onThemeModeInputChange: (String) -> Unit,
    saveThemeSettingsWithCharacterCard: SaveThemeSettingsAction,
    preferencesManager: UserPreferencesManager,
) {
    ThemeSettingsSectionTitle(
        title = stringResource(id = R.string.theme_title_mode),
        icon = Icons.Default.Brightness4,
    )

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.theme_system_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.theme_follow_system),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(id = R.string.theme_follow_system_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = useSystemThemeInput,
                    onCheckedChange = {
                        onUseSystemThemeInputChange(it)
                        saveThemeSettingsWithCharacterCard {
                            preferencesManager.saveThemeSettings(useSystemTheme = it)
                        }
                    },
                )
            }

            if (!useSystemThemeInput) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(id = R.string.theme_select),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeModeOption(
                        title = stringResource(id = R.string.theme_light),
                        selected = themeModeInput == UserPreferencesManager.THEME_MODE_LIGHT,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onThemeModeInputChange(UserPreferencesManager.THEME_MODE_LIGHT)
                            saveThemeSettingsWithCharacterCard {
                                preferencesManager.saveThemeSettings(
                                    themeMode = UserPreferencesManager.THEME_MODE_LIGHT,
                                )
                            }
                        },
                    )

                    ThemeModeOption(
                        title = stringResource(id = R.string.theme_dark),
                        selected = themeModeInput == UserPreferencesManager.THEME_MODE_DARK,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onThemeModeInputChange(UserPreferencesManager.THEME_MODE_DARK)
                            saveThemeSettingsWithCharacterCard {
                                preferencesManager.saveThemeSettings(
                                    themeMode = UserPreferencesManager.THEME_MODE_DARK,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ThemeSettingsChatStyleSection(
    cardColors: CardColors,
    chatStyleInput: String,
    onChatStyleInputChange: (String) -> Unit,
    inputStyleInput: String,
    onInputStyleInputChange: (String) -> Unit,
    bubbleShowAvatarInput: Boolean,
    onBubbleShowAvatarInputChange: (Boolean) -> Unit,
    saveThemeSettingsWithCharacterCard: SaveThemeSettingsAction,
    preferencesManager: UserPreferencesManager,
) {
    ThemeSettingsSectionTitle(
        title = stringResource(id = R.string.chat_style_title),
        icon = Icons.Default.ColorLens,
    )

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.chat_style_desc),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChatStyleOption(
                    title = stringResource(id = R.string.chat_style_cursor),
                    selected = chatStyleInput == UserPreferencesManager.CHAT_STYLE_CURSOR,
                    modifier = Modifier.weight(1f),
                ) {
                    onChatStyleInputChange(UserPreferencesManager.CHAT_STYLE_CURSOR)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            chatStyle = UserPreferencesManager.CHAT_STYLE_CURSOR,
                        )
                    }
                }

                ChatStyleOption(
                    title = stringResource(id = R.string.chat_style_bubble),
                    selected = chatStyleInput == UserPreferencesManager.CHAT_STYLE_BUBBLE,
                    modifier = Modifier.weight(1f),
                ) {
                    onChatStyleInputChange(UserPreferencesManager.CHAT_STYLE_BUBBLE)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            chatStyle = UserPreferencesManager.CHAT_STYLE_BUBBLE,
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(id = R.string.input_style_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(id = R.string.input_style_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChatStyleOption(
                    title = stringResource(id = R.string.input_style_classic),
                    selected =
                        inputStyleInput == UserPreferencesManager.INPUT_STYLE_CLASSIC,
                    modifier = Modifier.weight(1f),
                ) {
                    onInputStyleInputChange(UserPreferencesManager.INPUT_STYLE_CLASSIC)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            inputStyle = UserPreferencesManager.INPUT_STYLE_CLASSIC,
                        )
                    }
                }

                ChatStyleOption(
                    title = stringResource(id = R.string.input_style_agent),
                    selected = inputStyleInput == UserPreferencesManager.INPUT_STYLE_AGENT,
                    modifier = Modifier.weight(1f),
                ) {
                    onInputStyleInputChange(UserPreferencesManager.INPUT_STYLE_AGENT)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            inputStyle = UserPreferencesManager.INPUT_STYLE_AGENT,
                        )
                    }
                }
            }

            if (inputStyleInput == UserPreferencesManager.INPUT_STYLE_AGENT) {
                Text(
                    text = stringResource(id = R.string.input_style_agent_reserved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (chatStyleInput == UserPreferencesManager.CHAT_STYLE_BUBBLE) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.chat_style_bubble_show_avatar),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text =
                                stringResource(id = R.string.chat_style_bubble_show_avatar_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = bubbleShowAvatarInput,
                        onCheckedChange = {
                            onBubbleShowAvatarInputChange(it)
                            saveThemeSettingsWithCharacterCard {
                                preferencesManager.saveThemeSettings(bubbleShowAvatar = it)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ThemeSettingsDisplayOptionsSection(
    cardColors: CardColors,
    showThinkingProcessInput: Boolean,
    onShowThinkingProcessInputChange: (Boolean) -> Unit,
    showStatusTagsInput: Boolean,
    onShowStatusTagsInputChange: (Boolean) -> Unit,
    showInputProcessingStatusInput: Boolean,
    onShowInputProcessingStatusInputChange: (Boolean) -> Unit,
    saveThemeSettingsWithCharacterCard: SaveThemeSettingsAction,
    preferencesManager: UserPreferencesManager,
) {
    ThemeSettingsSectionTitle(
        title = stringResource(id = R.string.display_options_title),
        icon = Icons.Default.ColorLens,
    )

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.show_thinking_process),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(id = R.string.show_thinking_process_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = showThinkingProcessInput,
                    onCheckedChange = {
                        onShowThinkingProcessInputChange(it)
                        saveThemeSettingsWithCharacterCard {
                            preferencesManager.saveThemeSettings(showThinkingProcess = it)
                        }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.show_status_tags),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(id = R.string.show_status_tags_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = showStatusTagsInput,
                    onCheckedChange = {
                        onShowStatusTagsInputChange(it)
                        saveThemeSettingsWithCharacterCard {
                            preferencesManager.saveThemeSettings(showStatusTags = it)
                        }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.show_input_processing_status),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text =
                            stringResource(id = R.string.show_input_processing_status_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = showInputProcessingStatusInput,
                    onCheckedChange = {
                        onShowInputProcessingStatusInputChange(it)
                        saveThemeSettingsWithCharacterCard {
                            preferencesManager.saveThemeSettings(
                                showInputProcessingStatus = it,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun ThemeSettingsSectionTitle(
    title: String,
    icon: ImageVector,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
}
