import Foundation

enum ModelCredentialField: String, CaseIterable, Identifiable {
    case apiKey
    case baseURL
    case secretKey
    case deployment
    case region

    var id: String { rawValue }

    var label: String {
        switch self {
        case .apiKey: return "API Key"
        case .baseURL: return "Base URL"
        case .secretKey: return "Secret Key"
        case .deployment: return "Deployment 名称"
        case .region: return "Region"
        }
    }

    var placeholder: String {
        switch self {
        case .apiKey: return "sk-..."
        case .baseURL: return "https://..."
        case .secretKey: return "Secret Key"
        case .deployment: return "gpt-4o-mini"
        case .region: return "us-east-1"
        }
    }

    var isSecure: Bool {
        switch self {
        case .apiKey, .secretKey: return true
        default: return false
        }
    }
}

struct ModelOption: Identifiable, Hashable {
    let id: String
    let displayName: String

    init(_ id: String, name: String? = nil) {
        self.id = id
        self.displayName = name ?? id
    }
}

struct ModelProviderProfile {
    let models: [ModelOption]
    let defaultModelId: String
    let credentialFields: [ModelCredentialField]
    let defaultBaseURL: String?
    let endpointSuffix: String
    let allowsCustomModel: Bool
    let configurationHint: String?

    static func profile(for provider: ModelProvider) -> ModelProviderProfile {
        switch provider {
        case .openAI:
            return ModelProviderProfile(
                models: [
                    ModelOption("gpt-4o"),
                    ModelOption("gpt-4o-mini"),
                    ModelOption("gpt-4.1"),
                    ModelOption("gpt-4.1-mini"),
                    ModelOption("o3-mini"),
                ],
                defaultModelId: "gpt-4o-mini",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.openai.com/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .anthropic:
            return ModelProviderProfile(
                models: [
                    ModelOption("claude-sonnet-4-20250514", name: "Claude Sonnet 4"),
                    ModelOption("claude-3-5-sonnet-20241022", name: "Claude 3.5 Sonnet"),
                    ModelOption("claude-3-5-haiku-20241022", name: "Claude 3.5 Haiku"),
                ],
                defaultModelId: "claude-3-5-sonnet-20241022",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.anthropic.com/v1",
                endpointSuffix: "messages",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .minimax:
            return ModelProviderProfile(
                models: [
                    ModelOption("MiniMax-M2.5"),
                    ModelOption("MiniMax-M2.5-highspeed", name: "MiniMax-M2.5 极速"),
                    ModelOption("MiniMax-M2.1"),
                    ModelOption("MiniMax-M2"),
                    ModelOption("MiniMax-M3"),
                ],
                defaultModelId: "MiniMax-M2.5",
                credentialFields: [.apiKey, .baseURL],
                defaultBaseURL: "https://api.minimaxi.com/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: "国内默认 api.minimaxi.com；海外可用 api.minimax.io"
            )
        case .deepSeek:
            return ModelProviderProfile(
                models: [
                    ModelOption("deepseek-chat"),
                    ModelOption("deepseek-reasoner"),
                ],
                defaultModelId: "deepseek-chat",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.deepseek.com/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .moonshot:
            return ModelProviderProfile(
                models: [
                    ModelOption("moonshot-v1-8k"),
                    ModelOption("moonshot-v1-32k"),
                    ModelOption("moonshot-v1-128k"),
                ],
                defaultModelId: "moonshot-v1-8k",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.moonshot.cn/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .zhipu:
            return ModelProviderProfile(
                models: [
                    ModelOption("glm-4-flash"),
                    ModelOption("glm-4-plus"),
                    ModelOption("glm-4-air"),
                ],
                defaultModelId: "glm-4-flash",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://open.bigmodel.cn/api/paas/v4",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .alibaba:
            return ModelProviderProfile(
                models: [
                    ModelOption("qwen-plus"),
                    ModelOption("qwen-max"),
                    ModelOption("qwen-turbo"),
                ],
                defaultModelId: "qwen-plus",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .siliconFlow:
            return ModelProviderProfile(
                models: [
                    ModelOption("deepseek-ai/DeepSeek-V3"),
                    ModelOption("Qwen/Qwen2.5-72B-Instruct"),
                    ModelOption("Pro/deepseek-ai/DeepSeek-R1"),
                ],
                defaultModelId: "deepseek-ai/DeepSeek-V3",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.siliconflow.cn/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .google:
            return ModelProviderProfile(
                models: [
                    ModelOption("gemini-2.0-flash"),
                    ModelOption("gemini-2.5-flash-preview-04-17", name: "Gemini 2.5 Flash"),
                    ModelOption("gemini-2.5-pro-preview-03-25", name: "Gemini 2.5 Pro"),
                ],
                defaultModelId: "gemini-2.0-flash",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://generativelanguage.googleapis.com/v1beta/openai",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .groq:
            return ModelProviderProfile(
                models: [
                    ModelOption("llama-3.3-70b-versatile"),
                    ModelOption("llama-3.1-8b-instant"),
                ],
                defaultModelId: "llama-3.3-70b-versatile",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.groq.com/openai/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .openRouter:
            return ModelProviderProfile(
                models: [
                    ModelOption("openai/gpt-4o-mini"),
                    ModelOption("anthropic/claude-3.5-sonnet"),
                    ModelOption("deepseek/deepseek-chat"),
                ],
                defaultModelId: "openai/gpt-4o-mini",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://openrouter.ai/api/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .ollama:
            return ModelProviderProfile(
                models: [
                    ModelOption("llama3.2"),
                    ModelOption("qwen2.5"),
                    ModelOption("deepseek-r1"),
                ],
                defaultModelId: "llama3.2",
                credentialFields: [.baseURL],
                defaultBaseURL: "http://127.0.0.1:11434/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: "本地 Ollama 无需 API Key，仅需 Base URL"
            )
        case .azureOpenAI:
            return ModelProviderProfile(
                models: [
                    ModelOption("gpt-4o-mini"),
                    ModelOption("gpt-4o"),
                ],
                defaultModelId: "gpt-4o-mini",
                credentialFields: [.apiKey, .baseURL, .deployment],
                defaultBaseURL: "https://YOUR-RESOURCE.openai.azure.com/openai",
                endpointSuffix: "deployments/{deployment}/chat/completions",
                allowsCustomModel: true,
                configurationHint: "Base URL 填 Azure 资源地址（不含 deployments 路径）"
            )
        case .baidu:
            return ModelProviderProfile(
                models: [
                    ModelOption("ernie-4.0-8k"),
                    ModelOption("ernie-3.5-8k"),
                    ModelOption("ernie-speed-128k"),
                ],
                defaultModelId: "ernie-4.0-8k",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://qianfan.baidubce.com/v2",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: "使用千帆平台的 API Key（OpenAI 兼容模式）"
            )
        case .tencent:
            return ModelProviderProfile(
                models: [
                    ModelOption("hunyuan-turbos-latest"),
                    ModelOption("hunyuan-turbo"),
                    ModelOption("hunyuan-pro"),
                ],
                defaultModelId: "hunyuan-turbos-latest",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.hunyuan.cloud.tencent.com/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .bytedance:
            return ModelProviderProfile(
                models: [],
                defaultModelId: "",
                credentialFields: [.apiKey, .baseURL],
                defaultBaseURL: "https://ark.cn-beijing.volces.com/api/v3",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: "模型名称填火山方舟控制台中的 Endpoint ID（如 doubao-1-5-xxx）"
            )
        case .stepfun:
            return ModelProviderProfile(
                models: [
                    ModelOption("step-2-16k"),
                    ModelOption("step-1-8k"),
                ],
                defaultModelId: "step-2-16k",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.stepfun.com/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .baichuan:
            return ModelProviderProfile(
                models: [
                    ModelOption("Baichuan4-Turbo"),
                    ModelOption("Baichuan3-Turbo-128k"),
                ],
                defaultModelId: "Baichuan4-Turbo",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.baichuan-ai.com/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .lingyi:
            return ModelProviderProfile(
                models: [
                    ModelOption("yi-large"),
                    ModelOption("yi-medium"),
                ],
                defaultModelId: "yi-large",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.lingyiwanwu.com/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .sensetime:
            return ModelProviderProfile(
                models: [
                    ModelOption("SenseChat-5"),
                    ModelOption("SenseChat-Turbo"),
                ],
                defaultModelId: "SenseChat-5",
                credentialFields: [.apiKey, .baseURL],
                defaultBaseURL: "https://api.sensenova.cn/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .xAI:
            return ModelProviderProfile(
                models: [
                    ModelOption("grok-2-latest"),
                    ModelOption("grok-2-vision-latest"),
                ],
                defaultModelId: "grok-2-latest",
                credentialFields: [.apiKey],
                defaultBaseURL: "https://api.x.ai/v1",
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: nil
            )
        case .meta, .mistral, .cohere, .together, .perplexity, .ai21, .huggingFace:
            return ModelProviderProfile(
                models: [],
                defaultModelId: "",
                credentialFields: [.apiKey, .baseURL],
                defaultBaseURL: provider.defaultOpenAIBaseURL,
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: "请填写对应平台的 Base URL 与模型名称"
            )
        case .awsBedrock:
            return ModelProviderProfile(
                models: [
                    ModelOption("anthropic.claude-3-5-sonnet-20241022-v2:0", name: "Claude 3.5 Sonnet"),
                ],
                defaultModelId: "anthropic.claude-3-5-sonnet-20241022-v2:0",
                credentialFields: [.apiKey, .region, .secretKey],
                defaultBaseURL: nil,
                endpointSuffix: "chat/completions",
                allowsCustomModel: true,
                configurationHint: "API Key 填 Access Key ID，Secret Key 填 Secret Access Key；需配合 Bedrock 兼容网关"
            )
        }
    }

    func resolvedModelId(selectedModelId: String, customModelId: String, useCustomModel: Bool) -> String {
        if useCustomModel {
            return customModelId.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if !selectedModelId.isEmpty {
            return selectedModelId
        }
        return defaultModelId
    }
}

private extension ModelProvider {
    var defaultOpenAIBaseURL: String {
        switch self {
        case .meta: return "https://api.together.xyz/v1"
        case .mistral: return "https://api.mistral.ai/v1"
        case .cohere: return "https://api.cohere.com/compatibility/v1"
        case .together: return "https://api.together.xyz/v1"
        case .perplexity: return "https://api.perplexity.ai"
        case .ai21: return "https://api.ai21.com/studio/v1"
        case .huggingFace: return "https://api-inference.huggingface.co/v1"
        default: return "https://api.openai.com/v1"
        }
    }
}

struct ModelRuntimeConfig {
    let endpoint: URL
    let model: String
    let headers: [String: String]
    let provider: ModelProvider

    static func build(from preferences: AppPreferences.Type = AppPreferences.self) -> ModelRuntimeConfig? {
        let provider = preferences.modelProvider
        let profile = ModelProviderProfile.profile(for: provider)
        let model = profile.resolvedModelId(
            selectedModelId: preferences.modelId,
            customModelId: preferences.customModelId,
            useCustomModel: preferences.useCustomModel
        )

        guard !model.isEmpty else { return nil }

        if provider == .azureOpenAI {
            return buildAzureConfig(profile: profile, model: model, preferences: preferences)
        }

        guard let baseURL = resolveBaseURL(profile: profile, preferences: preferences) else {
            return nil
        }

        let endpoint = buildEndpoint(
            baseURL: baseURL,
            suffix: profile.endpointSuffix,
            deployment: preferences.modelDeployment
        )

        var headers: [String: String] = [:]
        let apiKey = preferences.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if !apiKey.isEmpty {
            headers["Authorization"] = "Bearer \(apiKey)"
        }

        if provider == .openRouter {
            headers["HTTP-Referer"] = "https://github.com/custard"
            headers["X-Title"] = "奶黄包"
        }

        return ModelRuntimeConfig(
            endpoint: endpoint,
            model: model,
            headers: headers,
            provider: provider
        )
    }

    private static func buildAzureConfig(
        profile: ModelProviderProfile,
        model: String,
        preferences: AppPreferences.Type
    ) -> ModelRuntimeConfig? {
        let deployment = preferences.modelDeployment.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !deployment.isEmpty else { return nil }

        guard let baseURL = resolveBaseURL(profile: profile, preferences: preferences) else {
            return nil
        }

        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        var path = components?.path ?? ""
        if path.hasSuffix("/") { path.removeLast() }
        path += "/deployments/\(deployment)/chat/completions"
        components?.path = path
        components?.queryItems = [URLQueryItem(name: "api-version", value: "2024-02-15-preview")]

        guard let endpoint = components?.url else { return nil }

        let apiKey = preferences.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        return ModelRuntimeConfig(
            endpoint: endpoint,
            model: model,
            headers: ["api-key": apiKey],
            provider: .azureOpenAI
        )
    }

    private static func resolveBaseURL(
        profile: ModelProviderProfile,
        preferences: AppPreferences.Type
    ) -> URL? {
        let custom = normalizedBaseURLString(preferences.modelBaseURL)
        let providerDefault = profile.defaultBaseURL.map { normalizedBaseURLString($0) } ?? ""

        // 已保存地址若是其他提供商的默认 URL（切换残留），改用当前提供商默认。
        // 不在已知默认集合中的自定义地址（如 MiniMax 海外 api.minimax.io）予以保留。
        let raw: String
        if custom.isEmpty {
            raw = providerDefault
        } else if !providerDefault.isEmpty,
                  custom != providerDefault,
                  allKnownDefaultBaseURLs().contains(custom) {
            raw = providerDefault
        } else {
            raw = custom
        }

        guard !raw.isEmpty else { return nil }
        return URL(string: raw)
    }

    private static func normalizedBaseURLString(_ value: String) -> String {
        var normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        while normalized.hasSuffix("/") {
            normalized.removeLast()
        }
        return normalized
    }

    private static func allKnownDefaultBaseURLs() -> Set<String> {
        Set(
            ModelProvider.allCases.compactMap { provider in
                ModelProviderProfile.profile(for: provider).defaultBaseURL.map {
                    normalizedBaseURLString($0)
                }
            }.filter { !$0.isEmpty }
        )
    }

    private static func buildEndpoint(baseURL: URL, suffix: String, deployment: String) -> URL {
        let replacedSuffix = suffix.replacingOccurrences(of: "{deployment}", with: deployment)
        var base = baseURL.absoluteString
        while base.hasSuffix("/") {
            base.removeLast()
        }
        if base.hasSuffix(replacedSuffix) {
            return URL(string: base)!
        }
        return URL(string: "\(base)/\(replacedSuffix)")!
    }
}

extension AppPreferences {
    static func validateModelConfig() -> String? {
        let provider = modelProvider
        let profile = ModelProviderProfile.profile(for: provider)
        let model = profile.resolvedModelId(
            selectedModelId: modelId,
            customModelId: customModelId,
            useCustomModel: useCustomModel
        )

        if model.isEmpty {
            return "请选择或填写模型名称"
        }

        for field in profile.credentialFields where field != .baseURL {
            if credentialValue(for: field).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return "请填写\(field.label)"
            }
        }

        if profile.credentialFields.contains(.baseURL) || provider == .ollama {
            let base = modelBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
            if base.isEmpty && profile.defaultBaseURL == nil {
                return "请填写 Base URL"
            }
        }

        if provider == .azureOpenAI,
           modelDeployment.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "请填写 Deployment 名称"
        }

        return nil
    }

    static func credentialValue(for field: ModelCredentialField) -> String {
        switch field {
        case .apiKey: return apiKey
        case .baseURL: return modelBaseURL
        case .secretKey: return modelSecretKey
        case .deployment: return modelDeployment
        case .region: return modelRegion
        }
    }
}
