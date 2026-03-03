package com.kymjs.ai.custard.data.preferences

enum class ToolCollapseMode(val value: String) {
    READ_ONLY("read_only"),
    ALL("all");

    companion object {
        fun fromValue(value: String?): ToolCollapseMode {
            return values().firstOrNull { it.value == value } ?: ALL
        }
    }
}
