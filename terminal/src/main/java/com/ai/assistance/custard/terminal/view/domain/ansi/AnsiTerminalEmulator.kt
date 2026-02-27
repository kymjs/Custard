package com.ai.assistance.custard.terminal.view.domain.ansi

import android.graphics.Color
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 终端字符数据
 */
data class TerminalChar(
    val char: Char = ' ',
    val attributes: TextAttributes = TextAttributes()
) {
    constructor(
        char: Char,
        fgColor: Int,
        bgColor: Int,
        isBold: Boolean = false,
        isDim: Boolean = false,
        isItalic: Boolean = false,
        isUnderline: Boolean = false,
        isBlinking: Boolean = false,
        isInverse: Boolean = false,
        isHidden: Boolean = false,
        isStrikethrough: Boolean = false
    ) : this(
        char,
        TextAttributes(
            fgColor, bgColor, isBold, isDim, isItalic,
            isUnderline, isBlinking, isInverse, isHidden, isStrikethrough
        )
    )
    
    // 兼容旧 API
    val fgColor: Int get() = attributes.fgColor
    val bgColor: Int get() = attributes.bgColor
    val isBold: Boolean get() = attributes.isBold
    val isDim: Boolean get() = attributes.isDim
    val isItalic: Boolean get() = attributes.isItalic
    val isUnderline: Boolean get() = attributes.isUnderline
    val isBlinking: Boolean get() = attributes.isBlinking
    val isInverse: Boolean get() = attributes.isInverse
    val isHidden: Boolean get() = attributes.isHidden
    val isStrikethrough: Boolean get() = attributes.isStrikethrough
}

/**
 * ANSI 终端模拟器
 * 完整实现 VT100/xterm 终端模拟
 */
