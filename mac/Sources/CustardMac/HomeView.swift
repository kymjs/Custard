import SwiftUI

struct HomeView: View {
    @Environment(\.custardPalette) private var palette
    @Binding var navigationPath: NavigationPath
    @State private var connectionSummary = AppPreferences.connectionSummary
    @State private var modelSummaryText = ""
    @State private var basicSettingsSummaryText = ""
    @State private var toolsSummaryText = ""
    @State private var agentPortSummaryText = ""
    @State private var showModelNotConfiguredAlert = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(homeTitle)
                    .font(.largeTitle.weight(.bold))
                    .padding(.bottom, 4)

                DashboardCardButton(
                    iconName: "cable.connector",
                    title: "连接配置",
                    subtitle: connectionSummary
                ) {
                    navigationPath.append(AppRoute.connectionConfig)
                }

                DashboardCardButton(
                    iconName: "cpu",
                    title: "模型配置",
                    subtitle: modelSummaryText
                ) {
                    navigationPath.append(AppRoute.modelConfig)
                }

                DashboardCardButton(
                    iconName: "gearshape",
                    title: "基础设置",
                    subtitle: basicSettingsSummaryText
                ) {
                    navigationPath.append(AppRoute.basicSettings)
                }

                DashboardCardButton(
                    iconName: "wrench.and.screwdriver",
                    title: "工具(MCP/SKILL/CLI)",
                    subtitle: toolsSummaryText
                ) {
                    navigationPath.append(AppRoute.toolsManagement)
                }

                DashboardCardButton(
                    iconName: "antenna.radiowaves.left.and.right",
                    title: "Agent 端口",
                    subtitle: agentPortSummaryText
                ) {
                    navigationPath.append(AppRoute.agentPort)
                }

                StartButtonCard {
                    if !AppPreferences.isConnectionConfigComplete {
                        navigationPath.append(AppRoute.connectionConfig)
                    } else if !AppPreferences.isModelConfigComplete {
                        showModelNotConfiguredAlert = true
                    } else {
                        navigationPath.append(AppRoute.control)
                    }
                }
            }
            .padding(20)
        }
        .background(palette.background)
        .navigationTitle("")
        .onAppear {
            reloadSummaries()
        }
        .onChange(of: navigationPath.count) { _ in
            reloadSummaries()
        }
        .alert("请先配置大模型", isPresented: $showModelNotConfiguredAlert) {
            Button("去配置") {
                navigationPath.append(AppRoute.modelConfig)
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("启动前请在「模型配置」中选择提供商、模型并填写所需凭证。")
        }
    }

    private var homeTitle: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        return "奶黄包v\(version)"
    }

    private func reloadSummaries() {
        connectionSummary = AppPreferences.connectionSummary
        modelSummaryText = AppPreferences.modelSummary
        basicSettingsSummaryText = basicSettingsSummary
        toolsSummaryText = AppPreferences.toolsSummary
        agentPortSummaryText = AppPreferences.agentPortSummary
    }

    private var basicSettingsSummary: String {
        var parts: [String] = []
        parts.append("轮次：\(AppPreferences.maxLLMTurns)")
        parts.append(AppPreferences.showThinkingContentEnabled ? "思考：开" : "思考：关")
        parts.append(AppPreferences.operationImageEnabled ? "操作图像：开" : "操作图像：关")
        var uiSources: [String] = []
        if AppPreferences.accessibilityUiTreeEnabled { uiSources.append("无障碍") }
        if AppPreferences.viewDebugUiTreeEnabled { uiSources.append("ViewDebug") }
        if AppPreferences.uiautomatorUiTreeEnabled { uiSources.append("uiautomator") }
        parts.append(uiSources.isEmpty ? "UI 采集：关" : "UI 采集：\(uiSources.joined(separator: "+"))")
        return parts.joined(separator: " · ")
    }
}

enum AppRoute: Hashable {
    case connectionConfig
    case modelConfig
    case basicSettings
    case toolsManagement
    case agentPort
    case control
}
