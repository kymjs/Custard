# 奶黄包 Skill 话术示例

用户原话 → 推荐命令（先 `status` 确认 `phone_connected: true`）。本套件对外品牌名为「奶黄包」。

## 读屏 / 截图（优先 `android-phone-screen` 奶黄包读屏）

| 用户说法 | 命令 |
|----------|------|
| 看看手机现在什么界面 | `get_screen` |
| 截一张当前 App 的图 | `get_screen --screenshot`，再用 Read 读 `screenshot_path` |
| 读一下手机剪贴板 | `read_clipboard` |
| 手机连着吗 / 奶黄包连着吗 | `status` |

## 操控（`custard-phone-control` 奶黄包操控）

| 用户说法 | 命令 |
|----------|------|
| 打开微信 | `open_app 微信` |
| 点一下屏幕中间 | `tap 50 50` |
| 在手机上输入「奶黄包」 | 先 `tap` 聚焦输入框，再 `type_text "奶黄包"` |
| 把手机剪贴板写成 xxx | `write_clipboard "xxx"` |
| 返回上一页 | `press_back` |
| 回桌面 | `press_home` |

## 典型调试流程

```bash
CUSTARD_TOOL="${CUSTARD_SKILL_DIR:-$HOME/.cursor/skills/custard-phone-control}/scripts/custard-tool"

bash "$CUSTARD_TOOL" status
bash "$CUSTARD_TOOL" get_screen --screenshot
bash "$CUSTARD_TOOL" open_app 设置
bash "$CUSTARD_TOOL" get_screen
bash "$CUSTARD_TOOL" tap 50 30
bash "$CUSTARD_TOOL" type_text "test"
```

## 路由提示

- 仅需**看界面 / 截图 / 读剪贴板** → `android-phone-screen`（奶黄包读屏）
- 需要**点击 / 输入 / 开应用** → `custard-phone-control`（奶黄包操控）
- 已配置 Cursor MCP `custard` 时，读屏可用 MCP `get_screen`，操控仍可用本 Skill 的 `custard-tool`
