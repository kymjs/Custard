import AppKit
import Foundation

@MainActor
final class ScreenFrameStore: ObservableObject {
    @Published var image: CGImage?
}

@MainActor
final class ConnectionManager: ObservableObject {
    @Published var isConnected = false
    @Published private(set) var isConnecting = false
    @Published var deviceInfo: DeviceInfo?
    /// Latest frame for capture APIs; not @Published so ControlView/ChatView are not invalidated per frame.
    private(set) var currentImage: CGImage?
    let frameStore = ScreenFrameStore()
    @Published var errorMessage: String?
    @Published var statusText = "未连接"
    @Published var clipboardStatus: String?
    @Published private(set) var connectedViaAdb = false
    @Published private(set) var isScreenCaptureBlocked = false
    @Published var uiTreeOverlayNodes: [UiTreeNode] = []

    private var socket: SocketConnection?
    private var readTask: Task<Void, Never>?
    private let decoder = H264Decoder()
    private var usedAdbForward = false
    private var uiTreeContinuation: CheckedContinuation<String?, Never>?
    private var uiTreeTimeoutTask: Task<Void, Never>?
    private var clipboardContinuation: CheckedContinuation<String?, Never>?
    private var clipboardTimeoutTask: Task<Void, Never>?
    private var textResultContinuation: CheckedContinuation<(Bool, String)?, Never>?
    private var textResultTimeoutTask: Task<Void, Never>?
    private var consecutiveBlackFrames = 0
    private var consecutiveNormalFrames = 0
    private var uiTreeOverlayRefreshTask: Task<Void, Never>?

    private let blackFrameDetectThreshold = 5
    private let normalFrameRecoverThreshold = 3

    init() {
        decoder.onFrame = { [weak self] image in
            // Run black-frame sampling on the decoder queue before hopping to MainActor.
            let isBlack = BlackFrameDetector.isMostlyBlack(image)
            Task { @MainActor in
                guard let self else { return }
                self.currentImage = image
                self.frameStore.image = image
                self.updateBlackFrameState(isBlack: isBlack)
            }
        }
    }

    func connect(host: String, port: UInt16 = Protocol.defaultPort, viaAdb: Bool = false) {
        if isConnected || isConnecting { return }
        disconnect()
        isConnecting = true
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let appPath = Bundle.main.bundlePath
        Logger.info("app version=\(appVersion) path=\(appPath)")
        Logger.info("connect start viaAdb=\(viaAdb) host=\(host) port=\(port)")
        statusText = viaAdb
            ? "正在通过 USB/ADB 连接..."
            : "正在连接 \(host):\(port)..."

        Task {
            defer { self.isConnecting = false }
            do {
                if viaAdb {
                    try AdbManager.setupUsbTunnel(local: port)
                    usedAdbForward = true
                    self.statusText = "正在检查手机端应用..."
                    try AdbManager.installCustardAppIfNeeded()
                    AdbManager.wakeAndroidApp()
                    self.statusText = "等待手机端启动共享屏幕..."
                }
                let connection = try await openConnectionWithRetry(
                    host: viaAdb ? Protocol.adbLocalHost : host,
                    port: port,
                    viaAdb: viaAdb
                )
                self.socket = connection
                self.isConnected = true
                self.connectedViaAdb = viaAdb
                self.errorMessage = nil
                self.statusText = viaAdb ? "已连接 (USB)" : "已连接 (WiFi)"
                Logger.info("connect success viaAdb=\(viaAdb)")
                AdbManager.logDiagnostics(context: "connect-success")
                self.startReading(from: connection)
            } catch {
                Logger.error(error, context: "connect failed viaAdb=\(viaAdb)")
                if viaAdb { AdbManager.removeForward(local: port) }
                self.errorMessage = error.localizedDescription
                self.statusText = "连接失败"
                self.isConnected = false
                self.usedAdbForward = false
            }
        }
    }