class AnsiTerminalEmulator(
    private var screenWidth: Int = 40,
    private var screenHeight: Int = 60,
    private val historySize: Int = 200 // 历史缓冲区行数
) {
    companion object {
        private const val TAG = "AnsiTerminalEmulator"
        private const val TAB_SIZE = 8
    }
    
    // 屏幕缓冲区
    private var screenBuffer: Array<Array<TerminalChar>> =
        Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
    
    // 行元数据：标记每一行是否是软换行（因宽度限制自动换行，而非真正的\n）
    private var lineWrapped: BooleanArray = BooleanArray(screenHeight) { false }
    
    // 历史缓冲区（用于滚动回溯）
    private val historyBuffer: MutableList<Array<TerminalChar>> = mutableListOf()
    private val historyWrapped: MutableList<Boolean> = mutableListOf()
    
    // 备用屏幕缓冲区（用于全屏应用如 vim）
    private var altScreenBuffer: Array<Array<TerminalChar>>? = null
    private var isAltScreenActive = false
    
    // 光标位置
    private var cursorX: Int = 0
    private var cursorY: Int = 0
    
    // 当前文本属性
    private var currentAttributes = TextAttributes()
    
    // 保存的光标状态（用于 DECSC/DECRC）
    private var savedCursorX: Int = 0
    private var savedCursorY: Int = 0
    private var savedAttributes = TextAttributes()
    
    // 终端模式标志
    private val terminalModes = mutableMapOf<Int, Boolean>()
    private var cursorVisible = true
    private var autoWrapMode = true
    private var originMode = false
    
    // 滚动区域（0-based, inclusive）
    private var scrollTop = 0
    private var scrollBottom = screenHeight - 1
    
    // 观察者模式：监听终端变化
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()
    
    // 监听新输出（用于自动滚动到底部）
    private val newOutputListeners = CopyOnWriteArrayList<() -> Unit>()
    
    // 缓存完整内容的视图（避免每次创建新列表）
    private val fullContentView = FullContentView()
    
    /**
     * 解析并执行 ANSI 序列
     */
    fun parse(text: String) {
        val scanner = AnsiScanner(text)
        
        while (scanner.hasNext()) {
            when (val seq = scanner.scanNext()) {
                is AnsiSequence.Text -> handleText(seq.char)
                is AnsiSequence.ControlChar -> handleControlChar(seq)
                is AnsiSequence.CSI -> handleCSI(seq)
                is AnsiSequence.OSC -> handleOSC(seq)
                is AnsiSequence.SingleEscape -> handleSingleEscape(seq)
                is AnsiSequence.DCS -> handleDCS(seq)
                is AnsiSequence.Unknown -> {
                    Log.w(TAG, "Unknown sequence: ${seq.raw}")
                }
                null -> break
            }
        }
        
        // 通知监听器内容已改变
        notifyChange()
        // 通知新输出监听器
        notifyNewOutput()
    }
    
    /**
     * 处理普通文本字符
     */
    private fun handleText(char: Char) {
        // 检查是否需要自动换行
        if (cursorX >= screenWidth) {
            if (autoWrapMode) {
                // 标记当前行为软换行
                if (cursorY >= 0 && cursorY < screenHeight) {
                    lineWrapped[cursorY] = true
                }
                cursorX = 0
                cursorY++
                if (cursorY > scrollBottom) {
                    cursorY = scrollBottom
                    scrollUp(1)
                }
            } else {
                cursorX = screenWidth - 1
            }
        }
        
        // 写入字符
        if (cursorY < screenHeight && cursorX < screenWidth) {
            screenBuffer[cursorY][cursorX] = TerminalChar(char, currentAttributes)
            cursorX++
        }
    }
    
    /**
     * 处理控制字符
     */
    private fun handleControlChar(seq: AnsiSequence.ControlChar) {
        when (seq.type) {
            ControlCharType.BELL -> {
                // 响铃 - 可以触发回调通知
                Log.d(TAG, "Bell")
            }
            ControlCharType.BACKSPACE -> {
                cursorX = (cursorX - 1).coerceAtLeast(0)
            }
            ControlCharType.TAB -> {
                val nextTabStop = ((cursorX / TAB_SIZE) + 1) * TAB_SIZE
                cursorX = nextTabStop.coerceAtMost(screenWidth - 1)
            }
            ControlCharType.LINE_FEED -> {
                // 换行符：这是硬换行，不标记为软换行
                if (cursorY >= 0 && cursorY < screenHeight) {
                    lineWrapped[cursorY] = false
                }
                cursorY++
                if (cursorY > scrollBottom) {
                    cursorY = scrollBottom
                    scrollUp(1)
                }
            }
            ControlCharType.VERTICAL_TAB, ControlCharType.FORM_FEED -> {
                // 类似换行
                cursorY++
                if (cursorY >= screenHeight) {
                    cursorY = screenHeight - 1
                    scrollUp(1)
                }
            }
            ControlCharType.CARRIAGE_RETURN -> {
                cursorX = 0
            }
            ControlCharType.DELETE -> {
                // 删除当前光标位置的字符
                if (cursorY < screenHeight && cursorX < screenWidth) {
                    screenBuffer[cursorY][cursorX] = TerminalChar()
                }
            }
            else -> {
                Log.d(TAG, "Unhandled control char: ${seq.type}")
            }
        }
    }
    
    /**
     * 处理 CSI 序列
     */
    private fun handleCSI(csi: AnsiSequence.CSI) {
        val params = csi.params
        val p1 = params.firstOrNull() ?: 0
        
        when (csi.command) {
            // 光标移动
            'H', 'f' -> { // CUP - Cursor Position
                val row = if (params.isNotEmpty()) (params[0] - 1).coerceAtLeast(0) else 0
                val col = if (params.size > 1) (params[1] - 1).coerceAtLeast(0) else 0
                
                if (originMode) {
                    cursorY = (scrollTop + row).coerceIn(scrollTop, scrollBottom)
                    cursorX = col.coerceIn(0, screenWidth - 1)
                } else {
                    cursorY = row.coerceIn(0, screenHeight - 1)
                    cursorX = col.coerceIn(0, screenWidth - 1)
                }
            }
            'A' -> { // CUU - Cursor Up
                val n = p1.coerceAtLeast(1)
                cursorY = (cursorY - n).coerceAtLeast(scrollTop)
            }
            'B' -> { // CUD - Cursor Down
                val n = p1.coerceAtLeast(1)
                cursorY = (cursorY + n).coerceAtMost(scrollBottom)
            }
            'C' -> { // CUF - Cursor Forward
                val n = p1.coerceAtLeast(1)
                cursorX = (cursorX + n).coerceAtMost(screenWidth - 1)
            }
            'D' -> { // CUB - Cursor Back
                val n = p1.coerceAtLeast(1)
                cursorX = (cursorX - n).coerceAtLeast(0)
            }
            'E' -> { // CNL - Cursor Next Line
                val n = p1.coerceAtLeast(1)
                cursorY = (cursorY + n).coerceAtMost(scrollBottom)
                cursorX = 0
            }
            'F' -> { // CPL - Cursor Previous Line
                val n = p1.coerceAtLeast(1)
                cursorY = (cursorY - n).coerceAtLeast(scrollTop)
                cursorX = 0
            }
            'G' -> { // CHA - Cursor Horizontal Absolute
                cursorX = (p1 - 1).coerceIn(0, screenWidth - 1)
            }
            'd' -> { // VPA - Vertical Position Absolute
                cursorY = (p1 - 1).coerceIn(0, screenHeight - 1)
            }
            
            // 屏幕清除
            'J' -> { // ED - Erase in Display
                when (p1) {
                    0 -> eraseFromCursorToEnd()
                    1 -> eraseFromStartToCursor()
                    2 -> clearScreen()
                    3 -> clearScreenAndScrollback()
                }
            }
            'K' -> { // EL - Erase in Line
                when (p1) {
                    0 -> eraseLineFromCursor()
                    1 -> eraseLineToCursor()
                    2 -> eraseLine()
                }
            }
            
            // 滚动
            'S' -> scrollUp(p1.coerceAtLeast(1)) // SU - Scroll Up
            'T' -> scrollDown(p1.coerceAtLeast(1)) // SD - Scroll Down
            'r' -> { // DECSTBM - Set Scrolling Region
                val top = if (params.isNotEmpty()) (params[0] - 1).coerceIn(0, screenHeight - 1) else 0
                val bottom = if (params.size > 1) (params[1] - 1).coerceIn(0, screenHeight - 1) else screenHeight - 1
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorX = 0
                    cursorY = if (originMode) scrollTop else 0
                }
            }
            
            // 插入/删除
            'L' -> insertLines(p1.coerceAtLeast(1)) // IL - Insert Lines
            'M' -> deleteLines(p1.coerceAtLeast(1)) // DL - Delete Lines
            '@' -> insertChars(p1.coerceAtLeast(1)) // ICH - Insert Characters
            'P' -> deleteChars(p1.coerceAtLeast(1)) // DCH - Delete Characters
            'X' -> eraseChars(p1.coerceAtLeast(1)) // ECH - Erase Characters
            
            // 文本属性
            'm' -> handleSGR(params)
            
            // 模式设置
            'h' -> setMode(params, csi.private, true)
            'l' -> setMode(params, csi.private, false)
            
            // 光标保存/恢复 (ANSI.SYS 风格)
            's' -> saveCursorAndAttrs()
            'u' -> restoreCursorAndAttrs()
            
            else -> {
                Log.w(TAG, "Unsupported CSI command: ${csi.command} with params: $params")
            }
        }
    }
    
    /**
     * 处理 SGR (Select Graphic Rendition) - 文本属性设置
     */
    private fun handleSGR(params: List<Int>) {
        if (params.isEmpty()) {
            currentAttributes = TextAttributes()
            return
        }
        
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> currentAttributes = TextAttributes()
                1 -> currentAttributes = currentAttributes.applyBold(true)
                2 -> currentAttributes = currentAttributes.applyDim(true)
                3 -> currentAttributes = currentAttributes.applyItalic(true)
                4 -> currentAttributes = currentAttributes.applyUnderline(true)
                5, 6 -> currentAttributes = currentAttributes.applyBlinking(true)
                7 -> currentAttributes = currentAttributes.applyInverse(true)
                8 -> currentAttributes = currentAttributes.applyHidden(true)
                9 -> currentAttributes = currentAttributes.applyStrikethrough(true)
                
                21, 22 -> currentAttributes = currentAttributes.applyBold(false).applyDim(false)
                23 -> currentAttributes = currentAttributes.applyItalic(false)
                24 -> currentAttributes = currentAttributes.applyUnderline(false)
                25 -> currentAttributes = currentAttributes.applyBlinking(false)
                27 -> currentAttributes = currentAttributes.applyInverse(false)
                28 -> currentAttributes = currentAttributes.applyHidden(false)
                29 -> currentAttributes = currentAttributes.applyStrikethrough(false)
                
                // 前景色 (标准)
                in 30..37 -> currentAttributes = currentAttributes.applyForeground(
                    AnsiColorUtils.getAnsiColor(p - 30)
                )
                38 -> { // 扩展前景色
                    val result = AnsiColorUtils.parseColorFromSgr(params, i + 1)
                    if (result != null) {
                        currentAttributes = currentAttributes.applyForeground(result.first)
                        i += result.second
                    }
                }
                39 -> currentAttributes = currentAttributes.applyForeground(Color.WHITE)
                
                // 背景色 (标准)
                in 40..47 -> currentAttributes = currentAttributes.applyBackground(
                    AnsiColorUtils.getAnsiColor(p - 40)
                )
                48 -> { // 扩展背景色
                    val result = AnsiColorUtils.parseColorFromSgr(params, i + 1)
                    if (result != null) {
                        currentAttributes = currentAttributes.applyBackground(result.first)
                        i += result.second
                    }
                }
                49 -> currentAttributes = currentAttributes.applyBackground(Color.BLACK)
                
                // 前景色 (明亮)
                in 90..97 -> currentAttributes = currentAttributes.applyForeground(
                    AnsiColorUtils.getAnsiBrightColor(p - 90)
                )
                
                // 背景色 (明亮)
                in 100..107 -> currentAttributes = currentAttributes.applyBackground(
                    AnsiColorUtils.getAnsiBrightColor(p - 100)
                )
                
                else -> Log.w(TAG, "Unsupported SGR parameter: $p")
            }
            i++
        }
    }
    
    /**
     * 设置终端模式
     */
    private fun setMode(params: List<Int>, isPrivate: Boolean, enable: Boolean) {
        for (param in params) {
            terminalModes[param] = enable
            
            if (isPrivate) {
                when (param) {
                    1 -> {} // DECCKM - 光标键模式
                    3 -> {} // DECCOLM - 132列模式
                    6 -> originMode = enable // DECOM - 原点模式
                    7 -> autoWrapMode = enable // DECAWM - 自动换行模式
                    25 -> cursorVisible = enable // DECTCEM - 光标可见性
                    1049 -> toggleAltScreen(enable) // 备用屏幕缓冲区
                    2004 -> {} // Bracketed paste mode
                }
            }
        }
    }
    
    /**
     * 切换备用屏幕
     */
    private fun toggleAltScreen(enable: Boolean) {
        if (enable && !isAltScreenActive) {
            // 保存主屏幕，切换到备用屏幕
            altScreenBuffer = screenBuffer
            screenBuffer = Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
            isAltScreenActive = true
            cursorX = 0
            cursorY = 0
        } else if (!enable && isAltScreenActive) {
            // 恢复主屏幕
            altScreenBuffer?.let {
                screenBuffer = it
                altScreenBuffer = null
            }
            isAltScreenActive = false
        }
    }
    
    /**
     * 处理 OSC (Operating System Command)
     */
    private fun handleOSC(osc: AnsiSequence.OSC) {
        when (osc.command) {
            0, 1, 2 -> { // 设置窗口标题
                Log.d(TAG, "Set window title: ${osc.data}")
            }
            4 -> { // 设置颜色调色板
                Log.d(TAG, "Set color palette: ${osc.data}")
            }
            else -> {
                Log.d(TAG, "Unsupported OSC command: ${osc.command}")
            }
        }
    }
    
    /**
     * 处理单字符转义序列
     */
    private fun handleSingleEscape(seq: AnsiSequence.SingleEscape) {
        when (seq.char) {
            '7' -> saveCursorAndAttrs() // DECSC
            '8' -> restoreCursorAndAttrs() // DECRC
            'c' -> resetTerminal() // RIS
            'D' -> { // IND - Index (move down, scroll if needed)
                cursorY++
                if (cursorY > scrollBottom) {
                    cursorY = scrollBottom
                    scrollUp(1)
                }
            }
            'E' -> { // NEL - Next Line
                cursorX = 0
                cursorY++
                if (cursorY > scrollBottom) {
                    cursorY = scrollBottom
                    scrollUp(1)
                }
            }
            'H' -> {} // HTS - Set Tab Stop
            'M' -> { // RI - Reverse Index (move up, scroll if needed)
                cursorY--
                if (cursorY < scrollTop) {
                    cursorY = scrollTop
                    scrollDown(1)
                }
            }
            'Z' -> {} // DECID - Identify Terminal
            else -> Log.w(TAG, "Unsupported single escape: ${seq.char}")
        }
    }
    
    /**
     * 处理 DCS (Device Control String)
     */
    private fun handleDCS(dcs: AnsiSequence.DCS) {
        Log.d(TAG, "DCS sequence (not implemented): ${dcs.data}")
    }
    
    // === 屏幕操作方法 ===
    
    private fun scrollUp(lines: Int = 1) {
        for (i in 0 until lines) {
            // 将滚动出去的顶行添加到历史缓冲区（仅当不在备用屏幕时）
            if (!isAltScreenActive && scrollTop == 0) {
                // 复制顶行到历史缓冲区
                val topLine = screenBuffer[scrollTop].copyOf()
                val topWrapped = lineWrapped[scrollTop]
                historyBuffer.add(topLine)
                historyWrapped.add(topWrapped)
                
                // 限制历史缓冲区大小
                if (historyBuffer.size > historySize) {
                    historyBuffer.removeAt(0)
                    historyWrapped.removeAt(0)
                }
            }
            
            for (y in scrollTop until scrollBottom) {
                screenBuffer[y] = screenBuffer[y + 1]
                lineWrapped[y] = lineWrapped[y + 1]
            }
            screenBuffer[scrollBottom] = Array(screenWidth) { 
                TerminalChar(attributes = currentAttributes.copy(
                    fgColor = Color.WHITE,
                    bgColor = Color.BLACK,
                    isBold = false,
                    isDim = false,
                    isItalic = false,
                    isUnderline = false,
                    isBlinking = false,
                    isInverse = false,
                    isHidden = false,
                    isStrikethrough = false
                ))
            }
            lineWrapped[scrollBottom] = false
        }
    }
    
    private fun scrollDown(lines: Int = 1) {
        for (i in 0 until lines) {
            for (y in scrollBottom downTo scrollTop + 1) {
                screenBuffer[y] = screenBuffer[y - 1]
            }
            screenBuffer[scrollTop] = Array(screenWidth) { TerminalChar() }
        }
    }
    
    private fun clearScreen() {
        for (y in 0 until screenHeight) {
            for (x in 0 until screenWidth) {
                screenBuffer[y][x] = TerminalChar()
            }
        }
        cursorX = 0
        cursorY = 0
    }
    
    private fun clearScreenAndScrollback() {
        clearScreen()
        // 清除历史缓冲区
        historyBuffer.clear()
        historyWrapped.clear()
    }
    
    private fun eraseFromCursorToEnd() {
        // 清除当前行从光标到行尾
        for (x in cursorX until screenWidth) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
        // 清除下面所有行
        for (y in cursorY + 1 until screenHeight) {
            for (x in 0 until screenWidth) {
                screenBuffer[y][x] = TerminalChar()
            }
        }
    }
    
    private fun eraseFromStartToCursor() {
        // 清除上面所有行
        for (y in 0 until cursorY) {
            for (x in 0 until screenWidth) {
                screenBuffer[y][x] = TerminalChar()
            }
        }
        // 清除当前行从行首到光标
        for (x in 0..cursorX) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun eraseLineFromCursor() {
        for (x in cursorX until screenWidth) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun eraseLineToCursor() {
        for (x in 0..cursorX) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun eraseLine() {
        for (x in 0 until screenWidth) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun insertLines(n: Int) {
        for (i in 0 until n) {
            for (y in scrollBottom downTo cursorY + 1) {
                screenBuffer[y] = screenBuffer[y - 1]
            }
            screenBuffer[cursorY] = Array(screenWidth) { TerminalChar() }
        }
    }
    
    private fun deleteLines(n: Int) {
        for (i in 0 until n) {
            for (y in cursorY until scrollBottom) {
                screenBuffer[y] = screenBuffer[y + 1]
            }
            screenBuffer[scrollBottom] = Array(screenWidth) { TerminalChar() }
        }
    }
    
    private fun insertChars(n: Int) {
        val line = screenBuffer[cursorY]
        for (i in 0 until n.coerceAtMost(screenWidth - cursorX)) {
            for (x in screenWidth - 1 downTo cursorX + 1) {
                line[x] = line[x - 1]
            }
            line[cursorX] = TerminalChar()
        }
    }
    
    private fun deleteChars(n: Int) {
        val line = screenBuffer[cursorY]
        for (i in 0 until n.coerceAtMost(screenWidth - cursorX)) {
            for (x in cursorX until screenWidth - 1) {
                line[x] = line[x + 1]
            }
            line[screenWidth - 1] = TerminalChar()
        }
    }
    
    private fun eraseChars(n: Int) {
        for (x in cursorX until (cursorX + n).coerceAtMost(screenWidth)) {
            screenBuffer[cursorY][x] = TerminalChar()
        }
    }
    
    private fun saveCursorAndAttrs() {
        savedCursorX = cursorX
        savedCursorY = cursorY
        savedAttributes = currentAttributes
    }
    
    private fun restoreCursorAndAttrs() {
        cursorX = savedCursorX
        cursorY = savedCursorY
        currentAttributes = savedAttributes
    }
    
    private fun resetTerminal() {
        clearScreen()
        currentAttributes = TextAttributes()
        cursorX = 0
        cursorY = 0
        scrollTop = 0
        scrollBottom = screenHeight - 1
        terminalModes.clear()
        autoWrapMode = true
        originMode = false
        cursorVisible = true
    }
    
    // === 公共 API ===
    
    fun renderScreenToString(): String {
        val builder = StringBuilder()
        for (y in 0 until screenHeight) {
            for (x in 0 until screenWidth) {
                builder.append(screenBuffer[y][x].char)
            }
            if (y < screenHeight - 1) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }
    
    fun getCursorX(): Int = cursorX
    fun getCursorY(): Int = cursorY
    fun isCursorVisible(): Boolean = cursorVisible
    
    fun getScreenContent(): Array<Array<TerminalChar>> = screenBuffer
    
    /**
     * 获取包含历史记录的完整内容（历史 + 屏幕）
     */
    fun getFullContent(): List<Array<TerminalChar>> {
        return fullContentView
    }
    
    /**
     * 内部类：提供历史+屏幕缓冲的统一视图，避免每次创建新列表
     */
    private inner class FullContentView : AbstractList<Array<TerminalChar>>() {
        override val size: Int
            get() = historyBuffer.size + screenBuffer.size
        
        override fun get(index: Int): Array<TerminalChar> {
            return if (index < historyBuffer.size) {
                historyBuffer[index]
            } else {
                screenBuffer[index - historyBuffer.size]
            }
        }
    }
    
    /**
     * 获取历史缓冲区大小
     */
    fun getHistorySize(): Int = historyBuffer.size
    
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == screenWidth && newHeight == screenHeight) return
        
        // 主流程：收集 -> 清理 -> 重排 -> 分配
        val logicalLines = collectLogicalLines()
        trimTrailingEmptyLines(logicalLines)
        val (newPhysicalLines, newPhysicalWrapped) = rewrapLogicalLines(logicalLines, newWidth)
        
        // 更新尺寸并重建缓冲区
        screenWidth = newWidth
        screenHeight = newHeight
        distributeToBuffers(newPhysicalLines, newPhysicalWrapped)
        
        // 重置滚动区域和光标
        scrollTop = 0
        scrollBottom = screenHeight - 1
        cursorX = 0
        
        notifyChange()
    }
    
    /**
     * 收集所有逻辑行（合并历史+屏幕的软换行）
     */
    private fun collectLogicalLines(): MutableList<MutableList<TerminalChar>> {
        val logicalLines = mutableListOf<MutableList<TerminalChar>>()
        var currentLogicalLine = mutableListOf<TerminalChar>()
        
        // 辅助函数：处理一行物理行
        fun processPhysicalLine(line: Array<TerminalChar>, isWrapped: Boolean) {
            // 计算有效长度：软换行取整行，硬换行去除末尾空白
            val effectiveLength = if (isWrapped) {
                line.size
            } else {
                findLastNonEmptyIndex(line) + 1
            }
            
            // 添加有效字符到当前逻辑行
            for (i in 0 until effectiveLength) {
                currentLogicalLine.add(line[i])
            }
            
            // 硬换行：逻辑行结束
            if (!isWrapped) {
                logicalLines.add(currentLogicalLine)
                currentLogicalLine = mutableListOf()
            }
        }
        
        // 处理历史缓冲区
        for (i in historyBuffer.indices) {
            processPhysicalLine(historyBuffer[i], historyWrapped[i])
        }
        
        // 处理屏幕缓冲区
        for (i in 0 until screenHeight) {
            processPhysicalLine(screenBuffer[i], lineWrapped[i])
        }
        
        // 处理残留的逻辑行（最后一行是软换行的情况）
        if (currentLogicalLine.isNotEmpty()) {
            logicalLines.add(currentLogicalLine)
        }
        
        return logicalLines
    }
    
    /**
     * 查找行中最后一个非空字符的索引
     */
    private fun findLastNonEmptyIndex(line: Array<TerminalChar>): Int {
        for (i in line.size - 1 downTo 0) {
            val c = line[i]
            // 检查是否为空白字符（无内容无样式）
            if (c.char != ' ' || c.bgColor != Color.BLACK || c.isInverse || c.isUnderline || c.isStrikethrough) {
                return i
            }
        }
        return -1 // 整行都是空白
    }
    
    /**
     * 移除末尾连续的空逻辑行
     */
    private fun trimTrailingEmptyLines(logicalLines: MutableList<MutableList<TerminalChar>>) {
        while (logicalLines.isNotEmpty()) {
            val lastLine = logicalLines.last()
            if (lastLine.isEmpty() || lastLine.all { 
                it.char == ' ' && it.bgColor == Color.BLACK && !it.isInverse && !it.isUnderline 
            }) {
                logicalLines.removeAt(logicalLines.size - 1)
            } else {
                break
            }
        }
    }
    
    /**
     * 根据新宽度重新折行
     */
    private fun rewrapLogicalLines(
        logicalLines: List<List<TerminalChar>>,
        newWidth: Int
    ): Pair<List<Array<TerminalChar>>, List<Boolean>> {
        val physicalLines = mutableListOf<Array<TerminalChar>>()
        val wrappedFlags = mutableListOf<Boolean>()
        
        for (logicalLine in logicalLines) {
            if (logicalLine.isEmpty()) {
                // 空逻辑行（真正的空行）
                physicalLines.add(Array(newWidth) { TerminalChar() })
                wrappedFlags.add(false)
                continue
            }
            
            // 将逻辑行按新宽度切分成多个物理行
            var offset = 0
            while (offset < logicalLine.size) {
                val chunkSize = (logicalLine.size - offset).coerceAtMost(newWidth)
                val chunk = Array(newWidth) { TerminalChar() }
                
                for (i in 0 until chunkSize) {
                    chunk[i] = logicalLine[offset + i]
                }
                
                offset += chunkSize
                val isWrapped = offset < logicalLine.size // 还有剩余内容则标记为软换行
                
                physicalLines.add(chunk)
                wrappedFlags.add(isWrapped)
            }
        }
        
        return Pair(physicalLines, wrappedFlags)
    }
    
    /**
     * 将物理行分配到历史缓冲区和屏幕缓冲区
     */
    private fun distributeToBuffers(
        physicalLines: List<Array<TerminalChar>>,
        wrappedFlags: List<Boolean>
    ) {
        historyBuffer.clear()
        historyWrapped.clear()
        screenBuffer = Array(screenHeight) { Array(screenWidth) { TerminalChar() } }
        lineWrapped = BooleanArray(screenHeight) { false }
        
        val totalLines = physicalLines.size
        
        if (totalLines <= screenHeight) {
            // 内容不足一屏，直接填充屏幕
            for (i in 0 until totalLines) {
                screenBuffer[i] = physicalLines[i]
                lineWrapped[i] = wrappedFlags[i]
            }
            cursorY = (totalLines - 1).coerceAtLeast(0)
        } else {
            // 内容超过一屏，分流到历史和屏幕
            val historyCount = totalLines - screenHeight
            val startHistoryIdx = (historyCount - historySize).coerceAtLeast(0)
            
            // 填充历史缓冲区（受限于 historySize）
            for (i in startHistoryIdx until historyCount) {
                historyBuffer.add(physicalLines[i])
                historyWrapped.add(wrappedFlags[i])
            }
            
            // 填充屏幕缓冲区（最后 screenHeight 行）
            for (i in 0 until screenHeight) {
                screenBuffer[i] = physicalLines[historyCount + i]
                lineWrapped[i] = wrappedFlags[historyCount + i]
            }
            
            cursorY = screenHeight - 1
        }
    }
    
    // === 观察者模式方法 ===
    
    /**
     * 添加变化监听器
     */
    fun addChangeListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }
    
    /**
     * 移除变化监听器
     */
    fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }
    
    /**
     * 添加新输出监听器
     */
    fun addNewOutputListener(listener: () -> Unit) {
        newOutputListeners.add(listener)
    }
    
    /**
     * 移除新输出监听器
     */
    fun removeNewOutputListener(listener: () -> Unit) {
        newOutputListeners.remove(listener)
    }
    
    /**
     * 通知所有监听器终端内容已改变
     */
    private fun notifyChange() {
        changeListeners.forEach { it() }
    }
    
    /**
     * 通知新输出监听器
     */
    private fun notifyNewOutput() {
        newOutputListeners.forEach { it() }
    }
} 
