---
name: android-phone-screen
description: >-
  奶黄包读屏 Skill：See and read a connected Android phone screen via 奶黄包（CustardMac）localhost API: UI tree, screenshot, clipboard read, connection status.
  Use when the user wants to view the phone screen, capture app screenshots, check what app is showing, read phone clipboard,
  or mentions 奶黄包/Custard/手机界面/Android截图/读屏/看手机/真机截图 — read-only, no tap or type.
  For tap/type/open_app use custard-phone-control（奶黄包操控）instead. Requires local Agent (Cloud Agent cannot access localhost).
---

# 奶黄包手机读屏

本 Skill 对外品牌名为「奶黄包」读屏（技术 ID：`android-phone-screen`），属于「奶黄包」Skill 套件。

通过 `custard-phone-control`（奶黄包操控）共享的 `custard-tool` 只读访问 Android 真机（奶黄包 `127.0.0.1:27184`）。

## 何时使用

- 用户提及「奶黄包」且只需看当前界面、截 App 图、读剪贴板、检查连接
- **不需要**点击、输入、开应用 → 用本 Skill
- 需要操控 → 改用 `custard-phone-control`（奶黄包操控）

## 命令入口

```bash
CONTROL_DIR="${CUSTARD_SKILL_DIR:-$HOME/.cursor/skills/custard-phone-control}"
CUSTARD_TOOL="$CONTROL_DIR/scripts/custard-tool"
bash "$CUSTARD_TOOL" <command>
```

## 可用命令

| 命令 | 说明 |
|------|------|
| `status` | 连接与工具开关 |
| `get_screen` | UI 摘要 |
| `get_screen --screenshot` | UI + 截图路径（用 Read 读图） |
| `read_clipboard` | 读手机剪贴板 |

详见上级目录 [examples.md](../examples.md) 与 [reference.md](../reference.md)。
