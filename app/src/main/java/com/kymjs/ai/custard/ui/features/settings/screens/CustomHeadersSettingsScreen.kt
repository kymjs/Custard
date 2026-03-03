package com.kymjs.ai.custard.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kymjs.ai.custard.ui.components.CustomScaffold
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.data.preferences.ApiPreferences
import kotlinx.coroutines.launch
import org.json.JSONObject

// --- 预设数据结构 ---
private data class HeaderPreset(@StringRes val nameResId: Int, val headers: Map<String, String>)

// --- 预设列表 ---
private val headerPresets = listOf(
    HeaderPreset(
        nameResId = R.string.headers_preset_android_browser,
        headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
    ),
    HeaderPreset(
        nameResId = R.string.headers_preset_desktop_browser_win,
        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
    ),
    HeaderPreset(
        nameResId = R.string.headers_preset_lang_zh,
        headers = mapOf("Accept-Language" to "zh-CN,zh;q=0.9")
    ),
    HeaderPreset(
        nameResId = R.string.headers_preset_lang_en,
        headers = mapOf("Accept-Language" to "en-US,en;q=0.9")
    ),
    HeaderPreset(
        nameResId = R.string.headers_preset_cmcc_gateway,
        headers = mapOf("X-Forwarded-For" to "211.136.1.10", "Via" to "CMNET")
    ),
    HeaderPreset(
        nameResId = R.string.headers_preset_us_la,
        headers = mapOf(
            "Accept-Language" to "en-US,en;q=0.9",
            "X-Forwarded-For" to "38.107.226.5" // An example IP from LA
        )
    )
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomHeadersSettingsScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    val apiPreferences = remember { ApiPreferences.getInstance(context) }
    val scope = rememberCoroutineScope()

    var headers by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showSaveSuccessMessage by remember { mutableStateOf(false) }
    var showPresetsMenu by remember { mutableStateOf(false) }

    // Load initial headers
    LaunchedEffect(Unit) {
        val headersJson = apiPreferences.getCustomHeaders()
        if (headersJson.isNotEmpty()) {
            val jsonObject = JSONObject(headersJson)
            val loadedHeaders = mutableListOf<Pair<String, String>>()
            for (key in jsonObject.keys()) {
                loadedHeaders.add(key to jsonObject.getString(key))
            }
            headers = loadedHeaders
        }
    }

    CustomScaffold(
        floatingActionButton = {
            Column {
                FloatingActionButton(
                    onClick = {
                        headers = headers + ("" to "")
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.headers_add_header))
                }
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            val jsonObject = JSONObject()
                            headers.forEach { (key, value) ->
                                if (key.isNotEmpty()) {
                                    jsonObject.put(key, value)
                                }
                            }
                            apiPreferences.saveCustomHeaders(jsonObject.toString())
                            showSaveSuccessMessage = true
                        }
                    }
                ) {
                    Icon(Icons.Default.Save, contentDescription = stringResource(R.string.headers_save_headers))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            // --- 预设按钮 ---
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    OutlinedButton(onClick = { showPresetsMenu = true }) {
                        Icon(Icons.Default.List, contentDescription = stringResource(R.string.headers_load_preset), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.headers_load_preset))
                    }
                    DropdownMenu(
                        expanded = showPresetsMenu,
                        onDismissRequest = { showPresetsMenu = false }
                    ) {
                        headerPresets.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Text(text = stringResource(preset.nameResId))
                                },
                                onClick = {
                                    // 合并预设，预设中的值会覆盖现有值
                                    val existingHeaders = headers.toMap().toMutableMap()
                                    existingHeaders.putAll(preset.headers)
                                    headers = existingHeaders.toList()

                                    showPresetsMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(headers) { index, header ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = header.first,
                            onValueChange = { newValue ->
                                headers = headers.toMutableList().also {
                                    it[index] = newValue to header.second
                                }
                            },
                            label = { Text(stringResource(R.string.headers_key_label)) },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = header.second,

                            onValueChange = { newValue ->
                                headers = headers.toMutableList().also {
                                    it[index] = header.first to newValue
                                }
                            },
                            label = { Text(stringResource(R.string.headers_value_label)) },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            headers = headers.toMutableList().apply { removeAt(index) }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.headers_delete_header))
                        }
                    }
                }
            }
        }
    }

    if (showSaveSuccessMessage) {
        LaunchedEffect(showSaveSuccessMessage) {
            kotlinx.coroutines.delay(2000)
            showSaveSuccessMessage = false
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Snackbar(
                action = {
                    TextButton(onClick = { showSaveSuccessMessage = false }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            ) {
                Text(stringResource(R.string.headers_saved))

            }
        }
    }
}