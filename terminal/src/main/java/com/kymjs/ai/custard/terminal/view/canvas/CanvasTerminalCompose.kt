package com.kymjs.ai.custard.terminal.view.canvas

import android.view.MotionEvent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kymjs.ai.custard.terminal.view.domain.ansi.AnsiTerminalEmulator

/**
 * Compose集成桥接
 * 将CanvasTerminalView包装为Compose组件
 */
@Composable
fun CanvasTerminalScreen(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    pty: com.kymjs.ai.custard.terminal.Pty? = null,
    onInput: (String) -> Unit = {},
    onScaleChanged: (Float) -> Unit = {},
    sessionId: String? = null,
    onScrollOffsetChanged: ((String, Float) -> Unit)? = null,
    getScrollOffset: ((String) -> Float)? = null
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                setConfig(config)
                setEmulator(emulator)
                setPty(pty)
                setInputCallback(onInput)
                setScaleCallback(onScaleChanged)
                setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
                
                // 全屏模式下自动请求焦点
                post {
                    requestFocus()
                }
                
                // 请求父容器不要拦截触摸事件，让终端视图处理滚动和缩放手势
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 返回 false 让 View 继续处理事件
                }
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
            view.setPty(pty)
            view.setInputCallback(onInput)
            view.setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
        },
        onRelease = { view ->
            // 在视图被移除时释放资源，避免持有渲染线程和监听器引用
            view.release()
        },
        modifier = modifier
    )
}

/**
 * 带配置的Canvas终端视图
 */
@Composable
fun ConfigurableCanvasTerminal(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    backgroundColor: Int = 0xFF000000.toInt(),
    foregroundColor: Int = 0xFFFFFFFF.toInt(),
    cursorColor: Int = 0xFF00FF00.toInt(),
    onInput: (String) -> Unit = {}
) {
    val config = remember(fontSize, backgroundColor, foregroundColor, cursorColor) {
        RenderConfig(
            fontSize = fontSize,
            backgroundColor = backgroundColor,
            foregroundColor = foregroundColor,
            cursorColor = cursorColor
        )
    }
    
    var currentScale by remember { mutableStateOf(1f) }
    
    CanvasTerminalScreen(
        emulator = emulator,
        modifier = modifier,
        config = config,
        onInput = onInput,
        onScaleChanged = { scale -> currentScale = scale }
    )
}

/**
 * 性能监控版本的Canvas终端
 */
@Composable
fun PerformanceMonitoredTerminal(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    onInput: (String) -> Unit = {},
    onFpsUpdate: (Float) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                setConfig(config)
                setEmulator(emulator)
                setInputCallback(onInput)
                setPerformanceCallback { fps: Float, frameTime: Long ->
                    onFpsUpdate(fps)
                }
                
                // 请求父容器不要拦截触摸事件，让终端视图处理滚动和缩放手势
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 返回 false 让 View 继续处理事件
                }
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
        },
        onRelease = { view ->
            // 在视图被移除时释放资源，避免持有渲染线程和监听器引用
            view.release()
        },
        modifier = modifier
    )
}

/**
 * 非全屏Canvas终端输出
 * 仅用于显示终端输出，不处理输入
 */
@Composable
fun CanvasTerminalOutput(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    pty: com.kymjs.ai.custard.terminal.Pty? = null,
    onRequestShowKeyboard: (() -> Unit)? = null,
    sessionId: String? = null,
    onScrollOffsetChanged: ((String, Float) -> Unit)? = null,
    getScrollOffset: ((String) -> Float)? = null
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                setConfig(config)
                setEmulator(emulator)
                setPty(pty)
                setFullscreenMode(false) // 关键：设置为非全屏模式
                setOnRequestShowKeyboard(onRequestShowKeyboard)
                setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
                
                // 请求父容器不要拦截触摸事件，让终端视图处理滚动手势
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // 返回 false 让 View 继续处理事件
                }
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
            view.setPty(pty)
            view.setOnRequestShowKeyboard(onRequestShowKeyboard)
            view.setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
        },
        onRelease = { view ->
            // 在视图被移除时释放资源，避免持有渲染线程和监听器引用
            view.release()
        },
        modifier = modifier
    )
}
