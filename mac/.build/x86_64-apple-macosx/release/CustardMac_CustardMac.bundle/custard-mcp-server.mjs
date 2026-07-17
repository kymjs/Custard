#!/usr/bin/env node
/**
 * 奶黄包（Custard）MCP Server — exposes tools via local HTTP API.
 *
 * Prerequisites:
 *   - 奶黄包（CustardMac）running with phone connected (Control/连接 page open)
 *   - Tool server on http://127.0.0.1:27184
 *
 * Cursor MCP config (~/.cursor/mcp.json):
 * {
 *   "mcpServers": {
 *     "custard": {
 *       "command": "node",
 *       "args": ["/path/to/custard/tools/custard-mcp-server.mjs"]
 *     }
 *   }
 * }
 */

import readline from "node:readline";

const HOST = process.env.CUSTARD_TOOL_HOST || "127.0.0.1";
const PORT = process.env.CUSTARD_TOOL_PORT || "27184";
const BASE = `http://${HOST}:${PORT}`;
const AGENT_TOKEN = process.env.CUSTARD_AGENT_TOKEN || "";

function mcpHeaders(extra = {}) {
  const headers = { "X-Custard-Tool-Source": "mcp", ...extra };
  if (AGENT_TOKEN) {
    headers["X-Custard-Agent-Token"] = AGENT_TOKEN;
  }
  return headers;
}

const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: false });

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n");
}

