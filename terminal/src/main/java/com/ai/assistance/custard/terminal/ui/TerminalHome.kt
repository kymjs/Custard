package com.ai.assistance.custard.terminal.ui

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.custard.terminal.data.CommandHistoryItem
import com.ai.assistance.custard.terminal.data.TerminalSessionData
import com.ai.assistance.custard.terminal.view.canvas.CanvasTerminalOutput
import com.ai.assistance.custard.terminal.view.canvas.CanvasTerminalScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.assistance.custard.terminal.TerminalEnv
import com.ai.assistance.custard.terminal.utils.VirtualKeyAction
import com.ai.assistance.custard.terminal.utils.VirtualKeyboardButtonConfig
import com.ai.assistance.custard.terminal.utils.VirtualKeyboardConfigManager
import com.ai.assistance.custard.terminal.view.SyntaxColors
import com.ai.assistance.custard.terminal.view.SyntaxHighlightingVisualTransformation
import com.ai.assistance.custard.terminal.view.highlight
import androidx.compose.material.icons.filled.Settings
import com.ai.assistance.custard.terminal.view.canvas.RenderConfig
import com.ai.assistance.custard.terminal.utils.TerminalFontConfigManager
import android.graphics.Typeface
import android.view.inputmethod.InputMethodManager
import java.io.File

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalHome(
    env: TerminalEnv,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val rootView = LocalView.current
    val fontConfigManager = remember { TerminalFontConfigManager.getInstance(context) }
    val virtualKeyboardConfigManager = remember { VirtualKeyboardConfigManager.getInstance(context) }
    
    // 字体配置状态
    var fontConfig by remember { 
        mutableStateOf(fontConfigManager.loadRenderConfig())
    }
    var virtualKeyboardLayout by remember {
        mutableStateOf(virtualKeyboardConfigManager.loadLayout())
    }
    
    // 监听字体配置变化（当从设置界面返回时）
    LaunchedEffect(Unit) {
        // 每次进入时重新读取配置
        fontConfig = fontConfigManager.loadRenderConfig()
    }
    
    // 当组件重新组合时，检查配置是否变化并更新
    DisposableEffect(Unit) {
        val newConfig = fontConfigManager.loadRenderConfig()
        
        if (fontConfig != newConfig) {
            fontConfig = newConfig
        }
        
        onDispose { }
    }

    DisposableEffect(context, virtualKeyboardConfigManager) {
        val settingsPrefs =
            context.getSharedPreferences(VirtualKeyboardConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == VirtualKeyboardConfigManager.PREF_KEY_VIRTUAL_KEYBOARD_LAYOUT) {
                virtualKeyboardLayout = virtualKeyboardConfigManager.loadLayout()
            }
        }
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 命令输入框焦点控制
    val inputFocusRequester = remember { FocusRequester() }
    var pendingShowIme by remember { mutableStateOf(false) }

    LaunchedEffect(pendingShowIme) {
        if (pendingShowIme) {
            pendingShowIme = false
            // 模仿全屏模式，在焦点切换后稍微延迟再请求输入法
            delay(100)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(rootView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // 语法高亮
    val visualTransformation = remember { SyntaxHighlightingVisualTransformation() }

    // 缩放状态
    var scaleFactor by remember { mutableStateOf(1f) }

    // 删除确认弹窗状态
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }
    
    // 非全屏模式下虚拟键盘显示状态
    var showVirtualKeyboard by remember { mutableStateOf(false) }
    var isDirectInputMode by remember { mutableStateOf(false) }

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    fun applyCtrlModifierChar(ch: Char): String? {
        return when {
            ch in 'a'..'z' || ch in 'A'..'Z' -> {
                val code = ch.uppercaseChar().code - 64
                code.toChar().toString()
            }
            ch == ' ' || ch == '@' -> "\u0000"
            ch == '[' -> "\u001b"
            ch == '\\' -> "\u001c"
            ch == ']' -> "\u001d"
            ch == '^' -> "\u001e"
            ch == '_' -> "\u001f"
            else -> null
        }
    }

    fun applyCtrlModifierToText(input: String): String {
        if (!ctrlActive) return input
        val sb = StringBuilder()
        input.forEach { ch ->
            val mapped = applyCtrlModifierChar(ch)
            if (mapped != null) sb.append(mapped) else sb.append(ch)
        }
        return sb.toString()
    }

    fun applyModifiers(input: String): String {
        var output = applyCtrlModifierToText(input)
        if (altActive) {
            output = if (output.startsWith("\u001b")) output else "\u001b$output"
        }
        return output
    }

    fun consumeModifiers() {
        if (ctrlActive) ctrlActive = false
        if (altActive) altActive = false
    }

    fun decodeVirtualKeyValue(rawValue: String): String {
        val output = StringBuilder()
        var index = 0

        while (index < rawValue.length) {
            val char = rawValue[index]
            if (char == '\\' && index + 1 < rawValue.length) {
                val next = rawValue[index + 1]
                when (next) {
                    'e' -> output.append('\u001b')
                    't' -> output.append('\t')
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    '\\' -> output.append('\\')
                    else -> {
                        output.append('\\')
                        output.append(next)
                    }
                }
                index += 2
                continue
            }

            output.append(char)
            index++
        }

        return output.toString()
    }

    fun sendDirectInput(input: String) {
        val output = if (ctrlActive || altActive) applyModifiers(input) else input
        env.onSendInput(output, false)
        if (ctrlActive || altActive) {
            consumeModifiers()
        }
    }

    fun sendCommandFromInput() {
        val commandText = env.command
        if (ctrlActive || altActive) {
            val output = applyModifiers(commandText)
            env.onSendInput(output, false)
            env.onCommandChange("")
            consumeModifiers()
        } else {
            env.onSendInput(commandText, true)
        }
    }

    fun toggleDirectInputMode() {
        isDirectInputMode = !isDirectInputMode
        if (isDirectInputMode) {
            // 进入直接输入模式：展开虚拟键盘，清空命令并收起系统键盘
            showVirtualKeyboard = true
            env.onCommandChange("")
            keyboardController?.hide()
        } else {
            // 退出直接输入模式：关闭虚拟键盘面板并恢复系统键盘
            showVirtualKeyboard = false
            keyboardController?.show()
        }
    }

    // 计算基于缩放因子的字体大小和间距
    val baseFontSize = 14.sp
    val fontSize = with(LocalDensity.current) {
        (baseFontSize.toPx() * scaleFactor).toSp()
    }
    val baseLineHeight = 1.2f
    val lineHeight = baseLineHeight * scaleFactor
    val basePadding = 8.dp
    val padding = basePadding * scaleFactor

    // 获取当前 session 的 PTY
    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 会话标签页
        SessionTabBar(
            sessions = env.sessions,
            currentSessionId = env.currentSessionId,
            onSessionClick = env::onSwitchSession,
            onNewSession = env::onNewSession,
            onCloseSession = { sessionId ->
                sessionToDelete = sessionId
                showDeleteConfirmDialog = true
            }
        )

        if (env.isFullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding() // 让整个列随软键盘上移
            ) {
                // 终端输出区域
                CanvasTerminalScreen(
                    emulator = env.terminalEmulator,
                    modifier = Modifier.weight(1f),
                    config = fontConfig,
                    pty = currentPty,
                    onInput = { sendDirectInput(it) },
                    sessionId = env.currentSessionId,
                    onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                    getScrollOffset = { id -> env.getScrollOffset(id) }
                )
                
                // 虚拟键盘 - 会随 imePadding 一起上移
                VirtualKeyboard(
                    onKeyPress = { key -> sendDirectInput(decodeVirtualKeyValue(key)) },
                    onToggleCtrl = { ctrlActive = !ctrlActive },
                    onToggleAlt = { altActive = !altActive },
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    keyRows = virtualKeyboardLayout.rows,
                    fontSize = fontSize * 0.7f,
                    padding = padding * 0.5f
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        .navigationBarsPadding()
            ) {
                // Canvas输出区域（占满剩余空间）
                if (isDirectInputMode) {
                    // 直接输入模式：使用与全屏相同的 CanvasTerminalScreen，点击画布时由 CanvasTerminalView 自己弹出输入法
                    CanvasTerminalScreen(
                        emulator = env.terminalEmulator,
                        modifier = Modifier.weight(1f),
                        config = fontConfig,
                        pty = currentPty,
                        onInput = { sendDirectInput(it) },
                        sessionId = env.currentSessionId,
                        onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                        getScrollOffset = { id -> env.getScrollOffset(id) }
                    )
                } else {
                    // 普通命令模式：只显示输出，点击画布时把焦点切到命令输入框并弹出输入法
                    CanvasTerminalOutput(
                        emulator = env.terminalEmulator,
                        modifier = Modifier.weight(1f),
                        config = fontConfig,
                        pty = currentPty,
                        onRequestShowKeyboard = {
                            inputFocusRequester.requestFocus()
                            pendingShowIme = true
                        },
                        sessionId = env.currentSessionId,
                        onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                        getScrollOffset = { id -> env.getScrollOffset(id) }
                    )
                }
                
                // 终端工具栏
                TerminalToolbar(
                    onInterrupt = env::onInterrupt,
                    onSendCommand = { env.onSendInput(it, true) },
                    fontSize = fontSize * 0.8f,
                    padding = padding,
                    onNavigateToSetup = onNavigateToSetup,
                    onNavigateToSettings = onNavigateToSettings,
                    isDirectInputMode = isDirectInputMode,
                    showVirtualKeyboard = showVirtualKeyboard,
                    onToggleVirtualKeyboard = { showVirtualKeyboard = !showVirtualKeyboard },
                    onToggleInputMode = { toggleDirectInputMode() }
                )

                // 当前输入行：直接输入映射模式下隐藏（按图示仅保留工具栏右侧快捷按钮）
                if (!isDirectInputMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(padding),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.padding(end = padding * 0.5f),
                            color = Color(0xFF006400), // DarkGreen
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = getTruncatedPrompt(env.currentDirectory.ifEmpty { "$ " }),
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                modifier = Modifier.padding(horizontal = padding * 0.5f, vertical = padding * 0.1f)
                            )
                        }
                        BasicTextField(
                            value = env.command,
                            onValueChange = env::onCommandChange,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(inputFocusRequester),
                            enabled = true,
                            textStyle = TextStyle(
                                color = SyntaxColors.commandDefault,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize
                            ),
                            cursorBrush = SolidColor(Color.Green),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                sendCommandFromInput()
                            })
                        )
                        // 虚拟键盘切换按钮
                        Surface(
                            modifier = Modifier
                                .padding(start = padding * 0.5f)
                                .clickable { showVirtualKeyboard = !showVirtualKeyboard },
                            color = if (showVirtualKeyboard) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "⌨",
                                color = Color.White,
                                fontFamily = FontFamily.Default,
                                fontSize = fontSize * 1.2f,
                                modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .padding(start = padding * 0.5f)
                                .clickable { toggleDirectInputMode() },
                            color = if (isDirectInputMode) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "⇄",
                                color = Color.White,
                                fontFamily = FontFamily.Default,
                                fontSize = fontSize * 1.1f,
                                modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                            )
                        }
                    }
                }
                
                // 虚拟键盘（当显示时）
                if (showVirtualKeyboard) {
                    VirtualKeyboard(
                        onKeyPress = { key -> sendDirectInput(decodeVirtualKeyValue(key)) },
                        onToggleCtrl = { ctrlActive = !ctrlActive },
                        onToggleAlt = { altActive = !altActive },
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        keyRows = virtualKeyboardLayout.rows,
                        fontSize = fontSize * 0.7f,
                        padding = padding * 0.5f
                    )
                }
            }
        }
    }

    // 删除确认弹窗
    if (showDeleteConfirmDialog && sessionToDelete != null) {
        val context = LocalContext.current
        val sessionTitle = env.sessions.find { it.id == sessionToDelete }?.title ?: context.getString(com.ai.assistance.custard.terminal.R.string.unknown_session)

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                sessionToDelete = null
            },
            title = {
                Text(
                    text = context.getString(com.ai.assistance.custard.terminal.R.string.confirm_delete_session),
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = context.getString(com.ai.assistance.custard.terminal.R.string.delete_session_message, sessionTitle),
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete?.let { sessionId ->
                            env.onCloseSession(sessionId)
                        }
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.custard.terminal.R.string.delete),
                        color = Color.Red
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.custard.terminal.R.string.cancel),
                        color = Color.White
                    )
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }
}

