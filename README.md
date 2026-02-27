# Custard

Custard AI 是移动端首个功能完备的 AI 智能助手应用，完全独立运行于 Android 设备上，拥有强大的工具调用能力。本项目旨在为开发者提供一个可深度定制和扩展的 AI 助手框架。

## 项目结构

```
Custard/
├── app/              # 主应用模块
├── dragonbones/      # 2D 骨骼动画
├── terminal/        # 终端模拟器
├── mnn/             # 深度学习推理引擎
├── llama/           # 本地大模型推理
├── mmd/             # MMD 模型解析
├── showerclient/    # 虚拟显示服务客户端
├── tools/           # 配套工具集
└── docs/            # 文档
```

---

## 模块说明

### :app — 主应用模块

主 Android 应用，承载 Custard AI 助手的全部功能。

- **AI 对话**：多厂商 LLM 接入（OpenAI、智谱、通义等）、流式响应、角色卡
- **虚拟形象**：支持 DragonBones / WebP / glTF / MMD 四种渲染方式
- **工具调用**：文件系统、Shell、调试器、MCP 插件、项目分析等
- **语音交互**：语音识别、TTS、语音助手服务
- **工作区**：基于 WebView 的代码编辑、Markdown 渲染、Workspace 备份
- **系统集成**：Tasker 插件、Android Widget、Shizuku 权限
- **数据**：Room、ObjectBox、DataStore、向量搜索（HNSW）
- **文档**：PDF、Office、LaTeX 渲染、文档转换
- **其他**：浮动窗、布局调整、人设卡生成、MCP 配置等

---

### :dragonbones — 2D 骨骼动画

基于 DragonBones 的 2D 骨骼动画库，用于虚拟形象的 2D 骨骼渲染。

- **技术栈**：C++ 原生 + JNI + Jetpack Compose
- **功能**：解析 DragonBones 资源、骨骼动画播放、Compose 渲染
- **用途**：为 AvatarType.DRAGONBONES 类型的虚拟形象提供渲染能力

---

### :terminal — 终端模拟器

在应用内运行的终端模拟器，支持本地 Shell、SSH、FTP/SFTP。

- **本地终端**：Canvas 渲染、命令历史、语法高亮、虚拟键盘
- **SSH**：基于 JSch 的 SSH 连接、会话管理
- **FTP/SFTP 服务器**：Apache FtpServer、Apache SSHD
- **UI**：Jetpack Compose、可配置主题与字体

---

### :mnn — 深度学习推理引擎

集成阿里巴巴 MNN 的 Android 库，用于在端侧执行深度学习推理。

- **推理能力**：支持从文件/字节数组加载模型、Session 创建与配置
- **优化配置**：ARM82、LLM、低内存模式、Transformer 融合等
- **用途**：图像处理、TTS、以及其它依赖 MNN 的 AI 功能
- **依赖**：上游 MNN C++ 源码（含 LLM 支持）

---

### :llama — 本地大模型推理

基于 llama.cpp 的本地大模型推理库，支持纯本地 LLM 对话。

- **会话管理**：`LlamaSession` 创建、流式生成、Token 计数
- **工具调用**：Grammar 配置、Trigger 模式
- **用途**：在无网络或隐私场景下进行本地 LLM 对话
- **依赖**：llama.cpp 子模块

---

### :mmd — MMD 模型解析

MikuMikuDance (MMD) 模型与动作文件的解析与检查模块。

- **模型格式**：PMD、PMX
- **动作格式**：VMD（模型名、动作数、表情、镜头等）
- **能力**：读取模型/动作摘要、获取顶点/面数、骨骼/材质等
- **用途**：为 AvatarType.MMD 类型的 3D 虚拟形象提供数据解析
- **依赖**：saba 库（PMD/PMX/VMD 解析）

---

### :showerclient — 虚拟显示服务客户端

与 Shower 虚拟显示服务通信的 Binder 客户端，用于自动化操作与投屏。

- **职责**：维持与 Shower 服务的 Binder 连接
- **功能**：创建虚拟显示、启动应用、触控（tap/swipe/touch/key）、截图
- **组件**：`ShowerController`、`ShowerVideoRenderer`、`ShowerSurfaceView`、`ShowerServerManager`
- **用途**：AI 驱动的 UI 自动化、虚拟显示投屏

---

## 配套工具 (tools/)

### custard-pc-agent

Windows 端辅助程序，用于 Custard 与 PC 的协同。

- **本地配置 UI**：`http://127.0.0.1:58321`
- **HTTP 中继**：供移动端 `windows_control` 使用
- **功能**：预设命令与原始命令执行、文件读写、配置管理
- **技术栈**：Node.js、HTTP API

### shower

Shower 虚拟显示服务端应用，与 `showerclient` 配合使用。

### desktop

桌面版 Custard（基于 JVM 或其它跨平台方案）。

### 其他工具

- **github**：与 GitHub 相关的脚本或工具
- **mcp_bridge**：MCP 协议桥接
- **hotbuild**：快速构建/发布
- **memory**：内存或缓存相关工具
- **string**：字符串处理工具

---

## 编译说明

参见 [docs/BUILDING.md](docs/BUILDING.md)。

## 依赖概览

- **minSdk**：26  
- **targetSdk**：34  
- **JDK**：17  
- **Kotlin**：1.9.x  
- **UI**：Jetpack Compose、Material 3  
