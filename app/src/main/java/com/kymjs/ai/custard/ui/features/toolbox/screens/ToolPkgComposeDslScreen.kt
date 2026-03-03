package com.kymjs.ai.custard.ui.features.toolbox.screens

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.kymjs.ai.custard.core.tools.AIToolHandler
import com.kymjs.ai.custard.core.tools.javascript.JsEngine
import com.kymjs.ai.custard.core.tools.packTool.PackageManager
import com.kymjs.ai.custard.core.tools.packTool.ToolPkgComposeDslNode
import com.kymjs.ai.custard.core.tools.packTool.ToolPkgComposeDslParser
import com.kymjs.ai.custard.core.tools.packTool.ToolPkgComposeDslRenderResult
import com.kymjs.ai.custard.ui.components.CustomScaffold
import com.kymjs.ai.custard.util.AppLogger
import com.kymjs.ai.custard.util.LocaleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ToolPkgComposeDslScreen"

@Suppress("UNUSED_PARAMETER")
@Composable
fun ToolPkgComposeDslToolScreen(
    navController: NavController,
    containerPackageName: String,
    uiModuleId: String,
    fallbackTitle: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val renderMutex = remember { Mutex() }

    val packageManager = remember {
        PackageManager.getInstance(context, AIToolHandler.getInstance(context))
    }
    val jsEngine = remember(containerPackageName, uiModuleId) { JsEngine(context) }
    DisposableEffect(jsEngine) {
        onDispose { jsEngine.destroy() }
    }

    var script by remember(containerPackageName, uiModuleId) { mutableStateOf<String?>(null) }
    var scriptEntryPath by remember(containerPackageName, uiModuleId) { mutableStateOf<String?>(null) }
    var renderResult by remember(containerPackageName, uiModuleId) {
        mutableStateOf<ToolPkgComposeDslRenderResult?>(null)
    }
    var errorMessage by remember(containerPackageName, uiModuleId) { mutableStateOf<String?>(null) }
    var isLoading by remember(containerPackageName, uiModuleId) { mutableStateOf(true) }
    var isDispatching by remember(containerPackageName, uiModuleId) { mutableStateOf(false) }
    var dispatchingCount by remember(containerPackageName, uiModuleId) { mutableStateOf(0) }

    fun buildModuleSpec(entryPath: String?): Map<String, Any?> =
        mapOf(
            "id" to uiModuleId,
            "runtime" to "compose_dsl",
            "entry" to (entryPath ?: ""),
            "title" to fallbackTitle,
            "toolPkgId" to containerPackageName
        )

    fun dispatchAction(actionId: String, payload: Any? = null) {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank()) {
            return
        }

        dispatchingCount += 1
        isDispatching = dispatchingCount > 0

        val dispatched =
            jsEngine.dispatchComposeDslActionAsync(
                actionId = normalizedActionId,
                payload = payload,
                onIntermediateResult = { intermediateResult ->
                    val parsedIntermediate =
                        ToolPkgComposeDslParser.parseRenderResult(intermediateResult)
                    if (parsedIntermediate != null) {
                        renderResult = parsedIntermediate
                        errorMessage = null
                    }
                },
                onComplete = {
                    dispatchingCount = (dispatchingCount - 1).coerceAtLeast(0)
                    isDispatching = dispatchingCount > 0
                },
                onError = { error ->
                    errorMessage = "compose_dsl runtime error: $error"
                    AppLogger.e(
                        TAG,
                        "compose_dsl async action failed: actionId=$normalizedActionId, error=$error"
                    )
                }
            )

        if (!dispatched) {
            dispatchingCount = (dispatchingCount - 1).coerceAtLeast(0)
            isDispatching = dispatchingCount > 0
        }
    }

    suspend fun render() {
        var followUpActionId: String? = null
        renderMutex.withLock {
            try {
                isLoading = true
                dispatchingCount = 0
                isDispatching = false
                errorMessage = null

                val scriptText: String? =
                    if (script == null) {
                        val loaded =
                            withContext(Dispatchers.IO) {
                                Pair(
                                    packageManager.getToolPkgComposeDslScript(
                                        containerPackageName = containerPackageName,
                                        uiModuleId = uiModuleId
                                    ),
                                    packageManager.getToolPkgComposeDslEntryPath(
                                        containerPackageName = containerPackageName,
                                        uiModuleId = uiModuleId
                                    )
                                )
                            }
                        if (scriptEntryPath.isNullOrBlank() && !loaded.second.isNullOrBlank()) {
                            scriptEntryPath = loaded.second
                        }
                        loaded.first
                    } else {
                        script
                    }

                if (scriptText.isNullOrBlank()) {
                    renderResult = null
                    errorMessage =
                        "compose_dsl script not found: package=$containerPackageName, module=$uiModuleId"
                    return
                }
                if (script == null) {
                    script = scriptText
                }

                val rawResult =
                    withContext(Dispatchers.IO) {
                        val language = LocaleUtils.getCurrentLanguage(context).trim()
                        jsEngine.executeComposeDslScript(
                            script = scriptText,
                            runtimeOptions =
                                mapOf(
                                    "packageName" to containerPackageName,
                                    "toolPkgId" to containerPackageName,
                                    "uiModuleId" to uiModuleId,
                                    "__custard_package_lang" to
                                        (if (language.isNotBlank()) language else "zh"),
                                    "__custard_script_entry" to (scriptEntryPath ?: ""),
                                    "moduleSpec" to buildModuleSpec(scriptEntryPath),
                                    "state" to (renderResult?.state ?: emptyMap<String, Any?>()),
                                    "memo" to (renderResult?.memo ?: emptyMap<String, Any?>())
                                )
                        )
                    }

                val rawText = rawResult?.toString()?.trim().orEmpty()
                val parsed = ToolPkgComposeDslParser.parseRenderResult(rawResult)
                if (parsed == null) {
                    val normalizedError =
                        when {
                            rawText.startsWith("Error:", ignoreCase = true) -> rawText
                            rawText.isNotBlank() -> "Invalid compose_dsl result: $rawText"
                            else -> "Invalid compose_dsl result"
                        }
                    renderResult = null
                    errorMessage = normalizedError
                    AppLogger.e(TAG, normalizedError)
                    return
                }

                renderResult = parsed
                errorMessage = null

                followUpActionId =
                    ToolPkgComposeDslParser.extractActionId(parsed.tree.props["onLoad"])
                Unit
            } catch (e: Exception) {
                renderResult = null
                errorMessage = "compose_dsl runtime error: ${e.message}"
                AppLogger.e(TAG, "compose_dsl render failed", e)
            } finally {
                isLoading = false
            }
        }

        val onLoadActionId = followUpActionId
        if (!onLoadActionId.isNullOrBlank()) {
            dispatchAction(actionId = onLoadActionId, payload = null)
        }
    }

    LaunchedEffect(containerPackageName, uiModuleId) {
        scope.launch {
            render()
        }
    }

    CustomScaffold { paddingValues ->
        val rootNode = renderResult?.tree
        val useOuterScroll = rootNode?.type?.equals("LazyColumn", ignoreCase = true) != true
        val contentModifier =
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .let { modifier ->
                    if (useOuterScroll) {
                        modifier.verticalScroll(rememberScrollState())
                    } else {
                        modifier
                    }
                }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMessage != null -> {
                    Column(
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    render()
                                }
                            }
                        ) {
                            Text("Retry")
                        }
                    }
                }
                rootNode != null -> {
                    Box(modifier = contentModifier) {
                        RenderComposeDslNode(
                            node = rootNode,
                            onAction = ::dispatchAction,
                            nodePath = "0"
                        )
                    }
                }
            }

            if (isDispatching) {
                LinearProgressIndicator(
                    modifier =
                        Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun RenderComposeDslNode(
    node: ToolPkgComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String
) {
    val props = node.props
    when (node.type.lowercase()) {
        "column" -> {
            val spacing = props.dp("spacing")
            Column(
                modifier = applyCommonModifier(Modifier, props),
                horizontalAlignment = props.horizontalAlignment("horizontalAlignment"),
                verticalArrangement = props.verticalArrangement("verticalArrangement", spacing)
            ) {
                node.children.forEachIndexed { index, child ->
                    val childWeight = child.props.floatOrNull("weight")
                    if (childWeight != null) {
                        Box(modifier = Modifier.weight(childWeight)) {
                            RenderComposeDslNode(
                                node = child,
                                onAction = onAction,
                                nodePath = "$nodePath/$index"
                            )
                        }
                    } else {
                        RenderComposeDslNode(
                            node = child,
                            onAction = onAction,
                            nodePath = "$nodePath/$index"
                        )
                    }
                }
            }
        }
        "row" -> {
            val spacing = props.dp("spacing")
            Row(
                modifier = applyCommonModifier(Modifier, props),
                horizontalArrangement = props.horizontalArrangement("horizontalArrangement", spacing),
                verticalAlignment = props.verticalAlignment("verticalAlignment")
            ) {
                node.children.forEachIndexed { index, child ->
                    val childWeight = child.props.floatOrNull("weight")
                    if (childWeight != null) {
                        Box(modifier = Modifier.weight(childWeight)) {
                            RenderComposeDslNode(
                                node = child,
                                onAction = onAction,
                                nodePath = "$nodePath/$index"
                            )
                        }
                    } else {
                        RenderComposeDslNode(
                            node = child,
                            onAction = onAction,
                            nodePath = "$nodePath/$index"
                        )
                    }
                }
            }
        }
        "box" -> {
            Box(
                modifier = applyCommonModifier(Modifier, props),
                contentAlignment = props.boxAlignment("contentAlignment")
            ) {
                node.children.forEachIndexed { index, child ->
                    RenderComposeDslNode(
                        node = child,
                        onAction = onAction,
                        nodePath = "$nodePath/$index"
                    )
                }
            }
        }
        "spacer" -> {
            Spacer(
                modifier =
                    Modifier
                        .width(props.dp("width"))
                        .height(props.dp("height"))
            )
        }
        "text" -> {
            val textStyle = props.textStyle("style")
            val textColor = props.colorOrNull("color")
            val fontWeight = props.fontWeightOrNull("fontWeight")
            Text(
                text = props.string("text"),
                style = if (fontWeight != null) textStyle.copy(fontWeight = fontWeight) else textStyle,
                color = textColor ?: Color.Unspecified,
                maxLines = props.int("maxLines", Int.MAX_VALUE),
                overflow = TextOverflow.Ellipsis,
                modifier = applyCommonModifier(Modifier, props)
            )
        }
        "textfield" -> {
            val actionId = ToolPkgComposeDslParser.extractActionId(props["onValueChange"])
            val label = props.stringOrNull("label")
            val placeholder = props.stringOrNull("placeholder")
            val externalValue = props.string("value")
            var textFieldValue by remember(nodePath) {
                mutableStateOf(
                    TextFieldValue(
                        text = externalValue,
                        selection = TextRange(externalValue.length)
                    )
                )
            }
            LaunchedEffect(nodePath, externalValue) {
                if (externalValue != textFieldValue.text) {
                    val start = textFieldValue.selection.start.coerceIn(0, externalValue.length)
                    val end = textFieldValue.selection.end.coerceIn(0, externalValue.length)
                    textFieldValue =
                        TextFieldValue(
                            text = externalValue,
                            selection = TextRange(start, end)
                        )
                }
            }
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { nextValue ->
                    if (!actionId.isNullOrBlank()) {
                        textFieldValue = nextValue
                        onAction(actionId, nextValue.text)
                    }
                },
                label = label?.let { { Text(it) } },
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = props.bool("singleLine", false),
                minLines = props.int("minLines", 1),
                modifier =
                    applyCommonModifier(Modifier.fillMaxWidth(), props)
            )
        }
        "button" -> {
            val actionId = ToolPkgComposeDslParser.extractActionId(props["onClick"])
            val hasChildren = node.children.isNotEmpty()
            Button(
                onClick = {
                    if (!actionId.isNullOrBlank()) {
                        onAction(actionId, null)
                    }
                },
                enabled = !actionId.isNullOrBlank() && props.bool("enabled", true),
                modifier = applyCommonModifier(Modifier, props)
            ) {
                if (hasChildren) {
                    node.children.forEachIndexed { index, child ->
                        RenderComposeDslNode(
                            node = child,
                            onAction = onAction,
                            nodePath = "$nodePath/$index"
                        )
                    }
                } else {
                    Text(props.string("text", "Button"))
                }
            }
        }
        "switch" -> {
            val actionId = ToolPkgComposeDslParser.extractActionId(props["onCheckedChange"])
            Switch(
                checked = props.bool("checked", false),
                onCheckedChange = { checked ->
                    if (!actionId.isNullOrBlank()) {
                        onAction(actionId, checked)
                    }
                },
                enabled = !actionId.isNullOrBlank() && props.bool("enabled", true),
                modifier = applyCommonModifier(Modifier, props)
            )
        }
        "checkbox" -> {
            val actionId = ToolPkgComposeDslParser.extractActionId(props["onCheckedChange"])
            Checkbox(
                checked = props.bool("checked", false),
                onCheckedChange = { checked ->
                    if (!actionId.isNullOrBlank()) {
                        onAction(actionId, checked)
                    }
                },
                enabled = !actionId.isNullOrBlank() && props.bool("enabled", true),
                modifier = applyCommonModifier(Modifier, props)
            )
        }
        "iconbutton" -> {
            val actionId = ToolPkgComposeDslParser.extractActionId(props["onClick"])
            IconButton(
                onClick = {
                    if (!actionId.isNullOrBlank()) {
                        onAction(actionId, null)
                    }
                },
                enabled = !actionId.isNullOrBlank() && props.bool("enabled", true),
                modifier = applyCommonModifier(Modifier, props)
            ) {
                Text(props.string("icon", "◎"))
            }
        }
        "card" -> {
            val containerColor = props.colorOrNull("containerColor")
            val contentColor = props.colorOrNull("contentColor")
            val spacing = props.dp("spacing")
            val cardColors =
                when {
                    containerColor != null && contentColor != null ->
                        CardDefaults.cardColors(
                            containerColor = containerColor,
                            contentColor = contentColor
                        )
                    containerColor != null ->
                        CardDefaults.cardColors(containerColor = containerColor)
                    contentColor != null ->
                        CardDefaults.cardColors(contentColor = contentColor)
                    else -> CardDefaults.cardColors()
                }
            Card(
                colors = cardColors,
                modifier = applyCommonModifier(Modifier, props)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    node.children.forEachIndexed { index, child ->
                        RenderComposeDslNode(
                            node = child,
                            onAction = onAction,
                            nodePath = "$nodePath/$index"
                        )
                    }
                }
            }
        }
        "icon" -> {
            val iconName = props.string("name", props.string("icon", "info"))
            val tint = props.colorOrNull("tint") ?: MaterialTheme.colorScheme.onSurfaceVariant
            Icon(
                imageVector = iconFromName(iconName),
                contentDescription = null,
                tint = tint,
                modifier = applyCommonModifier(Modifier, props)
            )
        }
        "lazycolumn" -> {
            val spacing = props.dp("spacing")
            LazyColumn(
                modifier = applyCommonModifier(Modifier.fillMaxSize(), props),
                verticalArrangement = Arrangement.spacedBy(spacing),
                contentPadding = PaddingValues(0.dp)
            ) {
                itemsIndexed(node.children) { index, child ->
                    RenderComposeDslNode(
                        node = child,
                        onAction = onAction,
                        nodePath = "$nodePath/$index"
                    )
                }
            }
        }
        "linearprogressindicator" -> {
            val progress = props.floatOrNull("progress")
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = applyCommonModifier(Modifier.fillMaxWidth(), props)
                )
            } else {
                LinearProgressIndicator(
                    modifier = applyCommonModifier(Modifier.fillMaxWidth(), props)
                )
            }
        }
        "circularprogressindicator" -> {
            val strokeWidth = props.floatOrNull("strokeWidth")
            val color = props.colorOrNull("color")
            CircularProgressIndicator(
                modifier = applyCommonModifier(Modifier, props),
                strokeWidth = if (strokeWidth != null) strokeWidth.dp else 4.dp,
                color = color ?: MaterialTheme.colorScheme.primary
            )
        }
        "snackbarhost" -> {
            Spacer(modifier = applyCommonModifier(Modifier, props))
        }
        else -> {
            Text(
                text = "Unsupported node: ${node.type}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun applyCommonModifier(base: Modifier, props: Map<String, Any?>): Modifier {
    var modifier = base

    val explicitWidth = props.floatOrNull("width")
    if (explicitWidth != null) {
        modifier = modifier.width(explicitWidth.dp)
    }
    val explicitHeight = props.floatOrNull("height")
    if (explicitHeight != null) {
        modifier = modifier.height(explicitHeight.dp)
    }

    if (props.bool("fillMaxSize", false)) {
        modifier = modifier.fillMaxSize()
    } else if (props.bool("fillMaxWidth", false)) {
        modifier = modifier.fillMaxWidth()
    }

    val allPadding = props.floatOrNull("padding")
    if (allPadding != null) {
        modifier = modifier.padding(allPadding.dp)
    } else {
        val horizontal = props.floatOrNull("paddingHorizontal")
        val vertical = props.floatOrNull("paddingVertical")
        if (horizontal != null || vertical != null) {
            modifier =
                modifier.padding(
                    horizontal = (horizontal ?: 0f).dp,
                    vertical = (vertical ?: 0f).dp
                )
        }
    }

    return modifier
}

private fun Map<String, Any?>.string(key: String, defaultValue: String = ""): String {
    return this[key]?.toString().orEmpty().ifBlank { defaultValue }
}

private fun Map<String, Any?>.stringOrNull(key: String): String? {
    val value = this[key]?.toString()?.trim().orEmpty()
    return if (value.isBlank()) null else value
}

private fun Map<String, Any?>.bool(key: String, defaultValue: Boolean): Boolean {
    val value = this[key] ?: return defaultValue
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value.toString().equals("true", ignoreCase = true)
    }
}

