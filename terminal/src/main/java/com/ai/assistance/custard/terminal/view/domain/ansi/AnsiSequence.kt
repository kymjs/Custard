package com.ai.assistance.custard.terminal.view.domain.ansi

import android.graphics.Color

/**
 * ANSI 序列类型
 */
sealed class AnsiSequence {
    /** 普通文本字符 */
    data class Text(val char: Char) : AnsiSequence()
    
    /** 控制字符 */
    data class ControlChar(val type: ControlCharType, val char: Char) : AnsiSequence()
    
    /** CSI (Control Sequence Introducer) 序列 - ESC[ */
    data class CSI(
        val params: List<Int>,
        val command: Char,
        val intermediates: String = "",
        val private: Boolean = false
    ) : AnsiSequence()
    
    /** OSC (Operating System Command) 序列 - ESC] */
    data class OSC(val command: Int, val data: String) : AnsiSequence()
    
    /** 单字符转义序列 - ESC + 单字符 */
    data class SingleEscape(val char: Char) : AnsiSequence()
    
    /** DCS (Device Control String) 序列 - ESC P */
    data class DCS(val data: String) : AnsiSequence()
    
    /** 未知或不支持的序列 */
    data class Unknown(val raw: String) : AnsiSequence()
}

/**
 * 控制字符类型
 */
enum class ControlCharType {
    NULL,           // \0 - Null
    BELL,           // \a - Bell/Alert
    BACKSPACE,      // \b - Backspace
    TAB,            // \t - Horizontal Tab
    LINE_FEED,      // \n - Line Feed/New Line
    VERTICAL_TAB,   // \v - Vertical Tab
    FORM_FEED,      // \f - Form Feed
    CARRIAGE_RETURN,// \r - Carriage Return
    ESCAPE,         // \x1B - Escape (handled separately)
    DELETE,         // \x7F - Delete
    OTHER           // Other control characters
}

/**
 * SGR (Select Graphic Rendition) 参数
 */
sealed class SgrParameter {
    object Reset : SgrParameter()
    object Bold : SgrParameter()
    object Dim : SgrParameter()
    object Italic : SgrParameter()
    object Underline : SgrParameter()
    object Blink : SgrParameter()
    object Inverse : SgrParameter()
    object Hidden : SgrParameter()
    object Strikethrough : SgrParameter()
    
    object NormalIntensity : SgrParameter()
    object NotItalic : SgrParameter()
    object NotUnderline : SgrParameter()
    object NotBlink : SgrParameter()
    object NotInverse : SgrParameter()
    object NotHidden : SgrParameter()
    object NotStrikethrough : SgrParameter()
    
    data class ForegroundColor(val color: Int) : SgrParameter()
    data class BackgroundColor(val color: Int) : SgrParameter()
    data class ForegroundColor256(val index: Int) : SgrParameter()
    data class BackgroundColor256(val index: Int) : SgrParameter()
    data class ForegroundColorRgb(val r: Int, val g: Int, val b: Int) : SgrParameter()
    data class BackgroundColorRgb(val r: Int, val g: Int, val b: Int) : SgrParameter()
    
    object DefaultForeground : SgrParameter()
    object DefaultBackground : SgrParameter()
}

/**
 * 光标移动方向
 */
enum class CursorDirection {
    UP,
    DOWN,
    FORWARD,
    BACK
}

/**
 * 屏幕清除模式
 */
enum class EraseMode {
    TO_END,         // 从光标到结尾
    TO_START,       // 从开始到光标
    ALL,            // 全部
    SCROLLBACK      // 包括滚动缓冲区（仅用于 J=3）
}

/**
 * 终端模式
 */
data class TerminalMode(
    val number: Int,
    val isPrivate: Boolean,
    val name: String
) {
    companion object {
        // 常用的 DEC 私有模式
        val CURSOR_KEYS = TerminalMode(1, true, "DECCKM")          // Cursor Keys Mode
        val ANSI_VT52 = TerminalMode(2, true, "DECANM")            // ANSI/VT52 Mode
        val COLUMN_MODE = TerminalMode(3, true, "DECCOLM")         // Column Mode
        val SMOOTH_SCROLL = TerminalMode(4, true, "DECSCLM")       // Smooth Scroll
        val REVERSE_VIDEO = TerminalMode(5, true, "DECSCNM")       // Reverse Video
        val ORIGIN_MODE = TerminalMode(6, true, "DECOM")           // Origin Mode
        val AUTO_WRAP = TerminalMode(7, true, "DECAWM")            // Auto Wrap Mode
        val AUTO_REPEAT = TerminalMode(8, true, "DECARM")          // Auto Repeat Mode
        val CURSOR_VISIBLE = TerminalMode(25, true, "DECTCEM")     // Text Cursor Enable
        val ALT_SCREEN = TerminalMode(1049, true, "ALT_SCREEN")    // Alternate Screen Buffer
        val BRACKETED_PASTE = TerminalMode(2004, true, "BRACKETED_PASTE")
        
        // ANSI 模式
        val INSERT_MODE = TerminalMode(4, false, "IRM")            // Insert/Replace Mode
        val LINE_FEED_MODE = TerminalMode(20, false, "LNM")        // Line Feed/New Line Mode
    }
}

/**
 * 文本属性
 */
data class TextAttributes(
    val fgColor: Int = Color.WHITE,
    val bgColor: Int = Color.BLACK,
    val isBold: Boolean = false,
    val isDim: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isBlinking: Boolean = false,
    val isInverse: Boolean = false,
    val isHidden: Boolean = false,
    val isStrikethrough: Boolean = false
) {
    fun reset() = TextAttributes()
    
    fun applyBold(value: Boolean) = copy(isBold = value, isDim = if (value) false else isDim)
    fun applyDim(value: Boolean) = copy(isDim = value, isBold = if (value) false else isBold)
    fun applyItalic(value: Boolean) = copy(isItalic = value)
    fun applyUnderline(value: Boolean) = copy(isUnderline = value)
    fun applyBlinking(value: Boolean) = copy(isBlinking = value)
    fun applyInverse(value: Boolean) = copy(isInverse = value)
    fun applyHidden(value: Boolean) = copy(isHidden = value)
    fun applyStrikethrough(value: Boolean) = copy(isStrikethrough = value)
    fun applyForeground(color: Int) = copy(fgColor = color)
    fun applyBackground(color: Int) = copy(bgColor = color)
    
    /**
     * 获取有效的前景色（考虑反转）
     */
    fun getEffectiveForeground(): Int = if (isInverse) bgColor else fgColor
    
    /**
     * 获取有效的背景色（考虑反转）
     */
    fun getEffectiveBackground(): Int = if (isInverse) fgColor else bgColor
} 