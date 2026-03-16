[![OSL](https://cdn.kymjs.com:8843/qiniu/image/logo3.png)](https://kymjs.com/works/)
=================

## 奶黄包 (Custard)

<div align="center">  

**一个完全独立运行的 Android AI Agent 应用**

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://kymjs.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kymjs.com)

你可以把奶黄包看作是 [OpenClaw](https://github.com/nicepkg/OpenClaw) 的 Android 版本，拥有强大的工具调用能力，可以在手机上以 AI Agent 的方式与系统深度交互，完成从对话、自动化操作到代码执行等各种任务。

> **关于名字**：奶黄包，是为了纪念一只因心脏病离开的加菲猫而命名。

</div>

---

## 技术特点

### 多 Provider LLM 接入

支持 OpenAI、Claude、Gemini、DeepSeek、阿里云、百度、Mistral、LM Studio 等主流云端大模型接口，通过统一的 `AIService` 抽象和 `AIServiceFactory` 工厂，一套代码适配所有 Provider。

### 端侧推理

集成 MNN（Mobile Neural Network）和 llama.cpp 两大端侧推理引擎，无需网络即可在设备上直接运行大语言模型，保障隐私与离线可用性。

### 80+ 内置工具

工具系统通过 `AIToolHandler` 统一注册与调度，内置文件系统操作、UI 自动化、Shell 执行、HTTP 请求、Web 访问、Intent 调用、终端管理、FFmpeg 多媒体处理、工作流、对话管理、SSH 等 80 余种工具，覆盖 Android 系统的绝大多数操作场景。

### 三级权限体系

工具实现按权限级别分为 Standard（普通）、Debugger（Shizuku/ADB）、Root（超级用户）三层，由 `ToolGetter` 在运行时根据设备状态自动选择最高可用级别，兼顾安全性与能力上限。每个工具还配备独立的四档权限控制（允许 / 谨慎 / 每次询问 / 禁止）。

### ToolPkg 插件系统

自定义的 `.toolpkg` 包格式（本质为 ZIP），支持子包、UI 模块（Compose DSL）、资源文件、多语言，开发者可用 JavaScript/TypeScript 编写工具脚本并打包分发，通过 `PackageManager` 动态加载和卸载。

### MCP 协议支持

实现 MCP（Model Context Protocol）SDK 0.7.0，`MCPRepository` 负责连接 MCP Server 并将远程工具注册到本地 `AIToolHandler`，实现与外部工具生态的无缝对接。

### 流式 Markdown 渲染

自研高性能流式 Markdown 渲染引擎，基于 KMP（Knuth-Morris-Pratt）算法进行线性时间复杂度的模式匹配，采用插件化架构和两阶段解析（块级 + 内联），配合 `BatchNodeUpdater` 批量更新 Compose UI，实现丝滑的"打字机"效果。

### 虚拟形象

支持 DragonBones 骨骼动画、glTF（Filament 渲染）、MMD（MikuMikuDance）、WebP 动图四种格式的虚拟形象，通过 `AvatarFactory` 统一创建、`AvatarController` 统一控制。

### 语音交互

集成 STT（语音转文字）与 TTS（文字转语音）双向能力。STT 支持 Deepgram、OpenAI、Sherpa-MNN/NCNN 等引擎；TTS 支持 OpenAI、SiliconFlow、HTTP 自定义等多种后端。可注册为系统默认语音助手。

### 记忆系统

基于向量检索（HNSW）+ 关键词 + 图谱关联的混合记忆架构，候选评分公式为 $S(m) = S_{kw} + S_{rev} + S_{sem}^{norm} + S_{graph}$，实现长期对话记忆的智能召回。

### 工作流引擎

可视化创建和管理自动化工作流，支持定时触发、开机触发、Intent 广播触发，后台由 `WorkflowScheduler` + Android WorkManager 驱动。

### 系统级集成

- **Shizuku**：免 Root 获得高级系统权限
- **Tasker**：双向联动，可作为 Tasker 插件被调用，也可触发 Tasker 任务
- **系统助手**：可注册为 Android 默认数字助手（ASSIST Intent）
- **悬浮窗**：全局悬浮聊天窗口和语音球
- **桌面小部件**：Glance 实现的语音助手快捷入口
- **通知监听**：读取和管理系统通知
- **屏幕录制**：截图与录屏能力

---

## 项目结构

```
Custard/
├── app/              # 主应用模块
├── dragonbones/      # DragonBones 骨骼动画渲染引擎
├── terminal/         # 终端模拟器（含 SSH/FTP/SFTP）
├── mnn/              # MNN 端侧推理引擎
├── llama/            # llama.cpp 端侧推理引擎
├── mmd/              # MMD 模型渲染引擎
└── showerclient/     # Shower IPC 客户端
```

### app — 主应用模块

包含全部业务逻辑，约 755 个 Kotlin 源文件，是项目的核心。

| 包 | 职责 |
|---|---|
| `api/chat/` | AI 服务封装、LLM Provider 适配、增强服务、深度搜索 |
| `api/speech/` | STT 引擎（Deepgram、OpenAI、Sherpa） |
| `api/voice/` | TTS 引擎（OpenAI、SiliconFlow、HTTP、无障碍） |
| `core/application/` | Application 初始化、Activity 生命周期、前台服务兼容 |
| `core/avatar/` | 虚拟形象工厂与多格式实现 |
| `core/chat/` | AI 消息管理器 |
| `core/config/` | 系统提示词、功能提示词、工具提示词 |
| `core/subpack/` | APK/EXE 编辑、签名、反编译 |
| `core/tools/` | 工具系统核心：注册、执行、权限、JS 引擎、MCP、包管理、Agent |
| `core/workflow/` | 工作流执行与调度 |
| `data/` | 数据库（Room/ObjectBox）、DAO、Repository、迁移、偏好设置 |
| `integrations/` | Tasker 和 Intent 集成 |
| `provider/` | 工作区/记忆 DocumentsProvider |
| `services/` | 核心服务、悬浮窗、通知监听、UI 调试器、语音助手 |
| `ui/` | Compose UI：主界面、聊天、设置、工具箱、工作流、记忆、包管理等 |
| `util/` | 流处理、向量计算、Markdown 解析、日志、归档等工具类 |
| `widget/` | Glance 桌面小部件 |

### dragonbones — 骨骼动画引擎

DragonBones 骨骼动画的 Android 渲染实现，通过 CMake 编译 C++ 原生代码，提供 Compose UI 组件。用于虚拟形象的骨骼动画播放。

### terminal — 终端模拟器

全功能终端模拟器模块，包含 JNI 原生终端进程管理、Compose UI 渲染、SSH 客户端（JSch）、FTP 服务器（Apache FtpServer）、SFTP/SSHD 服务器（Apache SSHD），支持无障碍操作。

### mnn — MNN 推理引擎

基于阿里 MNN（Mobile Neural Network）框架的端侧 LLM 推理模块。通过 CMake 编译原生库，启用 ARM82 指令集加速和 Transformer 融合优化，支持低内存模式和权重量化解压。

### llama — llama.cpp 引擎

基于 llama.cpp 的端侧 LLM 推理模块。通过 CMake 编译 C++ 原生代码，提供 GGUF 格式模型的加载与推理能力。

### mmd — MMD 渲染引擎

MikuMikuDance 模型的 Android 渲染实现，通过 CMake 编译 C++ 原生代码，用于 MMD 格式虚拟形象的 3D 渲染。

### showerclient — IPC 客户端

轻量级 Binder/IPC 客户端模块，仅包含 AIDL 接口定义和协程支持，不依赖 Compose，用于与 Shower 服务进程通信。

---

## 核心运行流程

### 应用启动流程

```
CustardApplication.onCreate()
  → 初始化 AIToolHandler（单例）
  → registerDefaultTools()  // 注册 80+ 内置工具
  → 初始化 AIMessageManager
  → MainActivity 启动
    → 检查用户协议
    → 检查权限状态
    → 检查数据迁移
    → 加载 MCP 插件（PluginLoadingScreen）
    → 进入 CustardApp 主界面
```

### 对话与消息流程

```
用户输入消息 → AIChatScreen
  → ChatViewModel.sendUserMessage()
    → MessageCoordinationDelegate.sendUserMessage()
      → 无当前对话时创建新对话
      → 处理附件、引用、工作区上下文
      → AIMessageManager.sendMessage()
        → EnhancedAIService.sendMessage()
          → AIService (具体 Provider) 发起流式请求
          → 逐 token 返回 Flow<String>
      → MessageProcessingDelegate 消费流式响应
        → 流式 Markdown 渲染到 UI
        → 检测 XML 工具调用标签
        → 保存消息到数据库
      → 检查并触发上下文总结
```

### 工具调用流程

```
LLM 响应中包含 <tool_call> XML
  → ToolExecutionManager 解析 XML
    → AIToolHandler.executeTool(aiTool)
      → Hook 通知: onToolCallRequested
      → ToolPermissionSystem 权限校验
      → Hook 通知: onToolExecutionStarted
      → ToolExecutor.invoke(aiTool) 执行
      → Hook 通知: onToolExecutionResult
    → 工具结果写回对话上下文
    → 继续发送给 LLM 处理
```

### 工具注册流程

```
ToolRegistration.registerAllTools()
  → ToolGetter 根据设备权限级别选择实现
    → STANDARD: 普通权限工具集
    → DEBUGGER: Shizuku/ADB 增强工具集
    → ROOT: 超级用户权限工具集
  → AIToolHandler.registerTool() 逐个注册
  → PackageManager 加载 .toolpkg 动态工具
  → MCPRepository 注册 MCP 远程工具
```

### 插件包加载流程

```
.toolpkg 文件（ZIP 格式）
  → PackageManager.loadPackage()
    → 解压并解析 manifest.json
    → 加载子包（JavaScript 脚本）
      → 解析 METADATA 注释块
      → 注册工具到 AIToolHandler（格式: subpackage:tool_name）
    → 加载 UI 模块（Compose DSL）
    → 解压资源文件到本地
```

### 语音交互流程

```
用户按住语音按钮 / 唤醒词激活
  → SpeechRecognitionManager 录音
    → VAD 静音检测（Silero / Sherpa）
    → STT 引擎转文字（Deepgram / OpenAI / Sherpa）
  → 文字进入对话流程
  → LLM 响应
    → VoiceService 调用 TTS 引擎
      → OpenAI / SiliconFlow / HTTP 后端
      → 播放语音
```

### 工作流执行流程

```
触发条件命中（定时 / 开机 / Intent 广播）
  → WorkflowScheduler 调度
    → WorkManager 发起 WorkflowWorker
      → WorkflowExecutor 执行节点图
        → 按连接关系遍历节点
        → 每个节点调用对应工具或 AI 服务
        → 传递中间结果到下一节点
```

---

## 核心类图

项目的核心类关系、ViewModel 委托体系、服务调用链、工具系统架构等类图详见 [CLASS_DIAGRAM.md](docs/CLASS_DIAGRAM.md)。

---

## 许可证

本项目基于 [GNU Lesser General Public License v3.0](LICENSE) 发布。

---

<div align="center">

**奶黄包** — 以猫之名，赋 AI 以灵

</div>
