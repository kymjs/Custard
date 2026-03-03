# 核心类图

本文档描述奶黄包（Custard）项目中核心类的职责与引用关系。

## 1. 整体架构总览

```mermaid
graph TD
    subgraph UI["UI 层 (Jetpack Compose)"]
        MA[MainActivity]
        CA[CustardApp]
        ACS[AIChatScreen]
        FS[FloatingChatService]
    end

    subgraph ViewModel["ViewModel 层"]
        CVM[ChatViewModel]
        CHD[ChatHistoryDelegate]
        MPD[MessageProcessingDelegate]
        MCD[MessageCoordinationDelegate]
        ACD[ApiConfigDelegate]
        TSD[TokenStatisticsDelegate]
        ATD[AttachmentDelegate]
        USD[UiStateDelegate]
    end

    subgraph Service["服务层"]
        AMM[AIMessageManager]
        EAS[EnhancedAIService]
        ASF[AIServiceFactory]
        TEM[ToolExecutionManager]
    end

    subgraph Provider["LLM Provider 层"]
        AIS[AIService]
        OAI[OpenAIProvider]
        CLA[ClaudeProvider]
        GEM[GeminiProvider]
        MNP[MNNProvider]
        LLP[LlamaCppProvider]
    end

    subgraph Tools["工具系统"]
        ATH[AIToolHandler]
        TR[ToolRegistration]
        TG[ToolGetter]
        TE[ToolExecutor]
        TPS[ToolPermissionSystem]
        PM[PackageManager]
        MCR[MCPRepository]
    end

    subgraph Data["数据层"]
        DB[(Room / ObjectBox)]
        DS[DataStore]
        RP[Repository]
    end

    MA --> CA
    CA --> ACS
    ACS --> CVM
    FS --> CVM

    CVM --> CHD
    CVM --> MPD
    CVM --> MCD
    CVM --> ACD
    CVM --> TSD
    CVM --> ATD
    CVM --> USD

    MCD --> AMM
    AMM --> EAS
    EAS --> ASF
    ASF --> AIS
    AIS --> OAI
    AIS --> CLA
    AIS --> GEM
    AIS --> MNP
    AIS --> LLP

    EAS --> TEM
    TEM --> ATH
    ATH --> TR
    TR --> TG
    TG --> TE
    ATH --> TPS
    ATH --> PM
    ATH --> MCR

    CHD --> RP
    RP --> DB
    RP --> DS
```

## 2. ViewModel 委托体系

`ChatViewModel` 采用多委托模式将复杂逻辑拆分到独立的 Delegate 类中，每个 Delegate 负责一个明确的职责域。

```mermaid
classDiagram
    class ChatViewModel {
        -chatHistoryDelegate: ChatHistoryDelegate
        -messageProcessingDelegate: MessageProcessingDelegate
        -messageCoordinationDelegate: MessageCoordinationDelegate
        -apiConfigDelegate: ApiConfigDelegate
        -tokenStatisticsDelegate: TokenStatisticsDelegate
        -attachmentDelegate: AttachmentDelegate
        -uiStateDelegate: UiStateDelegate
        -floatingWindowDelegate: FloatingWindowDelegate
        +sendUserMessage(text, attachments)
        +createNewChat()
        +switchChat(chatId)
        +cancelGeneration()
    }

    class ChatHistoryDelegate {
        +chatHistory: StateFlow~List~
        +currentChatId: StateFlow~String~
        +loadChatHistory()
        +createNewChat()
        +deleteChat(chatId)
        +switchChat(chatId)
    }

    class MessageProcessingDelegate {
        +isGenerating: StateFlow~Boolean~
        +processStreamResponse(flow)
        +addMessageToChat(message)
        +cancelGeneration()
    }

    class MessageCoordinationDelegate {
        +sendUserMessage(text, attachments)
        -sendMessageInternal(content)
        -checkAndTriggerSummary()
    }

    class ApiConfigDelegate {
        +apiConfig: StateFlow~ModelConfigData~
        +updateApiConfig(config)
    }

    class TokenStatisticsDelegate {
        +tokenUsage: StateFlow~TokenUsage~
        +updateTokenStats(usage)
    }

    class AttachmentDelegate {
        +attachments: StateFlow~List~
        +addAttachment(attachment)
        +removeAttachment(id)
    }

    class UiStateDelegate {
        +errorMessage: StateFlow~String~
        +showError(message)
        +showToast(message)
    }

    ChatViewModel --> ChatHistoryDelegate
    ChatViewModel --> MessageProcessingDelegate
    ChatViewModel --> MessageCoordinationDelegate
    ChatViewModel --> ApiConfigDelegate
    ChatViewModel --> TokenStatisticsDelegate
    ChatViewModel --> AttachmentDelegate
    ChatViewModel --> UiStateDelegate
```

## 3. AI 服务链路

从用户输入到 LLM 响应的完整服务调用链。