async function fetchJSON(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { ...mcpHeaders(options.headers || {}), ...(options.headers || {}) },
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${text}`);
  }
  return JSON.parse(text);
}

async function fetchScreen(includeScreenshot) {
  const url = `/screen${includeScreenshot ? "?screenshot=1" : ""}`;
  return fetchJSON(url);
}

async function listInstalledApps() {
  return fetchJSON("/tool/list_installed_apps");
}

async function openApp(packageOrName) {
  return fetchJSON("/tool/open_app", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ package_or_name: packageOrName }),
  });
}

async function tapScreen(x, y, action = "tap") {
  return fetchJSON("/tool/tap_screen", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ x, y, action }),
  });
}

async function readClipboard() {
  return fetchJSON("/tool/read_clipboard");
}

async function writeClipboard(text) {
  return fetchJSON("/tool/write_clipboard", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
}

async function typeText(text) {
  return fetchJSON("/tool/type_text", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
}

async function pressHome() {
  return fetchJSON("/tool/press_home", { method: "POST" });
}

async function pressBack() {
  return fetchJSON("/tool/press_back", { method: "POST" });
}

const TOOLS = [
  {
    name: "get_screen",
    description:
      "获取当前 Android 手机屏幕：分辨率、前台 Activity、UI 元素摘要，可选截图。需奶黄包（CustardMac）已连接手机。",
    inputSchema: {
      type: "object",
      properties: {
        include_screenshot: {
          type: "boolean",
          description: "是否附带原始尺寸 PNG 截图 base64",
        },
      },
    },
  },
  {
    name: "list_installed_apps",
    description:
      "获取当前手机已安装的第三方应用列表，包含应用名称与包名。需要 ADB 连接。",
    inputSchema: {
      type: "object",
      properties: {},
    },
  },
  {
    name: "open_app",
    description:
      "打开指定 Android 应用。会先获取已安装应用列表并匹配包名或应用名称，匹配成功后启动应用。",
    inputSchema: {
      type: "object",
      properties: {
        package_or_name: {
          type: "string",
          description: "应用包名（如 com.tencent.mm）或应用名称（如 微信）",
        },
      },
      required: ["package_or_name"],
    },
  },
  {
    name: "tap_screen",
    description:
      "点击手机屏幕指定位置。必须使用屏幕实际像素坐标，左上角 (0,0)。支持单击、双击、长按。",
    inputSchema: {
      type: "object",
      properties: {
        x: {
          type: "number",
          description: "横向像素坐标，0 为屏幕最左",
        },
        y: {
          type: "number",
          description: "纵向像素坐标，0 为屏幕最上",
        },
        action: {
          type: "string",
          enum: ["tap", "double_tap", "long_press"],
          description: "触摸动作：tap=单击，double_tap=双击，long_press=长按。默认 tap",
        },
      },
      required: ["x", "y"],
    },
  },
  {
    name: "read_clipboard",
    description: "读取 Android 手机当前剪贴板文本。需奶黄包（CustardMac）屏幕共享已连接。",
    inputSchema: {
      type: "object",
      properties: {},
    },
  },
  {
    name: "write_clipboard",
    description: "向 Android 手机剪贴板写入文本。需奶黄包（CustardMac）屏幕共享已连接。",
    inputSchema: {
      type: "object",
      properties: {
        text: {
          type: "string",
          description: "要写入手机剪贴板的文本内容",
        },
      },
      required: ["text"],
    },
  },
  {
    name: "type_text",
    description:
      "模拟键盘向手机当前获得焦点的输入框输入文本，支持中文。需奶黄包（CustardMac）屏幕共享已连接；输入前请先点击目标输入框。",
    inputSchema: {
      type: "object",
      properties: {
        text: {
          type: "string",
          description: "要输入到手机当前焦点控件的文本内容",
        },
      },
      required: ["text"],
    },
  },
  {
    name: "press_home",
    description: "按下 Android 手机的 Home 键，返回系统桌面。",
    inputSchema: {
      type: "object",
      properties: {},
    },
  },
  {
    name: "press_back",
    description: "按下 Android 手机的 Back 键，返回上一页或关闭当前界面。",
    inputSchema: {
      type: "object",
      properties: {},
    },
  },
];

rl.on("line", async (line) => {
  let req;
  try {
    req = JSON.parse(line);
  } catch {
    return;
  }

  const { id, method, params } = req;

  try {
    if (method === "initialize") {
      send({
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion: "2024-11-05",
          capabilities: { tools: {} },
          serverInfo: { name: "custard", version: "1.0.0" },
        },
      });
      return;
    }

    if (method === "tools/list") {
      send({
        jsonrpc: "2.0",
        id,
        result: { tools: TOOLS },
      });
      return;
    }

    if (method === "tools/call") {
      const name = params?.name;
      const args = params?.arguments || {};
      let data;

      switch (name) {
        case "get_screen":
          data = await fetchScreen(Boolean(args.include_screenshot));
          const content = [];
          if (data.screenshot_base64) {
            content.push({
              type: "image",
              data: data.screenshot_base64,
              mimeType: "image/png",
            });
            const { screenshot_base64: _screenshot, ...meta } = data;
            content.push({
              type: "text",
              text: JSON.stringify({ ...meta, has_screenshot: true }, null, 2),
            });
          } else {
            content.push({
              type: "text",
              text: JSON.stringify(data, null, 2),
            });
          }
          send({
            jsonrpc: "2.0",
            id,
            result: { content },
          });
          return;
        case "list_installed_apps":
          data = await listInstalledApps();
          break;
        case "open_app":
          if (!args.package_or_name) {
            throw new Error("Missing required argument: package_or_name");
          }
          data = await openApp(String(args.package_or_name));
          break;
        case "tap_screen":
          if (args.x == null || args.y == null) {
            throw new Error("Missing required arguments: x and y (pixel coordinates)");
          }
          data = await tapScreen(
            Number(args.x),
            Number(args.y),
            args.action ? String(args.action) : "tap"
          );
          break;
        case "read_clipboard":
          data = await readClipboard();
          break;
        case "write_clipboard":
          if (args.text == null) {
            throw new Error("Missing required argument: text");
          }
          data = await writeClipboard(String(args.text));
          break;
        case "type_text":
          if (args.text == null) {
            throw new Error("Missing required argument: text");
          }
          data = await typeText(String(args.text));
          break;
        case "press_home":
          data = await pressHome();
          break;
        case "press_back":
          data = await pressBack();
          break;
        default:
          throw new Error(`Unknown tool: ${name}`);
      }

      send({
        jsonrpc: "2.0",
        id,
        result: {
          content: [
            {
              type: "text",
              text: JSON.stringify(data, null, 2),
            },
          ],
        },
      });
      return;
    }

    if (method === "notifications/initialized") {
      return;
    }

    send({ jsonrpc: "2.0", id, error: { code: -32601, message: `Method not found: ${method}` } });
  } catch (err) {
    send({
      jsonrpc: "2.0",
      id,
      error: { code: -32000, message: String(err.message || err) },
    });
  }
});