    private func openConnectionWithRetry(
        host: String,
        port: UInt16,
        viaAdb: Bool,
        attempts: Int = 5
    ) async throws -> SocketConnection {
        let maxAttempts = viaAdb ? 40 : attempts
        var lastError: Error?
        for attempt in 1...maxAttempts {
            Logger.info("socket connect attempt \(attempt)/\(maxAttempts) to \(host):\(port)")
            let connection = SocketConnection(host: host, port: port)
            do {
                try await connection.open()
                try await connection.validateHandshake()
                Logger.info("socket connected and handshake ok")
                return connection
            } catch {
                Logger.warn("socket attempt \(attempt) failed: \(error)")
                connection.close()
                lastError = error
                if attempt < maxAttempts {
                    let delayNs: UInt64 = viaAdb ? 1_500_000_000 : 300_000_000
                    try await Task.sleep(nanoseconds: delayNs)
                }
            }
        }
        if viaAdb {
            throw ConnectionError.serverNotRunning
        }
        throw lastError ?? ConnectionError.connectionClosed
    }

    func disconnect() {
        Logger.info("disconnect")
        cancelPendingUiTreeRequest()
        cancelPendingClipboardRequest()
        cancelPendingTextResultRequest()
        readTask?.cancel()
        readTask = nil
        socket?.close()
        socket = nil
        isConnected = false
        connectedViaAdb = false
        deviceInfo = nil
        currentImage = nil
        frameStore.image = nil
        resetScreenCaptureBlockedState()
        decoder.reset()
        if usedAdbForward {
            AdbManager.removeForward()
            usedAdbForward = false
        }
        statusText = "未连接"
    }

    func sendTouch(action: UInt8, x: Float, y: Float) {
        var payload = Data()
        payload.append(Protocol.msgTouch)
        payload.append(action)
        appendFloat(x, to: &payload)
        appendFloat(y, to: &payload)
        send(payload)
    }

    func sendKey(action: UInt8, keyCode: Int) {
        var payload = Data()
        payload.append(Protocol.msgKey)
        payload.append(action)
        appendInt32(keyCode, to: &payload)
        send(payload)
    }

    func tapAndroidKey(_ keyCode: Int) {
        sendKey(action: Protocol.actionDown, keyCode: keyCode)
        sendKey(action: Protocol.actionUp, keyCode: keyCode)
    }

    func tap(x: Float, y: Float) {
        sendTouch(action: Protocol.actionDown, x: x, y: y)
        sendTouch(action: Protocol.actionUp, x: x, y: y)
    }