private fun Map<String, Any?>.int(key: String, defaultValue: Int): Int {
    val value = this[key] ?: return defaultValue
    return when (value) {
        is Number -> value.toInt()
        else -> value.toString().toIntOrNull() ?: defaultValue
    }
}

private fun Map<String, Any?>.floatOrNull(key: String): Float? {
    val value = this[key] ?: return null
    return when (value) {
        is Number -> value.toFloat()
        else -> value.toString().toFloatOrNull()
    }
}

private fun Map<String, Any?>.dp(key: String, defaultValue: Dp = 0.dp): Dp {
    return (floatOrNull(key) ?: defaultValue.value).dp
}

@Composable
private fun Map<String, Any?>.textStyle(key: String): androidx.compose.ui.text.TextStyle {
    return when (string(key).lowercase()) {
        "headlinesmall" -> MaterialTheme.typography.headlineSmall
        "headlinemedium" -> MaterialTheme.typography.headlineMedium
        "titlelarge" -> MaterialTheme.typography.titleLarge
        "titlemedium" -> MaterialTheme.typography.titleMedium
        "bodylarge" -> MaterialTheme.typography.bodyLarge
        "bodysmall" -> MaterialTheme.typography.bodySmall
        "labellarge" -> MaterialTheme.typography.labelLarge
        "labelmedium" -> MaterialTheme.typography.labelMedium
        "labelsmall" -> MaterialTheme.typography.labelSmall
        else -> MaterialTheme.typography.bodyMedium
    }
}

