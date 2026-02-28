package com.kymjs.ai.custard.core.tools.defaultTool.standard

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.core.tools.StringResultData
import com.kymjs.ai.custard.core.tools.ToolExecutor
import com.kymjs.ai.custard.data.model.AITool
import com.kymjs.ai.custard.data.model.ToolResult
import com.kymjs.ai.custard.util.AppLogger
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

class StandardWebSessionTools(private val context: Context) : ToolExecutor {

    companion object {
        private const val TAG = "WebSessionTools"
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val mainHandler = Handler(Looper.getMainLooper())

        private val sessions = ConcurrentHashMap<String, WebSession>()
        private val sessionOrder = mutableListOf<String>()
        private val tabViews = ConcurrentHashMap<String, LinearLayout>()
        private val tabTitleViews = ConcurrentHashMap<String, TextView>()
        private val tabCloseViews = ConcurrentHashMap<String, TextView>()

        private val sessionOrderLock = Any()
        private val overlayLock = Any()

        @Volatile private var overlayController: OverlayController? = null
    }

private class WebSessionOverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreField = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            savedStateRegistryController.performRestore(null)
        } else {
            AppLogger.w(
                "WebSessionOverlayLifecycleOwner",
                "Initializing not on main thread. This may cause issues."
            )
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = viewModelStoreField

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lifecycleRegistry.handleLifecycleEvent(event)
        } else {
            Handler(Looper.getMainLooper()).post {
                lifecycleRegistry.handleLifecycleEvent(event)
            }
        }
    }
}

    private class DeceptiveMinimizedLayout(context: Context) : FrameLayout(context) {
        var minimizedMeasureEnabled: Boolean = false
        var fakeWidthPx: Int = 1
        var fakeHeightPx: Int = 1

        init {
            clipChildren = false
            clipToPadding = false
        }

        fun setMinimizedMeasure(enabled: Boolean, fakeWidth: Int = fakeWidthPx, fakeHeight: Int = fakeHeightPx) {
            minimizedMeasureEnabled = enabled
            fakeWidthPx = fakeWidth.coerceAtLeast(1)
            fakeHeightPx = fakeHeight.coerceAtLeast(1)
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!minimizedMeasureEnabled) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }

            val childWidthSpec =
                View.MeasureSpec.makeMeasureSpec(fakeWidthPx, View.MeasureSpec.EXACTLY)
            val childHeightSpec =
                View.MeasureSpec.makeMeasureSpec(fakeHeightPx, View.MeasureSpec.EXACTLY)

            for (i in 0 until childCount) {
                getChildAt(i).measure(childWidthSpec, childHeightSpec)
            }

            setMeasuredDimension(1, 1)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (!minimizedMeasureEnabled) {
                super.onLayout(changed, left, top, right, bottom)
                return
            }

            for (i in 0 until childCount) {
                getChildAt(i).layout(0, 0, fakeWidthPx, fakeHeightPx)
            }
        }
    }

    private data class WebSession(
        val id: String,
        val webView: WebView,
        val sessionName: String?,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        @Volatile var currentUrl: String = "about:blank"
        @Volatile var pageTitle: String = ""
        @Volatile var pageLoaded: Boolean = false
        @Volatile var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
        @Volatile var lastFileChooserRequestAt: Long = 0L
    }

    private data class OverlayController(
        val appContext: Context,
        val windowManager: WindowManager,
        val rootView: DeceptiveMinimizedLayout,
        val cardView: LinearLayout,
        val titleView: TextView,
        val tabsContainer: LinearLayout,
        val webContainer: FrameLayout,
        val minimizeButton: TextView,
        val closeButton: TextView
    ) {
        var activeSessionId: String? = null
        var overlayParams: WindowManager.LayoutParams? = null
        var indicatorView: View? = null
        var indicatorParams: WindowManager.LayoutParams? = null
        var indicatorLifecycleOwner: WebSessionOverlayLifecycleOwner? = null
        var isExpanded: Boolean = true
    }

    override fun invoke(tool: AITool): ToolResult {
        return try {
            when (tool.name) {
                "start_web" -> startWeb(tool)
                "stop_web" -> stopWeb(tool)
                "web_navigate" -> webNavigate(tool)
                "web_eval" -> webEval(tool)
                "web_click" -> webClick(tool)
                "web_fill" -> webFill(tool)
                "web_file_upload" -> webFileUpload(tool)
                "web_wait_for" -> webWaitFor(tool)
                "web_snapshot" -> webSnapshot(tool)
                else -> error(tool.name, "Unsupported web session tool: ${tool.name}")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Tool execution failed: ${tool.name}", e)
            error(tool.name, e.message ?: "Unknown error")
        }
    }

    private fun startWeb(tool: AITool): ToolResult {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            return error(tool.name, "Overlay permission is required for start_web.")
        }

        val initialUrl = param(tool, "url")?.takeIf { it.isNotBlank() } ?: "about:blank"
        val userAgent = param(tool, "user_agent")?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
        val headers = parseHeaders(param(tool, "headers"))
        val sessionName = param(tool, "session_name")?.takeIf { it.isNotBlank() }

        val sessionId = UUID.randomUUID().toString()

        runOnMainSync {
            val session = createSessionOnMain(appContext, sessionId, sessionName, userAgent)
            sessions[sessionId] = session
            addSessionOrder(sessionId)

            val controller = ensureOverlayOnMain(appContext)
            addSessionTabOnMain(controller, session)
            activateSessionOnMain(controller, sessionId)
            setExpandedOnMain(controller, false)

            session.pageLoaded = false
            session.currentUrl = initialUrl
            if (headers.isNotEmpty()) {
                session.webView.loadUrl(initialUrl, headers)
            } else {
                session.webView.loadUrl(initialUrl)
            }
        }

        val payload =
            JSONObject()
                .put("session_id", sessionId)
                .put("status", "started")
                .put("url", initialUrl)
                .put("session_name", sessionName ?: "")
                .put("active_sessions", sessions.size)

        return ok(tool.name, payload)
    }

    private fun stopWeb(tool: AITool): ToolResult {
        val closeAll = boolParam(tool, "close_all", false)

        if (closeAll) {
            val ids = listSessionIdsInOrder().ifEmpty { sessions.keys().toList() }
            var closed = 0
            ids.forEach { id ->
                if (closeSession(id)) {
                    closed++
                }
            }

            val payload =
                JSONObject()
                    .put("status", "stopped")
                    .put("close_all", true)
                    .put("closed_count", closed)
                    .put("active_sessions", sessions.size)
            return ok(tool.name, payload)
        }

        val requestedSessionId = param(tool, "session_id")
        val targetSession = getSession(requestedSessionId)
            ?: return if (requestedSessionId.isNullOrBlank()) {
                error(tool.name, "No active web session. Start one first or pass session_id.")
            } else {
                error(tool.name, "Session not found: $requestedSessionId")
            }

        val closed = closeSession(targetSession.id)
        if (!closed) {
            return error(tool.name, "Session not found: ${targetSession.id}")
        }

        val payload =
            JSONObject()
                .put("status", "stopped")
                .put("session_id", targetSession.id)
                .put("active_sessions", sessions.size)

        return ok(tool.name, payload)
    }

    private fun webNavigate(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val targetUrl = param(tool, "url")
        if (targetUrl.isNullOrBlank()) {
            return error(tool.name, "url is required")
        }

        val headers = parseHeaders(param(tool, "headers"))

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
            session.pageLoaded = false
            session.currentUrl = targetUrl
            if (headers.isNotEmpty()) {
                session.webView.loadUrl(targetUrl, headers)
            } else {
                session.webView.loadUrl(targetUrl)
            }
        }

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "navigating")
                .put("url", targetUrl)

        return ok(tool.name, payload)
    }

    private fun webEval(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val script = param(tool, "script")
        if (script.isNullOrBlank()) {
            return error(tool.name, "script is required")
        }

        val timeoutMs = longParam(tool, "timeout_ms", DEFAULT_TIMEOUT_MS)

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        val rawResult = evaluateJavascriptSync(session.webView, script, timeoutMs)
        val decodedResult = decodeJsResult(rawResult)

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "ok")
                .put("result", decodedResult)
                .put("raw_result", rawResult)

        return ok(tool.name, payload)
    }

    private fun webClick(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val ref = param(tool, "ref")?.trim()?.takeIf { it.isNotBlank() }
        val element = param(tool, "element")?.trim()?.takeIf { it.isNotBlank() }
        if (ref.isNullOrBlank()) {
            return error(tool.name, "ref is required")
        }

        val buttonRaw = param(tool, "button")?.trim()
        val button =
            when {
                buttonRaw.isNullOrBlank() -> "left"
                buttonRaw == "left" || buttonRaw == "right" || buttonRaw == "middle" -> buttonRaw
                else -> return error(tool.name, "button must be one of: left, right, middle")
            }

        val doubleClick = boolParam(tool, "doubleClick", false)

        val (modifiers, invalidModifiers) = parseClickModifiers(param(tool, "modifiers"))
        if (invalidModifiers.isNotEmpty()) {
            return error(
                tool.name,
                "Invalid modifiers: ${invalidModifiers.joinToString(", ")}. Allowed: Alt, Control, ControlOrMeta, Meta, Shift"
            )
        }

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        val beforeUrl = readCurrentUrl(session.webView, session.currentUrl)
        val jsResult =
            dispatchClickByRef(
                webView = session.webView,
                ref = ref,
                button = button,
                modifiers = modifiers,
                doubleClick = doubleClick
            )
        if (jsResult?.optBoolean("ok", false) != true) {
            if (jsResult?.optString("error") == "ref_not_found") {
                return error(
                    tool.name,
                    "Ref $ref not found in the current page snapshot. Try capturing new snapshot."
                )
            }
            return error(
                tool.name,
                "Click failed: ${jsResult?.optString("error") ?: "unknown"}"
            )
        }

        waitForClickCompletion(session.webView, beforeUrl)

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "ok")
                .put("ref", ref)
                .put("button", button)
                .put("doubleClick", doubleClick)
                .put("result", jsResult)
        if (modifiers.isNotEmpty()) {
            payload.put("modifiers", JSONArray(modifiers.toList()))
        }
        if (!element.isNullOrBlank()) {
            payload.put("element", element)
        }

        return ok(tool.name, payload)
    }

    private fun dispatchClickByRef(
        webView: WebView,
        ref: String,
        button: String,
        modifiers: Set<String>,
        doubleClick: Boolean
    ): JSONObject? {
        val buttonValue =
            when (button) {
                "middle" -> 1
                "right" -> 2
                else -> 0
            }
        val buttonsValue =
            when (button) {
                "middle" -> 4
                "right" -> 2
                else -> 1
            }

        val altKey = modifiers.contains("Alt")
        val controlKey = modifiers.contains("Control") || modifiers.contains("ControlOrMeta")
        val metaKey = modifiers.contains("Meta") || modifiers.contains("ControlOrMeta")
        val shiftKey = modifiers.contains("Shift")

        val script =
            """
            (function() {
                try {
                    const refValue = ${quoteJs(ref)};
                    const list = Array.from(document.querySelectorAll('[aria-ref]')).filter((el) => {
                        return String(el.getAttribute('aria-ref') || '') === refValue;
                    });
                    if (!list.length) {
                        return JSON.stringify({ ok: false, error: "ref_not_found", ref: refValue });
                    }
                    const target = list[0];
                    try { target.scrollIntoView({ block: "center", inline: "center" }); } catch (_) {}
                    const rect = target.getBoundingClientRect();
                    const x = rect.left + rect.width / 2;
                    const y = rect.top + rect.height / 2;

                    try { target.focus({ preventScroll: true }); } catch (_) {}

                    const buttonValue = ${buttonValue};
                    const buttonsValue = ${buttonsValue};
                    const altKey = ${if (altKey) "true" else "false"};
                    const ctrlKey = ${if (controlKey) "true" else "false"};
                    const metaKey = ${if (metaKey) "true" else "false"};
                    const shiftKey = ${if (shiftKey) "true" else "false"};

                    function emit(type, detail) {
                        try {
                            target.dispatchEvent(new MouseEvent(type, {
                                bubbles: true,
                                cancelable: true,
                                composed: true,
                                view: window,
                                detail: detail,
                                clientX: x,
                                clientY: y,
                                screenX: x,
                                screenY: y,
                                button: buttonValue,
                                buttons: buttonsValue,
                                altKey,
                                ctrlKey,
                                metaKey,
                                shiftKey
                            }));
                        } catch (_) {}
                    }

                    function clickOnce(detail) {
                        emit("mousedown", detail);
                        emit("mouseup", detail);
                        emit("click", detail);
                    }

                    if (${if (doubleClick) "true" else "false"}) {
                        clickOnce(1);
                        clickOnce(2);
                        emit("dblclick", 2);
                    } else {
                        clickOnce(1);
                    }

                    return JSON.stringify({
                        ok: true,
                        ref: refValue,
                        button: ${quoteJs(button)},
                        doubleClick: ${if (doubleClick) "true" else "false"},
                        tag: String(target.tagName || "").toLowerCase()
                    });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()

        return try {
            val raw = evaluateJavascriptSync(webView, script, DEFAULT_TIMEOUT_MS.coerceIn(2_000L, 8_000L))
            val decoded = decodeJsResult(raw)
            JSONObject(decoded)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "click_dispatch_error")
        }
    }

    private fun parseClickModifiers(raw: String?): Pair<Set<String>, List<String>> {
        if (raw.isNullOrBlank()) {
            return emptySet<String>() to emptyList()
        }

        val allowed = setOf("Alt", "Control", "ControlOrMeta", "Meta", "Shift")
        val parsed = linkedSetOf<String>()
        val invalid = mutableListOf<String>()

        val arr =
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                return emptySet<String>() to listOf(raw)
            }

        for (i in 0 until arr.length()) {
            val token = arr.optString(i, "").trim()
            if (token in allowed) {
                parsed.add(token)
            } else {
                invalid.add(token.ifBlank { "<empty>" })
            }
        }

        return parsed to invalid
    }

    private fun readCurrentUrl(webView: WebView, fallback: String): String {
        return runCatching {
            val raw =
                evaluateJavascriptSync(
                    webView,
                    "(function(){ return String(location.href || ''); })();",
                    2_000L
                )
            decodeJsResult(raw)
        }.getOrDefault(fallback)
    }

    private fun waitForClickCompletion(webView: WebView, beforeUrl: String) {
        Thread.sleep(500)
        val deadline = System.currentTimeMillis() + 10_000L
        var urlChanged = false

        while (System.currentTimeMillis() < deadline) {
            try {
                val raw =
                    evaluateJavascriptSync(
                        webView,
                        "(function(){ return JSON.stringify({ url: String(location.href || ''), ready: String(document.readyState || '') }); })();",
                        2_000L
                    )
                val decoded = decodeJsResult(raw)
                val state = JSONObject(decoded)
                val currentUrl = state.optString("url", "")
                val ready = state.optString("ready", "")

                if (currentUrl != beforeUrl) {
                    urlChanged = true
                }

                if (urlChanged && ready == "complete") {
                    Thread.sleep(500)
                    return
                }

                if (!urlChanged && ready == "complete") {
                    return
                }
            } catch (_: Exception) {
                return
            }

            Thread.sleep(120)
        }
    }

    private fun webFill(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val selector = param(tool, "selector")
        val value = param(tool, "value")
        if (selector.isNullOrBlank()) {
            return error(tool.name, "selector is required")
        }
        if (value == null) {
            return error(tool.name, "value is required")
        }

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        val script =
            """
            (function() {
                try {
                    const el = document.querySelector(${quoteJs(selector)});
                    if (!el) return JSON.stringify({ ok: false, error: "element_not_found" });
                    el.focus();
                    el.value = ${quoteJs(value)};
                    el.dispatchEvent(new Event("input", { bubbles: true }));
                    el.dispatchEvent(new Event("change", { bubbles: true }));
                    return JSON.stringify({ ok: true, tag: (el.tagName || "").toLowerCase() });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
            """.trimIndent()

        val raw = evaluateJavascriptSync(session.webView, script, DEFAULT_TIMEOUT_MS)
        val decoded = decodeJsResult(raw)

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "ok")
                .put("result", decoded)

        return ok(tool.name, payload)
    }

    private fun webFileUpload(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val rawPaths = param(tool, "paths")?.trim().orEmpty()
        val shouldCancel = rawPaths.isBlank()

        val files: List<File> =
            if (shouldCancel) {
                emptyList()
            } else {
                val pathList = parseStringArrayParam(rawPaths)
                    ?: return error(tool.name, "paths must be a JSON array")

                val resolved = mutableListOf<File>()
                for (rawPath in pathList) {
                    val path = rawPath.trim()
                    if (path.isBlank()) {
                        return error(tool.name, "paths contains an empty item")
                    }
                    val file = File(path)
                    if (!file.isAbsolute) {
                        return error(tool.name, "path must be absolute: $path")
                    }
                    if (!file.exists() || !file.isFile) {
                        return error(tool.name, "file does not exist: $path")
                    }
                    resolved.add(file)
                }
                resolved
            }

        val callbackResult =
            runOnMainSync {
                ensureSessionAttachedOnMain(session.id)

                val callback = session.pendingFileChooserCallback
                    ?: return@runOnMainSync error(tool.name, "No file chooser is active")

                return@runOnMainSync try {
                    if (shouldCancel) {
                        callback.onReceiveValue(null)
                        session.pendingFileChooserCallback = null

                        val payload =
                            JSONObject()
                                .put("session_id", session.id)
                                .put("status", "ok")
                                .put("cancelled", true)
                        ok(tool.name, payload)
                    } else {
                        val uris = files.map { Uri.fromFile(it) }.toTypedArray()
                        callback.onReceiveValue(uris)
                        session.pendingFileChooserCallback = null

                        val uploaded = JSONArray()
                        files.forEach { uploaded.put(it.absolutePath) }

                        val payload =
                            JSONObject()
                                .put("session_id", session.id)
                                .put("status", "ok")
                                .put("uploaded_count", files.size)
                                .put("paths", uploaded)
                        ok(tool.name, payload)
                    }
                } catch (e: Exception) {
                    error(tool.name, "Failed to resolve file chooser: ${e.message}")
                }
            }

        return callbackResult
    }

    private fun webWaitFor(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val selector = param(tool, "selector")?.takeIf { it.isNotBlank() }
        val timeoutMs = longParam(tool, "timeout_ms", DEFAULT_TIMEOUT_MS).coerceAtLeast(200)
        val deadline = System.currentTimeMillis() + timeoutMs

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        while (System.currentTimeMillis() < deadline) {
            if (selector.isNullOrBlank()) {
                if (session.pageLoaded) {
                    val payload =
                        JSONObject()
                            .put("session_id", session.id)
                            .put("status", "ready")
                            .put("page_loaded", true)
                    return ok(tool.name, payload)
                }
            } else {
                runOnMainSync {
                    ensureSessionAttachedOnMain(session.id)
                }

                val script =
                    """
                    (function() {
                        try {
                            return !!document.querySelector(${quoteJs(selector)});
                        } catch (e) {
                            return false;
                        }
                    })();
                    """.trimIndent()

                val result = evaluateJavascriptSync(session.webView, script, 2_000)
                if (result == "true") {
                    val payload =
                        JSONObject()
                            .put("session_id", session.id)
                            .put("status", "ready")
                            .put("selector", selector)
                    return ok(tool.name, payload)
                }
            }
            Thread.sleep(120)
        }

        return error(tool.name, "Timeout waiting for condition")
    }

    private fun webSnapshot(tool: AITool): ToolResult {
        val session = getSession(param(tool, "session_id"))
            ?: return error(tool.name, "Session not found")

        val includeLinks = boolParam(tool, "include_links", true)
        val includeImages = boolParam(tool, "include_images", false)

        runOnMainSync {
            ensureSessionAttachedOnMain(session.id)
        }

        val script =
            """
            (function() {
                try {
                    const includeLinks = ${if (includeLinks) "true" else "false"};
                    const includeImages = ${if (includeImages) "true" else "false"};
                    const title = document.title || "";
                    const url = location.href || "";
                    let text = (document.body && document.body.innerText) ? document.body.innerText : "";
                    text = text.replace(/\n{3,}/g, "\n\n").trim();
                    if (text.length > 20000) {
                        text = text.slice(0, 20000) + "\n...(truncated)";
                    }

                    const interactiveSelector = [
                        "a[href]",
                        "button",
                        "input",
                        "select",
                        "textarea",
                        "summary",
                        "[role='button']",
                        "[onclick]",
                        "[tabindex]"
                    ].join(",");

                    const candidates = Array.from(document.querySelectorAll(interactiveSelector));
                    let nextRef = 1;
                    const existingRefNumbers = Array.from(document.querySelectorAll("[aria-ref]")).map((el) => {
                        const raw = String(el.getAttribute("aria-ref") || "");
                        const m = /^e(\d+)$/.exec(raw);
                        return m ? parseInt(m[1], 10) : 0;
                    }).filter((n) => Number.isFinite(n) && n > 0);
                    if (existingRefNumbers.length) {
                        nextRef = Math.max.apply(null, existingRefNumbers) + 1;
                    }

                    const refs = [];
                    candidates.slice(0, 200).forEach((el) => {
                        let ref = String(el.getAttribute("aria-ref") || "");
                        if (!ref) {
                            ref = "e" + (nextRef++);
                            try { el.setAttribute("aria-ref", ref); } catch (_) {}
                        }

                        const tag = String(el.tagName || "").toLowerCase();
                        const label = (
                            el.innerText ||
                            el.textContent ||
                            el.getAttribute("aria-label") ||
                            el.getAttribute("title") ||
                            el.getAttribute("name") ||
                            el.getAttribute("value") ||
                            ""
                        ).replace(/\s+/g, " ").trim();

                        refs.push({
                            ref,
                            tag,
                            label: label.slice(0, 80)
                        });
                    });

                    const lines = [];
                    lines.push("Title: " + title);
                    lines.push("URL: " + url);
                    lines.push("");
                    lines.push("Content:");
                    lines.push(text);

                    if (refs.length) {
                        lines.push("");
                        lines.push("Elements:");
                        refs.slice(0, 120).forEach((item) => {
                            const readable = item.label || "(no label)";
                            lines.push("[" + item.ref + "] <" + item.tag + "> " + readable);
                        });
                    }

                    if (includeLinks) {
                        const links = Array.from(document.querySelectorAll("a[href]")).slice(0, 100);
                        lines.push("");
                        lines.push("Results:");
                        links.forEach((a, i) => {
                            const href = a.href || "";
                            const t = (a.innerText || a.textContent || "").replace(/\s+/g, " ").trim() || href;
                            lines.push("[" + (i + 1) + "] " + t + " - " + href);
                        });
                    }

                    if (includeImages) {
                        const imgs = Array.from(document.querySelectorAll("img[src]")).slice(0, 100);
                        lines.push("");
                        lines.push("Images:");
                        imgs.forEach((img, i) => {
                            const src = img.src || "";
                            const alt = (img.alt || "").replace(/\s+/g, " ").trim();
                            lines.push("[" + (i + 1) + "] " + (alt || "image") + " - " + src);
                        });
                    }

                    return lines.join("\n");
                } catch (e) {
                    return "Snapshot error: " + String(e);
                }
            })();
            """.trimIndent()

        val raw = evaluateJavascriptSync(session.webView, script, DEFAULT_TIMEOUT_MS)
        val snapshot = decodeJsResult(raw)

        val payload =
            JSONObject()
                .put("session_id", session.id)
                .put("status", "ok")
                .put("snapshot", snapshot)

        return ok(tool.name, payload)
    }

    private fun createSessionOnMain(
        appContext: Context,
        sessionId: String,
        sessionName: String?,
        userAgent: String
    ): WebSession {
        val webView = WebView(appContext)
        val session = WebSession(id = sessionId, webView = webView, sessionName = sessionName)
        configureWebView(session, userAgent)
        return session
    }

    private fun configureWebView(session: WebSession, userAgent: String) {
        with(session.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgent
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }

        session.webView.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isLongClickable = true
            contentDescription = context.getString(R.string.web_session_accessibility_web_content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isScreenReaderFocusable = true
            }
        }

        session.webView.webChromeClient =
            object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: WebChromeClient.FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) {
                        return false
                    }

                    session.pendingFileChooserCallback?.onReceiveValue(null)
                    session.pendingFileChooserCallback = filePathCallback
                    session.lastFileChooserRequestAt = System.currentTimeMillis()

                    AppLogger.d(
                        TAG,
                        "Captured file chooser request for session=${session.id}, " +
                            "mode=${fileChooserParams?.mode}, multiple=${fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE}"
                    )
                    return true
                }
            }

        session.webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    session.currentUrl = url
                    session.pageLoaded = false
                    refreshSessionUiOnMain(session.id)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    session.currentUrl = url
                    session.pageTitle = view.title ?: ""
                    session.pageLoaded = true
                    refreshSessionUiOnMain(session.id)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") {
                        AppLogger.w(TAG, "Blocked non-http(s) navigation: $uri")
                        return true
                    }
                    return false
                }
            }
    }

    private fun ensureOverlayOnMain(appContext: Context): OverlayController {
        overlayController?.let { return it }

        synchronized(overlayLock) {
            overlayController?.let { return it }

            val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val rootView =
                DeceptiveMinimizedLayout(appContext).apply {
                    setBackgroundColor(AndroidColor.argb(110, 0, 0, 0))
                    setOnClickListener {}
                }

            val cardView =
                LinearLayout(appContext).apply {
                    orientation = LinearLayout.VERTICAL
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(16).toFloat()
                            setColor(AndroidColor.WHITE)
                        }
                }

            val cardLp =
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(dp(8), dp(24), dp(8), dp(8))
                }
            rootView.addView(cardView, cardLp)

            val topBar =
                LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(8), dp(8), dp(8))
                    setBackgroundColor(AndroidColor.parseColor("#F3F5F8"))
                    gravity = Gravity.CENTER_VERTICAL
                }

            val titleView =
                TextView(appContext).apply {
                    text = "Web Sessions"
                    textSize = 14f
                    setTextColor(AndroidColor.parseColor("#111827"))
                    maxLines = 1
                }

            val minimizeButton =
                TextView(appContext).apply {
                    text = "—"
                    textSize = 18f
                    setTextColor(AndroidColor.parseColor("#6B7280"))
                    setPadding(dp(10), dp(2), dp(10), dp(2))
                    contentDescription = appContext.getString(R.string.web_session_accessibility_minimize_panel)
                }

            val closeButton =
                TextView(appContext).apply {
                    text = "✕"
                    textSize = 16f
                    setTextColor(AndroidColor.parseColor("#6B7280"))
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    contentDescription = appContext.getString(R.string.web_session_accessibility_close_current_session)
                }

            topBar.addView(
                titleView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            topBar.addView(
                minimizeButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            topBar.addView(
                closeButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            cardView.addView(
                topBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val tabsScroll =
                HorizontalScrollView(appContext).apply {
                    isHorizontalScrollBarEnabled = false
                    setBackgroundColor(AndroidColor.parseColor("#FAFBFC"))
                }

            val tabsContainer =
                LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                }

            tabsScroll.addView(
                tabsContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            cardView.addView(
                tabsScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val webContainer =
                FrameLayout(appContext).apply {
                    setBackgroundColor(AndroidColor.WHITE)
                }

            cardView.addView(
                webContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            val controller =
                OverlayController(
                    appContext = appContext,
                    windowManager = windowManager,
                    rootView = rootView,
                    cardView = cardView,
                    titleView = titleView,
                    tabsContainer = tabsContainer,
                    webContainer = webContainer,
                    minimizeButton = minimizeButton,
                    closeButton = closeButton
                )

            controller.overlayParams = createOverlayLayoutParamsOnMain(expanded = true)
            windowManager.addView(rootView, controller.overlayParams)

            minimizeButton.setOnClickListener {
                setExpandedOnMain(controller, false)
            }
            closeButton.setOnClickListener {
                controller.activeSessionId?.let { closeSession(it) }
            }

            overlayController = controller
            return controller
        }
    }

    private fun destroyOverlayOnMain() {
        val controller = overlayController ?: return

        hideIndicatorOnMain(controller)

        try {
            controller.windowManager.removeView(controller.rootView)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error removing overlay: ${e.message}")
        }

        overlayController = null
        tabViews.clear()
        tabTitleViews.clear()
        tabCloseViews.clear()
    }

    private fun createOverlayLayoutParamsOnMain(expanded: Boolean): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        return if (expanded) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                x = 0
                y = 0
            }
        } else {
            WindowManager.LayoutParams(
                1,
                1,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(16)
                y = dp(16)
            }
        }
    }

    private fun createIndicatorLayoutParamsOnMain(): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val size = dp(40).coerceAtLeast(1)
        return WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(16)
        }
    }

    @Composable
    private fun MinimizedIndicator(
        onToggleFullscreen: () -> Unit,
        onDragBy: (dx: Int, dy: Int) -> Unit
    ) {
        val transition = rememberInfiniteTransition(label = "web-session-indicator")
        val primaryColor = MaterialTheme.colorScheme.primary

        val bobbingDp by
            transition.animateFloat(
                initialValue = -2f,
                targetValue = 2f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                label = "bobbing"
            )

        val wiggleDeg by
            transition.animateFloat(
                initialValue = -8f,
                targetValue = 8f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                label = "wiggle"
            )

        val pulse by
            transition.animateFloat(
                initialValue = 0.96f,
                targetValue = 1.04f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 1100, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                label = "pulse"
            )

        val minimizedIndicatorDescription =
            stringResource(R.string.web_session_accessibility_minimized_indicator)

        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            onDragBy(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                        }
                    }
                    .semantics {
                        contentDescription = minimizedIndicatorDescription
                    }
                    .clickable { onToggleFullscreen() }
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .scale(pulse)
                        .drawBehind {
                            val radius = size.minDimension / 2f
                            drawCircle(
                                brush =
                                    Brush.radialGradient(
                                        colors =
                                            listOf(
                                                Color.White.copy(alpha = 0.40f),
                                                primaryColor.copy(alpha = 0.16f),
                                                Color.Transparent
                                            ),
                                        center = Offset(size.width * 0.30f, size.height * 0.28f),
                                        radius = radius * 1.15f
                                    ),
                                radius = radius
                            )

                            drawCircle(
                                color = Color.White.copy(alpha = 0.20f),
                                radius = radius * 0.22f,
                                center = Offset(size.width * 0.28f, size.height * 0.28f)
                            )

                            drawCircle(
                                color = Color.White.copy(alpha = 0.08f),
                                radius = radius * 0.90f,
                                center = Offset(size.width * 0.55f, size.height * 0.62f)
                            )
                        }
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.35f),
                            shape = CircleShape
                        ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🌐",
                    color = primaryColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.offset(y = bobbingDp.dp).rotate(wiggleDeg)
                )
            }
        }
    }

    private fun showIndicatorOnMain(controller: OverlayController) {
        if (controller.indicatorView != null) {
            return
        }

        val params = controller.indicatorParams ?: createIndicatorLayoutParamsOnMain().also {
            controller.indicatorParams = it
        }

        val lifecycleOwner =
            WebSessionOverlayLifecycleOwner().apply {
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                handleLifecycleEvent(Lifecycle.Event.ON_START)
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }

        val indicator =
            ComposeView(controller.appContext).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    MaterialTheme {
                        MinimizedIndicator(
                            onToggleFullscreen = { setExpandedOnMain(controller, true) },
                            onDragBy = { dx, dy -> moveIndicatorByOnMain(controller, dx, dy) }
                        )
                    }
                }
            }

        controller.indicatorLifecycleOwner = lifecycleOwner
        controller.indicatorView = indicator
        controller.windowManager.addView(indicator, params)
    }

    private fun hideIndicatorOnMain(controller: OverlayController) {
        val indicator = controller.indicatorView ?: return

        controller.indicatorLifecycleOwner?.let { owner ->
            owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }

        try {
            controller.windowManager.removeView(indicator)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error removing indicator: ${e.message}")
        }

        controller.indicatorView = null
        controller.indicatorLifecycleOwner = null
    }

    private fun moveIndicatorByOnMain(controller: OverlayController, dx: Int, dy: Int) {
        val indicator = controller.indicatorView ?: return
        val params = controller.indicatorParams ?: return

        val maxX = (context.resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0)
        val maxY = (context.resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0)

        params.x = (params.x + dx).coerceIn(0, maxX)
        params.y = (params.y + dy).coerceIn(0, maxY)

        controller.indicatorParams = params
        controller.windowManager.updateViewLayout(indicator, params)
        syncOverlayPositionWhenMinimizedOnMain(controller)
    }

    private fun syncOverlayPositionWhenMinimizedOnMain(controller: OverlayController) {
        if (controller.isExpanded) {
            return
        }

        val overlayView = controller.rootView
        val overlayParams = controller.overlayParams ?: return
        val indicatorParams = controller.indicatorParams ?: return

        overlayParams.x = indicatorParams.x
        overlayParams.y = indicatorParams.y
        controller.overlayParams = overlayParams

        if (overlayView.windowToken != null) {
            controller.windowManager.updateViewLayout(overlayView, overlayParams)
        }
    }

    private fun setExpandedOnMain(controller: OverlayController, expanded: Boolean) {
        val params = controller.overlayParams ?: return
        controller.isExpanded = expanded

        if (expanded) {
            controller.rootView.setMinimizedMeasure(false)
            controller.rootView.setBackgroundColor(AndroidColor.argb(110, 0, 0, 0))
            controller.cardView.visibility = View.VISIBLE
            controller.cardView.alpha = 1f

            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.CENTER
            params.x = 0
            params.y = 0
            params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            hideIndicatorOnMain(controller)
        } else {
            if (controller.indicatorView == null) {
                showIndicatorOnMain(controller)
            }

            val displayMetrics = context.resources.displayMetrics
            controller.rootView.setMinimizedMeasure(
                enabled = true,
                fakeWidth = displayMetrics.widthPixels,
                fakeHeight = displayMetrics.heightPixels
            )
            controller.rootView.setBackgroundColor(AndroidColor.TRANSPARENT)
            controller.cardView.visibility = View.VISIBLE
            controller.cardView.alpha = 0.01f

            params.width = 1
            params.height = 1
            params.gravity = Gravity.TOP or Gravity.START
            params.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

            controller.indicatorParams?.let {
                params.x = it.x
                params.y = it.y
            }
        }

        keepActiveWebViewRunningOnMain(controller, expanded)

        controller.overlayParams = params
        if (controller.rootView.windowToken != null) {
            controller.windowManager.updateViewLayout(controller.rootView, params)
        }
    }

    private fun keepActiveWebViewRunningOnMain(controller: OverlayController, expanded: Boolean) {
        val activeSessionId = controller.activeSessionId ?: return
        val webView = sessions[activeSessionId]?.webView ?: return

        try {
            webView.onResume()
            webView.resumeTimers()
            webView.visibility = View.VISIBLE
            webView.alpha = 1f
            if (expanded) {
                if (!webView.hasFocus()) {
                    webView.requestFocus()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to keep active WebView running: ${e.message}")
        }
    }

    private fun addSessionTabOnMain(controller: OverlayController, session: WebSession) {
        if (tabViews[session.id] != null) {
            refreshSessionUiOnMain(session.id)
            return
        }

        val tabTitleView =
            TextView(controller.appContext).apply {
                textSize = 12f
                maxLines = 1
                text = tabLabel(session)
            }

        val tabCloseView =
            TextView(controller.appContext).apply {
                text = "✕"
                textSize = 11f
                setPadding(dp(6), 0, dp(2), 0)
                setOnClickListener {
                    closeSession(session.id)
                }
            }

        val tabView =
            LinearLayout(controller.appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(6), dp(6))
                addView(
                    tabTitleView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    tabCloseView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                setOnClickListener {
                    runOnMainSync {
                        activateSessionOnMain(controller, session.id)
                    }
                }
            }

        tabViews[session.id] = tabView
        tabTitleViews[session.id] = tabTitleView
        tabCloseViews[session.id] = tabCloseView

        val lp =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
        controller.tabsContainer.addView(tabView, lp)

        refreshSessionUiOnMain(session.id)
    }

    private fun removeSessionTabOnMain(controller: OverlayController, sessionId: String) {
        val tab = tabViews.remove(sessionId) ?: return
        tabTitleViews.remove(sessionId)
        tabCloseViews.remove(sessionId)
        try {
            controller.tabsContainer.removeView(tab)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error removing tab for $sessionId: ${e.message}")
        }
    }

    private fun activateSessionOnMain(controller: OverlayController, sessionId: String) {
        val session = sessions[sessionId] ?: return

        val parent = session.webView.parent
        if (parent is ViewGroup && parent !== controller.webContainer) {
            parent.removeView(session.webView)
        }

        controller.webContainer.removeAllViews()
        if (session.webView.parent == null) {
            controller.webContainer.addView(
                session.webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        controller.activeSessionId = sessionId
        controller.titleView.text = headerTitle(session)
        refreshTabStylesOnMain(controller)
    }

    private fun ensureSessionAttachedOnMain(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val controller = ensureOverlayOnMain(context.applicationContext)
        if (tabViews[session.id] == null) {
            addSessionTabOnMain(controller, session)
        }
        activateSessionOnMain(controller, sessionId)
    }

    private fun refreshTabStylesOnMain(controller: OverlayController) {
        val activeId = controller.activeSessionId
        tabViews.forEach { (id, tab) ->
            val active = id == activeId
            val titleView = tabTitleViews[id]
            val closeView = tabCloseViews[id]

            titleView?.setTextColor(
                if (active) AndroidColor.WHITE else AndroidColor.parseColor("#334155")
            )
            closeView?.setTextColor(
                if (active) AndroidColor.parseColor("#DBEAFE") else AndroidColor.parseColor("#64748B")
            )

            tab.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(
                        if (active) {
                            AndroidColor.parseColor("#2563EB")
                        } else {
                            AndroidColor.parseColor("#E2E8F0")
                        }
                    )
                }
        }
    }

    private fun refreshSessionUiOnMain(sessionId: String) {
        val session = sessions[sessionId] ?: return
        tabTitleViews[sessionId]?.text = tabLabel(session)

        val controller = overlayController ?: return
        if (controller.activeSessionId == sessionId) {
            controller.titleView.text = headerTitle(session)
        }
        refreshTabStylesOnMain(controller)
    }

    private fun tabLabel(session: WebSession): String {
        val index = indexOfSession(session.id)
        val base =
            when {
                session.pageTitle.isNotBlank() -> session.pageTitle
                !session.sessionName.isNullOrBlank() -> session.sessionName
                session.currentUrl.isNotBlank() -> session.currentUrl
                else -> "Session"
            }
        val short = shorten(base, 16)
        return if (index > 0) "$index · $short" else short
    }

    private fun headerTitle(session: WebSession): String {
        val title =
            when {
                session.pageTitle.isNotBlank() -> session.pageTitle
                !session.sessionName.isNullOrBlank() -> session.sessionName
                session.currentUrl.isNotBlank() -> session.currentUrl
                else -> "Web Session"
            }
        return shorten(title, 42)
    }

    private fun closeSession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        removeSessionOrder(sessionId)

        runOnMainSync {
            val controller = overlayController

            if (controller != null) {
                if (controller.activeSessionId == sessionId) {
                    controller.webContainer.removeAllViews()
                    controller.activeSessionId = null
                }
                removeSessionTabOnMain(controller, sessionId)
            }

            val parent = session.webView.parent
            if (parent is ViewGroup) {
                parent.removeView(session.webView)
            }
            session.pendingFileChooserCallback?.onReceiveValue(null)
            session.pendingFileChooserCallback = null
            cleanupWebViewOnMain(session.webView)

            val nextSessionId = listSessionIdsInOrder().firstOrNull()
            val currentController = overlayController
            if (nextSessionId != null && currentController != null) {
                activateSessionOnMain(currentController, nextSessionId)
            } else {
                destroyOverlayOnMain()
            }
        }

        return true
    }

    private fun cleanupWebViewOnMain(webView: WebView) {
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.removeAllViews()
            webView.destroy()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error during WebView cleanup: ${e.message}")
        }
    }

    private fun getSession(sessionId: String?): WebSession? {
        if (!sessionId.isNullOrBlank()) {
            return sessions[sessionId]
        }

        val activeId = resolvePreferredSessionId()
        if (activeId.isNullOrBlank()) {
            return null
        }
        return sessions[activeId]
    }

    private fun resolvePreferredSessionId(): String? {
        val activeId =
            runOnMainSync(timeoutMs = 2_000L) {
                overlayController?.activeSessionId
            }

        if (!activeId.isNullOrBlank() && sessions.containsKey(activeId)) {
            return activeId
        }

        val ordered = listSessionIdsInOrder().firstOrNull { sessions.containsKey(it) }
        if (!ordered.isNullOrBlank()) {
            return ordered
        }

        return sessions.keys.firstOrNull()
    }

    private fun addSessionOrder(sessionId: String) {
        synchronized(sessionOrderLock) {
            if (!sessionOrder.contains(sessionId)) {
                sessionOrder.add(sessionId)
            }
        }
    }

    private fun removeSessionOrder(sessionId: String) {
        synchronized(sessionOrderLock) {
            sessionOrder.remove(sessionId)
        }
    }

    private fun listSessionIdsInOrder(): List<String> {
        synchronized(sessionOrderLock) {
            return sessionOrder.toList()
        }
    }

    private fun indexOfSession(sessionId: String): Int {
        synchronized(sessionOrderLock) {
            val index = sessionOrder.indexOf(sessionId)
            return if (index < 0) -1 else index + 1
        }
    }

    private fun evaluateJavascriptSync(webView: WebView, script: String, timeoutMs: Long): String {
        val latch = CountDownLatch(1)
        var result: String? = null

        mainHandler.post {
            try {
                if (!webView.isAttachedToWindow) {
                    result = JSONObject.quote("WebView is not attached")
                    latch.countDown()
                    return@post
                }
                webView.evaluateJavascript(script) { value ->
                    result = value
                    latch.countDown()
                }
            } catch (e: Exception) {
                result = JSONObject.quote("JavaScript evaluation error: ${e.message}")
                latch.countDown()
            }
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("JavaScript execution timeout (${timeoutMs}ms)")
        }

        return result ?: "null"
    }

    private fun decodeJsResult(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") {
            return ""
        }

        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return try {
                JSONObject("{\"v\":$raw}").getString("v")
            } catch (_: Exception) {
                raw.substring(1, raw.length - 1)
            }
        }

        return raw
    }

    private fun quoteJs(value: String): String = JSONObject.quote(value)

    private fun shorten(text: String, maxLen: Int): String {
        val cleaned = text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        if (cleaned.length <= maxLen) {
            return cleaned
        }
        return cleaned.take(maxLen - 1) + "…"
    }

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }

        return try {
            val json = JSONObject(raw)
            val keys = json.keys()
            val out = mutableMapOf<String, String>()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = json.optString(key, "")
            }
            out
        } catch (e: Exception) {
            AppLogger.w(TAG, "Invalid headers JSON: ${e.message}")
            emptyMap()
        }
    }

    private fun parseStringArrayParam(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val array = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val value = array.opt(i)
                if (value == null || value == JSONObject.NULL) {
                    continue
                }
                out.add(value.toString())
            }
            out
        } catch (e: Exception) {
            AppLogger.w(TAG, "Invalid array JSON: ${e.message}")
            null
        }
    }

    private fun param(tool: AITool, name: String): String? =
        tool.parameters.find { it.name == name }?.value

    private fun boolParam(tool: AITool, name: String, default: Boolean): Boolean {
        return when (param(tool, name)?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }

    private fun intParam(tool: AITool, name: String, default: Int): Int {
        return param(tool, name)?.trim()?.toIntOrNull() ?: default
    }

    private fun longParam(tool: AITool, name: String, default: Long): Long {
        return param(tool, name)?.trim()?.toLongOrNull() ?: default
    }

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun ok(toolName: String, payload: JSONObject): ToolResult {
        return ToolResult(
            toolName = toolName,
            success = true,
            result = StringResultData(payload.toString())
        )
    }

    private fun error(toolName: String, message: String): ToolResult {
        return ToolResult(
            toolName = toolName,
            success = false,
            result = StringResultData(""),
            error = message
        )
    }

    private fun <T> runOnMainSync(timeoutMs: Long = 8_000L, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null

        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Main-thread operation timeout (${timeoutMs}ms)")
        }

        if (error != null) {
            throw RuntimeException(error)
        }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
