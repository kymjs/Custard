import Foundation
import SwiftUI

/// 管理 Agent 本地 HTTP 服务生命周期与后台手机连接
@MainActor
final class AgentPortManager: ObservableObject {
    private let server = ScreenToolServer()
    private weak var connection: ConnectionManager?
    private var controlViewActive = false
    private var autoConnectTask: Task<Void, Never>?

    func configure(connection: ConnectionManager) {
        self.connection = connection
        syncServerState()
    }

    func setControlViewActive(_ active: Bool) {
        controlViewActive = active
        syncServerState()
    }

    func onConnectionStateChanged(isConnected: Bool) {
        syncServerState()
        guard AppPreferences.agentApiEnabled, !isConnected else { return }
        scheduleAutoConnect()
    }

    func syncServerState() {
        guard let connection else { return }

        let shouldServe = connection.isConnected
            && (AppPreferences.agentApiEnabled || controlViewActive)

        if shouldServe {
            // start 内部幂等：已在监听则只刷新 connection，避免反复 stop/start 打挂 27184。
            server.start(connection: connection)
        } else if server.isRunning {
            server.stop()
        }

        if AppPreferences.agentApiEnabled && !connection.isConnected && !connection.isConnecting {
            scheduleAutoConnect()
        } else if connection.isConnected {
            autoConnectTask?.cancel()
            autoConnectTask = nil
        }
    }

    private func scheduleAutoConnect() {
        autoConnectTask?.cancel()
        autoConnectTask = Task {
            while !Task.isCancelled {
                guard AppPreferences.agentApiEnabled else { break }
                guard let connection else { break }
                if connection.isConnected { break }
                if !connection.isConnecting {
                    attemptConnect(connection: connection)
                }
                try? await Task.sleep(nanoseconds: 8_000_000_000)
            }
            autoConnectTask = nil
        }
    }

    private func attemptConnect(connection: ConnectionManager) {
        guard !connection.isConnected, !connection.isConnecting else { return }
        guard let type = AppPreferences.connectionType else { return }
        let portNum = UInt16(AppPreferences.wifiPort) ?? Protocol.defaultPort
        switch type {
        case .usb:
            connection.connect(host: Protocol.adbLocalHost, port: portNum, viaAdb: true)
        case .wifi:
            connection.connect(host: AppPreferences.wifiHost, port: portNum)
        }
    }
}
