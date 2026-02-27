package com.ai.assistance.custard.terminal.view.domain.ansi

/**
 * ANSI 序列扫描器
 * 负责从文本流中识别和提取 ANSI 控制序列
 */
class AnsiScanner(private val input: String) {
    private var position = 0
    
    /**
     * 是否还有更多字符可读
     */
    fun hasNext(): Boolean = position < input.length
    
    /**
     * 获取当前位置
     */
    fun getPosition(): Int = position
    
    /**
     * 查看当前字符但不移动位置
     */
    fun peek(): Char? = if (hasNext()) input[position] else null
    
    /**
     * 查看指定偏移量的字符但不移动位置
     */
    fun peek(offset: Int): Char? {
        val index = position + offset
        return if (index < input.length) input[index] else null
    }
    
    /**
     * 读取当前字符并移动位置
     */
    fun next(): Char? {
        return if (hasNext()) input[position++] else null
    }
    
    /**
     * 扫描下一个 ANSI 序列或字符
     */
    fun scanNext(): AnsiSequence? {
        if (!hasNext()) return null
        
        val char = peek() ?: return null
        
        return when {
            char == '\u001B' -> scanEscapeSequence()
            char.code in 0..31 || char == '\u007F' -> scanControlCharacter()
            else -> AnsiSequence.Text(next()!!)
        }
    }
    
    /**
     * 扫描所有序列
     */
    fun scanAll(): List<AnsiSequence> {
        val sequences = mutableListOf<AnsiSequence>()
        while (hasNext()) {
            scanNext()?.let { sequences.add(it) }
        }
        return sequences
    }
    
    /**
     * 扫描转义序列 (以 ESC 开头)
     */
    private fun scanEscapeSequence(): AnsiSequence {
        val start = position
        next() // 消费 ESC 字符
        
        return when (peek()) {
            '[' -> scanCSI()
            ']' -> scanOSC()
            'P' -> scanDCS()
            // DEC 特殊序列
            '7' -> { next(); AnsiSequence.SingleEscape('7')
            } // DECSC
            '8' -> { next(); AnsiSequence.SingleEscape('8')
            } // DECRC
            'c' -> { next(); AnsiSequence.SingleEscape('c')
            } // RIS
            'D' -> { next(); AnsiSequence.SingleEscape('D')
            } // IND
            'E' -> { next(); AnsiSequence.SingleEscape('E')
            } // NEL
            'H' -> { next(); AnsiSequence.SingleEscape('H')
            } // HTS
            'M' -> { next(); AnsiSequence.SingleEscape('M')
            } // RI
            'Z' -> { next(); AnsiSequence.SingleEscape('Z')
            } // DECID
            else -> {
                val unknownChar = next()
                AnsiSequence.Unknown(input.substring(start, position))
            }
        }
    }
    
    /**
     * 扫描 CSI 序列 (Control Sequence Introducer)
     * 格式: ESC [ [params] [intermediates] command
     */
    private fun scanCSI(): AnsiSequence {
        next() // 消费 '['
        
        // 检查是否为私有模式 (以 ? 开头)
        val isPrivate = peek() == '?'
        if (isPrivate) next()
        
        // 读取参数 (数字和分号)
        val paramsBuilder = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char.isDigit() || char == ';') {
                paramsBuilder.append(next())
            } else {
                break
            }
        }
        
        // 读取中间字符 (0x20-0x2F)
        val intermediates = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char.code in 0x20..0x2F) {
                intermediates.append(next())
            } else {
                break
            }
        }
        
        // 读取命令字符 (0x40-0x7E)
        val command = if (hasNext()) {
            val char = peek()!!
            if (char.code in 0x40..0x7E) {
                next()!!
            } else {
                '?' // 无效命令
            }
        } else {
            '?' // 未完成的序列
        }
        
        // 解析参数
        val params = if (paramsBuilder.isEmpty()) {
            emptyList()
        } else {
            paramsBuilder.toString()
                .split(';')
                .map { it.toIntOrNull() ?: 0 }
        }
        
        return AnsiSequence.CSI(
            params = params,
            command = command,
            intermediates = intermediates.toString(),
            private = isPrivate
        )
    }
    
    /**
     * 扫描 OSC 序列 (Operating System Command)
     * 格式: ESC ] command ; data BEL 或 ESC ] command ; data ESC \
     */
    private fun scanOSC(): AnsiSequence {
        next() // 消费 ']'
        
        // 读取命令号
        val commandBuilder = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char.isDigit()) {
                commandBuilder.append(next())
            } else {
                break
            }
        }
        
        val command = commandBuilder.toString().toIntOrNull() ?: 0
        
        // 消费分号
        if (peek() == ';') next()
        
        // 读取数据直到 BEL (\u0007) 或 ST (ESC \)
        val dataBuilder = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char == '\u0007') { // BEL
                next()
                break
            } else if (char == '\u001B' && peek(1) == '\\') { // ST
                next()
                next()
                break
            } else {
                dataBuilder.append(next())
            }
        }
        
        return AnsiSequence.OSC(command, dataBuilder.toString())
    }
    
    /**
     * 扫描 DCS 序列 (Device Control String)
     * 格式: ESC P data ST
     */
    private fun scanDCS(): AnsiSequence {
        next() // 消费 'P'
        
        // 读取数据直到 ST (ESC \)
        val dataBuilder = StringBuilder()
        while (hasNext()) {
            val char = peek()!!
            if (char == '\u001B' && peek(1) == '\\') {
                next()
                next()
                break
            } else {
                dataBuilder.append(next())
            }
        }
        
        return AnsiSequence.DCS(dataBuilder.toString())
    }
    
    /**
     * 扫描控制字符
     */
    private fun scanControlCharacter(): AnsiSequence {
        val char = next()!!
        val type = when (char) {
            '\u0000' -> ControlCharType.NULL
            '\u0007' -> ControlCharType.BELL
            '\b' -> ControlCharType.BACKSPACE
            '\t' -> ControlCharType.TAB
            '\n' -> ControlCharType.LINE_FEED
            '\u000B' -> ControlCharType.VERTICAL_TAB
            '\u000C' -> ControlCharType.FORM_FEED
            '\r' -> ControlCharType.CARRIAGE_RETURN
            '\u001B' -> ControlCharType.ESCAPE
            '\u007F' -> ControlCharType.DELETE
            else -> ControlCharType.OTHER
        }
        return AnsiSequence.ControlChar(type, char)
    }
}

