/* METADATA
{
    "name": "web",

    "display_name": {
        "zh": "Web 自动化操作",
        "en": "Web Automation"
    },
    "description": {
        "zh": "能够基于浏览器完成复杂的网页操作。",
        "en": "Enables complex web operations based on a real browser session."
    },
    "enabledByDefault": true,
    "tools": [
        {
            "name": "start",
            "description": { "zh": "启动网页会话并打开悬浮浏览窗口。", "en": "Start a web session and open a floating browser window." },
            "parameters": [
                { "name": "url", "description": { "zh": "可选，初始 URL", "en": "Optional initial URL." }, "type": "string", "required": false },
                { "name": "headers", "description": { "zh": "可选，请求头对象", "en": "Optional request headers object." }, "type": "object", "required": false },
                { "name": "user_agent", "description": { "zh": "可选，自定义 User-Agent", "en": "Optional custom User-Agent." }, "type": "string", "required": false },
                { "name": "session_name", "description": { "zh": "可选，会话名称", "en": "Optional session name." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "goto",
            "description": { "zh": "让会话跳转到指定 URL。", "en": "Navigate session to target URL." },
            "parameters": [
                { "name": "url", "description": { "zh": "目标 URL", "en": "Target URL." }, "type": "string", "required": true },
                { "name": "session_id", "description": { "zh": "可选，不传则使用 Kotlin 侧当前活动会话", "en": "Optional. Uses active Kotlin-side session when omitted." }, "type": "string", "required": false },
                { "name": "headers", "description": { "zh": "可选，请求头对象", "en": "Optional request headers object." }, "type": "object", "required": false }
            ]
        },
        {
            "name": "click",
            "description": { "zh": "按快照 ref 点击元素。", "en": "Click element by snapshot ref." },
            "parameters": [
                { "name": "session_id", "description": { "zh": "可选，不传则使用 Kotlin 侧当前活动会话。", "en": "Optional. Uses active Kotlin-side session when omitted." }, "type": "string", "required": false },
                { "name": "ref", "description": { "zh": "必填，snapshot 中的元素引用（例如 e12）。", "en": "Required element ref from snapshot (for example e12)." }, "type": "string", "required": true },
                { "name": "element", "description": { "zh": "可选，人类可读元素描述，仅用于语义提示。", "en": "Optional human-readable element description." }, "type": "string", "required": false },
                { "name": "button", "description": { "zh": "可选，left/right/middle", "en": "Optional mouse button: left/right/middle." }, "type": "string", "required": false },
                { "name": "modifiers", "description": { "zh": "可选，修饰键数组（仅 Alt/Control/ControlOrMeta/Meta/Shift）。", "en": "Optional modifier keys array (only Alt/Control/ControlOrMeta/Meta/Shift)." }, "type": "array", "required": false },
                { "name": "doubleClick", "description": { "zh": "可选，是否双击", "en": "Optional double click." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "fill",
            "description": { "zh": "按 CSS 选择器填写输入框。", "en": "Fill input by CSS selector." },
            "parameters": [
                { "name": "selector", "description": { "zh": "CSS 选择器", "en": "CSS selector." }, "type": "string", "required": true },
                { "name": "value", "description": { "zh": "写入值", "en": "Value to set." }, "type": "string", "required": true },
                { "name": "session_id", "description": { "zh": "可选，不传则使用 Kotlin 侧当前活动会话", "en": "Optional. Uses active Kotlin-side session when omitted." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "evaluate",
            "description": { "zh": "在网页中执行 JavaScript。", "en": "Evaluate JavaScript in current page." },
            "parameters": [
                { "name": "script", "description": { "zh": "JavaScript 脚本", "en": "JavaScript source." }, "type": "string", "required": true },
                { "name": "session_id", "description": { "zh": "可选，不传则使用 Kotlin 侧当前活动会话", "en": "Optional. Uses active Kotlin-side session when omitted." }, "type": "string", "required": false },
                { "name": "timeout_ms", "description": { "zh": "可选，执行超时", "en": "Optional execution timeout." }, "type": "number", "required": false }
            ]
        },
        {
            "name": "wait_for",
            "description": { "zh": "等待页面就绪或元素出现。", "en": "Wait for page ready or selector appearance." },
            "parameters": [
                { "name": "selector", "description": { "zh": "可选，CSS 选择器", "en": "Optional CSS selector." }, "type": "string", "required": false },
                { "name": "session_id", "description": { "zh": "可选，不传则使用 Kotlin 侧当前活动会话", "en": "Optional. Uses active Kotlin-side session when omitted." }, "type": "string", "required": false },
                { "name": "timeout_ms", "description": { "zh": "可选，等待超时", "en": "Optional wait timeout." }, "type": "number", "required": false }
            ]
        },
        {
            "name": "snapshot",
            "description": { "zh": "抓取当前网页文本快照。", "en": "Capture current page text snapshot." },
            "parameters": [
                { "name": "session_id", "description": { "zh": "可选，不传则使用 Kotlin 侧当前活动会话", "en": "Optional. Uses active Kotlin-side session when omitted." }, "type": "string", "required": false },
                { "name": "include_links", "description": { "zh": "可选，是否包含链接", "en": "Optional include links." }, "type": "boolean", "required": false },
                { "name": "include_images", "description": { "zh": "可选，是否包含图片", "en": "Optional include images." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "content",
            "description": { "zh": "获取页面主要内容；当内容过长时自动写入文件并返回路径。", "en": "Get main page content; auto-save to file and return path when too long." },
            "parameters": [
                { "name": "session_id", "description": { "zh": "可选，不传则使用 Kotlin 侧当前活动会话", "en": "Optional. Uses active Kotlin-side session when omitted." }, "type": "string", "required": false },
                { "name": "include_links", "description": { "zh": "可选，是否包含链接", "en": "Optional include links." }, "type": "boolean", "required": false },
                { "name": "include_images", "description": { "zh": "可选，是否包含图片", "en": "Optional include images." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "open_in_system_browser",
            "description": { "zh": "在系统浏览器中打开当前会话 URL（或指定 URL）。", "en": "Open current session URL (or a specified URL) in system browser." },
            "parameters": [
                { "name": "url", "description": { "zh": "可选，目标 URL。不传则自动读取当前会话 URL。", "en": "Optional target URL. If omitted, uses current session URL." }, "type": "string", "required": false },
                { "name": "session_id", "description": { "zh": "可选，读取当前会话 URL 时指定会话，不传则使用 Kotlin 侧当前活动会话。", "en": "Optional session when resolving current URL; uses active Kotlin-side session if omitted." }, "type": "string", "required": false },
                { "name": "package_name", "description": { "zh": "可选，指定浏览器包名。", "en": "Optional target browser package name." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "upload",
            "description": { "zh": "向网页文件选择器上传文件。paths 不传时取消当前 file chooser。", "en": "Upload files to an active web file chooser. If paths is omitted, cancels the current file chooser." },
            "parameters": [
                { "name": "session_id", "description": { "zh": "可选，不传则使用 Kotlin 侧当前活动会话", "en": "Optional. Uses active Kotlin-side session when omitted." }, "type": "string", "required": false },
                { "name": "paths", "description": { "zh": "可选，绝对路径数组。示例：['/sdcard/Download/a.txt']", "en": "Optional absolute file path array. Example: ['/sdcard/Download/a.txt']" }, "type": "array", "required": false }
            ]
        },
        {
            "name": "close",
            "description": { "zh": "关闭网页会话。", "en": "Close web session." },
            "parameters": [
                { "name": "session_id", "description": { "zh": "可选，不传则关闭 Kotlin 侧当前活动会话", "en": "Optional. Closes active Kotlin-side session when omitted." }, "type": "string", "required": false },
                { "name": "close_all", "description": { "zh": "可选，是否关闭全部会话", "en": "Optional, close all sessions." }, "type": "boolean", "required": false }
            ]
        }
    ]
}
*/
const MAX_INLINE_WEB_CONTENT_CHARS = 24000;
const Web = (function () {
    function toPayload(raw) {
        if (raw == null) {
            return {};
        }
        if (typeof raw === 'string') {
            try {
                return JSON.parse(raw);
            }
            catch (_a) {
                return { value: raw };
            }
        }
        if (typeof raw === 'object' && typeof raw.value === 'string') {
            try {
                return JSON.parse(raw.value);
            }
            catch (_b) {
                return Object.assign(Object.assign({}, raw), { value: raw.value });
            }
        }
        if (typeof raw === 'object') {
            return raw;
        }
        return { value: String(raw) };
    }
    function normalizeHeaders(headers) {
        if (!headers || typeof headers !== 'object') {
            return undefined;
        }
        const result = {};
        for (const [key, value] of Object.entries(headers)) {
            if (value === undefined || value === null) {
                continue;
            }
            result[String(key)] = String(value);
        }
        return result;
    }
    function optionalSessionId(raw) {
        if (raw === undefined || raw === null) {
            return undefined;
        }
        const sid = String(raw).trim();
        return sid.length > 0 ? sid : undefined;
    }
    function extractUrlFromPayload(payload) {
        const candidates = [payload === null || payload === void 0 ? void 0 : payload.url, payload === null || payload === void 0 ? void 0 : payload.result, payload === null || payload === void 0 ? void 0 : payload.value];
        for (const item of candidates) {
            if (typeof item !== 'string') {
                continue;
            }
            const trimmed = item.trim();
            if (trimmed.length > 0) {
                return trimmed;
            }
        }
        return undefined;
    }
    async function resolveUrlForSystemBrowser(sessionId, explicitUrl) {
        const providedUrl = explicitUrl !== undefined && explicitUrl !== null
            ? String(explicitUrl).trim()
            : '';
        if (providedUrl.length > 0) {
            return providedUrl;
        }
        const evalPayload = toPayload(await Tools.Net.webEval(sessionId, '(function(){ try { return window.location.href || document.URL || ""; } catch (e) { return ""; } })();', 3000));
        const detectedUrl = extractUrlFromPayload(evalPayload);
        if (!detectedUrl) {
            throw new Error('url 参数缺失，且无法从当前会话获取 URL');
        }
        return detectedUrl;
    }
    function extractPageContent(payload) {
        const candidates = [payload === null || payload === void 0 ? void 0 : payload.snapshot, payload === null || payload === void 0 ? void 0 : payload.content, payload === null || payload === void 0 ? void 0 : payload.text, payload === null || payload === void 0 ? void 0 : payload.value];
        for (const item of candidates) {
            if (typeof item === 'string' && item.length > 0) {
                return item;
            }
        }
        return '';
    }
    function sanitizeSessionId(sessionId) {
        if (!sessionId) {
            return 'default';
        }
        return sessionId.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 40) || 'default';
    }
    async function persistPageContentIfTooLong(payload, sessionId) {
        const content = extractPageContent(payload);
        if (!content || content.length <= MAX_INLINE_WEB_CONTENT_CHARS) {
            return payload;
        }
        await Tools.Files.mkdir(OPERIT_CLEAN_ON_EXIT_DIR, true);
        const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
        const rand = Math.floor(Math.random() * 1000000);
        const safeSessionId = sanitizeSessionId(sessionId);
        const filePath = `${OPERIT_CLEAN_ON_EXIT_DIR}/web_content_${safeSessionId}_${timestamp}_${rand}.txt`;
        await Tools.Files.write(filePath, content, false);
        return Object.assign(Object.assign({}, payload), { snapshot: '(saved_to_file)', snapshot_chars: content.length, snapshot_saved_to: filePath, operit_clean_on_exit_dir: OPERIT_CLEAN_ON_EXIT_DIR, hint: 'Content is large and saved to file. Use read_file_part or grep_code to inspect it.' });
    }
    async function start(params = {}) {
        return toPayload(await Tools.Net.startWeb({
            url: params.url,
            headers: normalizeHeaders(params.headers),
            user_agent: params.user_agent,
            session_name: params.session_name,
        }));
    }
    async function goto(params) {
        if (!params || !params.url) {
            throw new Error('url 参数必填');
        }
        return toPayload(await Tools.Net.webNavigate(optionalSessionId(params.session_id), String(params.url), normalizeHeaders(params.headers)));
    }
    async function click(params) {
        const ref = params && params.ref !== undefined && params.ref !== null
            ? String(params.ref).trim()
            : '';
        if (!ref) {
            throw new Error('ref 参数必填');
        }
        const normalizedButtonRaw = params && params.button !== undefined && params.button !== null
            ? String(params.button).trim()
            : '';
        if (normalizedButtonRaw &&
            normalizedButtonRaw !== 'left' &&
            normalizedButtonRaw !== 'right' &&
            normalizedButtonRaw !== 'middle') {
            throw new Error('button 只能是 left/right/middle');
        }
        const button = normalizedButtonRaw === 'left' ||
            normalizedButtonRaw === 'right' ||
            normalizedButtonRaw === 'middle'
            ? normalizedButtonRaw
            : undefined;
        let modifiers = undefined;
        if (params && params.modifiers !== undefined) {
            if (!Array.isArray(params.modifiers)) {
                throw new Error('modifiers 必须是数组');
            }
            const normalized = params.modifiers.map((item) => String(item).trim());
            const invalid = normalized.filter((item) => item !== 'Alt' &&
                item !== 'Control' &&
                item !== 'ControlOrMeta' &&
                item !== 'Meta' &&
                item !== 'Shift');
            if (invalid.length > 0) {
                throw new Error(`modifiers 存在非法值: ${invalid.join(', ')}`);
            }
            modifiers = normalized;
        }
        return toPayload(await Tools.Net.webClick({
            session_id: optionalSessionId(params === null || params === void 0 ? void 0 : params.session_id),
            ref,
            element: params && params.element !== undefined && params.element !== null
                ? String(params.element)
                : undefined,
            button,
            modifiers: modifiers && modifiers.length > 0 ? modifiers : undefined,
            doubleClick: params && params.doubleClick !== undefined
                ? Boolean(params.doubleClick)
                : undefined,
        }));
    }
    async function fill(params) {
        if (!params || !params.selector) {
            throw new Error('selector 参数必填');
        }
        if (params.value === undefined || params.value === null) {
            throw new Error('value 参数必填');
        }
        return toPayload(await Tools.Net.webFill(optionalSessionId(params.session_id), String(params.selector), String(params.value)));
    }
    async function evaluate(params) {
        if (!params || !params.script) {
            throw new Error('script 参数必填');
        }
        return toPayload(await Tools.Net.webEval(optionalSessionId(params.session_id), String(params.script), params.timeout_ms !== undefined ? Number(params.timeout_ms) : undefined));
    }
    async function wait_for(params = {}) {
        return toPayload(await Tools.Net.webWaitFor(optionalSessionId(params.session_id), params.selector !== undefined ? String(params.selector) : undefined, params.timeout_ms !== undefined ? Number(params.timeout_ms) : undefined));
    }
    async function snapshot(params = {}) {
        const sessionId = optionalSessionId(params.session_id);
        const payload = toPayload(await Tools.Net.webSnapshot(sessionId, {
            include_links: params.include_links !== undefined ? Boolean(params.include_links) : undefined,
            include_images: params.include_images !== undefined ? Boolean(params.include_images) : undefined,
        }));
        return persistPageContentIfTooLong(payload, sessionId);
    }
    async function content(params = {}) {
        return snapshot(params);
    }
    async function open_in_system_browser(params = {}) {
        const sessionId = optionalSessionId(params.session_id);
        const targetUrl = await resolveUrlForSystemBrowser(sessionId, params.url);
        const intentResult = await Tools.System.intent({
            action: 'android.intent.action.VIEW',
            uri: targetUrl,
            package: params.package_name ? String(params.package_name) : undefined,
            type: 'activity',
        });
        return {
            status: 'ok',
            url: targetUrl,
            session_id: sessionId,
            intent_result: intentResult,
        };
    }
    async function upload(params = {}) {
        const sessionId = optionalSessionId(params.session_id);
        let paths = undefined;
        if (params.paths !== undefined) {
            let parsed = params.paths;
            if (typeof parsed === "string") {
                try {
                    parsed = JSON.parse(parsed);
                }
                catch (_a) {
                    throw new Error("paths 必须是合法 JSON 数组字符串");
                }
            }
            if (!Array.isArray(parsed)) {
                throw new Error("paths 参数必须是数组");
            }
            paths = parsed.map((p) => String(p));
        }
        return toPayload(await Tools.Net.webFileUpload(sessionId, paths));
    }
    async function close(params = {}) {
        const closeAll = Boolean(params.close_all);
        if (closeAll) {
            return toPayload(await Tools.Net.stopWeb({ close_all: true }));
        }
        const sid = optionalSessionId(params.session_id);
        if (sid) {
            return toPayload(await Tools.Net.stopWeb({ session_id: sid, close_all: false }));
        }
        return toPayload(await Tools.Net.stopWeb({ close_all: false }));
    }
    async function wrap(toolName, fn, params) {
        try {
            const data = await fn(params || {});
            const result = {
                success: true,
                message: `${toolName} 执行成功`,
                data,
            };
            complete(result);
        }
        catch (error) {
            const result = {
                success: false,
                message: `${toolName} 执行失败: ${(error === null || error === void 0 ? void 0 : error.message) || String(error)}`,
                error: String((error === null || error === void 0 ? void 0 : error.stack) || error),
            };
            complete(result);
        }
    }
    async function main() {
        complete({
            success: true,
            message: 'Web 已就绪，可调用 start/goto/click/fill/evaluate/wait_for/snapshot/content/open_in_system_browser/upload/close',
        });
    }
    return {
        start: (params) => wrap('start', start, params),
        goto: (params) => wrap('goto', goto, params),
        click: (params) => wrap('click', click, params),
        fill: (params) => wrap('fill', fill, params),
        evaluate: (params) => wrap('evaluate', evaluate, params),
        wait_for: (params) => wrap('wait_for', wait_for, params),
        snapshot: (params) => wrap('snapshot', snapshot, params),
        content: (params) => wrap('content', content, params),
        open_in_system_browser: (params) => wrap('open_in_system_browser', open_in_system_browser, params),
        upload: (params) => wrap('upload', upload, params),
        close: (params) => wrap('close', close, params),
        main,
    };
})();
exports.start = Web.start;
exports.goto = Web.goto;
exports.click = Web.click;
exports.fill = Web.fill;
exports.evaluate = Web.evaluate;
exports.wait_for = Web.wait_for;
exports.snapshot = Web.snapshot;
exports.content = Web.content;
exports.open_in_system_browser = Web.open_in_system_browser;
exports.upload = Web.upload;
exports.close = Web.close;
exports.main = Web.main;