private fun Map<String, Any?>.horizontalAlignment(key: String): Alignment.Horizontal {
    return when (string(key).lowercase()) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }
}

private fun Map<String, Any?>.verticalAlignment(key: String): Alignment.Vertical {
    return when (string(key).lowercase()) {
        "center" -> Alignment.CenterVertically
        "end" -> Alignment.Bottom
        else -> Alignment.Top
    }
}

private fun Map<String, Any?>.boxAlignment(key: String): Alignment {
    return when (string(key).lowercase()) {
        "center" -> Alignment.Center
        "end" -> Alignment.BottomEnd
        else -> Alignment.TopStart
    }
}

private fun Map<String, Any?>.horizontalArrangement(key: String, spacing: Dp): Arrangement.Horizontal {
    return when (string(key).lowercase()) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(spacing)
    }
}

private fun Map<String, Any?>.verticalArrangement(key: String, spacing: Dp): Arrangement.Vertical {
    return when (string(key).lowercase()) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.Bottom
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(spacing)
    }
}

private fun Map<String, Any?>.fontWeightOrNull(key: String): FontWeight? {
    return when (string(key).lowercase()) {
        "thin" -> FontWeight.Thin
        "extralight", "ultralight" -> FontWeight.ExtraLight
        "light" -> FontWeight.Light
        "normal", "regular" -> FontWeight.Normal
        "medium" -> FontWeight.Medium
        "semibold", "demibold" -> FontWeight.SemiBold
        "bold" -> FontWeight.Bold
        "extrabold", "ultrabold" -> FontWeight.ExtraBold
        "black", "heavy" -> FontWeight.Black
        else -> null
    }
}

