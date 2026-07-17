import Foundation

enum ConnectionType: String, CaseIterable, Identifiable, Codable {
    case usb
    case wifi

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .usb: return "USB 连接"
        case .wifi: return "WiFi 连接"
        }
    }

    var iconName: String {
        switch self {
        case .usb: return "cable.connector"
        case .wifi: return "wifi"
        }
    }
}

enum ModelProvider: String, CaseIterable, Identifiable, Codable {
    case openAI = "OpenAI"
    case anthropic = "Anthropic"
    case google = "Google Gemini"
    case meta = "Meta Llama"
    case mistral = "Mistral AI"
    case cohere = "Cohere"
    case deepSeek = "DeepSeek"
    case xAI = "xAI Grok"
    case alibaba = "阿里云通义千问"
    case baidu = "百度文心一言"
    case tencent = "腾讯混元"
    case bytedance = "字节豆包"
    case zhipu = "智谱 GLM"
    case moonshot = "月之暗面 Kimi"
    case minimax = "MiniMax"
    case awsBedrock = "AWS Bedrock"
    case azureOpenAI = "Azure OpenAI"
    case groq = "Groq"
    case together = "Together AI"
    case perplexity = "Perplexity"
    case ai21 = "AI21 Labs"
    case huggingFace = "Hugging Face"
    case ollama = "Ollama"
    case openRouter = "OpenRouter"
    case siliconFlow = "硅基流动"
    case stepfun = "阶跃星辰"
    case baichuan = "百川智能"
    case lingyi = "零一万物"
    case sensetime = "商汤日日新"

    var id: String { rawValue }

    var displayName: String { rawValue }
}

enum AppPreferences {
    private static let connectionTypeKey = "connectionType"
    private static let wifiHostKey = "lastWifiHost"
    private static let wifiPortKey = "lastWifiPort"
    private static let modelProviderKey = "modelProvider"
    private static let modelIdKey = "modelId"
    private static let customModelIdKey = "customModelId"
    private static let useCustomModelKey = "useCustomModel"
    private static let apiKeyKey = "apiKey"
    private static let modelBaseURLKey = "modelBaseURL"
    private static let modelSecretKeyKey = "modelSecretKey"
    private static let modelDeploymentKey = "modelDeployment"
    private static let modelRegionKey = "modelRegion"
    private static let modelConfigSavedKey = "modelConfigSaved"
    private static let maxLLMTurnsKey = "maxLLMTurns"
    static let defaultMaxLLMTurns = 26
    static let minMaxLLMTurns = 1
    static let maxAllowedLLMTurns = 100
    private static let operationImageKey = "operationImageEnabled"
    private static let accessibilityUiTreeKey = "accessibilityUiTreeEnabled"
    private static let viewDebugUiTreeKey = "viewDebugUiTreeEnabled"
    private static let uiautomatorUiTreeKey = "uiautomatorUiTreeEnabled"
    static let showThinkingContentUserDefaultsKey = "showThinkingContentEnabled"
    private static let showThinkingContentKey = showThinkingContentUserDefaultsKey
    private static let desktopLogEnabledKey = "desktopLogEnabled"
    private static let debugModeKey = "debugMode"
    private static let toolEnabledKeyPrefix = "toolEnabled."
    private static let agentApiEnabledKey = "agentApiEnabled"
    private static let agentConnectionTokenKey = "agentConnectionToken"
    private static let agentAllowWriteKey = "agentAllowWrite"
    private static let agentAllowSystemKeysKey = "agentAllowSystemKeys"
    static let agentToolServerPort: UInt16 = 27184

    static var connectionType: ConnectionType? {
        get {
            guard let raw = UserDefaults.standard.string(forKey: connectionTypeKey) else { return nil }
            return ConnectionType(rawValue: raw)
        }
        set {
            if let newValue {
                UserDefaults.standard.set(newValue.rawValue, forKey: connectionTypeKey)
            } else {
                UserDefaults.standard.removeObject(forKey: connectionTypeKey)
            }
        }
    }

    static var wifiHost: String {
        get { UserDefaults.standard.string(forKey: wifiHostKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: wifiHostKey) }
    }

    static var wifiPort: String {
        get { UserDefaults.standard.string(forKey: wifiPortKey) ?? String(Protocol.defaultPort) }
        set { UserDefaults.standard.set(newValue, forKey: wifiPortKey) }
    }

