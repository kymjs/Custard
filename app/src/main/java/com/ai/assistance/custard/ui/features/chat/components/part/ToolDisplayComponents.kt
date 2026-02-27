package com.ai.assistance.custard.ui.features.chat.components.part

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.custard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 简洁样式的工具调用显示组件 使用箭头图标+工具名+参数的简洁行样式 */
@Composable
fun CompactToolDisplay(
        toolName: String,
        params: String = "",
        textColor: Color,
        modifier: Modifier = Modifier,
        enableDialog: Boolean = true  // 新增参数：是否启用弹窗功能，默认启用
) {
    val context = LocalContext.current
    val (displayToolName, displayParams) = remember(toolName, params) {
        normalizeToolDisplayForStrictProxy(toolName, params)
    }
    // 弹窗状态
    var showDetailDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val hasParams = displayParams.isNotBlank()
    val semanticDescription = remember(displayToolName, displayParams.length) {
        buildToolSemanticDescription(context, displayToolName, displayParams)
    }

    // 显示详细内容的弹窗 - 仅在启用弹窗时显示
    if (showDetailDialog && hasParams && enableDialog) {
        ContentDetailDialog(
            title = "$displayToolName ${context.getString(R.string.tool_call_parameters)}",
            content = displayParams,
            icon = getToolIcon(displayToolName),
            onDismiss = { showDetailDialog = false }
        )
    }

    Row(
            modifier =
                    modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clearAndSetSemantics {
                                contentDescription = semanticDescription
                            }
                            .clickable(enabled = hasParams && enableDialog) {
                                // 仅在启用弹窗时才允许点击打开详情
                                if (hasParams && enableDialog) showDetailDialog = true
                            }
                            .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        // 工具图标
        Icon(
                imageVector = getToolIcon(displayToolName),
                contentDescription = context.getString(R.string.tool_call),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 工具名称
        Text(
                text = displayToolName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(min = 80.dp, max = 120.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )

        // 参数内容（如果有）
        if (displayParams.isNotBlank()) {
            val summary = remember(displayParams.length) {
                // 尝试从XML中提取第一个参数的值作为摘要
                val firstParamRegex = "<param.*?>([^<]*)<\\/param>".toRegex()
                val match = firstParamRegex.find(displayParams)
                match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: displayParams.replace("\n", " ").trim() // 如果没有匹配或值为空，则显示清理后的原始参数
            }
            Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 卡片式工具显示组件 用于显示较长内容的工具调用，支持流式渲染，美化版 */
@Composable
fun DetailedToolDisplay(
        toolName: String,
        params: String = "",
        textColor: Color,
        modifier: Modifier = Modifier,
        enableDialog: Boolean = true  // 新增参数：是否启用弹窗功能，默认启用
) {
    val context = LocalContext.current
    val (displayToolName, displayParams) = remember(toolName, params) {
        normalizeToolDisplayForStrictProxy(toolName, params)
    }
    // 弹窗状态
    var showDetailDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val hasParams = displayParams.isNotBlank()
    val semanticDescription = remember(displayToolName, displayParams.length) {
        buildToolSemanticDescription(context, displayToolName, displayParams)
    }

    // 显示详细内容的弹窗 - 仅在启用弹窗时显示
    if (showDetailDialog && hasParams && enableDialog) {
        ContentDetailDialog(
            title = "$displayToolName ${context.getString(R.string.tool_call_parameters)}",
            content = displayParams,
            icon = getToolIcon(displayToolName),
            onDismiss = { showDetailDialog = false }
        )
    }

    Card(
            modifier =
                    modifier.fillMaxWidth()
                            .padding(top = 4.dp)
                            .clearAndSetSemantics {
                                contentDescription = semanticDescription
                            }
                            .clickable(enabled = hasParams && enableDialog) {
                                // 仅在启用弹窗时才允许点击打开详情
                                if (hasParams && enableDialog) showDetailDialog = true
                            },
            colors =
                    CardDefaults.cardColors(
                            containerColor =
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
            border =
                    BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
            shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 工具标题行
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
            ) {
                // 工具图标 - 与CompactToolDisplay保持一致的大小和位置
                Icon(
                        imageVector = getToolIcon(displayToolName),
                        contentDescription = context.getString(R.string.tool_call),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 工具名称
                Text(
                        text = displayToolName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )

                // 参数行数指示
                if (hasParams) {
                    val lineCount = remember(displayParams.length) { displayParams.lines().size }
                    Text(
                            text = "$lineCount ${context.getString(R.string.lines_count)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 参数内容 - 使用代码风格显示
            if (displayParams.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))

                // 按行拆分参数文本，并用remember缓存，仅在params改变时重新计算
                val lines = remember(displayParams.length) {
                    displayParams.lines().map { line ->
                        normalizeIndentForDisplay(unescapeXmlForDisplay(line))
                    }
                }

                // 创建LazyListState以控制滚动
                val listState = rememberLazyListState()

                // 当内容更新时，自动滚动到底部
                LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
                }

                // 使用有高度限制的代码显示区域
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .heightIn(min = 10.dp, max = 200.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(
                                                        alpha = 0.5f
                                                )
                                        )
                ) {
                    // 显示带行号的代码内容
                    CodeContentWithLineNumbers(
                            lines = lines,
                            textColor = textColor,
                            listState = listState,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 显示带行号的代码内容 */
@Composable
internal fun CodeContentWithLineNumbers(
    lines: List<String>,
    textColor: Color,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier, state = listState) {
        itemsIndexed(items = lines, key = { index, _ -> index }) { index, line ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                // 行号列，使用固定宽度以保证代码对齐
                Box(
                        modifier = Modifier.width(40.dp).padding(end = 8.dp),
                        contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                            text = "${index + 1}",
                            style =
                                    MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                    ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                // 代码内容列 - 移除水平滚动，改为自动换行
                Box(modifier = Modifier.weight(1f)) {
                    // 当行太长时，高亮解析会很卡，所以设置一个阈值
                    val lineLengthLimit = 300
                    val isXmlTagLine = remember(line) {
                        val t = line.trimStart()
                        if (!t.startsWith("<") || !t.contains(">")) {
                            false
                        } else {
                            val second = t.getOrNull(1)
                            val idx = if (second == '/') 2 else 1
                            t.getOrNull(idx)?.isLetter() == true
                        }
                    }

                    if (isXmlTagLine && line.length < lineLengthLimit) {
                        // 仅对XML标签行做高亮；避免把 <param> 内的代码内容也当作XML渲染（会导致颜色异常）
                        FormattedXmlText(
                                text = line,
                                textColor = textColor.copy(alpha = 0.8f),
                                modifier = Modifier.padding(vertical = 2.dp)
                        )
                    } else {
                        // 普通文本或过长的XML文本（不进行高亮）
                        Text(
                                text = line,
                                style =
                                        MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                        ),
                                color = textColor.copy(alpha = 0.8f),
                                softWrap = true
                        )
                    }
                }
            }
        }
    }
}

/** XML语法高亮文本 - 异步计算高亮以避免阻塞主线程 */
@Composable
internal fun FormattedXmlText(text: String, textColor: Color, modifier: Modifier = Modifier) {
    // 使用状态保存格式化后的文本
    var formattedText by remember(text) { mutableStateOf<AnnotatedString?>(null) }
    
    // 异步计算语法高亮
    LaunchedEffect(text) {
        val result = withContext(Dispatchers.Default) {
            buildAnnotatedString {
                val displayText = text.trimEnd()
                val leadingWhitespace = displayText.takeWhile { it == ' ' || it == '\t' }
                val contentText = displayText.drop(leadingWhitespace.length)

                // 保留原始缩进，避免每行开头空格被清空
                if (leadingWhitespace.isNotEmpty()) {
                    append(leadingWhitespace)
                }

                // 简单的XML语法高亮
                when {
                    // XML标签
                    contentText.startsWith("<") && contentText.contains(">") -> {
                        var inTag = false
                        var inAttr = false

                        for (i in contentText.indices) {
                            val char = contentText[i]
                            when {
                                char == '<' -> {
                                    inTag = true
                                    withStyle(SpanStyle(color = Color(0xFF9C27B0))) { // 紫色
                                        append(char)
                                    }
                                }
                                char == '>' -> {
                                    inTag = false
                                    withStyle(SpanStyle(color = Color(0xFF9C27B0))) { // 紫色
                                        append(char)
                                    }
                                }
                                char == '=' -> {
                                    inAttr = true
                                    withStyle(SpanStyle(color = Color(0xFF757575))) { // 灰色
                                        append(char)
                                    }
                                }
                                char == '"' -> {
                                    if (inAttr) {
                                        withStyle(SpanStyle(color = Color(0xFF4CAF50))) { // 绿色
                                            append(char)
                                        }
                                    } else {
                                        append(char)
                                    }
                                    inAttr = !inAttr
                                }
                                inTag && char.isLetterOrDigit() -> {
                                    withStyle(SpanStyle(color = Color(0xFF2196F3))) { // 蓝色
                                        append(char)
                                    }
                                }
                                inAttr -> {
                                    withStyle(SpanStyle(color = Color(0xFF4CAF50))) { // 绿色
                                        append(char)
                                    }
                                }
                                else -> {
                                    append(char)
                                }
                            }
                        }
                    }
                    // 普通文本
                    else -> {
                        append(contentText)
                    }
                }
            }
        }
        formattedText = result
    }

    // 显示格式化后的文本，计算完成前显示原始文本
    Text(
            text = formattedText ?: AnnotatedString(text.trimEnd()),
            style =
                    MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = textColor
                    ),
            softWrap = true,
            modifier = modifier
    )
}

private fun unescapeXmlForDisplay(input: String): String {
    var result = input

    if (result.startsWith("<![CDATA[") && result.endsWith("]]>") ) {
        result = result.substring(9, result.length - 3)
    }

    if (result.endsWith("]]>") ) {
        result = result.substring(0, result.length - 3)
    }

    if (result.startsWith("<![CDATA[") ) {
        result = result.substring(9)
    }

    return result.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}

private fun normalizeIndentForDisplay(line: String): String {
    if (line.isEmpty()) return line

    var i = 0
    var levels = 0
    var spaces = 0
    while (i < line.length) {
        when (val ch = line[i]) {
            '\t' -> {
                if (spaces > 0) {
                    levels++
                    spaces = 0
                }
                levels++
                i++
            }
            ' ' -> {
                spaces++
                if (spaces == 4) {
                    levels++
                    spaces = 0
                }
                i++
            }
            else -> break
        }
    }

    if (spaces > 0) {
        levels++
    }

    if (levels == 0) return line
    return " ".repeat(levels) + line.substring(i)
}

private fun normalizeToolDisplayForStrictProxy(toolName: String, params: String): Pair<String, String> {
    if (toolName != "package_proxy") {
        return toolName to params
    }

    val toolNameRegex = "<param\\s+name=\"tool_name\">([\\s\\S]*?)<\\/param>".toRegex()
    val paramsRegex = "<param\\s+name=\"params\">([\\s\\S]*?)<\\/param>".toRegex()

    val rawTargetToolName = toolNameRegex.find(params)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val rawProxiedParams = paramsRegex.find(params)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    val displayToolName = normalizeEscapedTextForDisplay(rawTargetToolName).ifBlank { toolName }
    val displayParams = if (rawProxiedParams.isNotBlank()) {
        parseProxyJsonParamsToXml(normalizeEscapedTextForDisplay(rawProxiedParams)) ?: params
    } else {
        params
    }

    return displayToolName to displayParams
}

private fun normalizeEscapedTextForDisplay(input: String): String {
    val unescaped = unescapeXmlForDisplay(input).replace("\\\"", "\"")
    val trimmed = unescaped.trim()

    return if (
        (trimmed.startsWith("\"{") && trimmed.endsWith("}\"")) ||
        (trimmed.startsWith("\"[") && trimmed.endsWith("]\""))
    ) {
        trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"")
    } else {
        unescaped
    }
}

private fun parseProxyJsonParamsToXml(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return ""
    }

    return try {
        when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> {
                val obj = JSONObject(trimmed)
                val lines = mutableListOf<String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val valueText = jsonValueToParamText(obj.opt(key))
                    lines.add("<param name=\"${escapeXmlAttribute(key)}\">${escapeXmlText(valueText)}</param>")
                }
                lines.joinToString("\n")
            }
            trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                val array = JSONArray(trimmed)
                val lines = mutableListOf<String>()
                for (index in 0 until array.length()) {
                    val valueText = jsonValueToParamText(array.opt(index))
                    lines.add("<param name=\"$index\">${escapeXmlText(valueText)}</param>")
                }
                lines.joinToString("\n")
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun jsonValueToParamText(value: Any?): String {
    return when (value) {
        null, JSONObject.NULL -> "null"
        is String -> value
        else -> value.toString()
    }
}

private fun escapeXmlAttribute(input: String): String {
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

private fun escapeXmlText(input: String): String {
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun buildToolSemanticDescription(context: android.content.Context, toolName: String, params: String): String {
    val toolLabel = context.getString(R.string.tool_call)
    if (params.isBlank()) {
        return "$toolLabel: $toolName"
    }
    val paramsLabel = context.getString(R.string.tool_call_parameters)
    val preview = buildParamsHeadPreview(params)
    return "$toolLabel: $toolName, $paramsLabel: $preview"
}

private fun buildParamsHeadPreview(params: String, maxChars: Int = 120): String {
    val firstParamRegex = "<param.*?>([^<]*)<\\/param>".toRegex()
    val matched = firstParamRegex.find(params)?.groupValues?.get(1)?.trim()
    val cleaned = (matched?.takeIf { it.isNotEmpty() } ?: params)
        .replace("\n", " ")
        .trim()
    return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars) + "..."
}

/** 工具参数详情弹窗 美观的弹窗显示完整的工具参数内容 */

/** 根据工具名称选择合适的图标 */
private fun getToolIcon(toolName: String): ImageVector {
    return when {
        // 文件工具
        toolName.contains("file") || toolName.contains("read") || toolName.contains("write") ->
                Icons.Default.FileOpen

        // 搜索工具
        toolName.contains("search") || toolName.contains("find") || toolName.contains("query") ->
                Icons.Default.Search

        // 命令行工具
        toolName.contains("terminal") ||
                toolName.contains("exec") ||
                toolName.contains("command") ||
                toolName.contains("shell") -> Icons.Default.Terminal

        // 代码工具
        toolName.contains("code") || toolName.contains("ffmpeg") -> Icons.Default.Code

        // 网络工具
        toolName.contains("http") || toolName.contains("web") || toolName.contains("visit") ->
                Icons.Default.Web

        // 默认图标
        else -> Icons.AutoMirrored.Filled.ArrowForward
    }
}
