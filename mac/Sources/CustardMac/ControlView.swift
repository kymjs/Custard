import SwiftUI

struct ControlView: View {
    @Environment(\.custardPalette) private var palette
    @EnvironmentObject private var agentPortManager: AgentPortManager
    @ObservedObject var connection: ConnectionManager
    @StateObject private var chatViewModel = ChatViewModel()
    @State private var didAttemptConnect = false

    var body: some View {
        VStack(spacing: 0) {
            toolbar
                .padding(.horizontal)
                .padding(.vertical, 8)
                .background(palette.surface)

            Divider()
                .overlay(palette.divider)

            if connection.isConnected, let info = connection.deviceInfo {
                HStack(spacing: 0) {
                    ScreenView(
                        connection: connection,
                        frameStore: connection.frameStore,
                        deviceInfo: info,
                        contentAlignment: .leading
                    )
                    .aspectRatio(
                        CGFloat(info.width) / CGFloat(info.height),
                        contentMode: .fit
                    )
                    .frame(maxHeight: .infinity)

                    Divider()
                        .overlay(palette.divider)

                    ChatView(viewModel: chatViewModel)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else {
                connectingPlaceholder
            }
        }
        .background(palette.background)
        .foregroundStyle(palette.onBackground)
        .navigationTitle("连接")
        .onAppear {
            chatViewModel.configure(connection: connection)
            // setControlViewActive 内部已 syncServerState，勿再重复调用以免无意义重启。
            agentPortManager.setControlViewActive(true)
            autoConnectIfNeeded()
        }
        .onChange(of: connection.isConnected) { isConnected in
            agentPortManager.onConnectionStateChanged(isConnected: isConnected)
        }
        .onDisappear {
            agentPortManager.setControlViewActive(false)
        }
    }

    private var toolbar: some View {
        HStack(spacing: 8) {
            Button("Mac → 手机剪贴板") {
                connection.syncMacClipboardToDevice()
            }
            .buttonStyle(.bordered)
            .disabled(!connection.isConnected)

            Button("手机 → Mac 剪贴板") {
                connection.requestClipboardFromDevice()
            }
            .buttonStyle(.bordered)
            .disabled(!connection.isConnected)

            if let status = connection.clipboardStatus {
                Text(status)
                    .font(.caption)
                    .foregroundStyle(palette.secondaryText)
            }

            Divider()
                .frame(height: 20)

            Button("返回") {
                connection.tapAndroidKey(4)
            }
            .buttonStyle(.bordered)
            .disabled(!connection.isConnected)

            Button("桌面") {
                connection.tapAndroidKey(3)
            }
            .buttonStyle(.bordered)
            .disabled(!connection.isConnected)

            Spacer()
        }
    }

    private var connectingPlaceholder: some View {
        VStack(spacing: 16) {
            if connection.isConnected == false && didAttemptConnect {
                ProgressView()
                    .tint(palette.primary)
                Text(connection.statusText)
                    .foregroundStyle(palette.secondaryText)
                if let error = connection.errorMessage {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(palette.error)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
            } else {
                ProgressView()
                    .tint(palette.primary)
                Text("正在连接...")
                    .foregroundStyle(palette.secondaryText)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
        .background(palette.background)
    }

    private func autoConnectIfNeeded() {
        guard !didAttemptConnect else { return }
        didAttemptConnect = true

        guard let type = AppPreferences.connectionType else { return }
        let portNum = UInt16(AppPreferences.wifiPort) ?? Protocol.defaultPort

        switch type {
        case .usb:
            connection.connect(
                host: Protocol.adbLocalHost,
                port: portNum,
                viaAdb: true
            )
        case .wifi:
            connection.connect(
                host: AppPreferences.wifiHost,
                port: portNum
            )
        }
    }
}