    static var modelProvider: ModelProvider {
        get {
            guard let raw = UserDefaults.standard.string(forKey: modelProviderKey),
                  let provider = ModelProvider(rawValue: raw) else {
                return .openAI
            }
            return provider
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: modelProviderKey) }
    }

    static var modelId: String {
        get { UserDefaults.standard.string(forKey: modelIdKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: modelIdKey) }
    }

    static var customModelId: String {
        get { UserDefaults.standard.string(forKey: customModelIdKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: customModelIdKey) }
    }

    static var useCustomModel: Bool {
        get { UserDefaults.standard.bool(forKey: useCustomModelKey) }
        set { UserDefaults.standard.set(newValue, forKey: useCustomModelKey) }
    }

    static var apiKey: String {
        get { UserDefaults.standard.string(forKey: apiKeyKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: apiKeyKey) }
    }

    static var modelBaseURL: String {
        get { UserDefaults.standard.string(forKey: modelBaseURLKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: modelBaseURLKey) }
    }

    static var modelSecretKey: String {
        get { UserDefaults.standard.string(forKey: modelSecretKeyKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: modelSecretKeyKey) }
    }

    static var modelDeployment: String {
        get { UserDefaults.standard.string(forKey: modelDeploymentKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: modelDeploymentKey) }
    }

    static var modelRegion: String {
        get { UserDefaults.standard.string(forKey: modelRegionKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: modelRegionKey) }
    }

    static var resolvedModelName: String {
        let profile = ModelProviderProfile.profile(for: modelProvider)
        return profile.resolvedModelId(
            selectedModelId: modelId,
            customModelId: customModelId,
            useCustomModel: useCustomModel
        )
    }

    static var maxLLMTurns: Int {
        get {
            let value = UserDefaults.standard.integer(forKey: maxLLMTurnsKey)
            guard (minMaxLLMTurns...maxAllowedLLMTurns).contains(value) else {
                return defaultMaxLLMTurns
            }
            return value
        }
        set {
            let clamped = min(max(newValue, minMaxLLMTurns), maxAllowedLLMTurns)
            UserDefaults.standard.set(clamped, forKey: maxLLMTurnsKey)
        }
    }

    static var operationImageEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: operationImageKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: operationImageKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: operationImageKey) }
    }

    static var accessibilityUiTreeEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: accessibilityUiTreeKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: accessibilityUiTreeKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: accessibilityUiTreeKey) }
    }

    static var viewDebugUiTreeEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: viewDebugUiTreeKey) }
        set { UserDefaults.standard.set(newValue, forKey: viewDebugUiTreeKey) }
    }

    static var uiautomatorUiTreeEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: uiautomatorUiTreeKey) == nil {
                return false
            }
            return UserDefaults.standard.bool(forKey: uiautomatorUiTreeKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: uiautomatorUiTreeKey) }
    }

    static var showThinkingContentEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: showThinkingContentKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: showThinkingContentKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: showThinkingContentKey) }
    }

    static var debugMode: DebugMode {
        get {
            if let raw = UserDefaults.standard.string(forKey: debugModeKey),
               let mode = DebugMode(rawValue: raw) {
                return mode
            }
            if UserDefaults.standard.object(forKey: desktopLogEnabledKey) != nil {
                return UserDefaults.standard.bool(forKey: desktopLogEnabledKey) ? .debug : .off
            }
            return .off
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: debugModeKey) }
    }

    static var logEnabled: Bool { debugMode.logEnabled }

    static var stepDebugEnabled: Bool { debugMode.stepDebugEnabled }

    static var desktopLogEnabled: Bool {
        get { logEnabled }
        set { debugMode = newValue ? .debug : .off }
    }

    static var isConnectionConfigComplete: Bool {
        guard let type = connectionType else { return false }
        switch type {
        case .usb:
            return true
        case .wifi:
            return !wifiHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && UInt16(wifiPort) != nil
        }
    }

    static var connectionSummary: String {
        guard let type = connectionType else { return "未配置" }
        switch type {
        case .usb:
            return "USB 连接"
        case .wifi:
            let host = wifiHost.isEmpty ? "未填写 IP" : wifiHost
            return "WiFi · \(host):\(wifiPort)"
        }
    }

    static var isModelConfigSaved: Bool {
        UserDefaults.standard.bool(forKey: modelConfigSavedKey)
    }

    static var isModelConfigComplete: Bool {
        guard isModelConfigSaved else { return false }
        return validateModelConfig() == nil
    }

    static var modelSummary: String {
        guard isModelConfigSaved else { return "未配置" }
        let provider = modelProvider.displayName
        let model = resolvedModelName
        if validateModelConfig() != nil {
            return "\(provider) · 配置不完整"
        }
        if model.isEmpty {
            return provider
        }
        return "\(provider) · \(model)"
    }

    static func saveWifiConnection(host: String, port: String) {
        wifiHost = host
        wifiPort = port
    }

    static func isToolEnabled(id: String) -> Bool {
        let key = toolEnabledKeyPrefix + id
        if UserDefaults.standard.object(forKey: key) != nil {
            return UserDefaults.standard.bool(forKey: key)
        }
        if id == LLMTools.getScreenName {
            let legacyKey = toolEnabledKeyPrefix + "llm.get_screen"
            if UserDefaults.standard.object(forKey: legacyKey) != nil {
                return UserDefaults.standard.bool(forKey: legacyKey)
            }
        }
        return true
    }

    static func setToolEnabled(id: String, enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: toolEnabledKeyPrefix + id)
    }

    static var toolsSummary: String {
        let enabledCount = CustardToolRegistry.allTools.filter { isToolEnabled(id: $0.id) }.count
        let total = CustardToolRegistry.allTools.count
        if enabledCount == total {
            return "全部开启 · \(total) 项"
        }
        if enabledCount == 0 {
            return "全部关闭 · \(total) 项"
        }
        return "已开启 \(enabledCount)/\(total)"
    }

    static var agentApiEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: agentApiEnabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: agentApiEnabledKey) }
    }

    static var agentConnectionToken: String {
        get {
            if let existing = UserDefaults.standard.string(forKey: agentConnectionTokenKey), !existing.isEmpty {
                return existing
            }
            return regenerateAgentConnectionToken()
        }
        set { UserDefaults.standard.set(newValue, forKey: agentConnectionTokenKey) }
    }

    @discardableResult
    static func regenerateAgentConnectionToken() -> String {
        let token = UUID().uuidString.lowercased()
        UserDefaults.standard.set(token, forKey: agentConnectionTokenKey)
        return token
    }

    static var agentSkillGitHubURL: String { AgentPortPaths.skillGitHubWebURL }

    static var agentAllowWrite: Bool {
        get {
            if UserDefaults.standard.object(forKey: agentAllowWriteKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: agentAllowWriteKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: agentAllowWriteKey) }
    }

    static var agentAllowSystemKeys: Bool {
        get {
            if UserDefaults.standard.object(forKey: agentAllowSystemKeysKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: agentAllowSystemKeysKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: agentAllowSystemKeysKey) }
    }

    static var agentPortSummary: String {
        if agentApiEnabled {
            return "已开启 · 端口 \(agentToolServerPort)"
        }
        return "未开启"
    }

    static var agentSkillPrompt: String {
        let token = agentConnectionToken
        let skillDir = AgentPortPaths.installedSkillPath
        return """
        请安装「奶黄包」Android 真机 Skill 套件：从 \(agentSkillGitHubURL) 克隆到 Cursor skill 全局目录（技术目录名 custard-phone-control），执行 bash "install.sh" \(token)（含读屏子 Skill android-phone-screen、Cursor Rule、MCP）。该套件对外品牌名为「奶黄包」。安装后执行：bash "\(skillDir)/scripts/custard-tool" status
        """
    }

    static func saveModelConfig(
        provider: ModelProvider,
        modelId: String,
        customModelId: String,
        useCustomModel: Bool,
        apiKey: String,
        baseURL: String,
        secretKey: String,
        deployment: String,
        region: String
    ) {
        modelProvider = provider
        self.modelId = modelId
        self.customModelId = customModelId
        self.useCustomModel = useCustomModel
        self.apiKey = apiKey
        modelBaseURL = baseURL
        modelSecretKey = secretKey
        modelDeployment = deployment
        modelRegion = region
        UserDefaults.standard.set(true, forKey: modelConfigSavedKey)
    }
}
