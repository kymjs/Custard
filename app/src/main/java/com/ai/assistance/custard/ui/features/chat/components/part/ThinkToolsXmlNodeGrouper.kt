package com.ai.assistance.custard.ui.features.chat.components.part

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.custard.R
import com.ai.assistance.custard.data.preferences.ToolCollapseMode
import com.ai.assistance.custard.ui.common.markdown.MarkdownGroupedItem
import com.ai.assistance.custard.ui.common.markdown.MarkdownNodeGrouper
import com.ai.assistance.custard.ui.common.markdown.XmlContentRenderer
import com.ai.assistance.custard.util.ChatMarkupRegex
import com.ai.assistance.custard.util.markdown.MarkdownNodeStable
import com.ai.assistance.custard.util.markdown.MarkdownProcessorType

class ThinkToolsXmlNodeGrouper(
    private val showThinkingProcess: Boolean,
    private val toolCollapseMode: ToolCollapseMode = ToolCollapseMode.ALL
) : MarkdownNodeGrouper {

    override fun group(nodes: List<MarkdownNodeStable>, rendererId: String): List<MarkdownGroupedItem> {
        val out = ArrayList<MarkdownGroupedItem>(nodes.size)
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]

            if (node.type != MarkdownProcessorType.XML_BLOCK) {
                out.add(MarkdownGroupedItem.Single(i))
                i++
                continue
            }

            val tag = extractXmlTagName(node.content)

            if (showThinkingProcess && (tag == "think" || tag == "thinking")) {
                var j = i + 1
                var toolCount = 0
                while (j < nodes.size) {
                    val next = nodes[j]
                    // 允许 think 与 tool/tool_result 之间出现纯空白文本（通常是换行）
                    if (next.type == MarkdownProcessorType.PLAIN_TEXT && next.content.isBlank()) {
                        j++
                        continue
                    }
                    if (next.type != MarkdownProcessorType.XML_BLOCK) break

                    val nextTag = extractXmlTagName(next.content)
                    val isThinkAgain = nextTag == "think" || nextTag == "thinking"
                    val isToolRelated = nextTag == "tool" || nextTag == "tool_result"
                    if (!isThinkAgain && !isToolRelated) break

                    if (isToolRelated) {
                        val toolName = extractToolNameFromToolOrResult(next.content)
                        if (!shouldGroupToolByName(toolName, toolCollapseMode)) break
                        if (nextTag == "tool") toolCount++
                    }

                    j++
                }

                if (toolCount > 0) {
                    out.add(
                        MarkdownGroupedItem.Group(
                            startIndex = i,
                            endIndexInclusive = j - 1,
                            stableKey = "think-tools-$i"
                        )
                    )
                    i = j
                    continue
                }

                out.add(MarkdownGroupedItem.Single(i))
                i++
                continue
            }

            if (tag == "tool" || tag == "tool_result") {
                val firstToolName = extractToolNameFromToolOrResult(node.content)
                if (!shouldGroupToolByName(firstToolName, toolCollapseMode)) {
                    out.add(MarkdownGroupedItem.Single(i))
                    i++
                    continue
                }

                var j = i + 1
                var toolCount = if (tag == "tool") 1 else 0
                var xmlToolRelatedCount = 1

                while (j < nodes.size) {
                    val next = nodes[j]
                    if (next.type == MarkdownProcessorType.PLAIN_TEXT && next.content.isBlank()) {
                        j++
                        continue
                    }
                    if (next.type != MarkdownProcessorType.XML_BLOCK) break

                    val nextTag = extractXmlTagName(next.content)
                    val isToolRelated = nextTag == "tool" || nextTag == "tool_result"
                    if (!isToolRelated) break

                    val toolName = extractToolNameFromToolOrResult(next.content)
                    if (!shouldGroupToolByName(toolName, toolCollapseMode)) break

                    xmlToolRelatedCount++
                    if (nextTag == "tool") toolCount++
                    j++
                }

                if (toolCount >= 2 && xmlToolRelatedCount >= 2) {
                    out.add(
                        MarkdownGroupedItem.Group(
                            startIndex = i,
                            endIndexInclusive = j - 1,
                            stableKey = "tools-only-$i"
                        )
                    )
                    i = j
                } else {
                    out.add(MarkdownGroupedItem.Single(i))
                    i++
                }
                continue
            }

            out.add(MarkdownGroupedItem.Single(i))
            i++
        }

        return out
    }

    @Composable
    override fun RenderGroup(
        group: MarkdownGroupedItem.Group,
        nodes: List<MarkdownNodeStable>,
        rendererId: String,
        isVisible: Boolean,
        isLastNode: Boolean,
        modifier: Modifier,
        textColor: Color,
        onLinkClick: ((String) -> Unit)?,
        xmlRenderer: XmlContentRenderer,
        fillMaxWidth: Boolean
    ) {
        val alpha by animateFloatAsState(
            targetValue = if (isVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 800),
            label = "fadeIn-think-tools-$rendererId"
        )

        val sliceEndExclusive = (group.endIndexInclusive + 1).coerceAtMost(nodes.size)
        val slice = if (group.startIndex in 0 until sliceEndExclusive) {
            nodes.subList(group.startIndex, sliceEndExclusive)
        } else {
            emptyList()
        }

        val toolCount = slice.count {
            it.type == MarkdownProcessorType.XML_BLOCK && extractXmlTagName(it.content) == "tool"
        }

        val isInProgress = slice.any {
            it.type == MarkdownProcessorType.XML_BLOCK && !isXmlFullyClosed(it.content)
        }

        fun isConformingTailNode(node: MarkdownNodeStable): Boolean {
            return when (node.type) {
                MarkdownProcessorType.PLAIN_TEXT -> node.content.isBlank()
                MarkdownProcessorType.XML_BLOCK -> {
                    val tag = extractXmlTagName(node.content)
                    when (tag) {
                        "think", "thinking" -> true
                        "tool", "tool_result" -> {
                            val toolName = extractToolNameFromToolOrResult(node.content)
                            if (toolName == null && !isXmlFullyClosed(node.content)) {
                                true
                            } else {
                                shouldGroupToolByName(toolName, toolCollapseMode)
                            }
                        }
                        null -> !isXmlFullyClosed(node.content)
                        else -> false
                    }
                }
                else -> false
            }
        }

        val tailStartIndex = (group.endIndexInclusive + 1).coerceAtMost(nodes.size)
        val hasNonConformingAfterGroup =
            if (tailStartIndex >= nodes.size) {
                false
            } else {
                nodes.subList(tailStartIndex, nodes.size).any { !isConformingTailNode(it) }
            }
        val shouldAutoExpand = !hasNonConformingAfterGroup

        var expanded by remember(rendererId, group.stableKey) { mutableStateOf(shouldAutoExpand) }
        var userOverride by remember(rendererId, group.stableKey) { mutableStateOf<Boolean?>(null) }
        val appearedKeys = remember(rendererId, group.stableKey) { mutableStateMapOf<String, Boolean>() }

        LaunchedEffect(shouldAutoExpand, userOverride) {
            if (userOverride != null) return@LaunchedEffect
            expanded = shouldAutoExpand
        }

        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 4.dp)
                    .graphicsLayer { this.alpha = alpha }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val newExpanded = !expanded
                        expanded = newExpanded
                        userOverride = newExpanded
                    },
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = tween(durationMillis = 300),
                    label = "arrowRotation-think-tools-$rendererId"
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = stringResource(
                        id = if (group.stableKey.startsWith("tools-only-")) {
                            R.string.tools_group_title_with_count
                        } else {
                            R.string.thinking_tools_group_title_with_count
                        },
                        toolCount
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor.copy(alpha = 0.7f)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp, start = 24.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        slice.forEachIndexed { idx, node ->
                            val absoluteIndex = group.startIndex + idx
                            val innerKey = "think-tools-$rendererId-${group.stableKey}-$absoluteIndex"
                            androidx.compose.runtime.key(innerKey) {
                                if (node.type == MarkdownProcessorType.XML_BLOCK) {
                                    val alreadyAppeared = appearedKeys[innerKey] == true
                                    var itemVisible by remember(innerKey, alreadyAppeared) {
                                        mutableStateOf(alreadyAppeared)
                                    }

                                    LaunchedEffect(innerKey) {
                                        if (!alreadyAppeared) {
                                            itemVisible = true
                                            appearedKeys[innerKey] = true
                                        }
                                    }

                                    val itemAlpha by animateFloatAsState(
                                        targetValue = if (itemVisible) 1f else 0f,
                                        animationSpec = tween(durationMillis = 800),
                                        label = "fadeIn-$innerKey"
                                    )

                                    xmlRenderer.RenderXmlContent(
                                        xmlContent = node.content,
                                        modifier = Modifier.fillMaxWidth().graphicsLayer { this.alpha = itemAlpha },
                                        textColor = textColor
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

private fun extractXmlTagName(xml: String): String? {
    val openTagRegex = "<([a-zA-Z_][a-zA-Z0-9_]*)".toRegex()
    return openTagRegex.find(xml.trim())?.groupValues?.getOrNull(1)
}

private fun extractToolName(xml: String): String? {
    val nameMatch = ChatMarkupRegex.nameAttr.find(xml)
    return nameMatch?.groupValues?.getOrNull(1)
}

private fun isXmlFullyClosed(xml: String): Boolean {
    val tagName = extractXmlTagName(xml) ?: return false
    val trimmed = xml.trim()
    if (trimmed.endsWith("/>") || trimmed.startsWith("<$tagName") && trimmed.endsWith("/>") ) {
        return true
    }
    return trimmed.contains("</$tagName>")
}

private fun extractToolNameFromToolOrResult(xml: String): String? {
    val tag = extractXmlTagName(xml)
    return when (tag) {
        "tool", "tool_result" -> extractToolName(xml)
        else -> null
    }
}

private fun shouldGroupToolByName(
    toolName: String?,
    toolCollapseMode: ToolCollapseMode
): Boolean {
    if (toolCollapseMode == ToolCollapseMode.ALL) {
        return true
    }

    val n = toolName?.trim()?.lowercase() ?: return false
    if (n.contains("search")) return true
    return n in setOf(
        "list_files",
        "grep_code",
        "grep_context",
        "read_file",
        "read_file_part",
        "read_file_full",
        "read_file_binary",
        "use_package",
        "find_files",
        "visit_web"
    )
}
