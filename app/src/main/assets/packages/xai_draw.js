/* METADATA
{
  "name": "xai_draw",

  "display_name": {
      "zh": "xAI 绘图",
      "en": "xAI Draw"
  },
  "description": {
    "zh": "使用 xAI 图像生成 API (grok-2-image-1212) 根据提示词画图，将图片保存到本地 /sdcard/Download/Custard/draws/ 目录，并返回 Markdown 图片提示。",
    "en": "Generate images with the xAI image generation API (grok-2-image-1212) from a prompt, save to /sdcard/Download/Custard/draws/, and return a Markdown image reference."
  },
  "env": [
    "XAI_API_KEY"
  ],
  "tools": [
    {
      "name": "draw_image",
      "description": {
        "zh": "根据提示词调用 xAI 图像生成 API 生成图片，保存到本地并返回 Markdown 图片提示。",
        "en": "Generate an image via the xAI image generation API using a prompt, save it locally, and return a Markdown image reference."
      },
      "parameters": [
        { "name": "prompt", "description": { "zh": "绘图提示词（英文或中文皆可）", "en": "Prompt for image generation (Chinese or English)" }, "type": "string", "required": true },
        { "name": "model", "description": { "zh": "xAI 图像模型名称，默认 grok-2-image-1212", "en": "xAI image model name (default: grok-2-image-1212)" }, "type": "string", "required": false },
        { "name": "size", "description": { "zh": "图片尺寸，例如 '1024x1024'，可选", "en": "Image size, e.g. '1024x1024' (optional)" }, "type": "string", "required": false },
        { "name": "file_name", "description": { "zh": "自定义保存到本地的文件名（不含路径和扩展名）", "en": "Custom output file name (without path or extension)" }, "type": "string", "required": false }
      ]
    }
  ]
}
*/
const xaiDraw = (function () {
    const HTTP_TIMEOUT_MS = 600000;
    const client = OkHttp.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS)
        .readTimeout(HTTP_TIMEOUT_MS)
        .writeTimeout(HTTP_TIMEOUT_MS)
        .build();
    const API_ENDPOINT = "https://api.x.ai/v1/images/generations";
    const DEFAULT_MODEL = "grok-2-image-1212";
    // Android 实际路径为 /sdcard/Download，对应系统中文名“下载”
    const DOWNLOAD_ROOT = "/sdcard/Download";
    const CUSTARD_DIR = `${DOWNLOAD_ROOT}/Custard`;
    const DRAWS_DIR = `${CUSTARD_DIR}/draws`;
    function getApiKey() {
        const apiKey = getEnv("XAI_API_KEY");
        if (!apiKey) {
            throw new Error("XAI_API_KEY 未配置，请在环境变量中设置 x.ai 的 API Key。");
        }
        return apiKey;
    }
    function sanitizeFileName(name) {
        const safe = name.replace(/[\\/:*?"<>|]/g, "_").trim();
        if (!safe) {
            return `xai_draw_${Date.now()}`;
        }
        return safe.substring(0, 80);
    }
    function buildFileName(prompt, customName) {
        if (customName && customName.trim().length > 0) {
            return sanitizeFileName(customName);
        }
        const shortPrompt = prompt.length > 40 ? `${prompt.substring(0, 40)}...` : prompt;
        const base = sanitizeFileName(shortPrompt || "image");
        const timestamp = Date.now();
        return `${base}_${timestamp}`;
    }
    async function ensureDirectories() {
        const dirs = [DOWNLOAD_ROOT, CUSTARD_DIR, DRAWS_DIR];
        for (const dir of dirs) {
            try {
                const result = await Tools.Files.mkdir(dir);
                if (!result.successful) {
                    console.warn(`创建目录失败(可能已存在): ${dir} -> ${result.details}`);
                }
            }
            catch (e) {
                console.warn(`创建目录异常: ${dir} -> ${(e === null || e === void 0 ? void 0 : e.message) || e}`);
            }
        }
    }
    async function callXaiImageApi(params) {
        const apiKey = getApiKey();
        const model = (params.model && params.model.trim().length > 0) ? params.model.trim() : DEFAULT_MODEL;
        const body = {
            model,
            prompt: params.prompt
        };
        if (params.size && params.size.trim().length > 0) {
            body.size = params.size.trim();
        }
        const headers = {
            "accept": "application/json",
            "content-type": "application/json",
            "Authorization": `Bearer ${apiKey}`
        };
        const request = client
            .newRequest()
            .url(API_ENDPOINT)
            .method("POST")
            .headers(headers)
            .body(JSON.stringify(body), "json");
        const response = await request.build().execute();
        if (!response.isSuccessful()) {
            throw new Error(`xAI 图片 API 调用失败: ${response.statusCode} - ${response.content}`);
        }
        let parsed;
        try {
            parsed = JSON.parse(response.content);
        }
        catch (e) {
            throw new Error(`解析 xAI 响应失败: ${(e === null || e === void 0 ? void 0 : e.message) || e}`);
        }
        if (!parsed || !parsed.data || !parsed.data[0] || !parsed.data[0].url) {
            throw new Error("xAI 响应中未找到图片 URL，请检查模型和参数是否正确。");
        }
        return String(parsed.data[0].url);
    }
    function guessExtensionFromUrl(url) {
        const match = url.match(/\.(png|jpg|jpeg|webp|gif)(?:\?|#|$)/i);
        if (match && match[1]) {
            return match[1].toLowerCase();
        }
        return "png";
    }
    async function draw_image(params) {
        if (!params || !params.prompt || params.prompt.trim().length === 0) {
            throw new Error("参数 prompt 不能为空。");
        }
        const prompt = params.prompt.trim();
        await ensureDirectories();
        const imageUrl = await callXaiImageApi({
            prompt,
            model: params.model,
            size: params.size
        });
        const ext = guessExtensionFromUrl(imageUrl);
        const baseName = buildFileName(prompt, params.file_name);
        const filePath = `${DRAWS_DIR}/${baseName}.${ext}`;
        const downloadResult = await Tools.Files.download(imageUrl, filePath);
        if (!downloadResult.successful) {
            throw new Error(`下载图片失败: ${downloadResult.details}`);
        }
        const fileUri = `file://${filePath}`;
        const markdown = `![AI生成的图片](${fileUri})`;
        const hintLines = [];
        hintLines.push("图片已生成并保存在本地 /sdcard/Download/Custard/draws/ 目录。");
        hintLines.push(`本地路径: ${filePath}`);
        hintLines.push("");
        hintLines.push("在后续回答中，请直接输出下面这一行 Markdown 来展示这张图片：");
        hintLines.push("");
        hintLines.push(markdown);
        return {
            file_path: filePath,
            file_uri: fileUri,
            markdown,
            prompt,
            model: params.model || DEFAULT_MODEL,
            hint: hintLines.join("\n")
        };
    }
    async function draw_image_wrapper(params) {
        try {
            const result = await draw_image(params);
            complete({
                success: true,
                message: "图片生成成功，已保存到 /sdcard/Download/Custard/draws/，并返回 Markdown 图片提示。",
                data: result
            });
        }
        catch (error) {
            console.error("draw_image 执行失败:", error);
            complete({
                success: false,
                message: `图片生成失败: ${(error === null || error === void 0 ? void 0 : error.message) || error}`,
                error_stack: error === null || error === void 0 ? void 0 : error.stack
            });
        }
    }
    return {
        draw_image: draw_image_wrapper
    };
})();
exports.draw_image = xaiDraw.draw_image;
