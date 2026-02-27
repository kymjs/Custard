package com.ai.assistance.custard.terminal.main

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.assistance.custard.terminal.TerminalEnv
import com.ai.assistance.custard.terminal.TerminalManager
import com.ai.assistance.custard.terminal.ui.SetupScreen
import com.ai.assistance.custard.terminal.ui.TerminalHome
import com.ai.assistance.custard.terminal.ui.SettingsScreen
import com.ai.assistance.custard.terminal.utils.UpdateChecker

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalScreen(
    env: TerminalEnv
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    var startDestination by remember { mutableStateOf<String?>(null) }

    val manager = remember { TerminalManager.getInstance(context) }
    val terminalState by manager.terminalState.collectAsState()
    val isTerminalReady = terminalState.currentSession?.isInitializing == false
    
    // 更新检查器
    val updateChecker = remember { UpdateChecker(context) }

    LaunchedEffect(Unit) {
        val sharedPreferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val isFirstLaunch = sharedPreferences.getBoolean("is_first_launch", true)
        startDestination = when {
            env.forceShowSetup -> TerminalRoutes.SETUP_ROUTE
            isFirstLaunch -> TerminalRoutes.SETUP_ROUTE
            else -> TerminalRoutes.TERMINAL_HOME_ROUTE
        }
        updateChecker.checkForUpdates(showToast = true)
    }

    // 使用 NavHost 处理所有导航
    NavHost(
        navController = navController,
        startDestination = if (startDestination != null) startDestination!! else TerminalRoutes.TERMINAL_HOME_ROUTE
    ) {
        
        composable(TerminalRoutes.TERMINAL_HOME_ROUTE) {
            TerminalHome(
                env = env,
                onNavigateToSetup = {
                    navController.navigate(TerminalRoutes.SETUP_ROUTE)
                },
                onNavigateToSettings = {
                    navController.navigate(TerminalRoutes.SETTINGS_ROUTE)
                }
            )
        }
        
        composable(TerminalRoutes.SETUP_ROUTE) {
            SetupScreen(
                onBack = {
                    val sharedPreferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
                    sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
                    navController.navigate(TerminalRoutes.TERMINAL_HOME_ROUTE) {
                        popUpTo(TerminalRoutes.SETUP_ROUTE) { inclusive = true }
                    }
                },
                onSetup = { commands ->
                    val sharedPreferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
                    sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
                    env.onSetup(commands)
                    navController.navigate(TerminalRoutes.TERMINAL_HOME_ROUTE) {
                        popUpTo(TerminalRoutes.SETUP_ROUTE) { inclusive = true }
                    }
                }
            )
        }
        
        composable(TerminalRoutes.SETTINGS_ROUTE) {
            SettingsScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
    
    // 当确定了目标页面后，导航到相应页面
    LaunchedEffect(startDestination) {
        if (startDestination != null && navController.currentBackStackEntry?.destination?.route != startDestination) {
            navController.navigate(startDestination!!) {
                popUpTo(TerminalRoutes.TERMINAL_HOME_ROUTE) { inclusive = true }
            }
        }
    }
} 