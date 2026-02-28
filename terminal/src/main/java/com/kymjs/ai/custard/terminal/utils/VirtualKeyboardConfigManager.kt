package com.kymjs.ai.custard.terminal.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class VirtualKeyAction(val persistedValue: String) {
    SEND_TEXT("send_text"),
    TOGGLE_CTRL("toggle_ctrl"),
    TOGGLE_ALT("toggle_alt");

    companion object {
        fun fromPersistedValue(value: String): VirtualKeyAction {
            return entries.firstOrNull { it.persistedValue == value } ?: SEND_TEXT
        }
    }
}

data class VirtualKeyboardButtonConfig(
    val label: String,
    val value: String,
    val action: VirtualKeyAction = VirtualKeyAction.SEND_TEXT
)

data class VirtualKeyboardLayoutConfig(
    val rows: List<List<VirtualKeyboardButtonConfig>>
) {
    init {
        require(rows.size == ROW_COUNT) { "Virtual keyboard must have $ROW_COUNT rows." }
        require(rows.all { it.size == COLUMN_COUNT }) { "Each row must have $COLUMN_COUNT keys." }
    }

    companion object {
        const val ROW_COUNT = 2
        const val COLUMN_COUNT = 7
    }
}

class VirtualKeyboardConfigManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadLayout(): VirtualKeyboardLayoutConfig {
        val rawLayout = prefs.getString(PREF_KEY_VIRTUAL_KEYBOARD_LAYOUT, null) ?: return defaultLayout()
        return runCatching { parseLayout(rawLayout) }.getOrElse { defaultLayout() }
    }

    fun saveLayout(layout: VirtualKeyboardLayoutConfig) {
        prefs.edit().putString(PREF_KEY_VIRTUAL_KEYBOARD_LAYOUT, serializeLayout(layout)).apply()
    }

    fun resetToDefault() {
        prefs.edit().remove(PREF_KEY_VIRTUAL_KEYBOARD_LAYOUT).apply()
    }

    private fun parseLayout(rawLayout: String): VirtualKeyboardLayoutConfig {
        val root = JSONObject(rawLayout)
        val rowsJson = root.getJSONArray(JSON_KEY_ROWS)
        val rows = mutableListOf<List<VirtualKeyboardButtonConfig>>()

        for (rowIndex in 0 until rowsJson.length()) {
            val rowJson = rowsJson.getJSONArray(rowIndex)
            val rowButtons = mutableListOf<VirtualKeyboardButtonConfig>()
            for (columnIndex in 0 until rowJson.length()) {
                val buttonJson = rowJson.getJSONObject(columnIndex)
                rowButtons.add(
                    VirtualKeyboardButtonConfig(
                        label = buttonJson.getString(JSON_KEY_LABEL),
                        value = buttonJson.optString(JSON_KEY_VALUE, ""),
                        action = VirtualKeyAction.fromPersistedValue(
                            buttonJson.optString(JSON_KEY_ACTION, VirtualKeyAction.SEND_TEXT.persistedValue)
                        )
                    )
                )
            }
            rows.add(rowButtons)
        }

        return VirtualKeyboardLayoutConfig(rows)
    }

    private fun serializeLayout(layout: VirtualKeyboardLayoutConfig): String {
        val rowsJson = JSONArray()
        layout.rows.forEach { row ->
            val rowJson = JSONArray()
            row.forEach { button ->
                rowJson.put(
                    JSONObject().apply {
                        put(JSON_KEY_LABEL, button.label)
                        put(JSON_KEY_VALUE, button.value)
                        put(JSON_KEY_ACTION, button.action.persistedValue)
                    }
                )
            }
            rowsJson.put(rowJson)
        }

        return JSONObject().apply {
            put(JSON_KEY_ROWS, rowsJson)
        }.toString()
    }

    companion object {
        const val PREFS_NAME = "terminal_settings"
        const val PREF_KEY_VIRTUAL_KEYBOARD_LAYOUT = "virtual_keyboard_layout_v1"

        private const val JSON_KEY_ROWS = "rows"
        private const val JSON_KEY_LABEL = "label"
        private const val JSON_KEY_VALUE = "value"
        private const val JSON_KEY_ACTION = "action"

        @Volatile
        private var instance: VirtualKeyboardConfigManager? = null

        fun getInstance(context: Context): VirtualKeyboardConfigManager {
            return instance ?: synchronized(this) {
                instance ?: VirtualKeyboardConfigManager(context.applicationContext).also { instance = it }
            }
        }

        fun defaultLayout(): VirtualKeyboardLayoutConfig {
            return VirtualKeyboardLayoutConfig(
                rows = listOf(
                    listOf(
                        VirtualKeyboardButtonConfig(label = "ESC", value = "\\e"),
                        VirtualKeyboardButtonConfig(label = "/", value = "/"),
                        VirtualKeyboardButtonConfig(label = "-", value = "-"),
                        VirtualKeyboardButtonConfig(label = "HOME", value = "\\e[H"),
                        VirtualKeyboardButtonConfig(label = "↑", value = "\\e[A"),
                        VirtualKeyboardButtonConfig(label = "END", value = "\\e[F"),
                        VirtualKeyboardButtonConfig(label = "PGUP", value = "\\e[5~")
                    ),
                    listOf(
                        VirtualKeyboardButtonConfig(label = "TAB", value = "\\t"),
                        VirtualKeyboardButtonConfig(label = "CTRL", value = "", action = VirtualKeyAction.TOGGLE_CTRL),
                        VirtualKeyboardButtonConfig(label = "ALT", value = "", action = VirtualKeyAction.TOGGLE_ALT),
                        VirtualKeyboardButtonConfig(label = "←", value = "\\e[D"),
                        VirtualKeyboardButtonConfig(label = "↓", value = "\\e[B"),
                        VirtualKeyboardButtonConfig(label = "→", value = "\\e[C"),
                        VirtualKeyboardButtonConfig(label = "PGDN", value = "\\e[6~")
                    )
                )
            )
        }
    }
}
