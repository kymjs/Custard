# 工作区功能插件化改造调研（草案）

## 1. 目标与范围

目标：把当前聊天里的“工作区能力”从原生固定 UI，改成可由 ToolPkg（`compose_dsl`）承载的插件能力。  
前提：`examples/windows_control` 作为第一个插件样例，保持不改。

本草案只回答两件事：

1. 需要开放哪些接口给插件。
2. 大概要改哪些文件。

---

## 2. 当前实现现状（代码锚点）

### 2.1 入口与页面承载

- 顶栏代码按钮触发：`app/src/main/java/com/ai/assistance/operit/ui/features/chat/screens/AIChatScreen.kt`
  - 调用 `actualViewModel.onWorkspaceButtonClick()`
  - 渲染 `WorkspaceScreen(...)`
- 工作区页面分流：`app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/workspace/WorkspaceScreen.kt`
  - 已绑定工作区 -> `WorkspaceManager`
  - 未绑定工作区 -> `WorkspaceSetup`

### 2.2 工作区绑定与服务控制

- 主要入口：`app/src/main/java/com/ai/assistance/operit/ui/features/chat/viewmodel/ChatViewModel.kt`
  - `bindChatToWorkspace(...)`
  - `unbindChatFromWorkspace(...)`
  - `updateWebServerForCurrentChat(...)`
  - `executeCommandInWorkspace(...)`
  - `onWorkspaceButtonClick()`
- 持久化中间层：`app/src/main/java/com/ai/assistance/operit/services/core/ChatHistoryDelegate.kt`
  - `bindChatToWorkspace(...)`
  - `unbindChatFromWorkspace(...)`
- 存储层：
  - `app/src/main/java/com/ai/assistance/operit/data/model/ChatEntity.kt`
  - `app/src/main/java/com/ai/assistance/operit/data/model/ChatHistory.kt`
  - `app/src/main/java/com/ai/assistance/operit/data/dao/ChatDao.kt`
  - `app/src/main/java/com/ai/assistance/operit/data/repository/ChatHistoryManager.kt`

### 2.3 工作区初始化与模板

- 初始化 UI：`app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/workspace/WorkspaceSetup.kt`
  - 创建模板目录
  - 选本地目录
  - SAF repo 书签（`repo:*` 环境）
- 模板与目录工具：`app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/WorkspaceUtils.kt`

### 2.4 compose_dsl 已有桥接能力

- `ctx` 当前能力定义：`app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsComposeDslBridge.kt`
  - 已有：`useState/useMemo/callTool/getEnv/setEnv/readResource`、包管理、`resolveToolName`
  - 没有：工作区绑定、模板创建、repo 书签、工作区预览服务控制
- Native 接口落点：
  - `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsEngine.kt`
  - `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsNativeInterfaceDelegates.kt`

---

## 3. 插件化后的缺口

要把 Workspace UI 迁到插件，需要补齐四类能力：

1. 聊天-工作区绑定能力（读当前绑定、绑定、解绑）。
2. 工作区创建能力（按模板创建并绑定）。
3. Repo 书签能力（列出/新增/删除/绑定 SAF 书签）。
4. 工作区预览与命令能力（启动 server、刷新预览、执行命令）。

现在这些能力都在原生 Compose 页面与 ViewModel 内部，插件不可直接调用。

---

## 4. 建议新增接口（给 `compose_dsl ctx`）

下面是建议的最小接口集，优先保证工作区配置向导可完整迁移。

### 4.1 聊天/绑定

- `ctx.getCurrentChatId(): Promise<string | null>`
- `ctx.getChatWorkspace(chatId?: string): Promise<{ path: string | null; environment: string | null; bound: boolean }>`
- `ctx.bindChatWorkspace(input: { chatId?: string; path: string; environment?: string | null }): Promise<{ ok: boolean; error?: string }>`
- `ctx.unbindChatWorkspace(chatId?: string): Promise<{ ok: boolean; error?: string }>`

### 4.2 模板创建

- `ctx.listWorkspaceTemplates(): Promise<Array<{ id: string; title: string; description: string }>>`
- `ctx.createWorkspaceFromTemplate(input: { chatId?: string; templateId: string }): Promise<{ ok: boolean; workspacePath?: string; error?: string }>`

`templateId` 可先对齐现有：`blank/web/android/node/typescript/python/java/go/office`。

### 4.3 Repo 书签（SAF）

- `ctx.listRepoBookmarks(): Promise<Array<{ name: string; uri: string }>>`
- `ctx.addRepoBookmark(input: { uri: string; name: string }): Promise<{ ok: boolean; error?: string }>`
- `ctx.removeRepoBookmark(input: { uri: string }): Promise<{ ok: boolean; error?: string }>`
- `ctx.bindRepoWorkspace(input: { chatId?: string; name: string }): Promise<{ ok: boolean; error?: string }>`

绑定规则沿用现状：`path="/"` + `environment="repo:<name>"`。

### 4.4 预览与命令