/**
 * ANSI 工具函数
 */
object AnsiUtils {
    /**
     * 检测文本中是否包含 ANSI 序列
     */
    fun containsAnsiSequences(text: String): Boolean {
        return text.contains('\u001B')
    }
    
    /**
     * 去除文本中的所有 ANSI 序列
     */
    fun stripAnsi(text: String): String {
        if (!containsAnsiSequences(text)) return text
        
        val scanner = AnsiScanner(text)
        val result = StringBuilder()
        
        while (scanner.hasNext()) {
            when (val seq = scanner.scanNext()) {
                is AnsiSequence.Text -> result.append(seq.char)
                is AnsiSequence.ControlChar -> {
                    // 保留某些控制字符
                    when (seq.type) {
                        ControlCharType.TAB,
                        ControlCharType.LINE_FEED,
                        ControlCharType.CARRIAGE_RETURN -> result.append(seq.char)
                        else -> {} // 忽略其他控制字符
                    }
                }
                else -> {} // 忽略所有 ANSI 序列
            }
        }
        
        return result.toString()
    }
    
    /**
     * 检测文本是否包含进度行特征的 ANSI 序列
     */
    fun isProgressLine(text: String): Boolean {
        if (!containsAnsiSequences(text)) {
            // 检查是否包含回车符但没有换行符
            return text.contains('\r') && !text.contains('\n')
        }
        
        val scanner = AnsiScanner(text)
        
        while (scanner.hasNext()) {
            when (val seq = scanner.scanNext()) {
                is AnsiSequence.ControlChar -> {
                    // 包含回车符但整个文本没有换行符
                    if (seq.type == ControlCharType.CARRIAGE_RETURN && !text.contains('\n')) {
                        return true
                    }
                }
                is AnsiSequence.CSI -> {
                    when (seq.command) {
                        'K' -> return true // 清除行
                        'A', 'B', 'C', 'D', 'G' -> return true // 光标移动
                        's', 'u' -> return true // 保存/恢复光标 (ANSI.SYS)
                        else -> {}
                    }
                }
                is AnsiSequence.SingleEscape -> {
                    // DEC 风格的保存/恢复光标
                    if (seq.char == '7' || seq.char == '8') {
                        return true
                    }
                }
                else -> {}
            }
        }
        
        return false
    }
    
    /**
     * 检测文本是否包含全屏模式切换序列
     */
    fun detectFullscreenMode(text: String): FullscreenMode? {
        val scanner = AnsiScanner(text)
        
        while (scanner.hasNext()) {
            when (val seq = scanner.scanNext()) {
                is AnsiSequence.CSI -> {
                    if (seq.private && (seq.command == 'h' || seq.command == 'l')) {
                        if (seq.params.contains(1049)) {
                            return if (seq.command == 'h') {
                                FullscreenMode.ENTER
                            } else {
                                FullscreenMode.EXIT
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        
        return null
    }
    
    enum class FullscreenMode {
        ENTER, EXIT
    }
} 