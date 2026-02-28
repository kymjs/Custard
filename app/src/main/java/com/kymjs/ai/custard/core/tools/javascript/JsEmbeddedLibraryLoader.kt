package com.kymjs.ai.custard.core.tools.javascript

import android.content.Context
import com.kymjs.ai.custard.util.AppLogger

internal fun loadPakoJs(context: Context): String {
    return try {
        context.assets.open("js/pako.js").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        AppLogger.e("JsEngine", "Failed to load pako.js", e)
        "// pako.js failed to load"
    }
}

internal fun loadCryptoJs(context: Context): String {
    return try {
        context.assets.open("js/CryptoJS.js").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        AppLogger.e("JsEngine", "Failed to load CryptoJS.js", e)
        "// CryptoJS.js failed to load"
    }
}

internal fun loadJimpJs(context: Context): String {
    return try {
        context.assets.open("js/Jimp.js").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        AppLogger.e("JsEngine", "Failed to load Jimp.js", e)
        "// Jimp.js failed to load"
    }
}

