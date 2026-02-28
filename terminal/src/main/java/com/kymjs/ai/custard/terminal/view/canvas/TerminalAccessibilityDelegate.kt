package com.kymjs.ai.custard.terminal.view.canvas

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.kymjs.ai.custard.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.kymjs.ai.custard.terminal.view.domain.ansi.TerminalChar

/**
 * 为终端视图提供无障碍支持
 * 将终端内容按行暴露给屏幕阅读器，支持逐行朗读
 */
class TerminalAccessibilityDelegate(
    private val view: CanvasTerminalView,
    private val getEmulator: () -> AnsiTerminalEmulator?,
    private val getTextMetrics: () -> TextMetrics,
    private val getScrollOffsetY: () -> Float
) : View.AccessibilityDelegate() {

    private val nodeProvider = TerminalAccessibilityNodeProvider()

    private fun isAccessibilityEnabled(): Boolean {
        val manager = view.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return manager?.isEnabled == true
    }

    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProvider {
        return nodeProvider
    }

    /**
     * 通知无障碍服务终端内容已更新
     */
    fun notifyContentChanged() {
        view.post {
            if (!isAccessibilityEnabled()) return@post
            try {
                view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            } catch (_: IllegalStateException) {
            }
        }
    }

    /**
     * 为终端的每一行创建虚拟无障碍节点
     */
    private inner class TerminalAccessibilityNodeProvider : AccessibilityNodeProvider() {

        // 常量定义（内部类不能有companion object）
        private val HOST_VIEW_ID = -1
        private val VIRTUAL_NODE_ID_BASE = 1000
        
        // 跟踪当前的无障碍焦点
        private var currentAccessibilityFocusedVirtualViewId: Int = -1

        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            val emulator = getEmulator() ?: return null
            
            return when (virtualViewId) {
                HOST_VIEW_ID -> createHostNodeInfo()
                else -> createVirtualNodeInfo(virtualViewId, emulator)
            }
        }

        /**
         * 创建主视图的无障碍节点信息
         */
        private fun createHostNodeInfo(): AccessibilityNodeInfo {
            val info = AccessibilityNodeInfo.obtain(view)
            view.onInitializeAccessibilityNodeInfo(info)
            
            info.className = CanvasTerminalView::class.java.name
            
            // 关键：让主视图完全不可访问，只作为虚拟节点的容器
            // 不设置 contentDescription，避免被选中
            info.isFocusable = false
            info.isAccessibilityFocused = false
            info.isClickable = false
            info.isLongClickable = false
            info.isEnabled = true
            
            // 添加所有可见行作为子节点
            val emulator = getEmulator()
            if (emulator != null) {
                val screenContent = emulator.getScreenContent()
                val visibleLines = screenContent.size
                
                for (i in 0 until visibleLines) {
                    info.addChild(view, VIRTUAL_NODE_ID_BASE + i)
                }
            }
            
            return info
        }

        /**
         * 创建单行文本的虚拟无障碍节点
         */
        private fun createVirtualNodeInfo(
            virtualViewId: Int,
            emulator: AnsiTerminalEmulator
        ): AccessibilityNodeInfo? {
            val lineIndex = virtualViewId - VIRTUAL_NODE_ID_BASE
            val screenContent = emulator.getScreenContent()
            
            if (lineIndex < 0 || lineIndex >= screenContent.size) {
                return null
            }
            
            val info = AccessibilityNodeInfo.obtain(view, virtualViewId)
            info.setParent(view)
            info.className = "android.widget.TextView"
            info.packageName = view.context.packageName
            
            // 获取该行的文本内容
            val lineContent = getLineText(screenContent[lineIndex])
            info.text = lineContent
            info.contentDescription = "第${lineIndex + 1}行: $lineContent"
            
            // 设置节点的屏幕位置
            val bounds = getLineBounds(lineIndex)
            info.setBoundsInParent(bounds)
            
            val screenBounds = Rect(bounds)
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            screenBounds.offset(location[0], location[1])
            info.setBoundsInScreen(screenBounds)
            
            // 只有在视图可见范围内的行才标记为可见
            val isVisible = bounds.top >= 0 && bounds.top < view.height
            info.isVisibleToUser = isVisible
            info.isEnabled = true
            info.isFocusable = true
            
            // 设置当前是否有无障碍焦点
            info.isAccessibilityFocused = (virtualViewId == currentAccessibilityFocusedVirtualViewId)
            
            // 支持的操作
            info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            info.addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            
            return info
        }

        /**
         * 将TerminalChar数组转换为可读文本
         */
        private fun getLineText(line: Array<TerminalChar>): String {
            val sb = StringBuilder()
            for (terminalChar in line) {
                if (terminalChar.char != '\u0000' && terminalChar.char != ' ') {
                    sb.append(terminalChar.char)
                } else if (terminalChar.char == ' ') {
                    sb.append(' ')
                }
            }
            // 移除尾部空格
            return sb.toString().trimEnd()
        }

        /**
         * 计算某一行在屏幕上的边界
         * 考虑滚动偏移，与终端渲染保持一致
         */
        private fun getLineBounds(lineIndex: Int): Rect {
            val emulator = getEmulator()
            val metrics = getTextMetrics()
            val charHeight = metrics.charHeight
            val scrollOffset = getScrollOffsetY()
            
            // lineIndex 是相对于屏幕内容的索引
            // 需要转换为绝对行号（包括历史记录）
            val historySize = emulator?.getHistorySize() ?: 0
            val absoluteRow = historySize + lineIndex
            
            // 计算实际显示位置（与渲染逻辑一致）
            val exactY = absoluteRow * charHeight - scrollOffset
            val top = exactY.toInt()
            val bottom = (exactY + charHeight).toInt()
            
            return Rect(
                0,
                top,
                view.width,
                bottom
            )
        }

        override fun performAction(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (virtualViewId == HOST_VIEW_ID) {
                        // 不允许主视图获得焦点
                        return false
                    }
                    
                    // 清除旧焦点
                    if (currentAccessibilityFocusedVirtualViewId != -1) {
                        val oldFocusedId = currentAccessibilityFocusedVirtualViewId
                        currentAccessibilityFocusedVirtualViewId = -1
                        sendEventForVirtualView(oldFocusedId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    }
                    
                    // 设置新焦点
                    currentAccessibilityFocusedVirtualViewId = virtualViewId
                    sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    return true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (virtualViewId == currentAccessibilityFocusedVirtualViewId) {
                        currentAccessibilityFocusedVirtualViewId = -1
                        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                        return true
                    }
                    return false
                }
            }
            return false
        }

        /**
         * 为虚拟视图发送无障碍事件
         */
        private fun sendEventForVirtualView(virtualViewId: Int, eventType: Int) {
            if (!isAccessibilityEnabled()) {
                return
            }

            val event = AccessibilityEvent.obtain(eventType)
            event.packageName = view.context.packageName
            event.className = "android.widget.TextView"
            
            // 设置事件源
            event.setSource(view, virtualViewId)
            
            try {
                view.parent?.requestSendAccessibilityEvent(view, event)
            } catch (_: IllegalStateException) {
            }
        }
        
        /**
         * 查找当前焦点
         */
        override fun findFocus(focus: Int): AccessibilityNodeInfo? {
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
                if (currentAccessibilityFocusedVirtualViewId != -1) {
                    return createAccessibilityNodeInfo(currentAccessibilityFocusedVirtualViewId)
                }
            }
            return null
        }
        
        /**
         * 根据触摸位置找到对应的虚拟节点（用于触摸探索）
         */
        override fun findAccessibilityNodeInfosByText(
            text: String?,
            virtualViewId: Int
        ): List<AccessibilityNodeInfo>? {
            // 这个方法虽然名字是findByText，但也会被触摸探索调用
            return null
        }
        
        /**
         * 根据坐标查找虚拟节点ID（支持触摸探索）
         * 考虑滚动偏移，与CanvasTerminalView.screenToTerminalCoords保持一致
         */
        fun findVirtualViewAt(x: Float, y: Float): Int {
            val emulator = getEmulator() ?: return HOST_VIEW_ID
            val fullContent = emulator.getFullContent() // 使用完整内容（包括历史）
            val metrics = getTextMetrics()
            val charHeight = metrics.charHeight
            val scrollOffset = getScrollOffsetY()
            
            // 计算点击位置对应的行号（考虑滚动偏移）
            val absoluteRow = ((y + scrollOffset) / charHeight).toInt()
            
            // 转换为屏幕可见区域内的相对行号
            val screenContent = emulator.getScreenContent()
            val historySize = emulator.getHistorySize()
            val visibleStartRow = (scrollOffset / charHeight).toInt()
            val lineIndex = absoluteRow - historySize
            
            // 检查是否在屏幕可见范围内
            if (lineIndex in 0 until screenContent.size) {
                return VIRTUAL_NODE_ID_BASE + lineIndex
            }
            
            return HOST_VIEW_ID
        }
        
        /**
         * 清除所有焦点
         */
        fun clearAccessibilityFocus() {
            if (currentAccessibilityFocusedVirtualViewId != -1) {
                performAction(
                    currentAccessibilityFocusedVirtualViewId,
                    AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
                    null
                )
            }
        }
    }
    
    /**
     * 根据坐标查找虚拟节点（暴露给View使用）
     */
    fun findVirtualViewAt(x: Float, y: Float): Int {
        return nodeProvider.findVirtualViewAt(x, y)
    }
    
    /**
     * 清除焦点
     */
    fun clearAccessibilityFocus() {
        nodeProvider.clearAccessibilityFocus()
    }
}
