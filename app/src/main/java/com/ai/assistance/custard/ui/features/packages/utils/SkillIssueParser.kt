package com.ai.assistance.custard.ui.features.packages.utils

import com.ai.assistance.custard.data.api.GitHubIssue
import com.ai.assistance.custard.util.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

object SkillIssueParser {
    private const val TAG = "SkillIssueParser"

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
                !p.contains("operit-", ignoreCase = true)
        }

        return candidate?.take(300)?.trim().orEmpty()
    }

    @Serializable
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    data class SkillMetadata(
        val description: String = "",
        @JsonNames("repoUrl")
        val repositoryUrl: String,
        val category: String = "",
        val tags: String = "",
        @JsonNames("version")
        val version: String = ""
    )

    data class ParsedSkillInfo(
        val title: String,
        val description: String,
        val repositoryUrl: String = "",
        val category: String = "",
        val tags: String = "",
        val version: String = "",
        val repositoryOwner: String = ""
    )

    fun parseSkillInfo(issue: GitHubIssue): ParsedSkillInfo {
        val body = issue.body
        if (body.isNullOrBlank()) {
            return ParsedSkillInfo(
                title = issue.title,
                description = "无描述信息"
            )
        }

        val metadata = parseSkillMetadata(body)
        val extractedDescription = extractHumanDescriptionFromBody(body)

        return if (metadata != null) {
            ParsedSkillInfo(
                title = issue.title,
                description = metadata.description.ifBlank { extractedDescription.ifBlank { "无描述信息" } },
                repositoryUrl = metadata.repositoryUrl,
                category = metadata.category,
                tags = metadata.tags,
                version = metadata.version,
                repositoryOwner = extractRepositoryOwner(metadata.repositoryUrl)
            )
        } else {
            ParsedSkillInfo(
                title = issue.title,
                description = extractedDescription.ifBlank { "无描述信息" },
                repositoryUrl = "",
                repositoryOwner = ""
            )
        }
    }

    private fun parseSkillMetadata(body: String): SkillMetadata? {
        val prefix = "<!-- operit-skill-json: "
        val start = body.indexOf(prefix)
        if (start < 0) return null

        val jsonStart = start + prefix.length
        val end = body.indexOf(" -->", startIndex = jsonStart)
        if (end <= jsonStart) return null

        val jsonString = body.substring(jsonStart, end)
        return try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<SkillMetadata>(jsonString)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse skill metadata JSON from issue body.", e)
            null
        }
    }

    private fun extractRepositoryOwner(repositoryUrl: String): String {
        if (repositoryUrl.isBlank()) return ""

        val githubPattern = Regex("""github\.com/([^/]+)/([^/]+)""")
        val match = githubPattern.find(repositoryUrl)

        return match?.groupValues?.get(1) ?: ""
    }
}
