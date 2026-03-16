# adb-ui-mcp

通过 ADB 获取 Android 屏幕 UI 树，并解析 UI 树后模拟点击对应 view 的 MCP Server。

## 前置条件

- 本机已安装 [ADB](https://developer.android.com/tools/adb)，且 `adb` 在 PATH 中
- 已通过 USB 或网络连接 Android 设备（`adb devices` 可识别）
- Node.js >= 18

## 安装与运行

```bash
cd mcp-servers/adb-ui
npm install
npm run build
node dist/index.js
```

Server 使用 stdio 与 MCP 客户端通信，通常由 Custard 的 MCP Bridge 或 Cursor 等客户端启动，无需单独运行。

## 提供的工具

| 工具名 | 说明 |
|--------|------|
| `get_ui_tree` | 通过 `adb shell uiautomator dump` 获取当前界面 UI 层级，返回简化后的文本树。可选参数：`display_id`（多屏时指定 display id） |
| `click_view` | 根据 resourceId、text、contentDesc 或 className 在 UI 树中查找节点，并对其中心点执行 `adb shell input tap`。支持 `index`（多匹配时取第几个）、`partialMatch`（文本/描述是否模糊匹配）、`display_id` |
| `tap` | 在指定坐标执行 `adb shell input tap x y`。参数：`x`、`y`（必填），可选 `display_id` |

## 与 Android 工程集成

Custard 的 Android 构建会把本 MCP 打进 APK：构建 app 前请在本目录执行 `npm run build`，然后执行 `./gradlew :app:assembleDebug` 等。应用首次加载 MCP 配置时会自动将 adb-ui 加入 `mcp_config.json` 并复制资源到 `mcp_plugins/adb-ui`，用户只需在 MCP 配置页启用并部署即可直接调用。

## 在 Custard 中配置（手动时）

在 MCP 配置中新增一个 Server，命令指向本目录下编译后的入口（需在已部署 Bridge 的环境里执行，例如 PC 或 Termux）：

**方式一：本地路径（Bridge 运行环境可访问该路径时）**

- 命令：`node`
- 参数：`/path/to/Custard/mcp-servers/adb-ui/dist/index.js`（替换为实际绝对路径）

**方式二：通过 npm 运行（若已发布或 link）**

- 命令：`node`
- 参数：`./mcp-servers/adb-ui/dist/index.js`（在 Bridge 工作目录下时的相对路径）

配置示例（`mcp.json` 或 Custard 插件配置中的 `mcpServers`）：

```json
{
  "mcpServers": {
    "adb-ui": {
      "command": "node",
      "args": ["/path/to/Custard/mcp-servers/adb-ui/dist/index.js"]
    }
  }
}
```

将 `/path/to/Custard` 替换为仓库在本机或 Bridge 运行环境中的实际路径。

## 实现说明

- UI 树来源：`uiautomator dump /sdcard/window_dump.xml`，再 `cat` 读取 XML。
- 解析：对 dump 出的 XML 做正则提取 `<node ...>` 的 `class`、`text`、`content-desc`、`resource-id`、`bounds` 等属性；点击时根据这些属性匹配节点，从 `bounds` 解析出中心点后执行 `input tap`。
- 与 Custard 内置的 DebuggerUITools/RootUITools 区别：本 MCP 运行在**连接 ADB 的主机**（通常是 PC），通过 ADB 控制设备；内置工具则在**设备本机**通过 Shell（Shizuku/ADB/Root）执行，无需主机端 ADB。

## 许可证

与 Custard 项目一致，采用 LGPL-3.0。
