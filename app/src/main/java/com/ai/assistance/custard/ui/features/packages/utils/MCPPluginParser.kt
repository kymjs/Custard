package com.ai.assistance.custard.ui.features.packages.utils

import com.ai.assistance.custard.util.AppLogger
import com.ai.assistance.custard.data.api.GitHubIssue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNames

/**
 * MCP 插件信息解析工具类
 */
object MCPPluginParser {
    private const val TAG = "MCPPluginParser"

    private val DESCRIPTION_LABEL_WORDS = setOf(
        "description",
        "desc",
        "summary",
        "introduction",
        "简介",
        "描述",
        "介绍",
        "说明"
    )

    private fun isLabelOnlyLine(raw: String): Boolean {
        val normalized = raw
            .replace("*", "")
            .replace("_", "")
            .trim()
            .trimEnd(':', '：')
        if (normalized.isBlank()) return false

        val parts = normalized
            .split('/', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return false

        return parts.all { part ->
            DESCRIPTION_LABEL_WORDS.contains(part.lowercase())
        }
    }

    private fun extractHumanDescriptionFromBody(body: String): String {
        if (body.isBlank()) return ""

        val withoutComments = body.replace(Regex("<!--[\\s\\S]*?-->"), "\n")
        val withoutCodeBlocks = withoutComments.replace(Regex("```[\\s\\S]*?```"), "\n")

        val sb = StringBuilder()
        val paragraphs = mutableListOf<String>()

        fun flush() {
            val p = sb.toString().trim()
            if (p.isNotBlank()) paragraphs.add(p)
            sb.clear()
        }

        for (rawLine in withoutCodeBlocks.lines()) {
            val t0 = rawLine.trim()
            if (t0.isBlank()) {
                flush()
                continue
            }

            if (isLabelOnlyLine(t0)) continue

            if (t0.startsWith("#")) continue
            if (t0.startsWith("|")) continue
            if (t0 == "---") continue

            val t = t0
                .replace(Regex("^\\*\\*[^*]+\\*\\*\\s*[:：]\\s*"), "")
                .replace(
                    Regex(
                        "^(描述|简介|介绍|说明|description|desc|summary|introduction)\\s*[:：]\\s*",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .trim()
            if (t.isBlank()) continue

            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)

            if (sb.length >= 400) {
                flush()
                break
            }
        }
        flush()

        val candidate = paragraphs.firstOrNull { p ->
            p.length >= 6 &&
                !p.startsWith("{") &&
                !p.contains("custard-", ignoreCase = true)
        }

        return candidate?.take(300)?.trim().orEmpty()
    }

    @Serializable
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    data class MCPMetadata(
        val description: String = "",
        val repositoryUrl: String,
        @JsonNames("installCommand") // 兼容旧的 installCommand
        val installConfig: String, // 改为安装配置
        val category: String,
        val tags: String,
        val version: String
    )

    data class ParsedPluginInfo(
        val title: String,
        val description: String,
        val repositoryUrl: String = "",
        val installConfig: String = "", // 改为安装配置
        val category: String = "",
        val tags: String = "",
        val version: String = "",
        val repositoryOwner: String = "", // 仓库所有者（插件作者）
        val repositoryOwnerAvatarUrl: String = "" // 仓库所有者头像URL
    )

    /**
     * 从 GitHub Issue 解析插件信息
     */
    fun parsePluginInfo(issue: GitHubIssue): ParsedPluginInfo {
        val body = issue.body ?: return ParsedPluginInfo(
            title = issue.title,
            description = "无描述信息"
        )

        // 尝试解析 JSON 元数据
        val metadata = parseMCPMetadata(body)
        val extractedDescription = extractHumanDescriptionFromBody(body)

        return if (metadata != null) {
            ParsedPluginInfo(
                title = issue.title,
                description = metadata.description.ifBlank { extractedDescription.ifBlank { "无描述信息" } },
                repositoryUrl = metadata.repositoryUrl,
                installConfig = metadata.installConfig,
                category = metadata.category,
                tags = metadata.tags,
                version = metadata.version,
                repositoryOwner = extractRepositoryOwner(metadata.repositoryUrl)
            )
        } else {
            ParsedPluginInfo(
                title = issue.title,
                description = extractedDescription.ifBlank { "无描述信息" },
                repositoryUrl = "",
                repositoryOwner = ""
            )
        }
    }

    /**
     * 解析隐藏在注释中的 JSON 元数据
     */
    private fun parseMCPMetadata(body: String): MCPMetadata? {
        val prefix = "<!-- custard-mcp-json: "
        val start = body.indexOf(prefix)
        if (start < 0) return null

        val jsonStart = start + prefix.length
        val end = body.indexOf(" -->", startIndex = jsonStart)
        if (end <= jsonStart) return null

        val jsonString = body.substring(jsonStart, end)
        return try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<MCPMetadata>(jsonString)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse MCP metadata JSON from issue body.", e)
            null
        }
    }

    /**
     * 从GitHub URL中提取仓库所有者
     */
    private fun extractRepositoryOwner(repositoryUrl: String): String {
        if (repositoryUrl.isBlank()) return ""
        
        val githubPattern = Regex("""github\.com/([^/]+)/([^/]+)""")
        val match = githubPattern.find(repositoryUrl)
        
        return match?.groupValues?.get(1) ?: ""
    }
} 