private fun getTruncatedPrompt(prompt: String, maxLength: Int = 16): String {
    val trimmed = prompt.trimEnd()
    return if (trimmed.length > maxLength) {
        "..." + trimmed.takeLast(maxLength - 3)
    } else {
        trimmed
    }
}

@Composable
private fun SessionTabBar(
    sessions: List<TerminalSessionData>,
    currentSessionId: String?,
    onSessionClick: (String) -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF2D2D2D),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 会话标签页列表
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(sessions) { session ->
                    SessionTab(
                        session = session,
                        isActive = session.id == currentSessionId,
                        onClick = { onSessionClick(session.id) },
                        onClose = if (sessions.size > 1) {
                            { onCloseSession(session.id) }
                        } else null
                    )
                }
            }

            // 新建会话按钮
            IconButton(
                onClick = onNewSession,
                modifier = Modifier.size(32.dp)
            ) {
                val context = LocalContext.current
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = context.getString(com.ai.assistance.custard.terminal.R.string.new_session),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionTab(
    session: TerminalSessionData,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = if (isActive) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = session.title,
                color = if (isActive) Color.White else Color.Gray,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 关闭按钮（只有多个会话时才显示）
            onClose?.let { closeAction ->
                val context = LocalContext.current
                IconButton(
                    onClick = closeAction,
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = context.getString(com.ai.assistance.custard.terminal.R.string.close_session),
                        tint = Color.Gray,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalToolbar(
    onInterrupt: () -> Unit,
    onSendCommand: (String) -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit,
    isDirectInputMode: Boolean,
    showVirtualKeyboard: Boolean,
    onToggleVirtualKeyboard: () -> Unit,
    onToggleInputMode: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            horizontalArrangement = Arrangement.spacedBy(padding * 0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isDirectInputMode) {
                // Ctrl+C 中断按钮
                Surface(
                    modifier = Modifier.clickable { onInterrupt() },
                    color = Color(0xFF4A4A4A),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(padding * 0.3f)
                    ) {
                        Text(
                            text = "Ctrl+C",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = context.getString(com.ai.assistance.custard.terminal.R.string.interrupt),
                            color = Color.Gray,
                            fontFamily = FontFamily.Default,
                            fontSize = fontSize * 0.9f
                        )
                    }
                }

                // 分隔线
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(padding * 1.5f)
                        .background(Color(0xFF3A3A3A))
                )
            }

            Spacer(Modifier.weight(1f))

            // 环境配置按钮
            Surface(
                modifier = Modifier.clickable { onNavigateToSetup() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.custard.terminal.R.string.environment_setup),
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (isDirectInputMode) {
                Surface(
                    modifier = Modifier.clickable { onToggleVirtualKeyboard() },
                    color = if (showVirtualKeyboard) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "⌨",
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize * 1.15f,
                        modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.35f)
                    )
                }
                Surface(
                    modifier = Modifier.clickable { onToggleInputMode() },
                    color = Color(0xFF4A4A4A),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "⇄",
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize * 1.05f,
                        modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                    )
                }
            }

            // 设置按钮
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = context.getString(com.ai.assistance.custard.terminal.R.string.settings),
                tint = Color.Gray,
                modifier = Modifier
                    .clickable { onNavigateToSettings() }
                    .padding(start = padding)
                    .size(padding * 2.5f)
            )
        }
    }
}

