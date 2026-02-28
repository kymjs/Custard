package com.kymjs.ai.custard.core.tools.javascript

import android.content.Context
import com.kymjs.ai.custard.util.AppLogger
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.kymjs.ai.custard.core.tools.AIToolHandler
import com.kymjs.ai.custard.core.tools.BooleanResultData
import com.kymjs.ai.custard.core.tools.IntResultData
import com.kymjs.ai.custard.core.tools.StringResultData
import com.kymjs.ai.custard.core.tools.packTool.PackageManager
import com.kymjs.ai.custard.data.model.AITool
import com.kymjs.ai.custard.data.model.ToolParameter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject
import com.kymjs.ai.custard.data.preferences.EnvPreferences
import android.graphics.Bitmap
import android.util.Base64
import com.kymjs.ai.custard.core.tools.BinaryResultData
import com.kymjs.ai.custard.core.tools.javascript.JsTimeoutConfig
import com.kymjs.ai.custard.util.ImagePoolManager
import com.kymjs.ai.custard.util.CustardPaths
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * JavaScript 引擎 - 通过 WebView 执行 JavaScript 脚本 提供与 Android 原生代码的交互机制
 *
 * 主要功能：
 * 1. 执行 JavaScript 脚本
 * 2. 为脚本提供工具调用能力
 * 3. 集成常用的第三方 JavaScript 库
 *
 * 工具调用使用方式:
 * - 标准模式: toolCall("toolType", "toolName", { param1: "value1" })
 * - 简化模式: toolCall("toolName", { param1: "value1" })
 * - 对象模式: toolCall({ type: "toolType", name: "toolName", params: { param1: "value1" } })
 * - 直接模式: toolCall("toolName")
 *
 * 便捷工具调用:
 * - 文件操作: Tools.Files.read("/path/to/file")
 * - 网络操作: Tools.Net.httpGet("https://example.com")
 * - 系统操作: Tools.System.sleep("1")
 * - 计算功能: Tools.calc("2 + 2 * 3")
 *
 * 完成脚本执行:
 * - complete(result) 函数传递最终结果返回给调用者
 */
class JsEngine(private val context: Context) {
    companion object {
        private const val TAG = "JsEngine"
        private const val BINARY_DATA_THRESHOLD = 32 * 1024 // 32KB
        private const val BINARY_HANDLE_PREFIX = "@binary_handle:"
    }

    // 存储原生Bitmap对象的注册表
    private val bitmapRegistry = ConcurrentHashMap<String, Bitmap>()
    // 存储大型二进制数据的注册表
    private val binaryDataRegistry = ConcurrentHashMap<String, ByteArray>()

    // WebView 实例用于执行 JavaScript
    private var webView: WebView? = null

    // 工具处理器
    private val toolHandler = AIToolHandler.getInstance(context)
    private val packageManager by lazy { PackageManager.getInstance(context, toolHandler) }

    // 工具调用接口
    private val toolCallInterface = JsToolCallInterface()

    // 结果回调
    private var resultCallback: CompletableFuture<Any?>? = null
    private var intermediateResultCallback: ((Any?) -> Unit)? = null

    // 用于存储工具调用的回调
    private val toolCallbacks = mutableMapOf<String, CompletableFuture<String>>()
    private val composeDslActionCompleteCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val composeDslActionErrorCallbacks = ConcurrentHashMap<String, (String) -> Unit>()

    // 标记 JS 环境是否已初始化
    private var jsEnvironmentInitialized = false

    private var envOverrides: Map<String, String> = emptyMap()

