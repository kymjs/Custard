# 奶黄包 Agent API 参考

本套件对外品牌名为「奶黄包」（技术目录：`custard-phone-control`）。

- Skill 仓库：https://github.com/kymjs/Custard-Skill
- Base URL：`http://127.0.0.1:27184`（**仅 loopback**）

## 命令 ↔ HTTP

| custard-tool | HTTP | Body |
|--------------|------|------|
| `get_screen` | `GET /screen` | — |
| `get_screen --screenshot` | `GET /screen` + `GET /screen.png` | stdout JSON 含 `screenshot_path`（默认不含 base64） |
| `get_screen --screenshot --inline` | 同上 | JSON 额外含 `screenshot_base64` |
| `list_apps` | `GET /tool/list_installed_apps` | — |
| `open_app <名>` | `POST /tool/open_app` | `{"package_or_name":"..."}` |
| `tap X Y [action]` | `POST /tool/tap_screen` | `{"x":X,"y":Y,"action":"tap"}`（像素坐标） |
| `read_clipboard` | `GET /tool/read_clipboard` | — |
| `write_clipboard <t>` | `POST /tool/write_clipboard` | `{"text":"..."}` |
| `type_text <t>` | `POST /tool/type_text` | `{"text":"..."}` |
| `press_home` | `POST /tool/press_home` | `{}` |
| `press_back` | `POST /tool/press_back` | `{}` |
| `status` | `GET /agent/status` | — |

Headers：

- `X-Custard-Tool-Source: agent`（Skill 默认，映射 MCP 开关）
- `X-Custard-Agent-Token: <token>`（API 开启时必填）

## 工具开关（tool_disabled）

Skill（`source=agent`）→ 检查 **MCP** 类：`mcp.get_screen`、`mcp.tap_screen` 等。

`custard-screen` CLI → `cli.get_screen`。

`GET /agent/status` 的 `tools` 数组包含全部 CLI/MCP 项及 `enabled` 状态。

## 错误码

| HTTP | error | 说明 |
|------|-------|------|
| 401 | unauthorized | Token 错误 |
| 403 | permission_denied | Agent 端口权限 |
| 403 | tool_disabled | 工具页关闭 |
| 429 | rate_limited | 超过 120 次/分钟 |
| 503 | — | 手机未连接 |

## 截图说明

`get_screen --screenshot` 流程：

1. `GET /screen` — UI 文本与元数据（无 base64）
2. `GET /screen.png` — PNG 二进制，脚本写入 `cache/latest-screen.png`
3. stdout 输出合并 JSON，含 `screenshot_path` 与 `has_screenshot: true`

**外部 Agent 应使用 Read 工具读取 `screenshot_path` 图片**（Cursor、Claude Code 等支持视觉的 Agent 均可）。`--inline` 可选地在 JSON 附带 base64，一般不必使用。

HTTP 亦可直接：`GET /screen?format=png` 或 `GET /screen.png` 返回原始尺寸 PNG。

## 限制

- 仅本机；Cloud Agent 不可用
- `list_apps` 需 ADB
- `type_text` 中文依赖奶黄包 IME 或 ADB/剪贴板回退
- 日志：`~/Library/Logs/CustardMac/agent-api.log`