- `ctx.startWorkspaceServer(chatId?: string): Promise<{ ok: boolean; previewUrl?: string; error?: string }>`
- `ctx.stopWorkspaceServer(): Promise<{ ok: boolean; error?: string }>`
- `ctx.refreshWorkspacePreview(): Promise<{ ok: boolean }>`
- `ctx.getWorkspacePreviewUrl(chatId?: string): Promise<string>`
- `ctx.executeWorkspaceCommand(input: { chatId?: string; command: string; usesDedicatedSession?: boolean; sessionTitle?: string }): Promise<{ ok: boolean; error?: string }>`

### 4.5 可选但很实用（选目录）

- `ctx.pickDocumentTree(): Promise<{ uri: string } | null>`

说明：这类系统选择器属于异步交互，需要 native 侧提供回调链路；不能靠纯同步 `@JavascriptInterface` 假装等待。

---

## 5. 需要改动的文件（按层）

### 5.1 JS Bridge 层（必改）

- `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsComposeDslBridge.kt`
  - 在 `ctx` 注入上述 workspace 方法。
- `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsEngine.kt`
  - 增加对应 `@JavascriptInterface` 方法。
- `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsNativeInterfaceDelegates.kt`
  - 新增 workspace 相关 delegate 实现。

建议新增一个分离文件（可选）：

- `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsWorkspaceInterfaceDelegates.kt`
  - 把 workspace 逻辑从通用 delegate 中拆分，避免继续膨胀。

### 5.2 工作区域服务层（高概率改）

- `app/src/main/java/com/ai/assistance/operit/ui/features/chat/viewmodel/ChatViewModel.kt`
  - 补充可供 bridge 直接调用的方法（获取当前 chat、绑定/解绑、server 控制、命令执行）。
- `app/src/main/java/com/ai/assistance/operit/services/core/ChatHistoryDelegate.kt`
  - 复用现有 bind/unbind；如需批量接口可补方法。
- `app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/WorkspaceUtils.kt`
  - 暴露模板列表和创建入口，供 bridge 调用。
- `app/src/main/java/com/ai/assistance/operit/data/preferences/ApiPreferences.kt`
  - 复用 saf bookmark；如需按 `name` 删除或查找，可加便捷方法。
- `app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/LocalWebServer.kt`
  - 可加 `getPreviewBaseUrl()` 这类便捷方法（减少硬编码 `8093`）。

### 5.3 UI 承载层（迁移阶段改）

- `app/src/main/java/com/ai/assistance/operit/ui/features/chat/screens/AIChatScreen.kt`
  - 将当前 `WorkspaceScreen` 入口改为“工作区插件容器”入口。
- `app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/workspace/WorkspaceScreen.kt`
  - 最终可被插件容器替换或仅保留过渡壳层。
- `app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/workspace/WorkspaceSetup.kt`
  - 目标是迁出为插件 UI（完成迁移后可清理）。
- `app/src/main/java/com/ai/assistance/operit/ui/features/chat/webview/workspace/WorkspaceManager.kt`
  - 若要“整个工作区界面插件化”，此文件最终也进入清理范围。
- `app/src/main/java/com/ai/assistance/operit/ui/features/toolbox/screens/ToolPkgComposeDslScreen.kt`
  - 可抽出通用 `compose_dsl` 容器，以便聊天页复用，不必绑定 toolbox 导航场景。

### 5.4 数据层（大概率可复用）

下面通常不需要 schema 改动，现有字段可直接复用：

- `app/src/main/java/com/ai/assistance/operit/data/model/ChatEntity.kt`
- `app/src/main/java/com/ai/assistance/operit/data/model/ChatHistory.kt`
- `app/src/main/java/com/ai/assistance/operit/data/dao/ChatDao.kt`
- `app/src/main/java/com/ai/assistance/operit/data/repository/ChatHistoryManager.kt`

---

## 6. 推荐迁移顺序

1. **先开 API，不换 UI**  
   先把 workspace bridge 能力补齐，用一个最小插件验证“能创建/绑定/解绑/启动预览”。
2. **再做 Workspace 插件 UI**  
   将当前 `WorkspaceSetup` 流程迁成插件（模板、repo 书签、绑定）。
3. **再切主入口**  
   `AIChatScreen` 顶栏按钮切到插件容器，原生页面下线。
4. **最后清理旧实现**  
   删除不再使用的 `WorkspaceSetup/WorkspaceScreen/WorkspaceManager` 相关路径。

---

## 7. 文档同步建议

迁移落地后需要更新：

- `docs/TOOLPKG_FORMAT_GUIDE.md`  
  在 `Context API` 增加 workspace 章节。
- `docs/DEFAULT_TOOLS_ARCH.md`  
  补一节“工作区插件化架构”与调用链图。

---

## 8. 决策闸口（实现前必须确认）

这是“方案替换”，需要先确认是否已发布版本：

1. 如果**已发布**：要做向前兼容，至少保留一个版本周期的旧入口或兼容桥接。
2. 如果**未发布**：可直接替换并彻底清理旧方案代码（保留仍可复用的底层能力）。
