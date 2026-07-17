import SwiftUI

struct ConnectionConfigView: View {
    @Environment(\.custardPalette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var selectedType: ConnectionType = .usb
    @State private var hostAddress = AppPreferences.wifiHost
    @State private var port = AppPreferences.wifiPort
    @State private var didAutoDetect = false

    var body: some View {
        Form {
            Section {
                Picker("连接方式", selection: $selectedType) {
                    ForEach(ConnectionType.allCases) { type in
                        Label(type.displayName, systemImage: type.iconName)
                            .tag(type)
                    }
                }
                .pickerStyle(.radioGroup)
            } header: {
                Text("选择连接方式")
            }

            if selectedType == .wifi {
                Section {
                    TextField("手机 IP 地址", text: $hostAddress)
                    TextField("端口", text: $port)
                } header: {
                    Text("WiFi 连接信息")
                } footer: {
                    Text("请确保手机与 Mac 在同一局域网，并在手机端开启屏幕共享。")
                }
            } else {
                Section {
                    Text("通过 USB 数据线连接手机，并开启 USB 调试。无需额外输入。")
                        .foregroundStyle(palette.secondaryText)
                        .font(.subheadline)
                } header: {
                    Text("USB 连接")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("连接配置")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("确定") {
                    saveAndDismiss()
                }
                .disabled(!isValid)
            }
        }
        .onAppear {
            autoDetectIfNeeded()
        }
        .onDisappear {
            saveIfValid()
        }
    }

    private var isValid: Bool {
        switch selectedType {
        case .usb:
            return true
        case .wifi:
            return !hostAddress.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && UInt16(port) != nil
        }
    }

    private func autoDetectIfNeeded() {
        guard !didAutoDetect else { return }
        didAutoDetect = true

        if let saved = AppPreferences.connectionType {
            selectedType = saved
            hostAddress = AppPreferences.wifiHost
            port = AppPreferences.wifiPort
            return
        }

        guard let detected = AdbManager.detectConnectionType() else { return }
        selectedType = detected
        AppPreferences.connectionType = detected

        if detected == .wifi, let serial = AdbManager.connectedDeviceSerial(),
           let endpoint = AdbManager.parseWifiEndpoint(from: serial) {
            hostAddress = endpoint.host
            port = endpoint.port
        }
    }

    private func saveAndDismiss() {
        saveIfValid()
        dismiss()
    }

    private func saveIfValid() {
        guard isValid else { return }
        AppPreferences.connectionType = selectedType
        if selectedType == .wifi {
            AppPreferences.saveWifiConnection(host: hostAddress, port: port)
        }
    }
}
