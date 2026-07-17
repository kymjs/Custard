import SwiftUI

struct ModelConfigView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var selectedProvider = AppPreferences.modelProvider
    @State private var selectedModelId = AppPreferences.modelId
    @State private var customModelId = AppPreferences.customModelId
    @State private var useCustomModel = AppPreferences.useCustomModel
    @State private var apiKey = AppPreferences.apiKey
    @State private var baseURL = AppPreferences.modelBaseURL
    @State private var secretKey = AppPreferences.modelSecretKey
    @State private var deployment = AppPreferences.modelDeployment
    @State private var region = AppPreferences.modelRegion
    @State private var validationMessage: String?

    private var profile: ModelProviderProfile {
        ModelProviderProfile.profile(for: selectedProvider)
    }

    var body: some View {
        Form {
            Section {
                Picker("模型提供商", selection: $selectedProvider) {
                    ForEach(ModelProvider.allCases) { provider in
                        Text(provider.displayName).tag(provider)
                    }
                }
                .onChange(of: selectedProvider) { _ in
                    applyProviderDefaults()
                }
            } header: {
                Text("模型提供商")
            }

            modelSection

            credentialsSection

            if let validationMessage {
                Section {
                    Text(validationMessage)
                        .foregroundStyle(.red)
                        .font(.caption)
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("模型配置")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("确定") {
                    saveAndDismiss()
                }
            }
        }
        .onAppear {
            reloadFromPreferences()
        }
        .onDisappear {
            saveIfNeeded()
        }
    }

    @ViewBuilder
    private var modelSection: some View {
        Section {
            if profile.models.isEmpty {
                TextField("模型名称 / Endpoint ID", text: $customModelId)
                    .textFieldStyle(.roundedBorder)
                    .onAppear { useCustomModel = true }
            } else {
                Picker("模型", selection: $selectedModelId) {
                    ForEach(profile.models) { model in
                        Text(model.displayName).tag(model.id)
                    }
                    if profile.allowsCustomModel {
                        Text("自定义…").tag("__custom__")
                    }
                }
                .onChange(of: selectedModelId) { newValue in
                    useCustomModel = newValue == "__custom__"
                }

                if useCustomModel || selectedModelId == "__custom__" {
                    TextField("自定义模型名称", text: $customModelId)
                        .textFieldStyle(.roundedBorder)
                }
            }
        } header: {
            Text("模型")
        } footer: {
            if let hint = profile.configurationHint {
                Text(hint)
            }
        }
    }

    @ViewBuilder
    private var credentialsSection: some View {
        Section {
            ForEach(profile.credentialFields) { field in
                credentialField(for: field)
            }
        } header: {
            Text("连接凭证")
        } footer: {
            Text("配置将自动保存到本地，下次打开会自动恢复。")
        }
    }

    @ViewBuilder
    private func credentialField(for field: ModelCredentialField) -> some View {
        switch field {
        case .apiKey:
            SecureField(field.label, text: $apiKey, prompt: Text(field.placeholder))
                .textFieldStyle(.roundedBorder)
        case .secretKey:
            SecureField(field.label, text: $secretKey, prompt: Text(field.placeholder))
                .textFieldStyle(.roundedBorder)
        case .baseURL:
            TextField(field.label, text: $baseURL, prompt: Text(profile.defaultBaseURL ?? field.placeholder))
                .textFieldStyle(.roundedBorder)
        case .deployment:
            TextField(field.label, text: $deployment, prompt: Text(field.placeholder))
                .textFieldStyle(.roundedBorder)
        case .region:
            TextField(field.label, text: $region, prompt: Text(field.placeholder))
                .textFieldStyle(.roundedBorder)
        }
    }

    private func applyProviderDefaults() {
        let profile = ModelProviderProfile.profile(for: selectedProvider)
        selectedModelId = profile.defaultModelId.isEmpty ? "__custom__" : profile.defaultModelId
        useCustomModel = profile.models.isEmpty || selectedModelId == "__custom__"
        if useCustomModel && customModelId.isEmpty {
            customModelId = profile.defaultModelId
        }
        // 切换提供商时始终对齐该提供商默认 Base URL，避免残留上一提供商地址。
        baseURL = profile.defaultBaseURL ?? ""
        validationMessage = nil
    }

    private func reloadFromPreferences() {
        selectedProvider = AppPreferences.modelProvider
        selectedModelId = AppPreferences.modelId
        customModelId = AppPreferences.customModelId
        useCustomModel = AppPreferences.useCustomModel
        apiKey = AppPreferences.apiKey
        baseURL = AppPreferences.modelBaseURL
        secretKey = AppPreferences.modelSecretKey
        deployment = AppPreferences.modelDeployment
        region = AppPreferences.modelRegion

        let profile = ModelProviderProfile.profile(for: selectedProvider)
        if selectedModelId.isEmpty {
            selectedModelId = profile.defaultModelId.isEmpty ? "__custom__" : profile.defaultModelId
        }
        if useCustomModel {
            selectedModelId = "__custom__"
        }
        if baseURL.isEmpty, let defaultBaseURL = profile.defaultBaseURL {
            baseURL = defaultBaseURL
        }
    }

    private func saveAndDismiss() {
        persistToPreferences()
        if let error = AppPreferences.validateModelConfig() {
            validationMessage = error
            return
        }
        dismiss()
    }

    private func saveIfNeeded() {
        persistToPreferences()
    }

    private func persistToPreferences() {
        let resolvedUseCustom = useCustomModel || selectedModelId == "__custom__"
        AppPreferences.saveModelConfig(
            provider: selectedProvider,
            modelId: selectedModelId == "__custom__" ? "" : selectedModelId,
            customModelId: customModelId,
            useCustomModel: resolvedUseCustom,
            apiKey: apiKey,
            baseURL: baseURL,
            secretKey: secretKey,
            deployment: deployment,
            region: region
        )
    }
}
