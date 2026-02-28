package com.kymjs.ai.custard.ui.features.startup.screens

import androidx.compose.runtime.staticCompositionLocalOf

val LocalPluginLoadingState = staticCompositionLocalOf<PluginLoadingState> {
    error("LocalPluginLoadingState not provided")
}
