package com.kymjs.ai.custard.util

object ChatMarkupRegex {
    val toolCallPattern = Regex(
        "<tool\\s+name=\"([^\"]+)\">([\\s\\S]*?)</tool>",
        RegexOption.MULTILINE
    )

    val toolTag = Regex(
        "<tool\\b[\\s\\S]*?</tool>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val toolSelfClosingTag = Regex(
        "<tool\\b[^>]*/>",
        RegexOption.IGNORE_CASE
    )

    val toolResultTag = Regex(
        "<tool_result\\b[\\s\\S]*?</tool_result>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val toolResultSelfClosingTag = Regex(
        "<tool_result\\b[^>]*/>",
        RegexOption.IGNORE_CASE
    )

    val toolResultTagWithAttrs = Regex(
        "<tool_result([^>]*)>([\\s\\S]*?)</tool_result>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val toolResultAnyPattern = Regex(
        "<tool_result[^>]*>([\\s\\S]*?)</tool_result>",
        RegexOption.MULTILINE
    )

    val toolResultWithNameAnyPattern = Regex(
        "<tool_result[^>]*name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</tool_result>",
        RegexOption.MULTILINE
    )

    val toolOrToolResultBlock = Regex(
        "(<tool\\s+name=\"([^\"]+)\"[\\s\\S]*?</tool>)|(<tool_result([^>]*)>[\\s\\S]*?</tool_result>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val xmlStatusPattern = Regex(
        "<status\\s+type=\"([^\"]+)\"(?:\\s+uuid=\"([^\"]+)\")?(?:\\s+title=\"([^\"]+)\")?(?:\\s+subtitle=\"([^\"]+)\")?>([\\s\\S]*?)</status>"
    )

    val xmlToolResultPattern = Regex(
        "<tool_result\\s+name=\"([^\"]+)\"\\s+status=\"([^\"]+)\">\\s*<content>([\\s\\S]*?)</content>\\s*</tool_result>"
    )

    val xmlToolRequestPattern = Regex(
        "<tool\\s+name=\"([^\"]+)\"(?:\\s+description=\"([^\"]+)\")?>([\\s\\S]*?)</tool>"
    )

    val namePattern = Regex("<tool\\s+name=\"([^\"]+)\"")

    val toolParamPattern = Regex("<param\\s+name=\"([^\"]+)\">([\\s\\S]*?)</param>")

    val nameAttr = Regex("name\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    val statusAttr = Regex("status\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    val typeAttr = Regex("type\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    val titleAttr = Regex("title\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    val subtitleAttr = Regex("subtitle\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    val toolAttr = Regex("tool\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    val uuidAttr = Regex("uuid\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    val contentTag = Regex(
        "<content>([\\s\\S]*?)</content>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val errorTag = Regex(
        "<error>([\\s\\S]*?)</error>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val statusTag = Regex(
        "<status\\b[\\s\\S]*?</status>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val statusSelfClosingTag = Regex(
        "<status\\b[^>]*/>",
        RegexOption.IGNORE_CASE
    )

    val thinkTag = Regex(
        "<think(?:ing)?\\b[\\s\\S]*?</think(?:ing)?>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val thinkSelfClosingTag = Regex(
        "<think(?:ing)?\\b[^>]*/>",
        RegexOption.IGNORE_CASE
    )

    val searchTag = Regex(
        "<search\\b[\\s\\S]*?</search>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val searchSelfClosingTag = Regex(
        "<search\\b[^>]*/>",
        RegexOption.IGNORE_CASE
    )

    val emotionTag = Regex(
        "<emotion\\b[\\s\\S]*?</emotion>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val memoryTag = Regex(
        "<memory>.*?</memory>",
        RegexOption.DOT_MATCHES_ALL
    )

    val replyToTag = Regex(
        "<reply_to\\s+sender=\"([^\"]+)\"\\s+timestamp=\"([^\"]+)\">([^<]*)</reply_to>"
    )

    val attachmentDataTag = Regex(
        "<attachment\\s+id=\"([^\"]+)\"\\s+filename=\"([^\"]+)\"\\s+type=\"([^\"]+)\"(?:\\s+size=\"([^\"]+)\")?\\s*>([\\s\\S]*?)</attachment>"
    )

    val attachmentDataSelfClosingTag = Regex(
        "<attachment\\s+id=\"([^\"]+)\"\\s+filename=\"([^\"]+)\"\\s+type=\"([^\"]+)\"(?:\\s+size=\"([^\"]+)\")?(?:\\s+content=\"(.*?)\")?\\s*/>",
        RegexOption.DOT_MATCHES_ALL
    )

    val attachmentTag = Regex(
        "<attachment\\b[\\s\\S]*?</attachment>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val attachmentSelfClosingTag = Regex(
        "<attachment\\b[\\s\\S]*?/>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val workspaceAttachmentTag = Regex(
        "<workspace_attachment\\b[\\s\\S]*?</workspace_attachment>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val proxySenderTag = Regex(
        "<proxy_sender\\s+name=\"([^\"]+)\"\\s*/>",
        RegexOption.IGNORE_CASE
    )

    val anyXmlTag = Regex("<[^>]*>")

    val pruneToolResultContentPattern = Regex(
        "<tool_result (.*? status=[\"'](.*?)[\"'])>(.*?)</tool_result>",
        RegexOption.DOT_MATCHES_ALL
    )
}