@Composable
private fun Map<String, Any?>.colorOrNull(key: String): Color? {
    val raw = stringOrNull(key) ?: return null
    return resolveColorToken(raw)
}

@Composable
private fun resolveColorToken(raw: String): Color? {
    val token =
        raw.lowercase()
            .replace("-", "")
            .replace("_", "")
            .trim()
    val scheme = MaterialTheme.colorScheme
    return when (token) {
        "primary" -> scheme.primary
        "onprimary" -> scheme.onPrimary
        "primarycontainer" -> scheme.primaryContainer
        "onprimarycontainer" -> scheme.onPrimaryContainer
        "secondary" -> scheme.secondary
        "onsecondary" -> scheme.onSecondary
        "secondarycontainer" -> scheme.secondaryContainer
        "onsecondarycontainer" -> scheme.onSecondaryContainer
        "tertiary" -> scheme.tertiary
        "ontertiary" -> scheme.onTertiary
        "tertiarycontainer" -> scheme.tertiaryContainer
        "ontertiarycontainer" -> scheme.onTertiaryContainer
        "error" -> scheme.error
        "onerror" -> scheme.onError
        "errorcontainer" -> scheme.errorContainer
        "onerrorcontainer" -> scheme.onErrorContainer
        "surface" -> scheme.surface
        "onsurface" -> scheme.onSurface
        "surfacevariant" -> scheme.surfaceVariant
        "onsurfacevariant" -> scheme.onSurfaceVariant
        "background" -> scheme.background
        "onbackground" -> scheme.onBackground
        "inverseprimary" -> scheme.inversePrimary
        "inverseonsurface" -> scheme.inverseOnSurface
        else -> {
            try {
                Color(AndroidColor.parseColor(raw))
            } catch (_: Exception) {
                null
            }
        }
    }
}

private fun iconFromName(name: String): ImageVector {
    return when (name.lowercase()) {
        "computer", "pc", "desktop" -> Icons.Default.Computer
        "checkcircle", "check", "success" -> Icons.Default.CheckCircle
        "error", "warning", "failed", "failure" -> Icons.Default.Error
        "settings", "gear" -> Icons.Default.Settings
        "share" -> Icons.Default.Share
        "info", "information" -> Icons.Default.Info
        else -> Icons.Default.Info
    }
}