    func swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300) {
        sendTouch(action: Protocol.actionDown, x: x1, y: y1)
        let steps = max(2, durationMs / 16)
        for step in 1..<steps {
            let t = Float(step) / Float(steps)
            sendTouch(
                action: Protocol.actionMove,
                x: x1 + (x2 - x1) * t,
                y: y1 + (y2 - y1) * t
            )
        }
        sendTouch(action: Protocol.actionUp, x: x2, y: y2)
    }

    func sendText(_ text: String) {
        let bytes = Data(text.utf8)
        var payload = Data()
        payload.append(Protocol.msgText)
        appendInt32(bytes.count, to: &payload)
        payload.append(bytes)
        send(payload)
    }

    func sendTextAndAwaitResult(_ text: String, timeoutSeconds: Double = 3) async -> (Bool, String)? {
        guard isConnected, socket != nil else { return nil }
        cancelPendingTextResultRequest()

        return await withCheckedContinuation { continuation in
            textResultContinuation = continuation
            sendText(text)

            textResultTimeoutTask = Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(timeoutSeconds * 1_000_000_000))
                finishTextResultRequest(with: nil)
            }
        }
    }

    func sendPasteAndAwaitResult(timeoutSeconds: Double = 3) async -> (Bool, String)? {
        guard isConnected, socket != nil else { return nil }
        cancelPendingTextResultRequest()

        return await withCheckedContinuation { continuation in
            textResultContinuation = continuation
            tapAndroidKey(279) // KEYCODE_PASTE

            textResultTimeoutTask = Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(timeoutSeconds * 1_000_000_000))
                finishTextResultRequest(with: nil)
            }
        }
    }

    func sendClipboardToDevice(_ text: String) {
        let bytes = Data(text.utf8)
        var payload = Data()
        payload.append(Protocol.msgClipboardSet)
        appendInt32(bytes.count, to: &payload)
        payload.append(bytes)
        send(payload)
        clipboardStatus = "已发送到手机"
    }

    func requestClipboardFromDevice() {
        var payload = Data()
        payload.append(Protocol.msgClipboardGet)
        send(payload)
        clipboardStatus = "正在获取..."
    }

    func requestClipboardText(timeoutSeconds: Double = 5) async -> String? {
        guard isConnected, socket != nil else { return nil }
        cancelPendingClipboardRequest()

        return await withCheckedContinuation { continuation in
            clipboardContinuation = continuation
            var payload = Data()
            payload.append(Protocol.msgClipboardGet)
            send(payload)

            clipboardTimeoutTask = Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(timeoutSeconds * 1_000_000_000))
                finishClipboardRequest(with: nil)
            }
        }
    }

    func syncMacClipboardToDevice() {
        let text = NSPasteboard.general.string(forType: .string) ?? ""
        guard !text.isEmpty else {
            clipboardStatus = "Mac 剪贴板为空"
            return
        }
        sendClipboardToDevice(text)
    }

    func refreshUiTreeOverlay() async {
        if let tree = await requestAccessibilityUiTree(timeoutSeconds: 3) {
            let nodes = UiTreeParser.parse(tree)
            if !nodes.isEmpty {
                uiTreeOverlayNodes = nodes
                return
            }
        }

        let hierarchy = await Task.detached(priority: .utility) {
            AdbManager.fetchUiHierarchySummary()
        }.value
        if let hierarchy {
            uiTreeOverlayNodes = UiTreeParser.parse(hierarchy)
        }
    }

    private func updateBlackFrameState(isBlack: Bool) {
        if isBlack {
            consecutiveNormalFrames = 0
            consecutiveBlackFrames += 1
            if consecutiveBlackFrames >= blackFrameDetectThreshold && !isScreenCaptureBlocked {
                isScreenCaptureBlocked = true
                Logger.info("screen capture blocked detected (FLAG_SECURE likely)")
                startUiTreeOverlayRefresh()
            }
        } else {
            consecutiveBlackFrames = 0
            consecutiveNormalFrames += 1
            if consecutiveNormalFrames >= normalFrameRecoverThreshold && isScreenCaptureBlocked {
                resetScreenCaptureBlockedState()
                Logger.info("screen capture blocked cleared")
            }
        }
    }

    private func resetScreenCaptureBlockedState() {
        isScreenCaptureBlocked = false
        consecutiveBlackFrames = 0
        consecutiveNormalFrames = 0
        uiTreeOverlayNodes = []
        stopUiTreeOverlayRefresh()
    }

    private func startUiTreeOverlayRefresh() {
        stopUiTreeOverlayRefresh()
        uiTreeOverlayRefreshTask = Task { @MainActor in
            await refreshUiTreeOverlay()
            while !Task.isCancelled && isScreenCaptureBlocked {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                guard !Task.isCancelled, isScreenCaptureBlocked else { break }
                await refreshUiTreeOverlay()
            }
        }
    }

    private func stopUiTreeOverlayRefresh() {
        uiTreeOverlayRefreshTask?.cancel()
        uiTreeOverlayRefreshTask = nil
    }

    func requestAccessibilityUiTree(timeoutSeconds: Double = 5) async -> String? {
        guard isConnected, socket != nil else { return nil }
        cancelPendingUiTreeRequest()
        cancelPendingClipboardRequest()

        return await withCheckedContinuation { continuation in
            uiTreeContinuation = continuation
            var payload = Data()
            payload.append(Protocol.msgUiTreeGet)
            send(payload)

            uiTreeTimeoutTask = Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(timeoutSeconds * 1_000_000_000))
                finishUiTreeRequest(with: nil)
            }
        }
    }

    private func finishUiTreeRequest(with text: String?) {
        uiTreeTimeoutTask?.cancel()
        uiTreeTimeoutTask = nil
        uiTreeContinuation?.resume(returning: text)
        uiTreeContinuation = nil
    }

    private func cancelPendingUiTreeRequest() {
        finishUiTreeRequest(with: nil)
    }

    private func finishClipboardRequest(with text: String?) {
        clipboardTimeoutTask?.cancel()
        clipboardTimeoutTask = nil
        clipboardContinuation?.resume(returning: text)
        clipboardContinuation = nil
    }

    private func cancelPendingClipboardRequest() {
        finishClipboardRequest(with: nil)
    }

    private func finishTextResultRequest(with result: (Bool, String)?) {
        textResultTimeoutTask?.cancel()
        textResultTimeoutTask = nil
        textResultContinuation?.resume(returning: result)
        textResultContinuation = nil
    }

    private func cancelPendingTextResultRequest() {
        finishTextResultRequest(with: nil)
    }

    private func parseTextResultJSON(_ data: Data) -> (Bool, String)? {
        guard
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let ok = object["ok"] as? Bool
        else { return nil }
        let message = object["message"] as? String ?? ""
        return (ok, message)
    }

    private func send(_ payload: Data) {
        try? socket?.send(payload)
    }

    private func appendInt32(_ value: Int, to data: inout Data) {
        var be = UInt32(value).bigEndian
        withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
    }

    private func appendFloat(_ value: Float, to data: inout Data) {
        var be = value.bitPattern.bigEndian
        withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
    }

    private func startReading(from connection: SocketConnection) {
        let decoder = self.decoder
        readTask = Task.detached(priority: .high) { [weak self, decoder] in
            do {
                if !connection.handshakeValidated {
                    let magicData = try connection.readExactBlocking(count: Protocol.magic.count)
                    guard String(data: magicData, encoding: .utf8) == Protocol.magic else {
                        throw ConnectionError.invalidMagic
                    }
                }
                Logger.info("protocol handshake ok")

                while !Task.isCancelled {
                    let typeData = try connection.readExactBlocking(count: 1)
                    let type = typeData[0]
                    // Immutable copy of weak self avoids Swift 6 "captured var self" in MainActor.run.
                    let manager = self

                    switch type {
                    case Protocol.msgDeviceInfo:
                        let width = try Self.readInt32Blocking(connection)
                        let height = try Self.readInt32Blocking(connection)
                        let codec = try connection.readExactBlocking(count: 1)
                        await MainActor.run {
                            manager?.deviceInfo = DeviceInfo(
                                width: width,
                                height: height,
                                codec: codec[0]
                            )
                            decoder.reset()
                            Logger.info("device info: \(width)x\(height) codec=\(codec[0])")
                        }

                    case Protocol.msgVideoFrame:
                        let flagsData = try connection.readExactBlocking(count: 1)
                        let length = try Self.readInt32Blocking(connection)
                        let frameData = try connection.readExactBlocking(count: length)
                        let isKeyFrame = flagsData[0] & Protocol.flagKeyframe != 0
                        decoder.decode(frameData, isKeyFrame: isKeyFrame)

                    case Protocol.msgClipboard:
                        let length = try Self.readInt32Blocking(connection)
                        let clipData = try connection.readExactBlocking(count: length)
                        if let text = String(data: clipData, encoding: .utf8) {
                            await MainActor.run {
                                if manager?.clipboardContinuation != nil {
                                    manager?.finishClipboardRequest(with: text)
                                } else {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(text, forType: .string)
                                    manager?.clipboardStatus = "已从手机同步剪贴板"
                                }
                            }
                        }

                    case Protocol.msgUiTree:
                        let length = try Self.readInt32Blocking(connection)
                        let treeData = try connection.readExactBlocking(count: length)
                        let text = String(data: treeData, encoding: .utf8)
                        await MainActor.run {
                            manager?.finishUiTreeRequest(with: text)
                        }

                    case Protocol.msgTextResult:
                        let length = try Self.readInt32Blocking(connection)
                        let resultData = try connection.readExactBlocking(count: length)
                        await MainActor.run {
                            if let parsed = manager?.parseTextResultJSON(resultData) {
                                manager?.finishTextResultRequest(with: parsed)
                            }
                        }

                    default:
                        throw ConnectionError.unknownMessageType(type)
                    }
                }
            } catch {
                if !Task.isCancelled {
                    Logger.error(error, context: "read loop")
                    let manager = self
                    await MainActor.run {
                        manager?.errorMessage = error.localizedDescription
                        manager?.statusText = "连接失败"
                        manager?.disconnect()
                    }
                }
            }
        }
    }

    private nonisolated static func readInt32Blocking(_ connection: SocketConnection) throws -> Int {
        let data = try connection.readExactBlocking(count: 4)
        return Int(data.withUnsafeBytes { $0.load(as: Int32.self).bigEndian })
    }
}

enum ConnectionError: LocalizedError, Equatable {
    case invalidMagic
    case unknownMessageType(UInt8)
    case connectionClosed
    case serverNotRunning

    var errorDescription: String? {
        switch self {
        case .invalidMagic:
            return "协议握手失败"
        case .unknownMessageType(let type):
            return "未知消息类型: \(type)"
        case .connectionClosed:
            return "连接已关闭"
        case .serverNotRunning:
            return "手机端未启动屏幕共享服务，请打开 [奶黄包] 并点击「开始共享屏幕」"
        }
    }
}