    // 初始化 WebView
    private fun initWebView() {
        if (webView == null) {
            // 需要在主线程创建 WebView
            val latch = CountDownLatch(1)
            ContextCompat.getMainExecutor(context).execute {
                try {
                    webView =
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true // 允许访问 sessionStorage 和 localStorage

                                // 为了安全，禁用文件系统访问，除非显式通过工具提供
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false

                                // 设置User Agent
                                settings.userAgentString = "Custard-JsEngine/1.0"
                                addJavascriptInterface(toolCallInterface, "NativeInterface")
                                // 加载一个带有有效基地址的空HTML页面，以解决 about:blank 的源安全问题
                                loadDataWithBaseURL("https://localhost", "<html></html>", "text/html", "UTF-8", null)
                            }
                    latch.countDown()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error initializing WebView: ${e.message}", e)
                    latch.countDown()
                }
            }
            latch.await(10, TimeUnit.SECONDS)
        }
    }

    private fun getComposeDslContextBridgeDefinition(): String {
        return buildComposeDslContextBridgeDefinition()
    }

    /** 初始化 JavaScript 环境 加载核心功能、工具库和辅助函数 这些代码只需要执行一次 */
    private fun initJavaScriptEnvironment() {
        if (jsEnvironmentInitialized) {
            return // 如果已经初始化，直接返回
        }

        val custardDownloadDir = CustardPaths.custardRootPathSdcard()
        val custardCleanOnExitDir = CustardPaths.cleanOnExitPathSdcard()

        val initScript =
                """
            // 添加全局错误处理器，捕获所有未处理的错误
            window.onerror = function(message, source, lineno, colno, error) {
                try {
                    // 构建完整的错误信息
                    var errorInfo = {
                        message: message,
                        source: source,
                        line: lineno,
                        column: colno,
                        stack: error && error.stack ? error.stack : "No stack trace available"
                    };
                    
                    // 记录详细的错误信息到控制台
                    console.error("GLOBAL ERROR CAUGHT:", JSON.stringify(errorInfo));
                    
                    // 如果尚未完成执行，报告错误
                    if (!window._hasCompleted) {
                        NativeInterface.setError("JavaScript Error: " + message + " at line " + lineno + 
                                               ", column " + colno + " in " + (source || "unknown") + 
                                               "\nStack: " + (error && error.stack ? error.stack : "No stack trace"));
                        window._hasCompleted = true;
                    }
                    
                    // 返回true表示我们已经处理了错误
                    return true;
                } catch(e) {
                    // 确保错误处理器本身不会抛出错误
                    console.error("Error in error handler:", e);
                    return false;
                }
            };
            
            // 添加异常对象扩展方法，用于格式化错误信息
            window.formatErrorDetails = function(error) {
                if (!error) return "Unknown error";
                
                try {
                    // 尝试提取完整的错误信息
                    var details = {
                        name: error.name || "Error",
                        message: error.message || String(error),
                        stack: error.stack || "No stack trace available",
                        fileName: error.fileName || "Unknown file",
                        lineNumber: error.lineNumber || "Unknown line",
                        columnNumber: error.columnNumber || "Unknown column"
                    };
                    
                    // 尝试从堆栈信息中提取更多信息
                    if (details.stack && (!details.fileName || details.fileName === "Unknown file")) {
                        var stackMatch = details.stack.match(/at\s+.*?\s+\((.+):(\d+):(\d+)\)/);
                        if (stackMatch) {
                            details.fileName = stackMatch[1] || details.fileName;
                            details.lineNumber = stackMatch[2] || details.lineNumber;
                            details.columnNumber = stackMatch[3] || details.columnNumber;
                        }
                    }
                    
                    // 生成详细的错误消息
                    var formattedMessage = details.name + ": " + details.message + "\n" +
                                          "File: " + details.fileName + "\n" +
                                          "Line: " + details.lineNumber + ", Column: " + details.columnNumber + "\n" +
                                          "Stack Trace:\n" + details.stack;
                    
                    return {
                        formatted: formattedMessage,
                        details: details
                    };
                } catch (e) {
                    console.error("Error formatting error details:", e);
                    return {
                        formatted: String(error),
                        details: { message: String(error) }
                    };
                }
            };
            
            // 添加一个专用的方法来报告详细错误
            window.reportDetailedError = function(error, context) {
                var errorDetails = window.formatErrorDetails(error);
                console.error("DETAILED ERROR (" + (context || "unknown context") + "):", errorDetails.formatted);
                
                if (typeof NativeInterface !== 'undefined' && NativeInterface.reportError) {
                    try {
                        NativeInterface.reportError(
                            errorDetails.details.name || "Error",
                            errorDetails.details.message || String(error),
                            errorDetails.details.lineNumber || 0,
                            errorDetails.details.stack || "No stack trace"
                        );
                    } catch (e) {
                        console.error("Failed to report error to native interface:", e);
                    }
                }
                
                return errorDetails;
            };
            
            // 增强console功能，将所有控制台输出发送到Android
            (function() {
                var originalConsole = {
                    log: console.log,
                    error: console.error,
                    warn: console.warn,
                    info: console.info
                };
                
                // 重写控制台方法
                console.log = function() {
                    try {
                        var args = Array.prototype.slice.call(arguments);
                        var message = args.map(function(arg) {
                            return typeof arg === 'object' ? JSON.stringify(arg) : String(arg);
                        }).join(' ');
                        
                        // 调用原始方法
                        originalConsole.log.apply(console, arguments);
                        
                        // 向Android报告日志
                        if (typeof NativeInterface !== 'undefined' && NativeInterface.logInfo) {
                            NativeInterface.logInfo("LOG: " + message);
                        }
                    } catch(e) {
                        originalConsole.error("Error in console.log:", e);
                    }
                };
                
                console.error = function() {
                    try {
                        var args = Array.prototype.slice.call(arguments);
                        var message = args.map(function(arg) {
                            return typeof arg === 'object' ? JSON.stringify(arg) : String(arg);
                        }).join(' ');
                        
                        // 调用原始方法
                        originalConsole.error.apply(console, arguments);
                        
                        // 向Android报告错误
                        if (typeof NativeInterface !== 'undefined' && NativeInterface.logError) {
                            NativeInterface.logError("ERROR: " + message);
                        }
                    } catch(e) {
                        originalConsole.error("Error in console.error:", e);
                    }
                };
            })();
            
            // 定义 toolCall 函数 - 支持多种参数传递方式，并且返回Promise
            function toolCall(toolType, toolName, toolParams) {
                // Create a Promise wrapping the tool call
                return new Promise((resolve, reject) => {
                    try {
                        // 生成唯一回调ID
                        const callbackId = '_tc_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                        
                        // 处理不同的参数调用模式
                        let type, name, params;
                        
                        if (arguments.length === 1 && typeof toolType === 'object') {
                            // 对象模式: toolCall({type: "...", name: "...", params: {...}})
                            const config = toolType;
                            type = config.type || "default";
                            name = config.name || "";
                            
                            // 安全地序列化参数对象
                            try {
                                let paramsObj = {};
                                if (config.params && typeof config.params === 'object') {
                                    paramsObj = Object.assign({}, config.params);
                                }
                                params = JSON.stringify(paramsObj);
                            } catch (e) {
                                console.error("Error serializing object mode params:", e);
                                params = "{}";
                            }
                        } else if (arguments.length === 1 && typeof toolType === 'string') {
                            // 字符串模式: toolCall("toolName")
                            type = "default";
                            name = toolType;
                            params = "{}";
                        } else if (arguments.length === 2 && typeof toolName === 'object') {
                            // 工具名+参数模式: toolCall("toolName", {param1: "value1"})
                            type = "default";
                            name = toolType;
                            
                            // 安全地序列化参数对象
                            try {
                                // 使用深拷贝而非直接引用，避免修改原始对象
                                const paramsCopy = Object.assign({}, toolName || {});
                                params = JSON.stringify(paramsCopy);
                            } catch (e) {
                                console.error("Error serializing params:", e);
                                params = "{}";
                            }
                        } else {
                            // 标准模式: toolCall("toolType", "toolName", {param1: "value1"})
                            type = toolType || "default";
                            name = toolName || "";
                            
                            // 安全地序列化参数对象
                            try {
                                const paramsCopy = Object.assign({}, toolParams || {});
                                params = JSON.stringify(paramsCopy);
                            } catch (e) {
                                console.error("Error serializing params:", e);
                                params = "{}";
                            }
                        }
                        
                        // 调用本地方法，并传递回调ID
                        NativeInterface.callToolAsync(callbackId, type, name, params);
                        
                        // 注册回调处理
                        window[callbackId] = function(result, isError) {
                            // 清理回调
                            delete window[callbackId];
                            
                            // Handle the structured result
                            if (isError) {
                                // Error results are now structured JSON objects
                                if (typeof result === 'object' && result.success === false) {
                                    reject(new Error(result.error || "Unknown error"));
                                } else {
                                    reject(new Error(typeof result === 'string' ? result : JSON.stringify(result)));
                                }
                            } else {
                                try {
                                    // Process structured result
                                    let processedResult;
                                    
                                    // If it's already a JS object (from JSON in sendToolResult)
                                    if (typeof result === 'object') {
                                        if (result.success) {
                                            // Return just the data part of successful results
                                            processedResult = result.data;
                                        } else {
                                            // For error objects, reject the promise
                                            reject(new Error(result.error || "Unknown error"));
                                            return;
                                        }
                                    } 
                                    // If it's a JSON string
                                    else if (typeof result === 'string' && (result.startsWith('{') || result.startsWith('['))) {
                                        const parsedResult = JSON.parse(result);
                                        
                                        // Check if it's our ToolResult format
                                        if (parsedResult && typeof parsedResult === 'object' && 'success' in parsedResult) {
                                            if (parsedResult.success) {
                                                // Return just the data for successful results
                                                processedResult = parsedResult.data;
                                            } else {
                                                // For error results, reject the promise
                                                reject(new Error(parsedResult.error || "Unknown error"));
                                                return;
                                            }
                                        } else {
                                            // Regular JSON result (legacy or other format)
                                            processedResult = parsedResult;
                                        }
                                    } else {
                                        // Plain string or other primitive
                                        processedResult = result;
                                    }
                                    
                                    // Resolve the promise with the final processed result
                                    resolve(processedResult);
                                } catch (e) {
                                    // If any parsing error occurs, return the original result
                                    console.error("Error processing tool result:", e);
                                    resolve(result);
                                }
                            }
                        };
                    } catch (error) {
                        reject(error);
                    }
                });
            }

            // 环境变量访问助手
            function getEnv(key) {
                try {
                    if (typeof NativeInterface !== 'undefined' && NativeInterface.getEnv) {
                        var name = String(key || "").trim();
                        if (!name) {
                            return undefined;
                        }
                        var value = NativeInterface.getEnv(name);
                        if (value === null || value === undefined || value === "") {
                            return undefined;
                        }
                        return String(value);
                    }
                } catch (e) {
                    console.error("getEnv error:", e);
                }
                return undefined;
            }

            function getState() {
                try {
                    var v = window['__custard_package_state'];
                    if (v === null || v === undefined || v === "") {
                        return undefined;
                    }
                    return String(v);
                } catch (e) {
                    return undefined;
                }
            }

            function getLang() {
                try {
                    var v = window['__custard_package_lang'];
                    if (v === null || v === undefined || v === "") {
                        return "en";
                    }
                    return String(v);
                } catch (e) {
                    return "en";
                }
            }

            function getCallerName() {
                try {
                    var v = window['__custard_package_caller_name'];
                    if (v === null || v === undefined || v === "") {
                        return undefined;
                    }
                    return String(v);
                } catch (e) {
                    return undefined;
                }
            }

            function getChatId() {
                try {
                    var v = window['__custard_package_chat_id'];
                    if (v === null || v === undefined || v === "") {
                        return undefined;
                    }
                    return String(v);
                } catch (e) {
                    return undefined;
                }
            }

            function getCallerCardId() {
                try {
                    var v = window['__custard_package_caller_card_id'];
                    if (v === null || v === undefined || v === "") {
                        return undefined;
                    }
                    return String(v);
                } catch (e) {
                    return undefined;
                }
            }

            // Custard standard directories (injected from native)
            var CUSTARD_DOWNLOAD_DIR = ${JSONObject.quote(custardDownloadDir)};
            var CUSTARD_CLEAN_ON_EXIT_DIR = ${JSONObject.quote(custardCleanOnExitDir)};
            
            // 加载工具调用的便捷方法
            ${getJsToolsDefinition()}

            // compose_dsl 上下文桥接（让 UI 脚本可直接使用 toolCall / Tools）
            ${getComposeDslContextBridgeDefinition()}
             
            // 定义完成回调
            function complete(result) {
                try {
                    console.log("complete() called with result type: " + typeof result);
                    
                    if (!window._hasCompleted) {
                        window._hasCompleted = true;
                        clearTimeout(window._safetyTimeout);
                        
                        // 确保结果是可以序列化的
                        let serializedResult;
                        try {
                            serializedResult = JSON.stringify(result);
                            console.log("Result serialized successfully, length: " + serializedResult.length);
                        } catch (serializeError) {
                            console.error("Failed to serialize result:", serializeError);
                            serializedResult = JSON.stringify({
                                error: "Failed to serialize result",
                                message: String(serializeError),
                                result: String(result).substring(0, 1000)
                            });
                        }
                        
                        // 通过JavaScript接口发送结果
                        try {
                            console.log("Calling NativeInterface.setResult...");
                            NativeInterface.setResult(serializedResult);
                            console.log("NativeInterface.setResult call completed");
                        } catch (nativeError) {
                            console.error("Error calling NativeInterface:", nativeError);
                            // 尝试再次调用，以防是暂时性错误
                            setTimeout(() => {
                                try {
                                    console.log("Retrying NativeInterface.setResult...");
                                    NativeInterface.setResult(serializedResult);
                                } catch (retryError) {
                                    console.error("Retry also failed:", retryError);
                                }
                            }, 100);
                        }
                    } else {
                        console.warn("complete() called but execution was already completed");
                    }
                } catch (completeError) {
                    console.error("Error in complete function:", completeError);
                    try {
                        NativeInterface.setError("Error in complete function: " + completeError.message);
                    } catch (e) {
                        console.error("Failed to report complete error:", e);
                    }
                }
            }

            function sendIntermediateResult(result) {
                try {
                    if (window._hasCompleted) {
                        console.warn("sendIntermediateResult called after execution was completed. Ignoring.");
                        return;
                    }
                    
                    let serializedResult;
                    try {
                        serializedResult = JSON.stringify(result);
                    } catch (serializeError) {
                        console.error("Failed to serialize intermediate result:", serializeError);
                        serializedResult = JSON.stringify({
                            error: "Failed to serialize result",
                            message: String(serializeError)
                        });
                    }
                    NativeInterface.sendIntermediateResult(serializedResult);
                } catch (error) {
                    console.error("Error in sendIntermediateResult function:", error);
                }
            }
            
            // 加载第三方库支持
            ${getJsThirdPartyLibraries()}

            // 加载 CryptoJS 原生桥接
            ${loadCryptoJs(context)}
            
            // 加载 Jimp 原生桥接
            ${loadJimpJs(context)}
            
            // 加载 UINode 库
            ${loadUINodeJs(context)}
            
            // 加载 AndroidUtils 库
            ${loadAndroidUtilsJs(context)}
            
            // 加载 OkHttp3 库
            ${loadOkHttp3Js(context)}
            
            // 加载 pako 库 (原生桥接)
            ${loadPakoJs(context)}
            
            // 函数处理异步Promise的辅助函数
            function __handleAsync(possiblePromise) {
                if (possiblePromise instanceof Promise) {
                    // 更强大的异步处理
                    console.log("Detected async Promise, waiting for resolution...");
                    
                    // 创建一个超时保护，确保非常长时间运行的Promise最终会被处理
                    const asyncTimeout = setTimeout(() => {
                        if (!window._hasCompleted) {
                            console.log("Async Promise timeout reached after ${JsTimeoutConfig.ASYNC_PROMISE_TIMEOUT_SECONDS} seconds");
                            // 尝试安全地完成执行
                            try {
                                window._hasCompleted = true;
                                // 创建超时结果
                                const timeoutResult = {
                                    warning: "Operation timeout", 
                                    result: "Promise did not resolve within the time limit (${JsTimeoutConfig.ASYNC_PROMISE_TIMEOUT_SECONDS} seconds)"
                                };
                                
                                NativeInterface.setResult(JSON.stringify(timeoutResult));
                                console.log("Timeout result set from Promise handler");
                            } catch (e) {
                                console.error("Error during timeout handling:", e);
                            }
                        }
                    }, ${JsTimeoutConfig.ASYNC_PROMISE_TIMEOUT_SECONDS * 1000}); // 比主超时稍短
                    
                    possiblePromise
                        .then(result => {
                            clearTimeout(asyncTimeout);
                            console.log("Async Promise resolved successfully");
                            if (!window._hasCompleted) {
                                try {
                                    window._hasCompleted = true;
                                    // 安全地序列化结果
                                    let serializedResult;
                                    try {
                                        serializedResult = JSON.stringify(result);
                                    } catch (serializeError) {
                                        console.error("Failed to serialize Promise result:", serializeError);
                                        // 创建一个简单的可以序列化的对象
                                        serializedResult = JSON.stringify({
                                            error: "Failed to serialize result",
                                            message: String(serializeError),
                                            result: String(result).substring(0, 1000)
                                        });
                                    }
                                    NativeInterface.setResult(serializedResult);
                                    console.log("Result set from Promise resolution");
                                } catch (completionError) {
                                    console.error("Error during async completion:", completionError);
                                    NativeInterface.setError("Async completion error: " + completionError.message);
                                }
                            } else {
                                console.log("Promise resolved, but execution was already completed");
                            }
                        })
                        .catch(error => {
                            clearTimeout(asyncTimeout);
                            
                            // 使用我们新的详细错误报告功能
                            const errorReport = window.reportDetailedError(error, "Async Promise Rejection");
                            
                            if (!window._hasCompleted) {
                                try {
                                    window._hasCompleted = true;
                                    
                                    // 使用格式化的错误信息
                                    NativeInterface.setError(JSON.stringify({
                                        error: "Promise rejection",
                                        details: errorReport.details,
                                        formatted: errorReport.formatted
                                    }));
                                    
                                    console.log("Detailed error information reported from Promise rejection");
                                } catch (errorHandlingError) {
                                    console.error("Error during async error handling:", errorHandlingError);
                                    
                                    // 尝试更简单的错误报告方式作为后备
                                    try {
                                        NativeInterface.setError("Error in Promise: " + String(error) + 
                                                               "\nError handling failed: " + String(errorHandlingError));
                                    } catch (e) {
                                        console.error("Complete failure in error handling chain:", e);
                                    }
                                }
                            }
                        });
                    return true; // Signal that we're handling it asynchronously
                }
                return false; // Not a promise
            }
        """.trimIndent()

        // 在 WebView 中执行初始化脚本
        val initLatch = CountDownLatch(1)
        ContextCompat.getMainExecutor(context).execute {
            try {
                webView?.evaluateJavascript(initScript) { result ->
                    AppLogger.d(TAG, "JS environment initialization completed: $result")
                    try {
                        webView?.evaluateJavascript("typeof __handleAsync === 'function'") { checkResult ->
                            val isHandleAsyncDefined = checkResult == "true"
                            if (isHandleAsyncDefined) {
                                jsEnvironmentInitialized = true
                            } else {
                                jsEnvironmentInitialized = false
                                AppLogger.e(TAG, "__handleAsync is not defined after JS environment initialization. Result: $checkResult")
                            }
                            initLatch.countDown()
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to verify __handleAsync after JS environment initialization: ${e.message}", e)
                        jsEnvironmentInitialized = false
                        initLatch.countDown()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize JS environment: ${e.message}", e)
                jsEnvironmentInitialized = false
                initLatch.countDown()
            }
        }

        // 等待初始化完成，使用超时避免无限等待
        try {
            if (!initLatch.await(10, TimeUnit.SECONDS)) {
                AppLogger.w(TAG, "JS environment initialization timeout after 10 seconds")
            }
        } catch (e: InterruptedException) {
            AppLogger.e(TAG, "JS environment initialization interrupted", e)
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 执行 JavaScript 脚本并调用其中的特定函数
     * @param script 完整的JavaScript脚本内容
     * @param functionName 要调用的函数名称
     * @param params 要传递给函数的参数
     * @return 函数执行结果
     */
    fun executeScriptFunction(
            script: String,
            functionName: String,
            params: Map<String, Any?>,
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null
    ): Any? {
        // Reset any previous state
        resetState()
        this.envOverrides = envOverrides
        this.intermediateResultCallback = onIntermediateResult

        initWebView()

        // 确保JavaScript环境已初始化
        if (!jsEnvironmentInitialized) {
            initJavaScriptEnvironment()
        }

        val future = CompletableFuture<Any?>()
        resultCallback = future

        // 将参数转换为 JSON 对象
        val paramsJson = JSONObject(params).toString()
        val scriptJson = JSONObject.quote(script)

        // 优化后的脚本执行代码，只包含必要的执行逻辑
        val executionScript =
                """
            // 清理定时器以准备新的执行
            (function() {
                try {
                    var highestTimeoutId = setTimeout(";");
                    for (var i = 0 ; i < highestTimeoutId ; i++) {
                        clearTimeout(i);
                        clearInterval(i);
                    }
                } catch(e) {}
            })();
            
            // 设置参数和执行状态
            var params = $paramsJson;
            window._hasCompleted = false;
            try {
                var __custardState = (params && params.__custard_package_state !== undefined && params.__custard_package_state !== null && String(params.__custard_package_state).length > 0)
                    ? String(params.__custard_package_state)
                    : undefined;
                window['__custard_package_state'] = __custardState;
            } catch (e) {
            }
            try {
                var __custardLang = (params && params.__custard_package_lang !== undefined && params.__custard_package_lang !== null && String(params.__custard_package_lang).length > 0)
                    ? String(params.__custard_package_lang)
                    : ((window['__custard_package_lang'] !== undefined && window['__custard_package_lang'] !== null && String(window['__custard_package_lang']).length > 0)
                        ? String(window['__custard_package_lang'])
                        : undefined);
                window['__custard_package_lang'] = __custardLang;
            } catch (e) {
            }
            try {
                var __custardCallerName = (params && params.__custard_package_caller_name !== undefined && params.__custard_package_caller_name !== null && String(params.__custard_package_caller_name).length > 0)
                    ? String(params.__custard_package_caller_name)
                    : undefined;
                window['__custard_package_caller_name'] = __custardCallerName;
            } catch (e) {
            }
            try {
                var __custardChatId = (params && params.__custard_package_chat_id !== undefined && params.__custard_package_chat_id !== null && String(params.__custard_package_chat_id).length > 0)
                    ? String(params.__custard_package_chat_id)
                    : undefined;
                window['__custard_package_chat_id'] = __custardChatId;
            } catch (e) {
            }
            try {
                var __custardCallerCardId = (params && params.__custard_package_caller_card_id !== undefined && params.__custard_package_caller_card_id !== null && String(params.__custard_package_caller_card_id).length > 0)
                    ? String(params.__custard_package_caller_card_id)
                    : undefined;
                window['__custard_package_caller_card_id'] = __custardCallerCardId;
            } catch (e) {
            }
            
            // 设置安全超时机制
            window._safetyTimeout = setTimeout(function() {
                if (!window._hasCompleted) {
                    console.log("Safety timeout warning at " + (${JsTimeoutConfig.PRE_TIMEOUT_SECONDS}) + " seconds");
                    // 不立即结束，而是添加另一个最终超时
                    setTimeout(function() {
                        if (!window._hasCompleted) {
                            window._hasCompleted = true;
                            NativeInterface.setResult("Script execution timed out after ${JsTimeoutConfig.MAIN_TIMEOUT_SECONDS} seconds");
                        }
                    }, 5000); // 再等5秒
                }
            }, ${JsTimeoutConfig.PRE_TIMEOUT_SECONDS * 1000});
            
            // 执行用户脚本
            try {
                // 创建模块执行环境 - 使用一个闭包来避免重复声明变量
                let moduleResult = (function() {
                    // 创建一个自包含的模块环境
                    const module = {exports: {}};
                    const exports = module.exports;
                    
                    const __custardPackageTarget =
                        (params &&
                            params.__custard_ui_package_name !== undefined &&
                            params.__custard_ui_package_name !== null &&
                            String(params.__custard_ui_package_name).length > 0)
                            ? String(params.__custard_ui_package_name)
                            : ((params &&
                                params.toolPkgId !== undefined &&
                                params.toolPkgId !== null &&
                                String(params.toolPkgId).length > 0)
                                ? String(params.toolPkgId)
                                : "");
                    const __custardEntryPath =
                        (params &&
                            params.__custard_script_entry !== undefined &&
                            params.__custard_script_entry !== null &&
                            String(params.__custard_script_entry).length > 0)
                            ? String(params.__custard_script_entry).replace(/\\/g, '/')
                            : ((params &&
                                params.moduleSpec &&
                                params.moduleSpec.entry !== undefined &&
                                params.moduleSpec.entry !== null &&
                                String(params.moduleSpec.entry).length > 0)
                                ? String(params.moduleSpec.entry).replace(/\\/g, '/')
                                : "");
                    const __custardModuleCache = {};

                    function __custardTranspileEsmToCjs(sourceCode, modulePath) {
                        var code = String(sourceCode || '');

                        code = code.replace(
                            /^\s*import\s+['"]([^'"]+)['"]\s*;?\s*$/gm,
                            function(_, mod) {
                                return "require('" + mod + "');";
                            }
                        );
                        code = code.replace(
                            /^\s*import\s+\*\s+as\s+([A-Za-z_$][\w$]*)\s+from\s+['"]([^'"]+)['"]\s*;?\s*$/gm,
                            function(_, localName, mod) {
                                return "const " + localName + " = require('" + mod + "');";
                            }
                        );
                        code = code.replace(
                            /^\s*import\s+([A-Za-z_$][\w$]*)\s+from\s+['"]([^'"]+)['"]\s*;?\s*$/gm,
                            function(_, localName, mod) {
                                return (
                                    "const " +
                                    localName +
                                    " = (function(__m){ return (__m && Object.prototype.hasOwnProperty.call(__m, 'default')) ? __m.default : __m; })(require('" +
                                    mod +
                                    "'));"
                                );
                            }
                        );
                        code = code.replace(
                            /^\s*import\s+([A-Za-z_$][\w$]*)\s*,\s*\{([^}]+)\}\s+from\s+['"]([^'"]+)['"]\s*;?\s*$/gm,
                            function(_, defaultName, namedPart, mod) {
                                var namedList = String(namedPart || '').trim();
                                return (
                                    "const __mod = require('" +
                                    mod +
                                    "');\n" +
                                    "const " +
                                    defaultName +
                                    " = (function(__m){ return (__m && Object.prototype.hasOwnProperty.call(__m, 'default')) ? __m.default : __m; })(__mod);\n" +
                                    "const { " +
                                    namedList.replace(/\sas\s/g, ': ') +
                                    " } = __mod;"
                                );
                            }
                        );
                        code = code.replace(
                            /^\s*import\s+\{([^}]+)\}\s+from\s+['"]([^'"]+)['"]\s*;?\s*$/gm,
                            function(_, namedPart, mod) {
                                var namedList = String(namedPart || '').trim();
                                return "const { " + namedList.replace(/\sas\s/g, ': ') + " } = require('" + mod + "');";
                            }
                        );

                        var exportedNames = [];
                        code = code.replace(
                            /^\s*export\s+(async\s+function|function|class)\s+([A-Za-z_$][\w$]*)/gm,
                            function(_, decl, name) {
                                exportedNames.push(name);
                                return decl + " " + name;
                            }
                        );
                        code = code.replace(
                            /^\s*export\s+(const|let|var)\s+([A-Za-z_$][\w$]*)/gm,
                            function(_, decl, name) {
                                exportedNames.push(name);
                                return decl + " " + name;
                            }
                        );
                        code = code.replace(
                            /^\s*export\s+default\s+/gm,
                            "module.exports.default = "
                        );
                        code = code.replace(
                            /^\s*export\s*\{\s*([^}]+)\s*\}\s*;?\s*$/gm,
                            function(_, rawSpec) {
                                var specs = String(rawSpec || '').split(',');
                                var lines = [];
                                for (var i = 0; i < specs.length; i++) {
                                    var item = specs[i].trim();
                                    if (!item) {
                                        continue;
                                    }
                                    var match = item.match(/^([A-Za-z_$][\w$]*)(\s+as\s+([A-Za-z_$][\w$]*))?$/);
                                    if (!match) {
                                        continue;
                                    }
                                    var localName = match[1];
                                    var exportName = match[3] || localName;
                                    lines.push("module.exports['" + exportName + "'] = " + localName + ";");
                                }
                                return lines.join('\n');
                            }
                        );
                        code = code.replace(
                            /^\s*export\s+\*\s+from\s+['"][^'"]+['"]\s*;?\s*$/gm,
                            "throw new Error('export * from is not supported in compose_dsl runtime');"
                        );
                        code = code.replace(/^\s*export\s*\{\s*\}\s*;?\s*$/gm, "");

                        if (exportedNames.length > 0) {
                            var uniqueNames = {};
                            var exportLines = [];
                            for (var i = 0; i < exportedNames.length; i++) {
                                var name = exportedNames[i];
                                if (uniqueNames[name]) {
                                    continue;
                                }
                                uniqueNames[name] = true;
                                exportLines.push("module.exports['" + name + "'] = " + name + ";");
                            }
                            if (exportLines.length > 0) {
                                code += "\n" + exportLines.join("\n");
                            }
                        }

                        console.warn(
                            '[compose_dsl] transpiled ESM to CJS: ' +
                                (modulePath || '<inline>') +
                                ', length=' +
                                code.length
                        );
                        return code;
                    }

                    function __custardBuildScriptFactory(scriptText, modulePath) {
                        try {
                            return new Function('module', 'exports', 'require', scriptText);
                        } catch (compileError) {
                            var message = String(compileError && compileError.message ? compileError.message : compileError);
                            var maybeEsm =
                                message.indexOf("Unexpected token 'export'") >= 0 ||
                                message.indexOf('Cannot use import statement outside a module') >= 0 ||
                                message.indexOf("Unexpected token 'import'") >= 0;
                            if (!maybeEsm) {
                                throw compileError;
                            }
                            console.warn(
                                '[compose_dsl] compile failed, retry as ESM->CJS: ' +
                                    (modulePath || '<inline>') +
                                    ', error=' +
                                    message
                            );
                            var transpiled = __custardTranspileEsmToCjs(scriptText, modulePath);
                            return new Function('module', 'exports', 'require', transpiled);
                        }
                    }

                    function __custardNormalizePath(path) {
                        var parts = String(path || '').replace(/\\/g, '/').split('/');
                        var stack = [];
                        for (var i = 0; i < parts.length; i++) {
                            var part = parts[i];
                            if (!part || part === '.') {
                                continue;
                            }
                            if (part === '..') {
                                if (stack.length > 0) {
                                    stack.pop();
                                }
                                continue;
                            }
                            stack.push(part);
                        }
                        return stack.join('/');
                    }

                    function __custardDirname(path) {
                        var normalized = __custardNormalizePath(path);
                        if (!normalized) {
                            return '';
                        }
                        var index = normalized.lastIndexOf('/');
                        if (index < 0) {
                            return '';
                        }
                        return normalized.substring(0, index);
                    }

                    function __custardResolveModulePath(moduleName, fromPath) {
                        var request = String(moduleName || '').replace(/\\/g, '/').trim();
                        if (!request) {
                            return '';
                        }
                        if (!(request.startsWith('.') || request.startsWith('/'))) {
                            return request;
                        }
                        if (request.startsWith('/')) {
                            return __custardNormalizePath(request);
                        }
                        var baseDir = __custardDirname(fromPath || __custardEntryPath || '');
                        var combined = baseDir ? (baseDir + '/' + request) : request;
                        return __custardNormalizePath(combined);
                    }

                    function __custardBuildCandidatePaths(modulePath) {
                        var normalized = __custardNormalizePath(modulePath);
                        if (!normalized) {
                            return [];
                        }
                        var candidates = [normalized];
                        var hasExt = /\.[a-z0-9]+$/i.test(normalized);
                        if (!hasExt) {
                            candidates.push(normalized + '.js');
                            candidates.push(normalized + '.json');
                            candidates.push(normalized + '/index.js');
                            candidates.push(normalized + '/index.json');
                        }
                        return candidates;
                    }

                    function __custardReadToolPkgModule(modulePath) {
                        if (!__custardPackageTarget) {
                            console.error('[compose_dsl] empty package target for module: ' + modulePath);
                            return null;
                        }
                        if (
                            typeof NativeInterface === 'undefined' ||
                            !NativeInterface ||
                            typeof NativeInterface.readToolPkgTextResource !== 'function'
                        ) {
                            console.error('[compose_dsl] NativeInterface.readToolPkgTextResource unavailable');
                            return null;
                        }
                        var candidates = __custardBuildCandidatePaths(modulePath);
                        for (var i = 0; i < candidates.length; i++) {
                            var candidatePath = candidates[i];
                            var moduleText = NativeInterface.readToolPkgTextResource(
                                __custardPackageTarget,
                                candidatePath
                            );
                            if (typeof moduleText === 'string' && moduleText.length > 0) {
                                console.log(
                                    '[compose_dsl] module loaded: ' +
                                        candidatePath +
                                        ', bytes=' +
                                        moduleText.length
                                );
                                return {
                                    path: candidatePath,
                                    text: moduleText
                                };
                            }
                        }
                        console.warn(
                            '[compose_dsl] module not found in toolpkg: ' +
                                modulePath +
                                ', candidates=' +
                                JSON.stringify(candidates)
                        );
                        return null;
                    }

                    function __custardExecuteModule(modulePath, moduleText, requireInternal) {
                        if (__custardModuleCache[modulePath]) {
                            return __custardModuleCache[modulePath].exports;
                        }
                        var localModule = { exports: {} };
                        __custardModuleCache[modulePath] = localModule;
                        try {
                            if (/\.json$/i.test(modulePath)) {
                                localModule.exports = JSON.parse(moduleText);
                                return localModule.exports;
                            }
                            var localRequire = function(nextName) {
                                return requireInternal(nextName, modulePath);
                            };
                            var compiled = __custardBuildScriptFactory(moduleText, modulePath);
                            compiled(localModule, localModule.exports, localRequire);
                            return localModule.exports;
                        } catch (e) {
                            console.error(
                                '[compose_dsl] execute module failed: ' +
                                    modulePath +
                                    ', error=' +
                                    (e && e.message ? e.message : String(e))
                            );
                            delete __custardModuleCache[modulePath];
                            throw e;
                        }
                    }

                    const requireInternal = function(moduleName, fromPath) {
                        if (moduleName === 'lodash') return _;
                        if (moduleName === 'uuid') {
                            return {
                                v4: function() {
                                    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
                                        var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
                                        return v.toString(16);
                                    });
                                }
                            };
                        }
                        if (moduleName === 'axios') {
                            return {
                                get: (url, config) => {
                                    const params = config ? Object.assign({}, { url }, config) : { url };
                                    return toolCall("http_request", params);
                                },
                                post: (url, data, config) => {
                                    const params = config ? Object.assign({}, { url, data }, config) : { url, data };
                                    return toolCall("http_request", params);
                                }
                            };
                        }

                        var request = String(moduleName || '').trim();
                        if (request.startsWith('.') || request.startsWith('/')) {
                            var resolvedModulePath = __custardResolveModulePath(request, fromPath);
                            var loadedModule = __custardReadToolPkgModule(resolvedModulePath);
                            if (loadedModule) {
                                return __custardExecuteModule(
                                    loadedModule.path,
                                    loadedModule.text,
                                    requireInternal
                                );
                            }
                            console.error(
                                'Failed to resolve module from toolpkg: ' +
                                    request +
                                    ' (from ' +
                                    (fromPath || __custardEntryPath || '<root>') +
                                    ')'
                            );
                            throw new Error(
                                'Cannot resolve module "' +
                                    request +
                                    '" from "' +
                                    (fromPath || __custardEntryPath || '<root>') +
                                    '"'
                            );
                        }

                        console.log('Attempted to require unsupported module: ' + request);
                        return {};
                    };

                    const require = function(moduleName) {
                        console.log(
                            '[compose_dsl] require request: ' +
                                String(moduleName || '') +
                                ', from=' +
                                (__custardEntryPath || '<root>')
                        );
                        return requireInternal(moduleName, __custardEntryPath);
                    };
                    
                    // 执行用户脚本，定义所有函数（通过Function包装，避免裸插入脚本导致解析期匿名错误）
                    const __custardScriptText = $scriptJson;
                    console.log(
                        '[compose_dsl] executing entry script, package=' +
                            (__custardPackageTarget || '<none>') +
                            ', entry=' +
                            (__custardEntryPath || '<none>') +
                            ', bytes=' +
                            __custardScriptText.length
                    );
                    const __custardScriptFactory = __custardBuildScriptFactory(
                        __custardScriptText,
                        __custardEntryPath || '<entry>'
                    );
                    __custardScriptFactory(module, exports, require);
                    
                    // 返回模块环境
                    return {
                        module: module,
                        exports: exports,
                        foundFunction: null
                    };
                })();
                
                // 从模块环境中获取结果
                const module = moduleResult.module;
                const moduleResultExports = moduleResult.exports;
                
                // 确保指定的函数存在 - 先查找exports，再查找module.exports，最后查找全局
                let functionResult = null;
                let functionFound = false;
                
                if (typeof moduleResultExports['${functionName}'] === 'function') {
                    // 如果函数是作为exports导出的
                    functionFound = true;
                    functionResult = moduleResultExports['${functionName}'](params);
                } else if (typeof module.exports['${functionName}'] === 'function') {
                    // 尝试替代的导出模式
                    functionFound = true;
                    functionResult = module.exports['${functionName}'](params);
                } else if (typeof window['${functionName}'] === 'function') {
                    // 全局函数
                    functionFound = true;
                    functionResult = window['${functionName}'](params);
                }
                
                if (functionFound) {
                    // 处理函数返回结果，特别是异步Promise
                    var handledAsync = false;
                    try {
                        if (typeof __handleAsync === 'function') {
                            handledAsync = __handleAsync(functionResult);
                        }
                    } catch (e) {
                        console.error("Error calling __handleAsync:", e);
                    }
                    if (!handledAsync) {
                        // 如果是同步结果且没有调用complete()，使用该结果
                        if (!window._hasCompleted) {
                            NativeInterface.setResult(JSON.stringify(functionResult));
                        }
                    }
                } else {
                    // 如果没有找到函数，记录所有可用的函数
                    var availableFunctions = [];
                    for (var key in moduleResultExports) {
                        if (typeof moduleResultExports[key] === 'function') {
                            availableFunctions.push(key);
                        }
                    }
                    for (var key in module.exports) {
                        if (typeof module.exports[key] === 'function' && !availableFunctions.includes(key)) {
                            availableFunctions.push(key);
                        }
                    }
                    
                    var errorMsg = "Function '${functionName}' not found in script. Available functions: " + 
                                  (availableFunctions.length > 0 ? availableFunctions.join(", ") : "none");
                    NativeInterface.setError(errorMsg);
                }
            } catch (error) {
                try {
                    console.error(
                        "[compose_dsl] script execution failed:",
                        error && error.stack ? error.stack : error
                    );
                } catch (logError) {
                }
                NativeInterface.setError("Script error: " + error.message);
            }
        """.trimIndent()

        // 在主线程中执行脚本
        ContextCompat.getMainExecutor(context).execute {
            webView?.evaluateJavascript(executionScript) { result ->
                AppLogger.d(TAG, "Script execution dispatched. Sync result: $result (final async result is handled separately)")
            }
        }

        // 等待结果或超时
        return try {
            // 创建一个定时器，在超时前提醒JavaScript
            val preTimeoutTimer = java.util.Timer()

            // 只在较长的脚本执行中使用超时预警
            preTimeoutTimer.schedule(
                    object : java.util.TimerTask() {
                        override fun run() {
                            try {
                                // 如果还没完成，尝试提前触发完成
                                if (!future.isDone) {
                                    AppLogger.d(TAG, "Pre-timeout warning triggered")
                                    ContextCompat.getMainExecutor(context).execute {
                                        webView?.evaluateJavascript(
                                                """
                                    if (typeof complete === 'function' && !window._hasCompleted) {
                                        console.log("Script execution approaching timeout");
                                        // 不强制完成，只记录警告
                                    }
                                """,
                                                null
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Error in pre-timeout handler: ${e.message}", e)
                            }
                        }
                    },
                    JsTimeoutConfig.PRE_TIMEOUT_SECONDS * 1000
            )

            try {
                val result = future.get(JsTimeoutConfig.MAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                preTimeoutTimer.cancel()
                result
            } catch (e: Exception) {
                preTimeoutTimer.cancel()
                throw e
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Script execution timed out or failed: ${e.message}", e)
            // 确保WebView的JavaScript不再继续执行
            ContextCompat.getMainExecutor(context).execute {
                webView?.evaluateJavascript(
                        "window._hasCompleted = true; clearTimeout(window._safetyTimeout);",
                        null
                )
            }
            "Error: ${e.message}"
        }
    }

    fun executeComposeDslScript(
            script: String,
            runtimeOptions: Map<String, Any?> = emptyMap(),
            envOverrides: Map<String, String> = emptyMap()
    ): Any? {
        return executeScriptFunction(
                script = buildComposeDslRuntimeWrappedScript(script),
                functionName = "__custard_render_compose_dsl",
                params = runtimeOptions,
                envOverrides = envOverrides
        )
    }

    fun executeComposeDslAction(
            actionId: String,
            payload: Any? = null,
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null
    ): Any? {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank()) {
            return "Error: compose action id is required"
        }
        val params = mutableMapOf<String, Any?>("__action_id" to normalizedActionId)
        if (payload != null) {
            params["__action_payload"] = payload
        }
        return executeScriptFunction(
                script = "",
                functionName = "__custard_dispatch_compose_dsl_action",
                params = params,
                envOverrides = envOverrides,
                onIntermediateResult = onIntermediateResult
        )
    }

    private fun toJsLiteral(value: Any?): String {
        if (value == null) {
            return "undefined"
        }
        return when (value) {
            is Number, is Boolean -> value.toString()
            is String -> JSONObject.quote(value)
            else -> {
                try {
                    val wrapped = JSONObject.wrap(value)
                    when (wrapped) {
                        null -> JSONObject.quote(value.toString())
                        JSONObject.NULL -> "null"
                        else -> wrapped.toString()
                    }
                } catch (e: Exception) {
                    JSONObject.quote(value.toString())
                }
            }
        }
    }

    fun dispatchComposeDslActionAsync(
            actionId: String,
            payload: Any? = null,
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null,
            onComplete: (() -> Unit)? = null,
            onError: ((String) -> Unit)? = null
    ): Boolean {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank()) {
            onError?.invoke("compose action id is required")
            onComplete?.invoke()
            return false
        }

        if (onIntermediateResult != null) {
            intermediateResultCallback = onIntermediateResult
        }
        this.envOverrides = envOverrides

        initWebView()
        if (!jsEnvironmentInitialized) {
            initJavaScriptEnvironment()
        }

        val actionToken = UUID.randomUUID().toString()
        if (onComplete != null) {
            composeDslActionCompleteCallbacks[actionToken] = onComplete
        }
        if (onError != null) {
            composeDslActionErrorCallbacks[actionToken] = onError
        }

        val actionIdJson = JSONObject.quote(normalizedActionId)
        val actionTokenJson = JSONObject.quote(actionToken)
        val hasPayload = payload != null
        val payloadLiteral = if (hasPayload) toJsLiteral(payload) else "undefined"
        val asyncDispatchScript =
                """
            (function() {
                var __actionToken = $actionTokenJson;
                try {
                    var __dispatchFn =
                        (typeof window !== 'undefined' && typeof window.__custard_dispatch_compose_dsl_action === 'function')
                            ? window.__custard_dispatch_compose_dsl_action
                            : (typeof __custard_dispatch_compose_dsl_action === 'function'
                                ? __custard_dispatch_compose_dsl_action
                                : null);
                    if (typeof __dispatchFn !== 'function') {
                        NativeInterface.reportComposeDslActionError(__actionToken, 'compose_dsl runtime dispatch function not found');
                        NativeInterface.reportComposeDslActionCompleted(__actionToken);
                        return;
                    }

                    var __request = { __action_id: $actionIdJson };
                    if ($hasPayload) {
                        __request.__action_payload = $payloadLiteral;
                    }

                    Promise.resolve(__dispatchFn(__request))
                        .then(function(__result) {
                            try {
                                NativeInterface.sendIntermediateResult(JSON.stringify(__result));
                            } catch (__ignored) {
                            }
                            NativeInterface.reportComposeDslActionCompleted(__actionToken);
                        })
                        .catch(function(__error) {
                            var __message = (__error && __error.message) ? __error.message : String(__error);
                            NativeInterface.reportComposeDslActionError(__actionToken, __message);
                            NativeInterface.reportComposeDslActionCompleted(__actionToken);
                        });
                } catch (__e) {
                    var __message = (__e && __e.message) ? __e.message : String(__e);
                    NativeInterface.reportComposeDslActionError(__actionToken, __message);
                    NativeInterface.reportComposeDslActionCompleted(__actionToken);
                }
            })();
        """.trimIndent()

        ContextCompat.getMainExecutor(context).execute {
            try {
                webView?.evaluateJavascript(asyncDispatchScript, null)
            } catch (e: Exception) {
                val errorText = "dispatch compose action failed: ${e.message}"
                AppLogger.e(TAG, errorText, e)
                composeDslActionErrorCallbacks.remove(actionToken)?.invoke(errorText)
                composeDslActionCompleteCallbacks.remove(actionToken)?.invoke()
            }
        }

        return true
    }

    fun cancelCurrentExecution(reason: String = "Execution canceled: requested by caller") {
        AppLogger.d(TAG, "Cancel current JS execution: $reason")
        resetState(cancellationMessage = reason)
        if (webView != null) {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    webView?.evaluateJavascript(
                            "window._hasCompleted = true; clearTimeout(window._safetyTimeout);",
                            null
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error stopping current JS execution: ${e.message}", e)
                }
            }
        }
    }

    /** 重置引擎状态，避免多次调用时的状态干扰 */
    private fun resetState(cancellationMessage: String = "Execution canceled: new execution started") {
        // 只有当之前的回调存在时才需要完成它
        val callback = resultCallback
        if (callback != null && !callback.isDone) {
            try {
                callback.complete(cancellationMessage)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error completing previous callback: ${e.message}", e)
            }
        }
        resultCallback = null
        intermediateResultCallback = null

        // 清理所有待处理的工具调用回调
        toolCallbacks.forEach { (_, future) ->
            if (!future.isDone) {
                future.complete("Operation canceled: engine reset")
            }
        }
        toolCallbacks.clear()
        composeDslActionCompleteCallbacks.clear()
        composeDslActionErrorCallbacks.clear()

        envOverrides = emptyMap()

        // 清理Bitmap注册表
        bitmapRegistry.values.forEach { it.recycle() }
        bitmapRegistry.clear()

        // 清理二进制数据注册表
        binaryDataRegistry.clear()

        // 如果WebView已经存在，执行轻量级清理
        if (webView != null) {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    // 使用更简单、更安全的清理代码
                    webView?.evaluateJavascript(
                            """
                        // 清理所有定时器
                        (function() {
                            var highestTimeoutId = setTimeout(";");
                            for (var i = 0 ; i < highestTimeoutId ; i++) {
                                clearTimeout(i);
                                clearInterval(i);
                            }
                        })();
                    """,
                            null
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error in WebView cleanup: ${e.message}", e)
                }
            }
        }
    }

    /** JavaScript 接口，提供 Native 调用方法 */
    @Keep
    inner class JsToolCallInterface {

        @JavascriptInterface
        fun decompress(data: String, algorithm: String): String {
            return try {
                if (algorithm.lowercase() != "deflate") {
                    throw IllegalArgumentException("Unsupported algorithm: $algorithm. Only 'deflate' is supported.")
                }

                val compressedData: ByteArray = if (data.startsWith(BINARY_HANDLE_PREFIX)) {
                    val handle = data.substring(BINARY_HANDLE_PREFIX.length)
                    binaryDataRegistry.remove(handle)
                        ?: throw Exception("Invalid or expired binary handle: $handle")
                } else {
                    // Assume Base64 encoding if no handle is present
                    Base64.decode(data, Base64.NO_WRAP)
                }

                if (compressedData.isEmpty()) {
                    return ""
                }

                val inflater = java.util.zip.Inflater(true) // 使用 nowrap=true 来处理没有 zlib头的原始 DEFLATE 数据
                inflater.setInput(compressedData)
                val outputStream = ByteArrayOutputStream()
                val buffer = ByteArray(1024)

                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && inflater.needsInput()) {
                        // This indicates an incomplete or corrupt stream.
                        throw java.util.zip.DataFormatException("Input is incomplete or corrupt")
                    }
                    outputStream.write(buffer, 0, count)
                }

                outputStream.close()
                inflater.end()
                
                outputStream.toByteArray().toString(Charsets.UTF_8)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Native decompress operation failed: ${e.message}", e)
                "{\"nativeError\":\"${e.message?.replace("\"", "'")}\"}"
            }
        }

        @JavascriptInterface
        fun getEnv(key: String): String? {
            return try {
                val name = key.trim()
                if (name.isEmpty()) {
                    ""
                } else {
                    val overridden = envOverrides[name]
                    if (!overridden.isNullOrEmpty()) {
                        overridden
                    } else {
                        EnvPreferences.getInstance(context).getEnv(name) ?: ""
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reading environment variable from JS: $key", e)
                ""
            }
        }

        @JavascriptInterface
        fun setEnv(key: String, value: String?) {
            JsNativeInterfaceDelegates.setEnv(context = context, key = key, value = value)
        }

        @JavascriptInterface
        fun setEnvs(valuesJson: String) {
            JsNativeInterfaceDelegates.setEnvs(context = context, valuesJson = valuesJson)
        }

        @JavascriptInterface
        fun isPackageImported(packageName: String): Boolean {
            return JsNativeInterfaceDelegates.isPackageImported(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun importPackage(packageName: String): String {
            return JsNativeInterfaceDelegates.importPackage(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun removePackage(packageName: String): String {
            return JsNativeInterfaceDelegates.removePackage(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun usePackage(packageName: String): String {
            return JsNativeInterfaceDelegates.usePackage(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun listImportedPackagesJson(): String {
            return JsNativeInterfaceDelegates.listImportedPackagesJson(
                    packageManager = packageManager
            )
        }

        @JavascriptInterface
        fun resolveToolName(
                packageName: String,
                subpackageId: String,
                toolName: String,
                preferImported: String
        ): String {
            return JsNativeInterfaceDelegates.resolveToolName(
                    packageManager = packageManager,
                    packageName = packageName,
                    subpackageId = subpackageId,
                    toolName = toolName,
                    preferImported = preferImported
            )
        }

        @JavascriptInterface
        fun readToolPkgResource(
                packageNameOrSubpackageId: String,
                resourceKey: String,
                outputFileName: String
        ): String {
            return JsNativeInterfaceDelegates.readToolPkgResource(
                    packageManager = packageManager,
                    packageNameOrSubpackageId = packageNameOrSubpackageId,
                    resourceKey = resourceKey,
                    outputFileName = outputFileName
            )
        }

        @JavascriptInterface
        fun readToolPkgTextResource(
                packageNameOrSubpackageId: String,
                resourcePath: String
        ): String {
            return JsNativeInterfaceDelegates.readToolPkgTextResource(
                    packageManager = packageManager,
                    packageNameOrSubpackageId = packageNameOrSubpackageId,
                    resourcePath = resourcePath
            )
        }

        @JavascriptInterface
        fun registerImageFromBase64(base64: String, mimeType: String): String {
            return try {
                val finalMime = if (mimeType.isNotBlank()) mimeType else "image/png"
                val id = ImagePoolManager.addImageFromBase64(base64, finalMime)
                if (id != "error") {
                    "<link type=\"image\" id=\"$id\"></link>"
                } else {
                    "[image registration failed]"
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "registerImageFromBase64 failed: ${e.message}", e)
                "[image registration failed: ${e.message}]"
            }
        }

        @JavascriptInterface
        fun registerImageFromPath(path: String): String {
            return try {
                val id = ImagePoolManager.addImage(path)
                if (id != "error") {
                    "<link type=\"image\" id=\"$id\"></link>"
                } else {
                    "[image registration failed]"
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "registerImageFromPath failed: ${e.message}", e)
                "[image registration failed: ${e.message}]"
            }
        }

        @JavascriptInterface
        fun image_processing(callbackId: String, operation: String, argsJson: String) {
            JsNativeInterfaceDelegates.imageProcessing(
                    callbackId = callbackId,
                    operation = operation,
                    argsJson = argsJson,
                    binaryDataRegistry = binaryDataRegistry,
                    bitmapRegistry = bitmapRegistry,
                    binaryHandlePrefix = BINARY_HANDLE_PREFIX
            ) { callback, result, isError ->
                sendToolResult(callback, result, isError)
            }
        }

        @JavascriptInterface
        fun crypto(algorithm: String, operation: String, argsJson: String): String {
            return JsNativeInterfaceDelegates.crypto(
                    algorithm = algorithm,
                    operation = operation,
                    argsJson = argsJson
            )
        }

        @JavascriptInterface
        fun sendIntermediateResult(result: String) {
            try {
                AppLogger.d(TAG, "Received intermediate result from JS: ${result.take(200)}")
                ContextCompat.getMainExecutor(context).execute {
                    intermediateResultCallback?.invoke(result)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error processing intermediate result: ${e.message}", e)
            }
        }

        @JavascriptInterface
        fun reportComposeDslActionError(actionToken: String, error: String) {
            try {
                AppLogger.e(
                        TAG,
                        "compose_dsl async action error: token=$actionToken, error=$error"
                )
                val callback = composeDslActionErrorCallbacks.remove(actionToken)
                if (callback != null) {
                    ContextCompat.getMainExecutor(context).execute {
                        callback.invoke(error)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reporting compose_dsl action error: ${e.message}", e)
            }
        }

        @JavascriptInterface
        fun reportComposeDslActionCompleted(actionToken: String) {
            try {
                val callback = composeDslActionCompleteCallbacks.remove(actionToken)
                composeDslActionErrorCallbacks.remove(actionToken)
                if (callback != null) {
                    ContextCompat.getMainExecutor(context).execute {
                        callback.invoke()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reporting compose_dsl action completion: ${e.message}", e)
            }
        }

        /** 同步工具调用（旧版本，保留兼容性） */
        @JavascriptInterface
        fun callTool(toolType: String, toolName: String, paramsJson: String): String {
            try {
                // 解析参数
                val params = mutableMapOf<String, String>()
                val jsonObject = JSONObject(paramsJson)
                jsonObject.keys().forEach { key ->
                    params[key] = jsonObject.opt(key)?.toString() ?: ""
                }

                // 调用工具
                AppLogger.d(TAG, "[Sync] JavaScript tool call: $toolType:$toolName with params: $params")

                // 参数验证
                if (toolName.isEmpty()) {
                    AppLogger.e(TAG, "Tool name cannot be empty")
                    return "Error: Tool name cannot be empty"
                }

                // 构建工具参数
                val toolParameters =
                        params.map { (name, value) -> ToolParameter(name = name, value = value) }

                // 构建完整工具名称 (如果有类型则使用类型:名称格式，否则直接使用名称)
                val fullToolName =
                        if (toolType.isNotEmpty() && toolType != "default") {
                            "$toolType:$toolName"
                        } else {
                            toolName
                        }

                // 创建工具调用对象
                val aiTool = AITool(name = fullToolName, parameters = toolParameters)

                AppLogger.d(TAG, "Executing tool (sync): $fullToolName")

                // 使用 AIToolHandler 执行工具
                val result = toolHandler.executeTool(aiTool)

                // 记录执行结果
                if (result.success) {
                    val resultString = result.result.toString()
                    AppLogger.d(
                            TAG,
                            "[Sync] Tool execution succeeded: ${resultString.take(1000)}${if (resultString.length > 1000) "..." else ""}"
                    )
                } else {
                    AppLogger.e(TAG, "[Sync] Tool execution failed: ${result.error}")
                }

                // 返回结果
                return if (result.success) {
                    // Convert tool result to JSON for proper handling of structured data
                    val resultJson =
                            Json.encodeToString(
                                    JsonElement.serializer(),
                                    buildJsonObject {
                                        put("success", JsonPrimitive(true))

                                        // Handle different result data types
                                        when (val resultData = result.result) {
                                            is StringResultData ->
                                                    put("data", JsonPrimitive(resultData.value))
                                            is BooleanResultData ->
                                                    put("data", JsonPrimitive(resultData.value))
                                            is IntResultData ->
                                                    put("data", JsonPrimitive(resultData.value))
                                            else -> {
                                                // 使用 toJson 方法获取 JSON 字符串
                                                val jsonString = resultData.toJson()
                                                // 确保获取的是有效的 JSON
                                                try {
                                                    put("data", Json.parseToJsonElement(jsonString))
                                                } catch (e: Exception) {
                                                    put("data", JsonPrimitive(jsonString))
                                                }
                                            }
                                        }
                                    }
                            )
                    resultJson
                } else {
                    // Return error as JSON
                    Json.encodeToString(
                            JsonElement.serializer(),
                            buildJsonObject {
                                put("success", JsonPrimitive(false))
                                put("error", JsonPrimitive(result.error ?: "Unknown error"))
                            }
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "[Sync] Error in tool call: ${e.message}", e)
                return Json.encodeToString(
                        JsonElement.serializer(),
                        buildJsonObject {
                            put("success", JsonPrimitive(false))
                            put("error", JsonPrimitive("Error: ${e.message}"))
                        }
                )
            }
        }

        /** 异步工具调用（新版本，使用Promise） */
        @JavascriptInterface
        fun callToolAsync(
                callbackId: String,
                toolType: String,
                toolName: String,
                paramsJson: String
        ) {
            try {
                // 解析参数
                val params = mutableMapOf<String, String>()
                val jsonObject = JSONObject(paramsJson)
                jsonObject.keys().forEach { key ->
                    params[key] = jsonObject.opt(key)?.toString() ?: ""
                }

                // 调用工具
                AppLogger.d(
                        TAG,
                        "[Async] JavaScript tool call: $toolType:$toolName with params: $params, callbackId: $callbackId"
                )

                // 参数验证
                if (toolName.isEmpty()) {
                    AppLogger.e(TAG, "Tool name cannot be empty")
                    val errorJson =
                            Json.encodeToString(
                                    JsonElement.serializer(),
                                    buildJsonObject {
                                        put("success", JsonPrimitive(false))
                                        put("error", JsonPrimitive("Tool name cannot be empty"))
                                    }
                            )
                    sendToolResult(callbackId, errorJson, true)
                    return
                }

                // 构建工具参数
                val toolParameters =
                        params.map { (name, value) -> ToolParameter(name = name, value = value) }

                // 构建完整工具名称 (如果有类型则使用类型:名称格式，否则直接使用名称)
                val fullToolName =
                        if (toolType.isNotEmpty() && toolType != "default") {
                            "$toolType:$toolName"
                        } else {
                            toolName
                        }

                // 创建工具调用对象
                val aiTool = AITool(name = fullToolName, parameters = toolParameters)

                AppLogger.d(TAG, "Executing tool (async): $fullToolName")

                // 在后台线程中执行工具调用
                Thread {
                            try {
                                // 使用 AIToolHandler 执行工具
                                val result = toolHandler.executeTool(aiTool)

                                // 记录执行结果
                                if (result.success) {
                                    val resultString = result.result.toString()
                                    AppLogger.d(
                                            TAG,
                                            "[Async] Tool execution succeeded: ${resultString.take(1000)}${if (resultString.length > 1000) "..." else ""}"
                                    )
                                    // 发送成功结果回调
                                    val resultJson =
                                            Json.encodeToString(
                                                    JsonElement.serializer(),
                                                    buildJsonObject {
                                                        put("success", JsonPrimitive(true))

                                                        // Handle different result data types
                                                        when (val resultData = result.result) {
                                                            is BinaryResultData -> {
                                                                if (resultData.value.size > BINARY_DATA_THRESHOLD) {
                                                                    val handle = UUID.randomUUID().toString()
                                                                    binaryDataRegistry[handle] = resultData.value
                                                                    AppLogger.d(TAG, "[Async] Stored large binary data with handle: $handle")
                                                                    put("data", JsonPrimitive("$BINARY_HANDLE_PREFIX$handle"))
                                                                } else {
                                                                    put("data", JsonPrimitive(Base64.encodeToString(resultData.value, Base64.NO_WRAP)))
                                                                }
                                                                put("dataType", JsonPrimitive("base64")) // Keep dataType for JS compatibility
                                                            }
                                                            is StringResultData ->
                                                                    put(
                                                                            "data",
                                                                            JsonPrimitive(
                                                                                    resultData.value
                                                                            )
                                                                    )
                                                            is BooleanResultData ->
                                                                    put(
                                                                            "data",
                                                                            JsonPrimitive(
                                                                                    resultData.value
                                                                            )
                                                                    )
                                                            is IntResultData ->
                                                                    put(
                                                                            "data",
                                                                            JsonPrimitive(
                                                                                    resultData.value
                                                                            )
                                                                    )
                                                            else -> {
                                                                // 使用 toJson 方法获取 JSON 字符串
                                                                val jsonString = resultData.toJson()
                                                                // 确保获取的是有效的 JSON
                                                                try {
                                                                    put(
                                                                            "data",
                                                                            Json.parseToJsonElement(
                                                                                    jsonString
                                                                            )
                                                                    )
                                                                } catch (e: Exception) {
                                                                    put(
                                                                            "data",
                                                                            JsonPrimitive(
                                                                                    jsonString
                                                                            )
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                            )
                                    sendToolResult(callbackId, resultJson, false)
                                } else {
                                    AppLogger.e(TAG, "[Async] Tool execution failed: ${result.error}")
                                    // 发送错误结果回调
                                    val errorJson =
                                            Json.encodeToString(
                                                    JsonElement.serializer(),
                                                    buildJsonObject {
                                                        put("success", JsonPrimitive(false))
                                                        put(
                                                                "error",
                                                                JsonPrimitive(
                                                                        result.error
                                                                                ?: "Unknown error"
                                                                )
                                                        )
                                                    }
                                            )
                                    sendToolResult(callbackId, errorJson, true)
                                }
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "[Async] Error in async tool execution: ${e.message}", e)
                                // 发送异常结果回调
                                val errorJson =
                                        Json.encodeToString(
                                                JsonElement.serializer(),
                                                buildJsonObject {
                                                    put("success", JsonPrimitive(false))
                                                    put(
                                                            "error",
                                                            JsonPrimitive("Error: ${e.message}")
                                                    )
                                                }
                                        )
                                sendToolResult(callbackId, errorJson, true)
                            }
                        }
                        .start()
            } catch (e: Exception) {
                AppLogger.e(TAG, "[Async] Error setting up async tool call: ${e.message}", e)
                val errorJson =
                        Json.encodeToString(
                                JsonElement.serializer(),
                                buildJsonObject {
                                    put("success", JsonPrimitive(false))
                                    put("error", JsonPrimitive("Error: ${e.message}"))
                                }
                        )
                sendToolResult(callbackId, errorJson, true)
            }
        }

        /** 向JavaScript发送工具调用结果 */
        private fun sendToolResult(callbackId: String, result: String, isError: Boolean) {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    // Check if the result is already a valid JSON literal (object, array, or quoted string).
                    val trimmedResult = result.trim()
                    val isJsonLiteral = (trimmedResult.startsWith("{") && trimmedResult.endsWith("}")) ||
                                        (trimmedResult.startsWith("[") && trimmedResult.endsWith("]")) ||
                                        (trimmedResult.startsWith("\"") && trimmedResult.endsWith("\""))

                    val jsCode =
                            if (isJsonLiteral) {
                                """
                            if (typeof window['$callbackId'] === 'function') {
                                window['$callbackId']($result, $isError);
                            } else {
                                console.error("Callback not found: $callbackId");
                            }
                        """.trimIndent()
                            } else {
                                // For plain strings or other primitives, escape and wrap in quotes for JS.
                                val escapedResult =
                                        result.replace("\\", "\\\\")
                                                .replace("\"", "\\\"")
                                                .replace("\n", "\\n")
                                                .replace("\r", "\\r")
                                """
                            if (typeof window['$callbackId'] === 'function') {
                                window['$callbackId']("$escapedResult", $isError);
                            } else {
                                console.error("Callback not found: $callbackId");
                            }
                        """.trimIndent()
                            }
                    webView?.evaluateJavascript(jsCode, null)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error sending tool result to JavaScript: ${e.message}", e)
                }
            }
        }

        @JavascriptInterface
        fun setResult(result: String) {
            try {
                val callback = resultCallback
                // 加入更详细的日志，帮助排查异步问题
                AppLogger.d(
                        TAG,
                        "Setting result from JavaScript: result=${result.take(500)}, length=${result.length}, callback=${callback != null}, isDone=${callback?.isDone}"
                )

                // 确保回调仍然有效
                if (callback == null) {
                    AppLogger.e(TAG, "Result callback is null when trying to complete")
                    return
                }

                if (callback.isDone) {
                    AppLogger.w(TAG, "Result callback is already completed when trying to set result")
                    return
                }

                // 使用主线程执行complete操作，避免可能的线程问题
                ContextCompat.getMainExecutor(context).execute {
                    try {
                        // 返回成功结果
                        if (!callback.isDone) {
                            AppLogger.d(TAG, "Actually completing the result callback")
                            callback.complete(result)
                        } else {
                            AppLogger.w(TAG, "Callback became complete between check and execution")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error completing result on main thread: ${e.message}", e)
                        if (!callback.isDone) {
                            callback.completeExceptionally(e)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting result: ${e.message}", e)
                resultCallback?.completeExceptionally(e)
            }
        }

        @JavascriptInterface
        fun setError(error: String) {
            try {
                val callback = resultCallback
                // 加入更详细的日志
                AppLogger.d(
                        TAG,
                        "Setting error from JavaScript: $error, callback=${callback != null}, isDone=${callback?.isDone}"
                )

                // 尝试解析错误信息，看是否是JSON格式
                var logMessage = error
                try {
                    if (error.startsWith("{") && error.endsWith("}")) {
                        val errorJson = JSONObject(error)
                        if (errorJson.has("formatted")) {
                            // 如果是我们格式化的错误对象，使用formatted字段作为日志
                            logMessage = errorJson.getString("formatted")
                        } else if (errorJson.has("error") && errorJson.has("message")) {
                            // 基本的错误对象
                            val errorType = errorJson.getString("error")
                            val errorMsg = errorJson.getString("message")
                            logMessage = "$errorType: $errorMsg"

                            // 添加更多详情如果有的话
                            if (errorJson.has("details")) {
                                val details = errorJson.getJSONObject("details")
                                if (details.has("fileName") && details.has("lineNumber")) {
                                    logMessage +=
                                            "\nAt ${details.getString("fileName")}:${details.getString("lineNumber")}"
                                }
                                if (details.has("stack")) {
                                    logMessage += "\nStack: ${details.getString("stack")}"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 不是有效的JSON或解析失败，使用原始错误字符串
                    AppLogger.d(TAG, "Error parsing error message as JSON: ${e.message}")
                }

                // 记录错误日志
                AppLogger.e(TAG, "JS ERROR: $logMessage")

                // 确保回调仍然有效
                if (callback == null) {
                    AppLogger.e(TAG, "Result callback is null when trying to complete with error")
                    return
                }

                if (callback.isDone) {
                    AppLogger.w(TAG, "Result callback is already completed when trying to set error")
                    return
                }

                // 使用主线程执行complete操作
                ContextCompat.getMainExecutor(context).execute {
                    // 返回错误结果
                    if (!callback.isDone) {
                        callback.complete("Error: $error")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting error result: ${e.message}", e)
                resultCallback?.completeExceptionally(e)
            }
        }

        @JavascriptInterface
        fun logInfo(message: String) {
            AppLogger.i(TAG, "JS: $message")
        }

        @JavascriptInterface
        fun logError(message: String) {
            AppLogger.e(TAG, "JS ERROR: $message")
        }

        @JavascriptInterface
        fun logDebug(message: String, data: String) {
            AppLogger.d(TAG, "JS DEBUG: $message | $data")
        }

        @JavascriptInterface
        fun reportError(
                errorType: String,
                errorMessage: String,
                errorLine: Int,
                errorStack: String
        ) {
            AppLogger.e(
                    TAG,
                    "DETAILED JS ERROR: \nType: $errorType\nMessage: $errorMessage\nLine: $errorLine\nStack: $errorStack"
            )
        }
    }

    /** 销毁引擎资源 */
    fun destroy() {
        try {
            // 确保任何挂起的回调被完成
            resultCallback?.complete("Engine destroyed")
            resultCallback = null
            intermediateResultCallback = null

            // 清理所有待处理的工具调用回调
            toolCallbacks.forEach { (_, future) ->
                if (!future.isDone) {
                    future.complete("Engine destroyed")
                }
            }
            toolCallbacks.clear()
            composeDslActionCompleteCallbacks.clear()
            composeDslActionErrorCallbacks.clear()

            // 清理Bitmap注册表
            bitmapRegistry.values.forEach { it.recycle() }
            bitmapRegistry.clear()

            // 清理二进制数据注册表
            binaryDataRegistry.clear()

            // 在主线程中销毁 WebView
            ContextCompat.getMainExecutor(context).execute {
                try {
                    webView?.apply {
                        removeJavascriptInterface("NativeInterface")
                        loadUrl("about:blank")
                        clearHistory()
                        clearCache(true)
                        destroy()
                    }
                    webView = null
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error destroying WebView: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error during JsEngine destruction: ${e.message}", e)
        }
    }

    /** 处理引擎异常 */
    private fun handleException(e: Exception): String {
        AppLogger.e(TAG, "JsEngine exception: ${e.message}", e)

        // 尝试重置当前状态
        try {
            resetState()
        } catch (resetEx: Exception) {
            AppLogger.e(TAG, "Failed to reset state after exception: ${resetEx.message}", resetEx)
        }

        return "Error: ${e.message}"
    }

    /** 诊断引擎状态 用于调试目的，记录当前状态信息 */
    fun diagnose() {
        try {
            AppLogger.d(TAG, "=== JsEngine Diagnostics ===")
            AppLogger.d(TAG, "WebView initialized: ${webView != null}")
            AppLogger.d(TAG, "Result callback: ${resultCallback?.isDone ?: "null"}")
            AppLogger.d(TAG, "Tool callbacks pending: ${toolCallbacks.size}")

            // 检查WebView状态
            if (webView != null) {
                ContextCompat.getMainExecutor(context).execute {
                    webView?.evaluateJavascript(
                            """
                        (function() {
                            var result = {
                                memory: (window.performance && window.performance.memory) 
                                    ? {
                                        totalJSHeapSize: window.performance.memory.totalJSHeapSize,
                                        usedJSHeapSize: window.performance.memory.usedJSHeapSize,
                                        jsHeapSizeLimit: window.performance.memory.jsHeapSizeLimit
                                      } 
                                    : "Not available",
                                timers: "Unable to count"
                            };
                            
                            // 尝试估计定时器数量
                            try {
                                var count = 0;
                                var id = setTimeout(function(){}, 0);
                                clearTimeout(id);
                                result.timers = id;
                            } catch(e) {}
                            
                            return JSON.stringify(result);
                        })();
                    """
                    ) { diagResult -> AppLogger.d(TAG, "WebView diagnostics: $diagResult") }
                }
            }

            AppLogger.d(TAG, "=========================")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error during diagnostics: ${e.message}", e)
        }
    }
}
