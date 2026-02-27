package com.ai.assistance.custard.core.tools.system

/**
 * 定义工具权限的层级
 * - STANDARD: 基础权限，不需要特殊权限
 * - ROOT: 需要root权限
 * - DEBUGGER: 调试和开发用途的权限
 */
enum class AndroidPermissionLevel {
    STANDARD,      // 普通应用权限
    DEBUGGER,      // 调试权限
    ROOT;          // Root权限

    companion object {
        /**
         * 从字符串转换为权限等级
         * @param value 权限等级字符串
         * @return 对应的权限等级，如果无法识别则默认为STANDARD
         */
        fun fromString(value: String?): AndroidPermissionLevel {
            return when(value?.uppercase()) {
                "STANDARD" -> STANDARD
                "DEBUGGER" -> DEBUGGER
                "ROOT" -> ROOT
                else -> STANDARD // 默认为最低权限
            }
        }
    }
} 