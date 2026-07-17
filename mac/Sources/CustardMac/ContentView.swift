import SwiftUI

struct ContentView: View {
    @Environment(\.custardPalette) private var palette
    @StateObject private var connection = ConnectionManager()
    @StateObject private var agentPortManager = AgentPortManager()
    @State private var navigationPath = NavigationPath()
    @State private var updatePrompt: AppUpdatePrompt?
    @State private var hasCheckedUpdate = false

    var body: some View {
        ZStack {
            NavigationStack(path: $navigationPath) {
                HomeView(navigationPath: $navigationPath)
                    .navigationDestination(for: AppRoute.self) { route in
                        switch route {
                        case .connectionConfig:
                            ConnectionConfigView()
                        case .modelConfig:
                            ModelConfigView()
                        case .basicSettings:
                            BasicSettingsView()
                        case .toolsManagement:
                            ToolsManagementView()
                        case .agentPort:
                            AgentPortSettingsView()
                        case .control:
                            ControlView(connection: connection)
                        }
                    }
            }
            .environmentObject(agentPortManager)
            .background(palette.background)
            .foregroundStyle(palette.onBackground)
            .disabled(updatePrompt?.kind == .forced)
            .allowsHitTesting(updatePrompt?.kind != .forced)

            if let updatePrompt {
                AppUpdateOverlay(
                    prompt: updatePrompt,
                    onUpgrade: {
                        AppUpdateChecker.openDownload(url: updatePrompt.downloadURL)
                        // 强更保持遮罩；普通更新可在下载后关闭。
                        if updatePrompt.kind == .optional {
                            self.updatePrompt = nil
                        }
                    },
                    onDismiss: updatePrompt.kind == .optional
                        ? { self.updatePrompt = nil }
                        : nil
                )
                .zIndex(1)
                .transition(.opacity)
            }
        }
        .frame(minWidth: 960, minHeight: 640)
        .onAppear {
            agentPortManager.configure(connection: connection)
            Task {
                await detectConnectionOnLaunch()
            }
            Task {
                await checkForUpdateOnLaunch()
            }
        }
        .onChange(of: connection.isConnected) { isConnected in
            agentPortManager.onConnectionStateChanged(isConnected: isConnected)
        }
    }

    private func checkForUpdateOnLaunch() async {
        guard !hasCheckedUpdate else { return }
        hasCheckedUpdate = true
        if let prompt = await AppUpdateChecker.fetchPromptIfNeeded() {
            await MainActor.run {
                updatePrompt = prompt
            }
        }
    }

    private func detectConnectionOnLaunch() async {
        guard AppPreferences.connectionType == nil else { return }

        let detected = await Task.detached(priority: .utility) {
            AdbManager.detectConnectionType()
        }.value

        guard let detected else { return }

        AppPreferences.connectionType = detected
        if detected == .wifi {
            let serial = await Task.detached(priority: .utility) {
                AdbManager.connectedDeviceSerial()
            }.value
            if let serial, let endpoint = AdbManager.parseWifiEndpoint(from: serial) {
                AppPreferences.saveWifiConnection(host: endpoint.host, port: endpoint.port)
            }
        }
    }
}
