package com.ai.assistance.custard.data.preferences

import android.content.Context

/**
 * 默认角色卡提示词的双语数据
 */
object CharacterCardBilingualData {

    /**
     * 获取默认角色卡描述
     */
    fun getDefaultDescription(context: Context): String {
        return if (isChineseLocale(context)) {
            "系统默认的角色卡配置"
        } else {
            "System default character card configuration"
        }
    }

    /**
     * 获取默认角色设定
     */
    fun getDefaultCharacterSetting(context: Context): String {
        return if (isChineseLocale(context)) {
            "你是Operit，一个全能AI助手，旨在解决用户提出的任何任务。"
        } else {
            "You are Operit, an all-purpose AI assistant designed to help users solve any task."
        }
    }

    /**
     * 获取默认其他内容
     */
    fun getDefaultOtherContent(context: Context): String {
        return if (isChineseLocale(context)) {
            "保持有帮助的语气，并清楚地传达限制。"
        } else {
            "Maintain a helpful tone and clearly communicate limitations."
        }
    }

    /**
     * 获取角色描述标签
     */
    fun getCharacterDescriptionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "角色描述："
        } else {
            "Character Description:"
        }
    }

    /**
     * 获取性格特征标签
     */
    fun getPersonalityLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "性格特征："
        } else {
            "Personality:"
        }
    }

    /**
     * 获取场景设定标签
     */
    fun getScenarioLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "场景设定："
        } else {
            "Scenario Setting:"
        }
    }

    /**
     * 获取对话示例标签
     */
    fun getDialogueExampleLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "对话示例："
        } else {
            "Dialogue Examples:"
        }
    }

    /**
     * 获取系统提示词标签
     */
    fun getSystemPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "系统提示词："
        } else {
            "System Prompt:"
        }
    }

    /**
     * 获取历史指令标签
     */
    fun getPostHistoryInstructionsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "历史指令："
        } else {
            "Post-History Instructions:"
        }
    }

    /**
     * 获取备用问候语标签
     */
    fun getAlternateGreetingsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "备用问候语："
        } else {
            "Alternate Greetings:"
        }
    }

    /**
     * 获取深度提示词标签
     */
    fun getDepthPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "深度提示词："
        } else {
            "Depth Prompt:"
        }
    }

    /**
     * 获取世界书标签名称模板
     */
    fun getWorldBookTagName(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "世界书: $characterName"
        } else {
            "World Book: $characterName"
        }
    }

    /**
     * 获取世界书标签描述模板
     */
    fun getWorldBookTagDescription(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "为角色'$characterName'自动生成的世界书。"
        } else {
            "World book auto-generated for character '$characterName'."
        }
    }

    /**
     * 获取来源标签
     */
    fun getSourceLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "来源：酒馆角色卡\n"
        } else {
            "Source: Tavern Character Card\n"
        }
    }

    /**
     * 获取作者标签
     */
    fun getAuthorLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者："
        } else {
            "Author:"
        }
    }

    /**
     * 获取作者备注标签
     */
    fun getAuthorNotesLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者备注：\n\n"
        } else {
            "Author Notes:\n\n"
        }
    }

    /**
     * 获取版本标签
     */
    fun getVersionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "版本："
        } else {
            "Version:"
        }
    }

    /**
     * 获取原始标签标签
     */
    fun getOriginalTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "原始标签："
        } else {
            "Original Tags:"
        }
    }

    /**
     * 获取格式标签
     */
    fun getFormatLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "格式："
        } else {
            "Format:"
        }
    }

    /**
     * 获取标签标签
     */
    fun getTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "标签："
        } else {
            "Tags:"
        }
    }

    /**
     * 获取等标签
     */
    fun getEtAlLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "等"
        } else {
            " et al."
        }
    }

    /**
     * 获取未找到标签
     */
    fun getNotFoundLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "未找到"
        } else {
            "not found"
        }
    }

    /**
     * 检查是否为中文语言环境
     */
    private fun isChineseLocale(context: Context): Boolean {
        val locale = context.resources.configuration.locales.get(0)
        return locale.language == "zh" || locale.language == "zho"
    }
}