@Composable
private fun VirtualKeyboard(
    onKeyPress: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    keyRows: List<List<VirtualKeyboardButtonConfig>>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            verticalArrangement = Arrangement.spacedBy(padding * 0.5f)
        ) {
            keyRows.forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
                ) {
                    rowKeys.forEach { keyConfig ->
                        val isActive = when (keyConfig.action) {
                            VirtualKeyAction.TOGGLE_CTRL -> ctrlActive
                            VirtualKeyAction.TOGGLE_ALT -> altActive
                            VirtualKeyAction.SEND_TEXT -> false
                        }
                        val clickOverride = when (keyConfig.action) {
                            VirtualKeyAction.TOGGLE_CTRL -> onToggleCtrl
                            VirtualKeyAction.TOGGLE_ALT -> onToggleAlt
                            VirtualKeyAction.SEND_TEXT -> null
                        }

                        KeyButton(
                            label = keyConfig.label,
                            key = keyConfig.value,
                            fontSize = fontSize,
                            padding = padding,
                            onKeyPress = onKeyPress,
                            modifier = Modifier.weight(1f),
                            isActive = isActive,
                            onClickOverride = clickOverride
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    key: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onClickOverride: (() -> Unit)? = null
) {
    val backgroundColor = if (isActive) Color(0xFF2563EB) else Color(0xFF3A3A3A)
    Surface(
        modifier = modifier
            .clickable { onClickOverride?.invoke() ?: onKeyPress(key) },
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = padding * 0.5f, vertical = padding * 0.8f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
} 
