package com.kymjs.ai.custard.ui.common.markdown

import com.kymjs.ai.custard.util.AppLogger
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.kymjs.ai.custard.R

private const val TAG = "TableBlock"
private val TABLE_MIN_COLUMN_WIDTH = 80.dp
private val TABLE_MAX_COLUMN_WIDTH = 320.dp
private const val TABLE_MAX_MEASURE_LINE_CHARS = 512

/**
 * 增强型表格组件
 *
 * 具有以下功能:
 * 1. 智能解析表格内容和结构
 * 2. 表头样式
 * 3. 边框
 * 4. 内容对齐
 * 5. 行渲染保护（使用key机制避免重复渲染）
 * 6. 水平滚动支持（处理长内容）
 * 7. 纵列对齐（保证列宽一致）
 * 8. 完整显示内容（不截断/省略）
 */
@Composable
fun EnhancedTableBlock(
    tableContent: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    // 使用remember创建组件唯一ID
    val componentId = remember { "table-${System.identityHashCode(tableContent)}" }
    AppLogger.d(TAG, "表格组件初始化: id=$componentId, 内容长度=${tableContent.length}")
    
    // 表格颜色
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val headerBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    
    // 水平滚动状态
    val horizontalScrollState = rememberScrollState()
    
    // 解析表格内容
    val tableData by remember(tableContent) {
        derivedStateOf<TableData> {
            parseTable(tableContent)
        }
    }
    
    // 渲染表格
    if (tableData.rows.isEmpty()) {
        return
    }
    
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val baseTextStyle = MaterialTheme.typography.bodyMedium
    
    // 计算每列的最大宽度
    val columnWidths = remember(tableData, density, baseTextStyle) {
        if (tableData.rows.isEmpty()) {
            emptyList()
        } else {
            // 确定列数
            val columnCount = tableData.rows.maxOf { it.size }
            
            // 初始化列宽数组
            val widths = MutableList(columnCount) { TABLE_MIN_COLUMN_WIDTH }
            
            // 计算每列的最大宽度
            tableData.rows.forEachIndexed { rowIndex, row ->
                val isHeaderRow = rowIndex == 0 && tableData.hasHeader
                val rowTextStyle: TextStyle = if (isHeaderRow) {
                    baseTextStyle.copy(fontWeight = FontWeight.Bold)
                } else {
                    baseTextStyle
                }

                row.forEachIndexed { colIndex, cell ->
                    val hasOverlongLine =
                        cell.lineSequence().any { it.length > TABLE_MAX_MEASURE_LINE_CHARS }
                    val maxLineWidthPx =
                        if (hasOverlongLine) {
                            with(density) { TABLE_MAX_COLUMN_WIDTH.roundToPx() }
                        } else {
                            cell
                                .split('\n')
                                .maxOfOrNull { line ->
                                    textMeasurer
                                        .measure(
                                            text = AnnotatedString(line),
                                            style = rowTextStyle,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        .size
                                        .width
                                }
                                ?: 0
                        }

                    val measuredWidthDp = with(density) { maxLineWidthPx.toDp() } + 16.dp
                    val cellWidth = measuredWidthDp.coerceIn(TABLE_MIN_COLUMN_WIDTH, TABLE_MAX_COLUMN_WIDTH)
                    if (colIndex < widths.size && cellWidth > widths[colIndex]) {
                        widths[colIndex] = cellWidth
                    }
                }
            }
            
            widths
        }
    }
    
    val tableBlockDesc = stringResource(R.string.table_block)
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = tableBlockDesc },
        shape = RoundedCornerShape(4.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            // 使用horizontalScroll包装表格内容
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState)
            ) {
                Column(modifier = Modifier.wrapContentWidth(unbounded = true)) {
                    // 使用表格ID作为key
                    tableData.rows.forEachIndexed { rowIndex, row ->
                        val isHeader = rowIndex == 0 && tableData.hasHeader
                        
                        // 使用行索引和内容哈希作为复合key
                        val rowKey = "$componentId-row-$rowIndex-${row.joinToString("").hashCode()}"
                        
                        androidx.compose.runtime.key(rowKey) {
                            Row(
                                modifier = Modifier
                                    .then(
                                        if (isHeader) Modifier.background(headerBackground) else Modifier
                                    )
                                    .height(IntrinsicSize.Min)
                            ) {
                                row.forEachIndexed { colIndex, cell ->
                                    // 使用预计算的列宽
                                    val columnWidth = if (colIndex < columnWidths.size) {
                                        columnWidths[colIndex]
                                    } else {
                                        TABLE_MIN_COLUMN_WIDTH // 默认宽度
                                    }
                                    
                                    // 表格单元格
                                    Box(
                                        modifier = Modifier
                                            .width(columnWidth)
                                            .fillMaxHeight()
                                            .border(width = 0.5.dp, color = borderColor)
                                            .padding(8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = cell,
                                            color = textColor,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                            // 允许换行，配合列宽上限避免长文本撑爆表格
                                            softWrap = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 表格数据结构
 */
private data class TableData(
    val rows: List<List<String>>,
    val hasHeader: Boolean
)

/**
 * 解析Markdown表格内容
 */
private fun parseTable(content: String): TableData {
    fun isHeaderSeparatorLine(line: String): Boolean {
        return line.trim().matches(
            Regex("^\\s*\\|?\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)+\\|?\\s*$")
        )
    }

    fun parseCells(line: String): MutableList<String> {
        val trimmed = line.trim()
        var parts = trimmed.split('|').toMutableList()
        if (trimmed.startsWith("|")) {
            parts = parts.drop(1).toMutableList()
        }
        if (trimmed.endsWith("|") && parts.isNotEmpty()) {
            parts.removeAt(parts.lastIndex)
        }
        return parts
            .map { it.trim().replace(Regex("(?i)<br\\s*/?>"), "\n") }
            .toMutableList()
    }

    val lines = content.lines().filter { it.trim().isNotEmpty() && it.contains('|') }

    if (lines.isEmpty()) {
        return TableData(emptyList(), false)
    }

    val hasHeader = lines.size > 1 && isHeaderSeparatorLine(lines[1])

    val rawRows = mutableListOf<MutableList<String>>()
    var maxColumns = 0

    lines.forEachIndexed { index, line ->
        if (index == 1 && hasHeader) {
            return@forEachIndexed
        }

        val cells = parseCells(line)
        maxColumns = maxOf(maxColumns, cells.size)
        rawRows.add(cells)
    }

    val rows = rawRows.map { row ->
        while (row.size < maxColumns) {
            row.add("")
        }
        row.toList()
    }

    return TableData(rows, hasHeader)
}