```mermaid
classDiagram
    class AIMessageManager {
        <<singleton>>
        +sendMessage(service, messages, config)
        +buildUserMessageContent(text, attachments)
        +shouldGenerateSummary(): Boolean
        +summarizeMemory(service)
    }

    class EnhancedAIService {
        <<singleton>>
        -aiService: AIService
        -toolExecutionManager: ToolExecutionManager
        -chatInstances: Map~String, Instance~
        +sendMessage(messages, config): Flow~String~
        +generateSummary(messages): String
        +getInstance(context): EnhancedAIService
    }

    class AIServiceFactory {
        +createService(config): AIService
    }

    class AIService {
        <<interface>>
        +sendMessage(messages, config): Flow~String~
        +getModelsList(): List~String~
        +countTokens(text): Int
    }

    class PlanModeManager {
        +executeDeepSearchMode(query, service)
    }

    class ToolExecutionManager {
        -aiToolHandler: AIToolHandler
        +executeToolCalls(xmlContent): List~ToolResult~
    }

    AIMessageManager --> EnhancedAIService : 调用
    AIMessageManager --> PlanModeManager : 深度搜索
    EnhancedAIService --> AIServiceFactory : 创建 Provider
    AIServiceFactory --> AIService : 实例化
    EnhancedAIService --> ToolExecutionManager : 工具执行

    class OpenAIProvider {
        +sendMessage()
        +getModelsList()
    }
    class ClaudeProvider {
        +sendMessage()
        +getModelsList()
    }
    class GeminiProvider {
        +sendMessage()
        +getModelsList()
    }
    class MNNProvider {
        +sendMessage()
        +loadModel()
    }
    class LlamaCppProvider {
        +sendMessage()
        +loadModel()
    }

    AIService <|.. OpenAIProvider
    AIService <|.. ClaudeProvider
    AIService <|.. GeminiProvider
    AIService <|.. MNNProvider
    AIService <|.. LlamaCppProvider
```

## 4. 工具系统

工具注册、权限校验与执行的完整架构。

```mermaid
classDiagram
    class AIToolHandler {
        <<singleton>>
        -tools: Map~String, ToolEntry~
        -hooks: List~AIToolHook~
        -permissionSystem: ToolPermissionSystem
        +registerTool(name, executor, desc, dangerCheck)
        +unregisterTool(name)
        +executeTool(aiTool): ToolResult
        +getRegisteredTools(): List~AITool~
        +registerDefaultTools()
    }

    class ToolRegistration {
        +registerAllTools(handler, context)
    }

    class ToolGetter {
        +getFileSystemTools(level): ToolExecutor
        +getUITools(level): ToolExecutor
        +getSystemOperationTools(level): ToolExecutor
    }

    class ToolExecutor {
        <<interface>>
        +invoke(aiTool): ToolResult
        +validateParameters(params): Boolean
    }

    class AIToolHook {
        <<interface>>
        +onToolCallRequested(tool)
        +onToolPermissionCheck(tool)
        +onToolExecutionStarted(tool)
        +onToolExecutionResult(tool, result)
        +onToolExecutionError(tool, error)
        +onTurnComplete()
    }

    class ToolPermissionSystem {
        +checkPermission(toolName): PermissionLevel
        +setPermission(toolName, level)
    }

    class PackageManager {
        +loadPackage(toolpkg)
        +activateSubpackage(id)
        +deactivateSubpackage(id)
    }

    class MCPRepository {
        +connectServer(config)
        +getTools(): List~MCPTool~
    }

    class MCPToolExecutor {
        +invoke(aiTool): ToolResult
    }

    class StandardFileSystemTools {
        +invoke(aiTool): ToolResult
    }
    class StandardUITools {
        +invoke(aiTool): ToolResult
    }
    class StandardSystemOperationTools {
        +invoke(aiTool): ToolResult
    }
    class DebuggerFileSystemTools {
        +invoke(aiTool): ToolResult
    }
    class RootFileSystemTools {
        +invoke(aiTool): ToolResult
    }

    AIToolHandler --> ToolRegistration : 初始化注册
    ToolRegistration --> ToolGetter : 获取实现
    AIToolHandler --> ToolPermissionSystem : 权限校验
    AIToolHandler --> AIToolHook : 生命周期通知
    ToolGetter --> ToolExecutor : 按权限级别返回
    ToolExecutor <|.. StandardFileSystemTools
    ToolExecutor <|.. StandardUITools
    ToolExecutor <|.. StandardSystemOperationTools
    ToolExecutor <|.. DebuggerFileSystemTools
    ToolExecutor <|.. RootFileSystemTools
    ToolExecutor <|.. MCPToolExecutor
    AIToolHandler --> PackageManager : 动态工具
    AIToolHandler --> MCPRepository : MCP 工具
```

## 5. 工具权限分层

工具实现按权限级别分为三层，通过 `ToolGetter` 动态选择。

