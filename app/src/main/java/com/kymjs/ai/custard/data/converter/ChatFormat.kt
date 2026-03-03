package com.kymjs.ai.custard.data.converter

/**
 * 支持的聊天记录格式
 */
enum class ChatFormat {
    /** Custard 原生格式 */
    CUSTARD,
    
    /** ChatGPT conversations.json 格式 */
    CHATGPT,
    
    /** ChatBox 导出格式 */
    CHATBOX,
    
    /** Claude 导出格式 */
    CLAUDE,
    
    /** Markdown 格式 */
    MARKDOWN,
    
    /** 通用 JSON 格式 (role-content 结构) */
    GENERIC_JSON,
    
    /** CSV 格式 */
    CSV,
    
    /** 纯文本格式 */
    PLAIN_TEXT,
    
    /** 未知格式 */
    UNKNOWN
}

/**
 * 导出格式
 */
enum class ExportFormat {
    /** JSON 格式（Custard原生） */
    JSON,
    
    /** Markdown 格式 */
    MARKDOWN,
    
    /** HTML 格式 */
    HTML,
    
    /** 纯文本格式 */
    TXT,
    
    /** CSV 格式 */
    CSV
}
