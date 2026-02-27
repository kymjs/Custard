package com.ai.assistance.custard.ui.features.settings.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.custard.R
import com.ai.assistance.custard.data.model.CharacterCard
import com.ai.assistance.custard.data.preferences.CharacterCardManager
import com.ai.assistance.custard.data.preferences.DisplayPreferencesManager
import com.ai.assistance.custard.data.preferences.UserPreferencesManager
import com.ai.assistance.custard.ui.features.settings.components.ColorPickerDialog
import com.ai.assistance.custard.ui.features.settings.sections.ThemeSettingsAvatarSection
import com.ai.assistance.custard.ui.features.settings.sections.ThemeSettingsBackgroundSection
import com.ai.assistance.custard.ui.features.settings.sections.ThemeSettingsCharacterBindingInfoCard
import com.ai.assistance.custard.ui.features.settings.sections.ThemeSettingsChatStyleSection
import com.ai.assistance.custard.ui.features.settings.sections.ThemeSettingsColorCustomizationSection
import com.ai.assistance.custard.ui.features.settings.sections.ThemeSettingsDisplayOptionsSection
import com.ai.assistance.custard.ui.features.settings.sections.ThemeSettingsFontSection
import com.ai.assistance.custard.ui.features.settings.sections.ThemeSettingsThemeModeSection
import com.ai.assistance.custard.util.AppLogger
import com.ai.assistance.custard.util.FileUtils
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen() {
    val context = LocalContext.current
    val preferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val displayPreferencesManager = remember { DisplayPreferencesManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    // 添加角色卡管理器
    val characterCardManager = remember { CharacterCardManager.getInstance(context) }

    // 获取当前活跃角色卡
    val activeCharacterCard = characterCardManager.activeCharacterCardFlow.collectAsState(
        initial = CharacterCard(
            id = "default_character",
            name = stringResource(R.string.theme_default_character_card),
            description = "",
            characterSetting = "",
            otherContent = "",
            attachedTagIds = emptyList(),
            advancedCustomPrompt = "",
            marks = "",
            isDefault = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    ).value

    // Collect theme settings
    val themeMode =
            preferencesManager.themeMode.collectAsState(
                            initial = UserPreferencesManager.THEME_MODE_LIGHT
                    )
                    .value
    val useSystemTheme = preferencesManager.useSystemTheme.collectAsState(initial = true).value
    val customPrimaryColor =
            preferencesManager.customPrimaryColor.collectAsState(initial = null).value
    val customSecondaryColor =
            preferencesManager.customSecondaryColor.collectAsState(initial = null).value
    val useCustomColors = preferencesManager.useCustomColors.collectAsState(initial = false).value

    // Collect background image settings
    val useBackgroundImage =
            preferencesManager.useBackgroundImage.collectAsState(initial = false).value
    val backgroundImageUri =
            preferencesManager.backgroundImageUri.collectAsState(initial = null).value
    val backgroundImageOpacity =
            preferencesManager.backgroundImageOpacity.collectAsState(initial = 0.3f).value

    // Collect background media type and video settings
    val backgroundMediaType =
            preferencesManager.backgroundMediaType.collectAsState(
                            initial = UserPreferencesManager.MEDIA_TYPE_IMAGE
                    )
                    .value
    val videoBackgroundMuted =
            preferencesManager.videoBackgroundMuted.collectAsState(initial = true).value
    val videoBackgroundLoop =
            preferencesManager.videoBackgroundLoop.collectAsState(initial = true).value

    // Collect toolbar transparency setting
    val toolbarTransparent =
            preferencesManager.toolbarTransparent.collectAsState(initial = false).value
    
    // Collect AppBar custom color settings
    val useCustomAppBarColor =
            preferencesManager.useCustomAppBarColor.collectAsState(initial = false).value
    val customAppBarColor =
            preferencesManager.customAppBarColor.collectAsState(initial = null).value

    // Collect status bar color settings
    val useCustomStatusBarColor =
            preferencesManager.useCustomStatusBarColor.collectAsState(initial = false).value
    val customStatusBarColor =
            preferencesManager.customStatusBarColor.collectAsState(initial = null).value
    val statusBarTransparent =
            preferencesManager.statusBarTransparent.collectAsState(initial = false).value
    val statusBarHidden =
            preferencesManager.statusBarHidden.collectAsState(initial = false).value
    val chatHeaderTransparent =
            preferencesManager.chatHeaderTransparent.collectAsState(initial = false).value
    val chatInputTransparent =
            preferencesManager.chatInputTransparent.collectAsState(initial = false).value
    val chatHeaderOverlayMode =
            preferencesManager.chatHeaderOverlayMode.collectAsState(initial = false).value

    // Collect AppBar content color settings
    val forceAppBarContentColor =
            preferencesManager.forceAppBarContentColor.collectAsState(initial = false).value
    val appBarContentColorMode =
            preferencesManager.appBarContentColorMode.collectAsState(
                            initial = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT
                    )
                    .value

    // Collect ChatHeader icon color settings
    val chatHeaderHistoryIconColor =
            preferencesManager.chatHeaderHistoryIconColor.collectAsState(initial = null).value
    val chatHeaderPipIconColor =
            preferencesManager.chatHeaderPipIconColor.collectAsState(initial = null).value

    // Collect background blur settings
    val useBackgroundBlur =
            preferencesManager.useBackgroundBlur.collectAsState(initial = false).value
    val backgroundBlurRadius =
            preferencesManager.backgroundBlurRadius.collectAsState(initial = 10f).value

    // Collect chat style setting
    val chatStyle = preferencesManager.chatStyle.collectAsState(initial = UserPreferencesManager.CHAT_STYLE_CURSOR).value

    // Collect input style setting
    val inputStyle =
        preferencesManager.inputStyle.collectAsState(
            initial = UserPreferencesManager.INPUT_STYLE_AGENT,
        ).value

    val bubbleShowAvatar = preferencesManager.bubbleShowAvatar.collectAsState(initial = true).value

    // Collect new display settings
    val showThinkingProcess = preferencesManager.showThinkingProcess.collectAsState(initial = true).value
    val showStatusTags = preferencesManager.showStatusTags.collectAsState(initial = true).value
    val showInputProcessingStatus = preferencesManager.showInputProcessingStatus.collectAsState(initial = true).value

    // Collect avatar settings
    val userAvatarUri = preferencesManager.customUserAvatarUri.collectAsState(initial = null).value
    val aiAvatarUri = preferencesManager.customAiAvatarUri.collectAsState(initial = null).value
    val avatarShape = preferencesManager.avatarShape.collectAsState(initial = UserPreferencesManager.AVATAR_SHAPE_CIRCLE).value
    val avatarCornerRadius = preferencesManager.avatarCornerRadius.collectAsState(initial = 8f).value

    // Collect on color mode
    val onColorMode = preferencesManager.onColorMode.collectAsState(initial = UserPreferencesManager.ON_COLOR_MODE_AUTO).value

    // Collect recent colors
    val recentColors = preferencesManager.recentColorsFlow.collectAsState(initial = emptyList()).value



    var showSaveSuccessMessage by remember { mutableStateOf(false) }

    // 自动保存主题到当前角色卡的函数
    val saveThemeToActiveCharacterCard: () -> Unit = {
        scope.launch {
            preferencesManager.saveCurrentThemeToCharacterCard(activeCharacterCard.id)
        }
    }

    // 包装的保存函数，会同时保存设置和角色卡主题
    fun saveThemeSettingsWithCharacterCard(saveAction: suspend () -> Unit) {
        scope.launch {
            saveAction()
            saveThemeToActiveCharacterCard()
        }
    }

    // Default color definitions
    val defaultPrimaryColor = Color.Magenta.toArgb()
    val defaultSecondaryColor = Color.Blue.toArgb()

    // Mutable state
    var themeModeInput by remember { mutableStateOf(themeMode) }
    var useSystemThemeInput by remember { mutableStateOf(useSystemTheme) }
    var primaryColorInput by remember { mutableStateOf(customPrimaryColor ?: defaultPrimaryColor) }
    var secondaryColorInput by remember {
        mutableStateOf(customSecondaryColor ?: defaultSecondaryColor)
    }
    var useCustomColorsInput by remember { mutableStateOf(useCustomColors) }

    // Background image state
    var useBackgroundImageInput by remember { mutableStateOf(useBackgroundImage) }
    var backgroundImageUriInput by remember { mutableStateOf(backgroundImageUri) }
    var backgroundImageOpacityInput by remember { mutableStateOf(backgroundImageOpacity) }

    // Background media type and video settings state
    var backgroundMediaTypeInput by remember { mutableStateOf(backgroundMediaType) }
    var videoBackgroundMutedInput by remember { mutableStateOf(videoBackgroundMuted) }
    var videoBackgroundLoopInput by remember { mutableStateOf(videoBackgroundLoop) }

    // Toolbar transparency state
    var toolbarTransparentInput by remember { mutableStateOf(toolbarTransparent) }
    
    // AppBar custom color state
    var useCustomAppBarColorInput by remember { mutableStateOf(useCustomAppBarColor) }
    var customAppBarColorInput by remember { mutableStateOf(customAppBarColor ?: defaultPrimaryColor) }

    // Status bar color state
    var useCustomStatusBarColorInput by remember { mutableStateOf(useCustomStatusBarColor) }
    var customStatusBarColorInput by remember { mutableStateOf(customStatusBarColor ?: defaultPrimaryColor) }
    var statusBarTransparentInput by remember { mutableStateOf(statusBarTransparent) }
    var statusBarHiddenInput by remember { mutableStateOf(statusBarHidden) }
    var chatHeaderTransparentInput by remember { mutableStateOf(chatHeaderTransparent) }
    var chatInputTransparentInput by remember { mutableStateOf(chatInputTransparent) }
    var chatHeaderOverlayModeInput by remember { mutableStateOf(chatHeaderOverlayMode) }

    // AppBar content color state
    var forceAppBarContentColorInput by remember { mutableStateOf(forceAppBarContentColor) }
    var appBarContentColorModeInput by remember { mutableStateOf(appBarContentColorMode) }

    // ChatHeader icon color state
    var chatHeaderHistoryIconColorInput by remember {
        mutableStateOf(chatHeaderHistoryIconColor ?: Color.Gray.toArgb())
    }
    var chatHeaderPipIconColorInput by remember {
        mutableStateOf(chatHeaderPipIconColor ?: Color.Gray.toArgb())
    }

    // Background blur state
    var useBackgroundBlurInput by remember { mutableStateOf(useBackgroundBlur) }
    var backgroundBlurRadiusInput by remember { mutableStateOf(backgroundBlurRadius) }

    // Chat style state
    var chatStyleInput by remember { mutableStateOf(chatStyle) }
    var inputStyleInput by remember { mutableStateOf(inputStyle) }

    var bubbleShowAvatarInput by remember { mutableStateOf(bubbleShowAvatar) }

    // New display settings state
    var showThinkingProcessInput by remember { mutableStateOf(showThinkingProcess) }
    var showStatusTagsInput by remember { mutableStateOf(showStatusTags) }
    var showInputProcessingStatusInput by remember { mutableStateOf(showInputProcessingStatus) }

    // Avatar state
    var userAvatarUriInput by remember { mutableStateOf(userAvatarUri) }
    var aiAvatarUriInput by remember { mutableStateOf(aiAvatarUri) }
    var avatarShapeInput by remember { mutableStateOf(avatarShape) }
    var avatarCornerRadiusInput by remember { mutableStateOf(avatarCornerRadius) }

    // 添加全局用户头像状态（已迁移到DisplayPreferencesManager）
    val globalUserAvatarUri = displayPreferencesManager.globalUserAvatarUri.collectAsState(initial = null).value
    var globalUserAvatarUriInput by remember { mutableStateOf(globalUserAvatarUri) }

    // 添加全局用户名称状态（已迁移到DisplayPreferencesManager）
    val globalUserName = displayPreferencesManager.globalUserName.collectAsState(initial = null).value
    var globalUserNameInput by remember { mutableStateOf(globalUserName) }

    // On color mode state
    var onColorModeInput by remember { mutableStateOf(onColorMode) }

    // 字体设置状态
    val useCustomFont = preferencesManager.useCustomFont.collectAsState(initial = false).value
    val fontType = preferencesManager.fontType.collectAsState(initial = UserPreferencesManager.FONT_TYPE_SYSTEM).value
    val systemFontName = preferencesManager.systemFontName.collectAsState(initial = UserPreferencesManager.SYSTEM_FONT_DEFAULT).value
    val customFontPath = preferencesManager.customFontPath.collectAsState(initial = null).value
    val fontScale = preferencesManager.fontScale.collectAsState(initial = 1.0f).value

    var useCustomFontInput by remember { mutableStateOf(useCustomFont) }
    var fontTypeInput by remember { mutableStateOf(fontType) }
    var systemFontNameInput by remember { mutableStateOf(systemFontName) }
    var customFontPathInput by remember { mutableStateOf(customFontPath) }
    var fontScaleInput by remember { mutableStateOf(fontScale) }

    var showColorPicker by remember { mutableStateOf(false) }
    var currentColorPickerMode by remember { mutableStateOf("primary") }

    // Video player state
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
                // Add stricter memory limits
                .setLoadControl(
                        DefaultLoadControl.Builder()
                                .setBufferDurationsMs(
                                        5000, // Minimum buffer time reduced to 5 seconds
                                        10000, // Maximum buffer time reduced to 10 seconds
                                        500, // Minimum buffer for playback
                                        1000 // Minimum buffer for playback after rebuffering
                                )
                                .setTargetBufferBytes(5 * 1024 * 1024) // Limit buffer to 5MB
                                .setPrioritizeTimeOverSizeThresholds(true)
                                .build()
                )
                .build()
                .apply {
                    // Set loop playback
                    repeatMode = Player.REPEAT_MODE_ALL
                    // Set mute
                    volume = if (videoBackgroundMutedInput) 0f else 1f
                    playWhenReady = true

                    // If there's a background video URI, load it
                    if (!backgroundImageUriInput.isNullOrEmpty() &&
                                    backgroundMediaTypeInput ==
                                            UserPreferencesManager.MEDIA_TYPE_VIDEO
                    ) {
                        try {
                            val mediaItem = MediaItem.Builder()
                                .setUri(Uri.parse(backgroundImageUriInput))
                                .build()
                            setMediaItem(mediaItem)
                            prepare()
                        } catch (e: Exception) {
                            AppLogger.e("ThemeSettings", "Video loading error", e)
                        }
                    }
                }
    }

    // Free ExoPlayer resources when component is destroyed
    DisposableEffect(Unit) {
        onDispose {
            try {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.release()
            } catch (e: Exception) {
                AppLogger.e("ThemeSettings", "ExoPlayer release error", e)
            }
        }
    }

    // Handle video URI changes
    LaunchedEffect(backgroundImageUriInput, backgroundMediaTypeInput) {
        if (!backgroundImageUriInput.isNullOrEmpty() &&
                        backgroundMediaTypeInput == UserPreferencesManager.MEDIA_TYPE_VIDEO
        ) {
            try {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.setMediaItem(MediaItem.Builder()
                    .setUri(Uri.parse(backgroundImageUriInput))
                    .build())
                exoPlayer.prepare()
                exoPlayer.play()
            } catch (e: Exception) {
                AppLogger.e("ThemeSettings", "更新视频来源错误", e)
            }
        }
    }

    // Handle video settings changes - add error handling
    LaunchedEffect(videoBackgroundMutedInput, videoBackgroundLoopInput) {
        try {
            exoPlayer.volume = if (videoBackgroundMutedInput) 0f else 1f
            exoPlayer.repeatMode =
                    if (videoBackgroundLoopInput) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        } catch (e: Exception) {
            AppLogger.e("ThemeSettings", "更新视频设置错误", e)
        }
    }

    // Image crop launcher
    val cropImageLauncher =
            rememberLauncherForActivityResult(CropImageContract()) { result ->
                if (result.isSuccessful) {
                    val croppedUri = result.uriContent
                    if (croppedUri != null) {
                        scope.launch {
                            val internalUri =
                                    FileUtils.copyFileToInternalStorage(context, croppedUri, "background")
                            if (internalUri != null) {
                                AppLogger.d("ThemeSettings", "Background image saved to: $internalUri")
                                backgroundImageUriInput = internalUri.toString()
                                backgroundMediaTypeInput = UserPreferencesManager.MEDIA_TYPE_IMAGE
                                saveThemeSettingsWithCharacterCard {
                                    preferencesManager.saveThemeSettings(
                                        backgroundImageUri = internalUri.toString(),
                                        backgroundMediaType =
                                                UserPreferencesManager.MEDIA_TYPE_IMAGE
                                    )
                                }
                                showSaveSuccessMessage = true
                                Toast.makeText(
                                                context,
                                                context.getString(R.string.theme_image_saved),
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            } else {
                                Toast.makeText(
                                                context,
                                                context.getString(R.string.theme_copy_failed),
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                            }
                        }
                    }
                } else if (result.error != null) {
                    Toast.makeText(
                                    context,
                                    context.getString(
                                            R.string.theme_image_crop_failed,
                                            result.error!!.message
                                    ),
                                    Toast.LENGTH_LONG
                            )
                            .show()
                }
            }

    // Launch image crop function
    fun launchImageCrop(uri: Uri) {
        // Use safe way to get system colors
        var primaryColor: Int
        var onPrimaryColor: Int
        var surfaceColor: Int
        var statusBarColor: Int

        // Check system dark theme
        val isNightMode =
                context.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES

        try {
            // Try to use theme colors - this is a fallback option
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
            primaryColor = typedValue.data

            // Try to get system status bar color (API 23+)
            try {
                context.theme.resolveAttribute(android.R.attr.colorPrimaryDark, typedValue, true)
                statusBarColor = typedValue.data
            } catch (e: Exception) {
                // If unable to get, use theme color
                statusBarColor = primaryColor
            }

            context.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
            surfaceColor = typedValue.data

            onPrimaryColor =
                    if (isNightMode) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        } catch (e: Exception) {
            // Use fallback colors
            primaryColor = if (isNightMode) 0xFF9C27B0.toInt() else 0xFF6200EE.toInt() // Purple
            statusBarColor =
                    if (isNightMode) 0xFF7B1FA2.toInt() else 0xFF3700B3.toInt() // Dark purple
            surfaceColor =
                    if (isNightMode) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            onPrimaryColor =
                    if (isNightMode) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        }

        val cropOptions =
                CropImageContractOptions(
                        uri,
                        CropImageOptions().apply {
                            guidelines = com.canhub.cropper.CropImageView.Guidelines.ON
                            outputCompressFormat = android.graphics.Bitmap.CompressFormat.JPEG
                            outputCompressQuality = 90
                            fixAspectRatio = false
                            cropMenuCropButtonTitle = context.getString(R.string.theme_crop_done)
                            activityTitle = context.getString(R.string.theme_crop_image)

                            // Set theme colors
                            toolbarColor = primaryColor
                            toolbarBackButtonColor = onPrimaryColor
                            toolbarTitleColor = onPrimaryColor
                            activityBackgroundColor = surfaceColor
                            backgroundColor = surfaceColor

                            // Status bar color
                            statusBarColor = statusBarColor

                            // Use light/dark theme
                            activityMenuIconColor = onPrimaryColor

                            // Improve user experience
                            showCropOverlay = true
                            showProgressBar = true
                            multiTouchEnabled = true
                            autoZoomEnabled = true
                        }
                )
        cropImageLauncher.launch(cropOptions)
    }

    // Image/video picker launcher
    val mediaPickerLauncher =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) {
                    uri: Uri? ->
                if (uri != null) {
                    // Check if it's a video file
                    val isVideo = FileUtils.isVideoFile(context, uri)

                    if (isVideo) {
                        // Video file check size
                        val isVideoSizeAcceptable =
                                FileUtils.checkVideoSize(context, uri, 30) // Limit to 30MB

                        if (!isVideoSizeAcceptable) {
                            // Video too large, show warning
                            Toast.makeText(
                                            context,
                                            context.getString(R.string.theme_video_too_large),
                                            Toast.LENGTH_LONG
                                    )
                                    .show()
                            return@rememberLauncherForActivityResult
                        }

                        // Video file size acceptable, directly save
                        scope.launch {
                            val internalUri = FileUtils.copyFileToInternalStorage(context, uri, "background_video")

                            if (internalUri != null) {
                                AppLogger.d("ThemeSettings", "Background video saved to: $internalUri")
                                backgroundImageUriInput = internalUri.toString()
                                backgroundMediaTypeInput = UserPreferencesManager.MEDIA_TYPE_VIDEO
                                saveThemeSettingsWithCharacterCard {
                                    preferencesManager.saveThemeSettings(
                                        backgroundImageUri = internalUri.toString(),
                                        backgroundMediaType =
                                                UserPreferencesManager.MEDIA_TYPE_VIDEO
                                    )
                                }
                                showSaveSuccessMessage = true
                                Toast.makeText(
                                                context,
                                                context.getString(R.string.theme_video_saved),
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            } else {
                                Toast.makeText(
                                                context,
                                                context.getString(R.string.theme_copy_failed),
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                            }
                        }
                    } else {
                        // Image file first launch crop
                        launchImageCrop(uri)
                    }
                }
            }

    // Migrate existing background image if needed (on first load)
    LaunchedEffect(Unit) {
        // Check if we have a background image URI that starts with content://
        backgroundImageUri?.let { uriString ->
            if (uriString.startsWith("content://")) {
                try {
                    // Try to copy to internal storage
                    val uri = Uri.parse(uriString)
                    scope.launch {
                        val internalUri = FileUtils.copyFileToInternalStorage(context, uri, "migrated_background")
                        if (internalUri != null) {
                            AppLogger.d("ThemeSettings", "Migrated background image to: $internalUri")
                            // Update the URI in preferences
                            preferencesManager.saveThemeSettings(
                                    backgroundImageUri = internalUri.toString()
                            )
                            // Update the local state
                            backgroundImageUriInput = internalUri.toString()
                            Toast.makeText(context, context.getString(R.string.background_image_migrated), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e("ThemeSettings", "Error migrating background image", e)
                    // If migration fails, disable background image to prevent
                    // crashes
                    scope.launch {
                        preferencesManager.saveThemeSettings(useBackgroundImage = false)
                        useBackgroundImageInput = false
                        Toast.makeText(context, context.getString(R.string.background_image_access_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // When settings change, update local state
    LaunchedEffect(
            themeMode,
            useSystemTheme,
            customPrimaryColor,
            customSecondaryColor,
            useCustomColors,
            useBackgroundImage,
            backgroundImageUri,
            backgroundImageOpacity,
            backgroundMediaType,
            videoBackgroundMuted,
            videoBackgroundLoop,
            toolbarTransparent,
            useCustomStatusBarColor,
            customStatusBarColor,
            statusBarTransparent,
            statusBarHidden,
            chatHeaderTransparent,
            chatInputTransparent,
            chatHeaderOverlayMode,
            forceAppBarContentColor,
            appBarContentColorMode,
            chatHeaderHistoryIconColor,
            chatHeaderPipIconColor,
            useBackgroundBlur,
            backgroundBlurRadius,
            chatStyle,
            inputStyle,
            bubbleShowAvatar,
            showThinkingProcess,
            showStatusTags,
            showInputProcessingStatus,
            userAvatarUri,
            aiAvatarUri,
            avatarShape,
            avatarCornerRadius,
            onColorMode,
            globalUserAvatarUri,
            globalUserName,
            useCustomFont,
            fontType,
            systemFontName,
            customFontPath,
            fontScale
    ) {
        themeModeInput = themeMode
        useSystemThemeInput = useSystemTheme
        if (customPrimaryColor != null) primaryColorInput = customPrimaryColor
        if (customSecondaryColor != null) secondaryColorInput = customSecondaryColor
        useCustomColorsInput = useCustomColors
        useBackgroundImageInput = useBackgroundImage
        backgroundImageUriInput = backgroundImageUri
        backgroundImageOpacityInput = backgroundImageOpacity
        backgroundMediaTypeInput = backgroundMediaType
        videoBackgroundMutedInput = videoBackgroundMuted
        videoBackgroundLoopInput = videoBackgroundLoop
        toolbarTransparentInput = toolbarTransparent
        useCustomStatusBarColorInput = useCustomStatusBarColor
        if (customStatusBarColor != null) customStatusBarColorInput = customStatusBarColor
        statusBarTransparentInput = statusBarTransparent
        statusBarHiddenInput = statusBarHidden
        chatHeaderTransparentInput = chatHeaderTransparent
        chatInputTransparentInput = chatInputTransparent
        chatHeaderOverlayModeInput = chatHeaderOverlayMode
        forceAppBarContentColorInput = forceAppBarContentColor
        appBarContentColorModeInput = appBarContentColorMode
        if (chatHeaderHistoryIconColor != null) {
            chatHeaderHistoryIconColorInput = chatHeaderHistoryIconColor
        }
        if (chatHeaderPipIconColor != null) {
            chatHeaderPipIconColorInput = chatHeaderPipIconColor
        }
        useBackgroundBlurInput = useBackgroundBlur
        backgroundBlurRadiusInput = backgroundBlurRadius
        chatStyleInput = chatStyle
        inputStyleInput = inputStyle
        bubbleShowAvatarInput = bubbleShowAvatar
        showThinkingProcessInput = showThinkingProcess
        showStatusTagsInput = showStatusTags
        showInputProcessingStatusInput = showInputProcessingStatus
        userAvatarUriInput = userAvatarUri
        aiAvatarUriInput = aiAvatarUri
        avatarShapeInput = avatarShape
        avatarCornerRadiusInput = avatarCornerRadius
        onColorModeInput = onColorMode
        globalUserAvatarUriInput = globalUserAvatarUri
        globalUserNameInput = globalUserName
        useCustomFontInput = useCustomFont
        fontTypeInput = fontType
        systemFontNameInput = systemFontName
        customFontPathInput = customFontPath
        fontScaleInput = fontScale
    }

    // 字体文件选择器 launcher
    val fontPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    // 获取文件扩展名进行验证
                    val extension = FileUtils.getFileExtension(context, uri)?.lowercase()
                    
                    // 检查是否是支持的字体文件格式
                    if (extension != null && (extension == "ttf" || extension == "otf" || extension == "ttc")) {
                        // 复制字体文件到内部存储
                        val internalUri = FileUtils.copyFileToInternalStorage(context, uri, "custom_font")
                        if (internalUri != null) {
                            AppLogger.d("ThemeSettings", "Font file saved to: $internalUri")
                            customFontPathInput = internalUri.toString()
                            fontTypeInput = UserPreferencesManager.FONT_TYPE_FILE
                            saveThemeSettingsWithCharacterCard {
                                preferencesManager.saveThemeSettings(
                                    customFontPath = internalUri.toString(),
                                    fontType = UserPreferencesManager.FONT_TYPE_FILE
                                )
                            }
                            Toast.makeText(context, context.getString(R.string.font_file_saved, extension), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.font_file_save_failed), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // 不是支持的字体格式
                        Toast.makeText(
                            context, 
                            context.getString(R.string.unsupported_font_format),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

    // Avatar picker and cropper launcher
    var avatarPickerMode by remember { mutableStateOf("user") }

    val cropAvatarLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val croppedUri = result.uriContent
            if (croppedUri != null) {
                scope.launch {
                    val uniqueName = when (avatarPickerMode) {
                        "user" -> "user_avatar"
                        "ai" -> "ai_avatar"
                        "global_user" -> "global_user_avatar"
                        else -> "user_avatar"
                    }
                    val internalUri = FileUtils.copyFileToInternalStorage(context, croppedUri, uniqueName)
                    if (internalUri != null) {
                        when (avatarPickerMode) {
                            "user" -> {
                                AppLogger.d("ThemeSettings", "User avatar saved to: $internalUri")
                                userAvatarUriInput = internalUri.toString()
                                saveThemeSettingsWithCharacterCard {
                                    preferencesManager.saveThemeSettings(customUserAvatarUri = internalUri.toString())
                                }
                            }
                            "ai" -> {
                                AppLogger.d("ThemeSettings", "AI avatar saved to: $internalUri")
                                aiAvatarUriInput = internalUri.toString()
                                preferencesManager.saveThemeSettings(customAiAvatarUri = internalUri.toString())
                            }
                            "global_user" -> {
                                AppLogger.d("ThemeSettings", "Global user avatar saved to: $internalUri")
                                globalUserAvatarUriInput = internalUri.toString()
                                displayPreferencesManager.saveDisplaySettings(globalUserAvatarUri = internalUri.toString())
                            }
                        }
                        Toast.makeText(context, context.getString(R.string.avatar_updated), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.theme_copy_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else if (result.error != null) {
            Toast.makeText(context, context.getString(R.string.avatar_crop_failed, result.error!!.message), Toast.LENGTH_LONG).show()
        }
    }

    fun launchAvatarCrop(uri: Uri) {
        val cropOptions = CropImageContractOptions(
            uri,
            CropImageOptions().apply {
                guidelines = com.canhub.cropper.CropImageView.Guidelines.ON
                outputCompressFormat = android.graphics.Bitmap.CompressFormat.PNG
                outputCompressQuality = 90
                fixAspectRatio = true
                aspectRatioX = 1
                aspectRatioY = 1
                cropMenuCropButtonTitle = context.getString(R.string.theme_crop_done)
                activityTitle = context.getString(R.string.crop_avatar)
                // Basic theming, can be expanded later
                toolbarColor = Color.Gray.toArgb()
                toolbarTitleColor = Color.White.toArgb()
            }
        )
        cropAvatarLauncher.launch(cropOptions)
    }

    val avatarImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            launchAvatarCrop(uri)
        }
    }


    // Get background image state to check if we need opaque cards
    val hasBackgroundImage =
            preferencesManager.useBackgroundImage.collectAsState(initial = false).value

    // Color surface modifier based on whether background image is used
    val cardModifier =
            if (hasBackgroundImage) {
                // Make cards fully opaque when background image is used
                CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
                )
            } else {
                CardDefaults.cardColors()
            }

    // Add a scroll state that we can control
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)) {
        ThemeSettingsCharacterBindingInfoCard(
            aiAvatarUri = aiAvatarUri,
            activeCharacterName = activeCharacterCard.name,
            cardColors = cardModifier,
        )

        ThemeSettingsThemeModeSection(
            cardColors = cardModifier,
            useSystemThemeInput = useSystemThemeInput,
            onUseSystemThemeInputChange = { useSystemThemeInput = it },
            themeModeInput = themeModeInput,
            onThemeModeInputChange = { themeModeInput = it },
            saveThemeSettingsWithCharacterCard = ::saveThemeSettingsWithCharacterCard,
            preferencesManager = preferencesManager,
        )

        ThemeSettingsColorCustomizationSection(
            cardColors = cardModifier,
            preferencesManager = preferencesManager,
            scope = scope,
            saveThemeSettingsWithCharacterCard = ::saveThemeSettingsWithCharacterCard,
            statusBarHiddenInput = statusBarHiddenInput,
            onStatusBarHiddenInputChange = { statusBarHiddenInput = it },
            statusBarTransparentInput = statusBarTransparentInput,
            onStatusBarTransparentInputChange = { statusBarTransparentInput = it },
            useCustomStatusBarColorInput = useCustomStatusBarColorInput,
            onUseCustomStatusBarColorInputChange = { useCustomStatusBarColorInput = it },
            customStatusBarColorInput = customStatusBarColorInput,
            toolbarTransparentInput = toolbarTransparentInput,
            onToolbarTransparentInputChange = { toolbarTransparentInput = it },
            useCustomAppBarColorInput = useCustomAppBarColorInput,
            onUseCustomAppBarColorInputChange = { useCustomAppBarColorInput = it },
            customAppBarColorInput = customAppBarColorInput,
            chatHeaderTransparentInput = chatHeaderTransparentInput,
            onChatHeaderTransparentInputChange = { chatHeaderTransparentInput = it },
            chatHeaderOverlayModeInput = chatHeaderOverlayModeInput,
            onChatHeaderOverlayModeInputChange = { chatHeaderOverlayModeInput = it },
            chatInputTransparentInput = chatInputTransparentInput,
            onChatInputTransparentInputChange = { chatInputTransparentInput = it },
            forceAppBarContentColorInput = forceAppBarContentColorInput,
            onForceAppBarContentColorInputChange = { forceAppBarContentColorInput = it },
            appBarContentColorModeInput = appBarContentColorModeInput,
            onAppBarContentColorModeInputChange = { appBarContentColorModeInput = it },
            chatHeaderHistoryIconColorInput = chatHeaderHistoryIconColorInput,
            chatHeaderPipIconColorInput = chatHeaderPipIconColorInput,
            useCustomColorsInput = useCustomColorsInput,
            onUseCustomColorsInputChange = { useCustomColorsInput = it },
            primaryColorInput = primaryColorInput,
            secondaryColorInput = secondaryColorInput,
            onColorModeInput = onColorModeInput,
            onOnColorModeInputChange = { onColorModeInput = it },
            onShowColorPicker = {
                currentColorPickerMode = it
                showColorPicker = true
            },
            onShowSaveSuccessMessage = { showSaveSuccessMessage = true },
        )

        ThemeSettingsChatStyleSection(
            cardColors = cardModifier,
            chatStyleInput = chatStyleInput,
            onChatStyleInputChange = { chatStyleInput = it },
            inputStyleInput = inputStyleInput,
            onInputStyleInputChange = { inputStyleInput = it },
            bubbleShowAvatarInput = bubbleShowAvatarInput,
            onBubbleShowAvatarInputChange = { bubbleShowAvatarInput = it },
            saveThemeSettingsWithCharacterCard = ::saveThemeSettingsWithCharacterCard,
            preferencesManager = preferencesManager,
        )

        ThemeSettingsDisplayOptionsSection(
            cardColors = cardModifier,
            showThinkingProcessInput = showThinkingProcessInput,
            onShowThinkingProcessInputChange = { showThinkingProcessInput = it },
            showStatusTagsInput = showStatusTagsInput,
            onShowStatusTagsInputChange = { showStatusTagsInput = it },
            showInputProcessingStatusInput = showInputProcessingStatusInput,
            onShowInputProcessingStatusInputChange = { showInputProcessingStatusInput = it },
            saveThemeSettingsWithCharacterCard = ::saveThemeSettingsWithCharacterCard,
            preferencesManager = preferencesManager,
        )

        ThemeSettingsFontSection(
            cardColors = cardModifier,
            context = context,
            preferencesManager = preferencesManager,
            saveThemeSettingsWithCharacterCard = ::saveThemeSettingsWithCharacterCard,
            useCustomFontInput = useCustomFontInput,
            onUseCustomFontInputChange = { useCustomFontInput = it },
            fontTypeInput = fontTypeInput,
            onFontTypeInputChange = { fontTypeInput = it },
            systemFontNameInput = systemFontNameInput,
            onSystemFontNameInputChange = { systemFontNameInput = it },
            customFontPathInput = customFontPathInput,
            onCustomFontPathInputChange = { customFontPathInput = it },
            fontScaleInput = fontScaleInput,
            onFontScaleInputChange = { fontScaleInput = it },
            fontPickerLauncher = fontPickerLauncher,
        )

        ThemeSettingsAvatarSection(
            cardColors = cardModifier,
            scope = scope,
            preferencesManager = preferencesManager,
            displayPreferencesManager = displayPreferencesManager,
            saveThemeSettingsWithCharacterCard = ::saveThemeSettingsWithCharacterCard,
            userAvatarUriInput = userAvatarUriInput,
            onUserAvatarUriInputChange = { userAvatarUriInput = it },
            globalUserAvatarUriInput = globalUserAvatarUriInput,
            onGlobalUserAvatarUriInputChange = { globalUserAvatarUriInput = it },
            globalUserNameInput = globalUserNameInput,
            onGlobalUserNameInputChange = { globalUserNameInput = it },
            avatarShapeInput = avatarShapeInput,
            onAvatarShapeInputChange = { avatarShapeInput = it },
            avatarCornerRadiusInput = avatarCornerRadiusInput,
            onAvatarCornerRadiusInputChange = { avatarCornerRadiusInput = it },
            avatarImagePicker = avatarImagePicker,
            onAvatarPickerModeChange = { avatarPickerMode = it },
        )


        ThemeSettingsBackgroundSection(
            cardColors = cardModifier,
            context = context,
            preferencesManager = preferencesManager,
            saveThemeSettingsWithCharacterCard = ::saveThemeSettingsWithCharacterCard,
            exoPlayer = exoPlayer,
            launchImageCrop = ::launchImageCrop,
            mediaPickerLauncher = mediaPickerLauncher,
            scrollState = scrollState,
            useBackgroundImageInput = useBackgroundImageInput,
            onUseBackgroundImageInputChange = { useBackgroundImageInput = it },
            backgroundMediaTypeInput = backgroundMediaTypeInput,
            onBackgroundMediaTypeInputChange = { backgroundMediaTypeInput = it },
            backgroundImageUriInput = backgroundImageUriInput,
            backgroundImageOpacityInput = backgroundImageOpacityInput,
            onBackgroundImageOpacityInputChange = { backgroundImageOpacityInput = it },
            videoBackgroundMutedInput = videoBackgroundMutedInput,
            onVideoBackgroundMutedInputChange = { videoBackgroundMutedInput = it },
            videoBackgroundLoopInput = videoBackgroundLoopInput,
            onVideoBackgroundLoopInputChange = { videoBackgroundLoopInput = it },
            useBackgroundBlurInput = useBackgroundBlurInput,
            onUseBackgroundBlurInputChange = { useBackgroundBlurInput = it },
            backgroundBlurRadiusInput = backgroundBlurRadiusInput,
            onBackgroundBlurRadiusInputChange = { backgroundBlurRadiusInput = it },
        )

        // Reset button
        OutlinedButton(
                onClick = {
                    scope.launch {
                        preferencesManager.resetThemeSettings()
                        // 同时删除当前角色卡的主题配置
                        preferencesManager.deleteCharacterCardTheme(activeCharacterCard.id)
                        // Reset local state after reset
                        themeModeInput = UserPreferencesManager.THEME_MODE_LIGHT
                        useSystemThemeInput = true
                        primaryColorInput = defaultPrimaryColor
                        secondaryColorInput = defaultSecondaryColor
                        useCustomColorsInput = false
                        useBackgroundImageInput = false
                        backgroundImageUriInput = null
                        backgroundImageOpacityInput = 0.3f
                        backgroundMediaTypeInput = UserPreferencesManager.MEDIA_TYPE_IMAGE
                        videoBackgroundMutedInput = true
                        videoBackgroundLoopInput = true
                        toolbarTransparentInput = false
                        useCustomAppBarColorInput = false
                        customAppBarColorInput = defaultPrimaryColor
                        useCustomStatusBarColorInput = false
                        customStatusBarColorInput = defaultPrimaryColor
                        statusBarTransparentInput = false
                        statusBarHiddenInput = false
                        chatHeaderTransparentInput = false
                        chatInputTransparentInput = false
                        chatHeaderOverlayModeInput = false
                        forceAppBarContentColorInput = false
                        appBarContentColorModeInput = UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT
                        chatHeaderHistoryIconColorInput = Color.Gray.toArgb()
                        chatHeaderPipIconColorInput = Color.Gray.toArgb()
                        useBackgroundBlurInput = false
                        backgroundBlurRadiusInput = 10f
                        chatStyleInput = UserPreferencesManager.CHAT_STYLE_CURSOR
                        inputStyleInput = UserPreferencesManager.INPUT_STYLE_AGENT
                        bubbleShowAvatarInput = true
                        showThinkingProcessInput = true
                        showStatusTagsInput = true
                        showInputProcessingStatusInput = true
                        userAvatarUriInput = null
                        aiAvatarUriInput = null
                        avatarShapeInput = UserPreferencesManager.AVATAR_SHAPE_CIRCLE
                        avatarCornerRadiusInput = 8f
                        onColorModeInput = UserPreferencesManager.ON_COLOR_MODE_AUTO
                        showSaveSuccessMessage = true
                        globalUserAvatarUriInput = null
                        globalUserNameInput = null
                        useCustomFontInput = false
                        fontTypeInput = UserPreferencesManager.FONT_TYPE_SYSTEM
                        systemFontNameInput = UserPreferencesManager.SYSTEM_FONT_DEFAULT
                        customFontPathInput = null
                        fontScaleInput = 1.0f
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        ) { Text(stringResource(id = R.string.theme_reset)) }

        // Show save success message
        if (showSaveSuccessMessage) {
            LaunchedEffect(key1 = showSaveSuccessMessage) {
                kotlinx.coroutines.delay(2000)
                showSaveSuccessMessage = false
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                        text = stringResource(id = R.string.theme_saved),
                        color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Color picker dialog
        if (showColorPicker) {
            ColorPickerDialog(
                    showColorPicker = showColorPicker,
                    currentColorPickerMode = currentColorPickerMode,
                    primaryColorInput = primaryColorInput,
                    secondaryColorInput = secondaryColorInput,
                    statusBarColorInput = customStatusBarColorInput,
                    appBarColorInput = customAppBarColorInput,
                    historyIconColorInput = chatHeaderHistoryIconColorInput,
                    pipIconColorInput = chatHeaderPipIconColorInput,
                    recentColors = recentColors,
                    onColorSelected = {
                        primaryColor,
                        secondaryColor,
                        statusBarColor,
                        appBarColor,
                        historyIconColor,
                        pipIconColor ->
                        primaryColor?.let { primaryColorInput = it }
                        secondaryColor?.let { secondaryColorInput = it }
                        statusBarColor?.let { customStatusBarColorInput = it }
                        appBarColor?.let { customAppBarColorInput = it }
                        historyIconColor?.let { chatHeaderHistoryIconColorInput = it }
                        pipIconColor?.let { chatHeaderPipIconColorInput = it }

                        // Save the new color to recent colors
                        val newColor =
                                primaryColor
                                        ?: secondaryColor
                                        ?: statusBarColor
                                        ?: appBarColor
                                        ?: historyIconColor
                                        ?: pipIconColor
                        newColor?.let { scope.launch { preferencesManager.addRecentColor(it) } }

                        // Save the colors
                        saveThemeSettingsWithCharacterCard {
                            when (currentColorPickerMode) {
                                "primary" ->
                                        primaryColor?.let {
                                            preferencesManager.saveThemeSettings(
                                                    customPrimaryColor = it
                                            )
                                        }
                                "secondary" ->
                                        secondaryColor?.let {
                                            preferencesManager.saveThemeSettings(
                                                    customSecondaryColor = it
                                            )
                                        }
                                "statusBar" ->
                                        statusBarColor?.let {
                                            preferencesManager.saveThemeSettings(
                                                    customStatusBarColor = it
                                            )
                                        }
                                "appBar" ->
                                        appBarColor?.let {
                                            preferencesManager.saveThemeSettings(
                                                    customAppBarColor = it
                                            )
                                        }
                                "historyIcon" ->
                                        historyIconColor?.let {
                                            preferencesManager.saveThemeSettings(
                                                    chatHeaderHistoryIconColor = it
                                            )
                                        }
                                "pipIcon" ->
                                        pipIconColor?.let {
                                            preferencesManager.saveThemeSettings(
                                                    chatHeaderPipIconColor = it
                                            )
                                        }
                            }
                        }
                    },
                    onDismiss = { showColorPicker = false }
            )
        }


    }
}
