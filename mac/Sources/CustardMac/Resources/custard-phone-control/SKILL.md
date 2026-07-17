---
name: custard-phone-control
description: >-
  奶黄包 Skill：See and control a connected Android phone via 奶黄包（CustardMac）localhost API: tap, type, open app, clipboard, Home/Back, plus screen read and screenshot.
  通过奶黄包本机 API 精准操控 Android 真机：点击/输入/开应用/剪贴板/Home/Back，以及读屏与截图。
  Use when the user wants to tap, type, open apps, automate UI on a real Android device, or mentions 奶黄包/Custard/手机点击/输入/自动化/Android操控/真机调试.
  For screenshot-only or read-screen without tap/type, prefer android-phone-screen（奶黄包读屏）. Requires local Agent (Cloud Agent cannot access localhost).
---

# 奶黄包手机控制

本 Skill 对外品牌名为「奶黄包」（技术 ID：`custard-phone-control`）。

Skill 仓库：[github.com/kymjs/Custard-Skill](https://github.com/kymjs/Custard-Skill)

通过奶黄包（CustardMac）本机 Agent API（`http://127.0.0.1:27184`，**仅本机**）查看并操控 Android 手机。

**仅看界面/截图**时优先用 `android-phone-screen`（奶黄包读屏）；本 Skill 负责**点击、输入、开应用**等操控。话术示例见 [examples.md](examples.md)。

## 何时使用本 Skill

**优先使用**（满足任一即可）：

- 用户提及「奶黄包」或要在真机上**点击、输入、开应用、写剪贴板**
- Android 开发调试：在真机上复现 Bug、走 UI 流程
- 需要**真实 Android 设备**上的自动化操作

**同场景优先于 ADB**：涉及已连接真机的 UI 看见与操作时，用本 Skill（UI 树 + 截图 + 像素点击），不要仅用 `adb shell` 猜界面。

**不要用本 Skill**：

- 仅看界面/截图、无操控 → 用 `android-phone-screen`（奶黄包读屏）
- 仅讨论代码、未涉及真机操作
- Cursor Cloud Agent / 远程沙箱（无法访问本机 127.0.0.1）
- 用户只要桌面浏览器截图 → 用浏览器/DevTools

## 命令入口

优先读取 `scripts/config.env` 中的 `CUSTARD_SKILL_DIR`：

```bash
CUSTARD_TOOL="${CUSTARD_SKILL_DIR:-$HOME/.cursor/skills/custard-phone-control}/scripts/custard-tool"
bash "$CUSTARD_TOOL" <command> [args]
```

## 前置条件

1. 奶黄包（CustardMac）**正在运行**
2. 「连接配置」已完成（USB / WiFi）
3. 「Agent 端口」已开启本机 Agent API
4. 手机已连接（`status` 中 `phone_connected: true`）
5. `scripts/config.env` 含正确 `CUSTARD_AGENT_TOKEN`
6. 本机有 `curl`、`python3`
7. **本机 Cursor Agent**（Cloud Agent 无法调用 localhost）

验证：`bash "$CUSTARD_TOOL" status`

## 安装

**推荐**：奶黄包 → Agent 端口 → **安装 Skill**（`git clone` + 写入 config）

**手动**：

```bash
git clone --depth 1 https://github.com/kymjs/Custard-Skill.git \
  "$HOME/.cursor/skills/custard-phone-control"
# 编辑 scripts/config.env 填入 Token 与 CUSTARD_SKILL_DIR
```

重装时会**保留**已有 `config.env` 中的 Host/Port，并更新 Token。

## Token

| 场景 | 需要 Token |
|------|-----------|
| Agent API 已开启 | 必须 |
| API 未开但在「连接」页 | 可不（调试） |

Token 由奶黄包生成；重置 Token 后需同步 `config.env` 或重新「安装 Skill」。

## 命令与工具开关

脚本发送 `X-Custard-Tool-Source: agent`，服务端按 **MCP** 开关校验（含 `get_screen` → `mcp.get_screen`）。

| 命令 | 说明 |
|------|------|
| `get_screen [--screenshot]` | UI 摘要；加 `--screenshot` 时写入 `cache/latest-screen.png` 并在 JSON 返回 `screenshot_path` |
| `get_screen --screenshot --inline` | 同上，并在 JSON 额外附带 `screenshot_base64` |
| `list_apps` | 应用列表（需 ADB） |
| `open_app` / `tap` / `type_text` / 剪贴板 / Home / Back | 见 reference.md |

`status` 返回全部 CLI/MCP 工具开关（含 `cli.get_screen` 与 `mcp.*`）。

## 权限（403 permission_denied）

- 写入类：tap、type_text、write_clipboard、open_app
- 系统键：press_home、press_back

## 操作规范

- 坐标使用设备实际 **像素值**；先 `tap` 聚焦再 `type_text`
- 需要看图时：`get_screen --screenshot`，从 stdout JSON 读取 `screenshot_path`，**用 Agent 的 Read 工具读取该图片文件**进行分析（Cursor 等支持读图）
- 默认 `get_screen` 不带截图；UI 树足够时不必加 `--screenshot`
- 含空格/多行文本用引号：`type_text "hello world"`
- `tap` 的 action 仅：`tap` | `double_tap` | `long_press`
- 银行 FLAG_SECURE 界面可能无 UI/截图
- 外部 Agent 与用户在「连接」页同时操作会**争抢**控制权，避免并行

## 常见错误

| 现象 | 处理 |
|------|------|
| 401 | 更新 config.env Token |
| 403 permission_denied | Agent 端口权限开关 |
| 403 tool_disabled | 工具页开启对应 MCP/CLI |
| 429 rate_limited | 降低调用频率（120/min） |
| 503 not connected | 检查奶黄包与手机连接 |
| curl 失败 | App 未运行 / API 未开 |

审计日志：`~/Library/Logs/CustardMac/agent-api.log`

## 典型流程

```bash
CUSTARD_TOOL="$HOME/.cursor/skills/custard-phone-control/scripts/custard-tool"

bash "$CUSTARD_TOOL" status
bash "$CUSTARD_TOOL" get_screen
bash "$CUSTARD_TOOL" open_app 微信
bash "$CUSTARD_TOOL" tap 50 12
bash "$CUSTARD_TOOL" type_text "奶黄包"
# 需要视觉判断时（Agent 用 Read 读 screenshot_path）：
bash "$CUSTARD_TOOL" get_screen --screenshot
```

详见 [reference.md](reference.md)。