```mermaid
graph TD
    TG[ToolGetter] -->|STANDARD| S["Standard 层
    普通权限工具
    无需特殊权限"]
    TG -->|DEBUGGER| D["Debugger 层
    Shizuku/ADB 权限
    继承 Standard 能力"]
    TG -->|ROOT| R["Root 层
    超级用户权限
    继承 Debugger 能力"]

    subgraph Standard["Standard 工具集"]
        SFS[StandardFileSystemTools]
        SUI[StandardUITools]
        SSO[StandardSystemOperationTools]
    end

    subgraph Debugger["Debugger 工具集"]
        DFS[DebuggerFileSystemTools]
        DUI[DebuggerUITools]
        DSO[DebuggerSystemOperationTools]
    end

    subgraph Root["Root 工具集"]
        RFS[RootFileSystemTools]
        RUI[RootUITools]
        RSO[RootSystemOperationTools]
    end

    S --> Standard
    D --> Debugger
    R --> Root
```

## 6. 流式渲染引擎

基于 KMP 算法的流式 Markdown 渲染架构。

```mermaid
classDiagram
    class Stream~T~ {
        <<interface>>
        +collect(collector)
        +splitBy(plugins): Stream~Group~
    }

    class AbstractStream~T~ {
        +collect(collector)
    }

    class StreamPlugin {
        <<interface>>
        +name: String
        +kmpGraph: StreamKmpGraph
        +priority: Int
    }

    class StreamKmpGraph {
        -states: List~State~
        -failureFunction: IntArray
        +feed(char): MatchResult
        +reset()
    }

    class StreamMarkdownRenderer {
        -blockPlugins: List~StreamPlugin~
        -inlinePlugins: List~StreamPlugin~
        -batchUpdater: BatchNodeUpdater
        +render(charStream): List~MarkdownNode~
    }

    class BatchNodeUpdater {
        -pendingUpdates: Queue
        +scheduleUpdate(node)
        +flush()
    }

    Stream <|.. AbstractStream
    StreamMarkdownRenderer --> StreamPlugin : 使用
    StreamPlugin --> StreamKmpGraph : 包含
    StreamMarkdownRenderer --> BatchNodeUpdater : 批量更新
    StreamMarkdownRenderer --> Stream : 处理字符流
```

## 7. 虚拟形象系统

支持多种格式的虚拟形象渲染。

```mermaid
classDiagram
    class AvatarController {
        <<interface>>
        +play(animation)
        +stop()
        +setExpression(name)
    }

    class AvatarModel {
        <<interface>>
        +load(path)
        +getAnimations(): List
    }

    class DragonBonesAvatar {
        -nativeRenderer: Long
        +play(animation)
        +load(path)
    }

    class GltfAvatar {
        -filamentEngine: Engine
        +play(animation)
        +load(path)
    }

    class MmdAvatar {
        -nativeHandle: Long
        +play(animation)
        +load(path)
    }

    class WebpAvatar {
        +play(animation)
        +load(path)
    }

    class AvatarFactory {
        +create(type, path): AvatarController
    }

    AvatarController <|.. DragonBonesAvatar
    AvatarController <|.. GltfAvatar
    AvatarController <|.. MmdAvatar
    AvatarController <|.. WebpAvatar
    AvatarModel <|.. DragonBonesAvatar
    AvatarModel <|.. GltfAvatar
    AvatarModel <|.. MmdAvatar
    AvatarModel <|.. WebpAvatar
    AvatarFactory --> AvatarController : 创建
```

## 8. 数据流全景

从用户输入到最终 UI 渲染的完整数据流。

```mermaid
sequenceDiagram
    participant U as 用户
    participant UI as AIChatScreen
    participant VM as ChatViewModel
    participant MC as MessageCoordinationDelegate
    participant AM as AIMessageManager
    participant ES as EnhancedAIService
    participant AS as AIService/Provider
    participant TM as ToolExecutionManager
    participant TH as AIToolHandler
    participant TE as ToolExecutor
    participant MP as MessageProcessingDelegate

    U->>UI: 输入消息 & 点击发送
    UI->>VM: sendUserMessage(text)
    VM->>MC: sendUserMessage(text, attachments)
    MC->>AM: sendMessage(enhancedService, messages)
    AM->>ES: sendMessage(messages, config)
    ES->>AS: sendMessage(messages) [流式]

    loop 流式响应
        AS-->>ES: Flow<String> 逐 token 返回
        ES-->>MP: 更新 UI 状态
        MP-->>UI: 实时渲染 Markdown
    end

    alt 包含工具调用
        ES->>TM: executeToolCalls(xmlContent)
        TM->>TH: executeTool(aiTool)
        TH->>TH: 权限校验
        TH->>TE: invoke(aiTool)
        TE-->>TH: ToolResult
        TH-->>TM: ToolResult
        TM-->>ES: 工具结果写回对话
        ES->>AS: 继续对话（含工具结果）
    end

    ES-->>AM: 对话完成
    AM-->>MC: 检查是否需要总结
    MC-->>VM: 更新聊天历史
    VM-->>UI: 刷新界面
```
