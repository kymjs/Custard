package com.kymjs.ai.custard.ui.theme

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Build
import com.kymjs.ai.custard.util.AppLogger
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.kymjs.ai.custard.data.preferences.UserPreferencesManager
import com.kymjs.ai.custard.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_AUTO
import com.kymjs.ai.custard.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_DARK
import com.kymjs.ai.custard.data.preferences.UserPreferencesManager.Companion.ON_COLOR_MODE_LIGHT
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFE082),          // 主色：亮色主色的反色
    onPrimary = Color(0xFF3D2C00),        // 主色上的文字：深棕
    primaryContainer = Color(0xFF554300), // 主色容器：深暖棕
    onPrimaryContainer = Color(0xFFFFE082), // 主色容器上的文字：浅暖黄

    secondary = Color(0xFFD0D0D0),         // 辅助色：浅灰（头盔白的暗色版）
    onSecondary = Color(0xFF212121),       // 辅助色上的文字：深灰
    secondaryContainer = Color(0xFF494949), // 辅助色容器：中灰
    onSecondaryContainer = Color(0xFFE0E0E0), // 辅助色容器上的文字：浅灰

    tertiary = Color(0xFFFFB4A2),          // 第三色：亮色第三色的反色
    onTertiary = Color(0xFF44271E),        // 第三色上的文字：深棕红
    tertiaryContainer = Color(0xFF5C2D20), // 第三色容器：深棕红
    onTertiaryContainer = Color(0xFFFFDCD2), // 第三色容器上的文字：浅粉

    background = Color(0xFF1C1A12),        // 背景：深暖灰（接近黑色）
    onBackground = Color(0xFFE6E1D5),      // 背景上的文字：浅米色
    surface = Color(0xFF1C1A12),           // 表面：与背景一致
    onSurface = Color(0xFFE6E1D5),         // 表面上的文字：浅米色

    surfaceVariant = Color(0xFF494536),    // 表面变体：中灰
    onSurfaceVariant = Color(0xFFCBC4B5),  // 表面变体上的文字：浅米色
    surfaceTint = Color(0xFFFFE082),                  // 表面色调：使用主色
    inverseSurface = Color(0xFFE6E1D5),    // 反色表面：浅米色
    inverseOnSurface = Color(0xFF303030),  // 反色表面上的文字：深灰

    error = Color(0xFFFFB4AB),             // 错误色：浅红色
    onError = Color(0xFF690005),           // 错误色上的文字：深红色
    errorContainer = Color(0xFF93000A),    // 错误容器：深红色
    onErrorContainer = Color(0xFFFFDAD6),  // 错误容器上的文字：浅红色

    outline = Color(0xFF938F80),           // 轮廓线：中灰色
    outlineVariant = Color(0xFF494536),    // 轮廓线变体：深灰色
    scrim = Color(0xCC000000),              // 遮罩：半透黑

    surfaceBright = Color(0xFF424036),     // 亮面：中深灰
    surfaceContainer = Color(0xFF28261B),   // 表面容器：深暖灰
    surfaceContainerHigh = Color(0xFF322F25), // 高亮度表面容器
    surfaceContainerHighest = Color(0xFF3D3A2F), // 最高亮度表面容器
    surfaceContainerLow = Color(0xFF232116),   // 低亮度表面容器
    surfaceContainerLowest = Color(0xFF121212), // 最低亮度表面容器（近黑）
    surfaceDim = Color(0xFF1C1A12),         // 暗面：与背景一致
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFFFD166),          // 主色：温暖的浅橘黄（猫的条纹与头盔装饰）
    onPrimary = Color(0xFF212121),        // 主色上的文字：深灰（确保可读性）
    primaryContainer = Color(0xFFFFE8B3), // 主色容器：更浅的暖黄色
    onPrimaryContainer = Color(0xFF3D2C00), // 主色容器上的文字：深棕
    inversePrimary = Color(0xFFFFE082),   // 反色主色：稍亮的暖黄X

    secondary = Color(0xFFF5F5F5),         // 辅助色：头盔的白色
    onSecondary = Color(0xFF212121),       // 辅助色上的文字：深灰
    secondaryContainer = Color(0xFFE0E0E0), // 辅助色容器：浅灰色
    onSecondaryContainer = Color(0xFF424242), // 辅助色容器上的文字：中灰

    tertiary = Color(0xFFF8B195),          // 第三色：猫爪与脸颊的淡粉色
    onTertiary = Color(0xFF212121),        // 第三色上的文字：深灰
    tertiaryContainer = Color(0xFFFFDCD2), // 第三色容器：极浅的粉色
    onTertiaryContainer = Color(0xFF44271E), // 第三色容器上的文字：深棕红

    background = Color(0xFFFFF8E1),        // 背景：极浅的暖米色（接近图中背景）
    onBackground = Color(0xFF212121),      // 背景上的文字：深灰
    surface = Color(0xFFFFF8E1),           // 表面：与背景一致
    onSurface = Color(0xFF212121),         // 表面上的文字：深灰

    surfaceVariant = Color(0xFFE8E0D0),    // 表面变体：浅米色
    onSurfaceVariant = Color(0xFF494536),  // 表面变体上的文字：中灰
    surfaceTint = Color(0xFFFFD166),       // 表面色调：使用主色X
    inverseSurface = Color(0xFF303030),     // 反色表面：深灰
    inverseOnSurface = Color(0xFFF5F5F5),   // 反色表面上的文字：白色

    error = Color(0xFFB00020),             // 错误色：标准红色
    onError = Color(0xFFFFFFFF),            // 错误色上的文字：白色
    errorContainer = Color(0xFFFCD8E0),      // 错误容器：浅红色
    onErrorContainer = Color(0xFF370B15),   // 错误容器上的文字：深红

    outline = Color(0xFF757575),           // 轮廓线：中灰色
    outlineVariant = Color(0xFFC4C4C4),     // 轮廓线变体：浅灰色
    scrim = Color(0xCC000000),              // 遮罩：半透黑

    surfaceBright = Color(0xFFFFF8E1),     // 亮面：与背景一致
    surfaceContainer = Color(0xFFFFF0D8),   // 表面容器：稍深的暖米色
    surfaceContainerHigh = Color(0xFFFFE9D0), // 高亮度表面容器
    surfaceContainerHighest = Color(0xFFFFE2C8), // 最高亮度表面容器
    surfaceContainerLow = Color(0xFFFFF9E4),   // 低亮度表面容器
    surfaceContainerLowest = Color(0xFFFFFFFF), // 最低亮度表面容器（白色）
    surfaceDim = Color(0xFFE5D9BD),         // 暗面：暖灰色
)

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun CustardTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    // 获取主题设置
    val useSystemTheme by preferencesManager.useSystemTheme.collectAsState(initial = true)
    val themeMode by
    preferencesManager.themeMode.collectAsState(
        initial = UserPreferencesManager.THEME_MODE_LIGHT
    )
    val useCustomColors by preferencesManager.useCustomColors.collectAsState(initial = false)
    val customPrimaryColor by preferencesManager.customPrimaryColor.collectAsState(initial = null)
    val customSecondaryColor by
    preferencesManager.customSecondaryColor.collectAsState(initial = null)
    val onColorMode by preferencesManager.onColorMode.collectAsState(initial = ON_COLOR_MODE_AUTO)

    // 获取背景图片设置
    val useBackgroundImage by preferencesManager.useBackgroundImage.collectAsState(initial = false)
    val backgroundImageUri by preferencesManager.backgroundImageUri.collectAsState(initial = null)
    val backgroundImageOpacity by
    preferencesManager.backgroundImageOpacity.collectAsState(initial = 0.3f)

    // 获取背景媒体类型和视频设置
    val backgroundMediaType by
    preferencesManager.backgroundMediaType.collectAsState(
        initial = UserPreferencesManager.MEDIA_TYPE_IMAGE
    )
    val videoBackgroundMuted by
    preferencesManager.videoBackgroundMuted.collectAsState(initial = true)
    val videoBackgroundLoop by preferencesManager.videoBackgroundLoop.collectAsState(initial = true)

    // 获取状态栏颜色设置
    val useCustomStatusBarColor by
    preferencesManager.useCustomStatusBarColor.collectAsState(initial = false)
    val customStatusBarColorValue by
    preferencesManager.customStatusBarColor.collectAsState(initial = null)
    val statusBarTransparent by
    preferencesManager.statusBarTransparent.collectAsState(initial = false)
    val statusBarHidden by
    preferencesManager.statusBarHidden.collectAsState(initial = false)

    // 获取背景模糊设置
    val useBackgroundBlur by preferencesManager.useBackgroundBlur.collectAsState(initial = false)
    val backgroundBlurRadius by preferencesManager.backgroundBlurRadius.collectAsState(initial = 10f)

    // 获取字体设置
    val useCustomFont by preferencesManager.useCustomFont.collectAsState(initial = false)
    val fontType by preferencesManager.fontType.collectAsState(initial = UserPreferencesManager.FONT_TYPE_SYSTEM)
    val systemFontName by preferencesManager.systemFontName.collectAsState(initial = UserPreferencesManager.SYSTEM_FONT_DEFAULT)
    val customFontPath by preferencesManager.customFontPath.collectAsState(initial = null)
    val fontScale by preferencesManager.fontScale.collectAsState(initial = 1.0f)

    // 创建自定义 Typography
    val customTypography = remember(useCustomFont, fontType, systemFontName, customFontPath, fontScale) {
        createCustomTypography(
            context = context,
            useCustomFont = useCustomFont,
            fontType = fontType,
            systemFontName = systemFontName,
            customFontPath = customFontPath,
            fontScale = fontScale
        )
    }

    // 确定是否使用暗色主题
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme =
        if (useSystemTheme) {
            systemDarkTheme
        } else {
            themeMode == UserPreferencesManager.THEME_MODE_DARK
        }

    // Dynamic color is available on Android 12+
    val dynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // 基础主题色调
    var colorScheme =
        when {
//            dynamicColor -> {
//                if (darkTheme) dynamicDarkColorScheme(context)
//                else dynamicLightColorScheme(context)
//            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    // 应用自定义颜色和文本颜色
    if (useCustomColors) {
        customPrimaryColor?.let { primaryArgb ->
            val primary = Color(primaryArgb)
            val secondary = customSecondaryColor?.let { Color(it) } ?: colorScheme.secondary

            colorScheme = if (darkTheme) {
                generateDarkColorScheme(primary, secondary, onColorMode)
            } else {
                generateLightColorScheme(primary, secondary, onColorMode)
            }
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = window.decorView.let { decorView ->
                WindowCompat.getInsetsController(window, decorView)
            }

            // 始终保持沉浸式模式，让Compose处理状态栏背景
            WindowCompat.setDecorFitsSystemWindows(window, false)

            // 隐藏或显示状态栏
            if (statusBarHidden) {
                // 隐藏状态栏
                insetsController?.hide(WindowInsetsCompat.Type.statusBars())
                insetsController?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                // 显示状态栏
                insetsController?.show(WindowInsetsCompat.Type.statusBars())

                // 状态栏颜色和图标颜色控制
                val statusBarColor = when {
                    statusBarTransparent -> Color.Transparent.toArgb()
                    useBackgroundImage && backgroundImageUri != null -> Color.Transparent.toArgb()  // 有背景时透明
                    useCustomStatusBarColor && customStatusBarColorValue != null -> customStatusBarColorValue!!.toInt()
                    else -> colorScheme.primary.toArgb()
                }
                window.statusBarColor = statusBarColor
                insetsController?.isAppearanceLightStatusBars = !isColorLight(Color(statusBarColor))
            }

            // 设置导航栏颜色（底部小白条所在的区域）
            // 在有背景图片时，让导航栏透明
            if (useBackgroundImage && backgroundImageUri != null) {
                // 关键：禁用导航栏对比度强制模式（Android 10+）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                // 设置为完全透明
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                // 根据主题设置导航栏图标颜色
                insetsController?.isAppearanceLightNavigationBars = !darkTheme
            } else {
                // 没有背景时使用软件背景色作为导航栏背景色
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = true
                }
                window.navigationBarColor = colorScheme.background.toArgb()
                // 根据导航栏背景色动态设置导航栏图标颜色
                // isAppearanceLightNavigationBars = true 表示图标为深色（适用于浅色背景）
                // isAppearanceLightNavigationBars = false 表示图标为浅色（适用于深色背景）
                insetsController?.isAppearanceLightNavigationBars = !isColorLight(colorScheme.background)
            }
        }
    }

    // 视频播放器状态
    val exoPlayer =
        remember(
            useBackgroundImage,
            backgroundImageUri,
            backgroundMediaType,
            videoBackgroundLoop,
            videoBackgroundMuted
        ) {
            if (useBackgroundImage &&
                backgroundImageUri != null &&
                backgroundMediaType == UserPreferencesManager.MEDIA_TYPE_VIDEO
            ) {
                ExoPlayer.Builder(context)
                    // Add memory optimizations
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(
                                5000,  // 最小缓冲时间，减少到5秒
                                10000, // 最大缓冲时间，减少到10秒
                                500,   // 回放所需的最小缓冲
                                1000   // 重新缓冲后回放所需的最小缓冲
                            )
                            .setTargetBufferBytes(5 * 1024 * 1024) // 将缓冲限制为5MB
                            .setPrioritizeTimeOverSizeThresholds(true)
                            .build()
                    )
                    .build()
                    .apply {
                        // 设置循环播放
                        repeatMode =
                            if (videoBackgroundLoop) Player.REPEAT_MODE_ALL
                            else Player.REPEAT_MODE_OFF
                        // 设置静音
                        volume = if (videoBackgroundMuted) 0f else 1f
                        playWhenReady = true

                        // 加载视频
                        try {
                            val mediaItem = MediaItem.Builder()
                                .setUri(Uri.parse(backgroundImageUri))
                                .build()
                            setMediaItem(mediaItem)
                            prepare()
                        } catch (e: Exception) {
                            AppLogger.e(
                                "CustardTheme",
                                "Error loading video background: ${e.message}",
                                e
                            )
                            // Fallback to no background if video can't be loaded
                            coroutineScope.launch {
                                preferencesManager.saveThemeSettings(
                                    useBackgroundImage = false
                                )
                            }
                        }
                    }
            } else {
                null
            }
        }

    // 释放ExoPlayer资源
    DisposableEffect(key1 = Unit) {
        onDispose {
            try {
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                exoPlayer?.release()
            } catch (e: Exception) {
                AppLogger.e("CustardTheme", "ExoPlayer释放错误", e)
            }
        }
    }

    // 监听应用生命周期，控制视频播放
    if (exoPlayer != null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        exoPlayer.pause()
                    }

                    Lifecycle.Event.ON_RESUME -> {
                        exoPlayer.play()
                    }

                    else -> {}
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // 应用主题和自定义背景
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // First, create a solid barrier background to prevent system theme colors from showing
        // through
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        if (darkTheme) Color.Black else Color.White
                    ) // Solid barrier background
        )

        // 如果使用背景图片且URI不为空，则显示背景图片
        if (useBackgroundImage && backgroundImageUri != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                val uri = Uri.parse(backgroundImageUri)
                val coroutineScope = rememberCoroutineScope()

                // 根据媒体类型显示不同的背景
                if (backgroundMediaType == UserPreferencesManager.MEDIA_TYPE_IMAGE) {
                    // 显示背景图片
                    val painter =
                        rememberAsyncImagePainter(
                            model = uri,
                            error =
                                rememberAsyncImagePainter(
                                    if (darkTheme) Color.Black
                                    else Color.White // Use solid colors for
                                    // error fallback
                                )
                        )

                    // 监听图片加载失败时的逻辑
                    LaunchedEffect(painter) {
                        if (painter.state is AsyncImagePainter.State.Error) {
                            AppLogger.e(
                                "CustardTheme",
                                "Error loading background image from URI: $backgroundImageUri"
                            )

                            // Check if it's a file:// URI pointing to our internal storage
                            if (uri.scheme == "file") {
                                val file = uri.path?.let { File(it) }
                                if (file == null || !file.exists()) {
                                    AppLogger.e(
                                        "CustardTheme",
                                        "Internal file doesn't exist: ${file?.absolutePath}"
                                    )
                                } else {
                                    AppLogger.e(
                                        "CustardTheme",
                                        "File exists but couldn't be loaded: ${file.absolutePath}, size: ${file.length()}"
                                    )
                                }
                            }

                            coroutineScope.launch {
                                preferencesManager.saveThemeSettings(useBackgroundImage = false)
                            }
                        }
                    }

                    // 显示背景图片
                    Image(
                        painter = painter,
                        contentDescription = "Background Image",
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .alpha(backgroundImageOpacity) // 使用设置的不透明度
                                .then(
                                    if (useBackgroundBlur)
                                        Modifier.blur(radius = backgroundBlurRadius.dp)
                                    else Modifier
                                ),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // 显示背景视频
                    exoPlayer?.let { player ->
                        AndroidView(
                            factory = { ctx ->
                                StyledPlayerView(ctx).apply {
                                    this.player = player
                                    useController = false
                                    layoutParams =
                                        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                                    // Set scale type to fill the view without distortion
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    // Apply background color matching the current theme
                                    setBackgroundColor(
                                        if (darkTheme) android.graphics.Color.BLACK
                                        else android.graphics.Color.WHITE
                                    )
                                    // Create a semi-transparent overlay to control opacity
                                    foreground =
                                        android.graphics.drawable.ColorDrawable(
                                            android.graphics.Color.argb(
                                                ((1f - backgroundImageOpacity) *
                                                        255)
                                                    .toInt(),
                                                if (darkTheme) 0 else 255, // R: Black(0) or White(255)
                                                if (darkTheme) 0 else 255, // G: Black(0) or White(255)
                                                if (darkTheme) 0 else 255  // B: Black(0) or White(255)
                                            )
                                        )
                                }
                            },
                            update = { view ->
                                // Update player reference if needed
                                if (view.player != player) {
                                    view.player = player
                                }

                                // Update the foreground transparency when opacity changes
                                view.foreground =
                                    android.graphics.drawable.ColorDrawable(
                                        android.graphics.Color.argb(
                                            ((1f - backgroundImageOpacity) * 255)
                                                .toInt(),
                                            if (darkTheme) 0 else 255, // R: Black(0) or White(255)
                                            if (darkTheme) 0 else 255, // G: Black(0) or White(255)
                                            if (darkTheme) 0 else 255  // B: Black(0) or White(255)
                                        )
                                    )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 内容层 - Make sure it's not transparent
                MaterialTheme(
                    colorScheme =
                        colorScheme.copy(
                            // Make surfaces more opaque
                            surface = colorScheme.surface.copy(alpha = 1f),
                            surfaceVariant =
                                colorScheme.surfaceVariant.copy(alpha = 1f),
                            background = colorScheme.background.copy(alpha = 1f),
                            surfaceContainer =
                                colorScheme.surfaceContainer.copy(alpha = 1f),
                            surfaceContainerHigh =
                                colorScheme.surfaceContainerHigh.copy(alpha = 1f),
                            surfaceContainerHighest =
                                colorScheme.surfaceContainerHighest.copy(
                                    alpha = 1f
                                ),
                            surfaceContainerLow =
                                colorScheme.surfaceContainerLow.copy(alpha = 1f),
                            surfaceContainerLowest =
                                colorScheme.surfaceContainerLowest.copy(alpha = 1f)
                        ),
                    typography = customTypography,
                    content = content
                )
            }
        } else {
            // 不使用背景图片时，直接应用主题
            MaterialTheme(colorScheme = colorScheme, typography = customTypography, content = content)
        }
    }
}

/** 为亮色主题生成基于主色的完整颜色方案 */
private fun generateLightColorScheme(
    primaryColor: Color,
    secondaryColor: Color,
    onColorMode: String
): ColorScheme {
    val onPrimary = when (onColorMode) {
        ON_COLOR_MODE_LIGHT -> Color.White
        ON_COLOR_MODE_DARK -> Color.Black
        else -> getContrastingTextColor(primaryColor)
    }
    val onSecondary = when (onColorMode) {
        ON_COLOR_MODE_LIGHT -> Color.White
        ON_COLOR_MODE_DARK -> Color.Black
        else -> getContrastingTextColor(secondaryColor)
    }

    val primaryContainer = lightenColor(primaryColor, 0.7f)
    val onPrimaryContainer = getContrastingTextColor(primaryContainer)
    val secondaryContainer = lightenColor(secondaryColor, 0.7f)
    val onSecondaryContainer = getContrastingTextColor(secondaryContainer)

    // Return a complete color scheme, ensuring onSurface and onSurfaceVariant are consistent
    return LightColorScheme.copy(
        primary = primaryColor,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondaryColor,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        // Ensure other colors are consistent with a light theme
        onSurface = Color.Black,
        onSurfaceVariant = Color.Black.copy(alpha = 0.7f),
        onBackground = Color.Black
    )
}

/** 为暗色主题生成基于主色的完整颜色方案 */
private fun generateDarkColorScheme(
    primaryColor: Color,
    secondaryColor: Color,
    onColorMode: String
): ColorScheme {
    val adjustedPrimaryColor = lightenColor(primaryColor, 0.2f)
    val adjustedSecondaryColor = lightenColor(secondaryColor, 0.2f)

    val onPrimary = when (onColorMode) {
        ON_COLOR_MODE_LIGHT -> Color.White
        ON_COLOR_MODE_DARK -> Color.Black
        else -> getContrastingTextColor(adjustedPrimaryColor)
    }
    val onSecondary = when (onColorMode) {
        ON_COLOR_MODE_LIGHT -> Color.White
        ON_COLOR_MODE_DARK -> Color.Black
        else -> getContrastingTextColor(adjustedSecondaryColor)
    }

    val primaryContainer = darkenColor(primaryColor, 0.3f)
    val onPrimaryContainer = getContrastingTextColor(primaryContainer, forceLight = true)
    val secondaryContainer = darkenColor(secondaryColor, 0.3f)
    val onSecondaryContainer = getContrastingTextColor(secondaryContainer, forceLight = true)

    // Return a complete color scheme, ensuring onSurface and onSurfaceVariant are consistent
    return DarkColorScheme.copy(
        primary = adjustedPrimaryColor,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = adjustedSecondaryColor,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        // Ensure other colors are consistent with a dark theme
        onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.7f),
        onBackground = Color.White
    )
}

/** Add a new helper function to determine appropriate text color based on background color */
private fun getContrastingTextColor(
    backgroundColor: Color,
    forceDark: Boolean = false,
    forceLight: Boolean = false
): Color {
    // If forced, return the specified color
    if (forceDark) return Color.Black
    if (forceLight) return Color.White

    // Calculate color contrast and return appropriate color
    // Using luminance formula from Web Content Accessibility Guidelines (WCAG)
    val luminance =
        0.299 * backgroundColor.red +
                0.587 * backgroundColor.green +
                0.114 * backgroundColor.blue

    // Use a threshold of 0.5 for deciding between white and black text
    // Higher threshold (e.g., 0.6) would use white text more often
    return if (luminance > 0.5) Color.Black else Color.White
}

/** 使颜色变亮 */
private fun lightenColor(color: Color, factor: Float): Color {
    val r = color.red + (1f - color.red) * factor
    val g = color.green + (1f - color.green) * factor
    val b = color.blue + (1f - color.blue) * factor
    return Color(r, g, b, color.alpha)
}

/** 使颜色变暗 */
private fun darkenColor(color: Color, factor: Float): Color {
    val r = color.red * (1f - factor)
    val g = color.green * (1f - factor)
    val b = color.blue * (1f - factor)
    return Color(r, g, b, color.alpha)
}

/** 混合两种颜色 */
private fun blendColors(color1: Color, color2: Color, ratio: Float): Color {
    val r = color1.red * (1 - ratio) + color2.red * ratio
    val g = color1.green * (1 - ratio) + color2.green * ratio
    val b = color1.blue * (1 - ratio) + color2.blue * ratio
    return Color(r, g, b)
}

/** 判断颜色是否较浅 */
private fun isColorLight(color: Color): Boolean {
    // 计算颜色亮度 (0.0-1.0)
    val luminance = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
    return luminance > 0.5
}

/** 判断颜色是否较深 */
private fun isColorDark(color: Color): Boolean {
    return !isColorLight(color)
}
