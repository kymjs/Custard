package com.ai.assistance.custard.util.streamnative

import com.ai.assistance.custard.util.markdown.MarkdownNode
import com.ai.assistance.custard.util.markdown.MarkdownProcessorType

object NativeMarkdownParser {

    init {
        System.loadLibrary("streamnative")
    }

    private external fun nativeParseMarkdown(content: String): IntArray

    fun parseToNodes(content: String): List<MarkdownNode> {
        val data = nativeParseMarkdown(content)
        if (data.isEmpty()) return emptyList()

        var idx = 0
        val blockCount = data[idx++]
        val nodes = ArrayList<MarkdownNode>(blockCount)

        repeat(blockCount) {
            if (idx >= data.size) return@repeat

            val blockTypeOrdinal = data[idx++]
            val blockType =
                MarkdownProcessorType.entries.getOrNull(blockTypeOrdinal)
                    ?: MarkdownProcessorType.PLAIN_TEXT

            val node = MarkdownNode(blockType)

            if (idx >= data.size) {
                nodes.add(node)
                return@repeat
            }

            val blockPieceCount = data[idx++]
            repeat(blockPieceCount) {
                if (idx + 1 >= data.size) return@repeat
                val start = data[idx++]
                val end = data[idx++]
                if (start >= 0 && end >= start && end <= content.length) {
                    node.content + content.substring(start, end)
                }
            }

            if (idx >= data.size) {
                nodes.add(node)
                return@repeat
            }

            val inlineCount = data[idx++]
            repeat(inlineCount) {
                if (idx >= data.size) return@repeat
                val inlineTypeOrdinal = data[idx++]
                val inlineType =
                    MarkdownProcessorType.entries.getOrNull(inlineTypeOrdinal)
                        ?: MarkdownProcessorType.PLAIN_TEXT

                if (idx >= data.size) return@repeat
                val inlinePieceCount = data[idx++]

                val child = MarkdownNode(inlineType)
                var hasAnyPiece = false
                repeat(inlinePieceCount) {
                    if (idx + 1 >= data.size) return@repeat
                    val start = data[idx++]
                    val end = data[idx++]
                    if (start >= 0 && end >= start && end <= content.length) {
                        val s = content.substring(start, end)
                        child.content + s
                        node.content + s
                        hasAnyPiece = true
                    }
                }

                if (hasAnyPiece) {
                    node.children.add(child)
                }
            }

            nodes.add(node)
        }

        return nodes
    }
}
