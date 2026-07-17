import Foundation
import Network

/// 本地 HTTP 服务，供 CLI / Agent Skill 调用（默认仅 127.0.0.1:27184）
final class ScreenToolServer {
    static let defaultPort: UInt16 = 27184

    private var listener: NWListener?
    private weak var connection: ConnectionManager?
    private var boundPort: UInt16?
    /// 递增以作废过期的 state / 重试回调，避免 stop 后误重启。
    private var epoch: UInt64 = 0
    private var retryTask: Task<Void, Never>?

    /// 已持有 listener（含正在绑定中）；幂等 start 用。
    @MainActor
    var isRunning: Bool { listener != nil }

    @MainActor
    func start(connection: ConnectionManager, port: UInt16 = 27184) {
        // 已在目标端口上服务：只刷新 connection，禁止 stop/start 抖动（会导致 EADDRINUSE）。
        if listener != nil, boundPort == port {
            self.connection = connection
            return
        }

        beginListening(connection: connection, port: port, reason: "start")
    }

    @MainActor
    func stop() {
        retryTask?.cancel()
        retryTask = nil
        epoch &+= 1
        listener?.cancel()
        listener = nil
        boundPort = nil
        connection = nil
    }

    @MainActor
    private func beginListening(connection: ConnectionManager, port: UInt16, reason: String) {
        retryTask?.cancel()
        retryTask = nil

        if let existing = listener {
            existing.cancel()
            listener = nil
        }

        epoch &+= 1
        let startEpoch = epoch
        self.connection = connection
        boundPort = port

        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return }

        do {
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true
            parameters.requiredLocalEndpoint = NWEndpoint.hostPort(
                host: NWEndpoint.Host("127.0.0.1"),
                port: nwPort
            )
            let listener = try NWListener(using: parameters)
            listener.newConnectionHandler = { [weak self] conn in
                Task { await self?.handle(connection: conn) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in
                    self?.handleListenerState(state, port: port, epoch: startEpoch)
                }
            }
            listener.start(queue: .global(qos: .utility))
            self.listener = listener
            Logger.info("ScreenToolServer \(reason) requested on 127.0.0.1:\(port)")
        } catch {
            Logger.error(error, context: "ScreenToolServer start")
            listener = nil
            boundPort = nil
            scheduleRestart(port: port, epoch: startEpoch, afterNanoseconds: 200_000_000)
        }
    }

    @MainActor
    private func handleListenerState(_ state: NWListener.State, port: UInt16, epoch: UInt64) {
        guard epoch == self.epoch else { return }
        switch state {
        case .ready:
            Logger.info("ScreenToolServer listening on 127.0.0.1:\(port)")
        case .failed(let error):
            Logger.warn("ScreenToolServer failed: \(error)")
            listener?.cancel()
            listener = nil
            boundPort = nil
            // cancel 后端口释放可能滞后，短暂重试避免 Agent API 永久挂掉。
            scheduleRestart(port: port, epoch: epoch, afterNanoseconds: 250_000_000)
        case .cancelled:
            if listener != nil {
                listener = nil
                boundPort = nil
            }
        default:
            break
        }
    }

    @MainActor
    private func scheduleRestart(port: UInt16, epoch: UInt64, afterNanoseconds: UInt64) {
        guard epoch == self.epoch else { return }
        guard connection != nil else { return }
        retryTask?.cancel()
        retryTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: afterNanoseconds)
            guard !Task.isCancelled else { return }
            guard epoch == self.epoch else { return }
            guard let connection = self.connection else { return }
            guard self.listener == nil else { return }
            self.beginListening(connection: connection, port: port, reason: "retry")
        }
    }

    private func handle(connection conn: NWConnection) async {
        conn.start(queue: .global(qos: .utility))
        guard let requestData = await readHTTPRequest(from: conn) else {
            conn.cancel()
            return
        }

        let requestLine = String(data: requestData, encoding: .utf8)?
            .split(separator: "\r\n", maxSplits: 1).first
            .map(String.init) ?? ""
        let requestHeaders = parseHTTPHeaders(from: requestData)
        let method = requestLine.split(separator: " ").first.map(String.init) ?? "GET"
        let path = requestLine.split(separator: " ").dropFirst().first.map(String.init) ?? "/"
        let tokenPrefix = extractAgentToken(from: requestHeaders).map { String($0.prefix(8)) }

        let responseData: Data
        do {
            responseData = try await AgentRequestGate.shared.process {
                try await self.routeRequest(
                    requestLine: requestLine,
                    requestData: requestData,
                    headers: requestHeaders
                )
            }
            let status = httpStatus(from: responseData) ?? 200
            AgentAuditLog.record(method: method, path: path, status: status, tokenPrefix: tokenPrefix)
        } catch AgentRequestGateError.rateLimited {
            let body = """
            {"error":"rate_limited","message":"请求过于频繁，请稍后重试（限制 120 次/分钟）。"}

            """
            responseData = httpResponse(status: 429, body: body)
            AgentAuditLog.record(method: method, path: path, status: 429, tokenPrefix: tokenPrefix)
        } catch {
            let body = """
            {"error":"internal_error","message":"\(error.localizedDescription)"}

            """
            responseData = httpResponse(status: 500, body: body)
            AgentAuditLog.record(method: method, path: path, status: 500, tokenPrefix: tokenPrefix)
        }

        // isComplete: true 再 cancel，避免 Connection: close 抢先拆掉未发完的尾部（curl 18 / JPEG 截断）。
        conn.send(
            content: responseData,
            contentContext: .defaultMessage,
            isComplete: true,
            completion: .contentProcessed { error in
                if let error {
                    Logger.warn("ScreenToolServer send failed: \(error)")
                }
                conn.cancel()
            }
        )
    }

    private func routeRequest(
        requestLine: String,
        requestData: Data,
        headers: [String: String]
    ) async throws -> Data {
        if let authResponse = unauthorizedResponse(headers: headers, requestLine: requestLine) {
            return authResponse
        }

        if requestLine.hasPrefix("GET /screen") || requestLine.hasPrefix("GET /tool/get_screen") {
            let screenToolId = toolSourceKind(from: headers) == "mcp" ? "mcp.get_screen" : "cli.get_screen"
            if let disabledResponse = disabledExternalToolResponse(toolId: screenToolId, headers: headers) {
                return disabledResponse
            }
            let includeScreenshot = requestLine.contains("screenshot=1")
                || requestLine.contains("include_screenshot=true")
                || requestLine.hasPrefix("GET /screen.png")
            let returnRawPNG = requestLine.contains("format=png")
                || requestLine.contains("raw=1")
                || requestLine.hasPrefix("GET /screen.png")
            if returnRawPNG {
                return await buildScreenPNGResponse()
            }
            return await buildScreenResponse(includeScreenshot: includeScreenshot)
        }

        if requestLine.hasPrefix("GET /tool/list_installed_apps") || requestLine.hasPrefix("GET /apps") {
            if let disabledResponse = disabledExternalToolResponse(toolId: "mcp.list_installed_apps", headers: headers) {
                return disabledResponse
            }
            return await buildListInstalledAppsResponse()
        }

        if requestLine.hasPrefix("POST /tool/open_app") {
            if let deniedResponse = agentWriteDeniedResponse(headers: headers) { return deniedResponse }
            if let disabledResponse = disabledExternalToolResponse(toolId: "mcp.open_app", headers: headers) {
                return disabledResponse
            }
            let body = httpBody(from: requestData)
            return await buildOpenAppResponse(body: body)
        }

        if requestLine.hasPrefix("POST /tool/tap_screen") {
            if let deniedResponse = agentWriteDeniedResponse(headers: headers) { return deniedResponse }
            if let disabledResponse = disabledExternalToolResponse(toolId: "mcp.tap_screen", headers: headers) {
                return disabledResponse
            }
            let body = httpBody(from: requestData)
            return await buildTapScreenResponse(body: body)
        }

        if requestLine.hasPrefix("GET /tool/read_clipboard") {
            if let disabledResponse = disabledExternalToolResponse(toolId: "mcp.read_clipboard", headers: headers) {
                return disabledResponse
            }
            return await buildReadClipboardResponse()
        }

        if requestLine.hasPrefix("POST /tool/write_clipboard") {
            if let deniedResponse = agentWriteDeniedResponse(headers: headers) { return deniedResponse }
            if let disabledResponse = disabledExternalToolResponse(toolId: "mcp.write_clipboard", headers: headers) {
                return disabledResponse
            }
            let body = httpBody(from: requestData)
            return await MainActor.run { buildWriteClipboardResponse(body: body) }
        }

        if requestLine.hasPrefix("POST /tool/type_text") {
            if let deniedResponse = agentWriteDeniedResponse(headers: headers) { return deniedResponse }
            if let disabledResponse = disabledExternalToolResponse(toolId: "mcp.type_text", headers: headers) {
                return disabledResponse
            }
            let body = httpBody(from: requestData)
            return await buildTypeTextResponse(body: body)
        }

        if requestLine.hasPrefix("POST /tool/press_home") {
            if let deniedResponse = agentSystemKeyDeniedResponse(headers: headers) { return deniedResponse }
            if let disabledResponse = disabledExternalToolResponse(toolId: "mcp.press_home", headers: headers) {
                return disabledResponse
            }
            return await buildPressHomeResponse()
        }

        if requestLine.hasPrefix("POST /tool/press_back") {
            if let deniedResponse = agentSystemKeyDeniedResponse(headers: headers) { return deniedResponse }
            if let disabledResponse = disabledExternalToolResponse(toolId: "mcp.press_back", headers: headers) {
                return disabledResponse
            }
            return await buildPressBackResponse()
        }

        if requestLine.hasPrefix("GET /agent/status") {
            return await MainActor.run { buildAgentStatusResponse() }
        }

        if requestLine.hasPrefix("GET /health") {
            return httpResponse(status: 200, body: "{\"ok\":true}\n")
        }

        let body = """
        {"error":"not found","endpoints":["GET /screen?screenshot=1","GET /tool/list_installed_apps","POST /tool/open_app","POST /tool/tap_screen","GET /tool/read_clipboard","POST /tool/write_clipboard","POST /tool/type_text","POST /tool/press_home","POST /tool/press_back","GET /agent/status","GET /health"]}

        """
        return httpResponse(status: 404, body: body)
    }

    private func toolSourceKind(from headers: [String: String]) -> String {
        let source = headers["x-custard-tool-source"] ?? "cli"
        if source == "agent" || source == "mcp" { return "mcp" }
        return "cli"
    }

    private func unauthorizedResponse(headers: [String: String], requestLine: String) -> Data? {
        guard AppPreferences.agentApiEnabled else { return nil }
        if requestLine.hasPrefix("GET /health") { return nil }
        let provided = extractAgentToken(from: headers)
        guard provided == AppPreferences.agentConnectionToken else {
            return httpResponse(status: 401, body: """
            {"error":"unauthorized","message":"Agent API 已开启，请提供有效 Token（X-Custard-Agent-Token 或 Authorization: Bearer）。"}

            """)
        }
        return nil
    }

    private func extractAgentToken(from headers: [String: String]) -> String? {
        if let token = headers["x-custard-agent-token"], !token.isEmpty { return token }
        if let auth = headers["authorization"], auth.hasPrefix("Bearer ") {
            return String(auth.dropFirst(7)).trimmingCharacters(in: .whitespaces)
        }
        return nil
    }

    private func agentWriteDeniedResponse(headers: [String: String]) -> Data? {
        guard AppPreferences.agentApiEnabled, extractAgentToken(from: headers) != nil else { return nil }
        guard !AppPreferences.agentAllowWrite else { return nil }
        return httpResponse(status: 403, body: """
        {"error":"permission_denied","message":"当前未允许外部 Agent 执行写入操作，请在奶黄包「Agent 端口」设置中开启。"}

        """)
    }

    private func agentSystemKeyDeniedResponse(headers: [String: String]) -> Data? {
        guard AppPreferences.agentApiEnabled, extractAgentToken(from: headers) != nil else { return nil }
        guard !AppPreferences.agentAllowSystemKeys else { return nil }
        return httpResponse(status: 403, body: """
        {"error":"permission_denied","message":"当前未允许外部 Agent 执行系统按键，请在奶黄包「Agent 端口」设置中开启。"}

        """)
    }

    private func disabledExternalToolResponse(toolId: String, headers: [String: String]) -> Data? {
        let preferenceId: String
        if toolId.hasPrefix("mcp.") || toolId.hasPrefix("cli.") {
            preferenceId = toolId
        } else {
            preferenceId = "\(toolSourceKind(from: headers)).\(toolId)"
        }
        guard !AppPreferences.isToolEnabled(id: preferenceId) else { return nil }
        let toolName = preferenceId.replacingOccurrences(of: "mcp.", with: "").replacingOccurrences(of: "cli.", with: "")
        return httpResponse(status: 403, body: """
        {"error":"tool_disabled","tool":"\(toolName)","preference_id":"\(preferenceId)","message":"该工具已被用户禁用，请在奶黄包首页「工具(MCP/SKILL/CLI)」中开启。"}

        """)
    }

    private func buildListInstalledAppsResponse() async -> Data {
        let text = await InstalledAppTool.listInstalledAppsText()
        if text.hasPrefix("{") {
            return httpResponse(status: 200, body: text + "\n")
        }
        let escaped = text
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
        return httpResponse(status: 503, body: "{\"error\":\"\(escaped)\"}\n")
    }

    private func buildOpenAppResponse(body: String) async -> Data {
        guard
            let data = body.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let packageOrName = object["package_or_name"] as? String
        else {
            return httpResponse(status: 400, body: "{\"error\":\"missing package_or_name\"}\n")
        }
        let result = await InstalledAppTool.openApp(packageOrName: packageOrName)
        return await encodeJSON(["ok": result.hasPrefix("已打开应用"), "message": result])
    }

    private func buildTapScreenResponse(body: String) async -> Data {
        guard
            let data = body.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let x = numericValue(object["x"]),
            let y = numericValue(object["y"])
        else {
            return httpResponse(status: 400, body: "{\"error\":\"missing or invalid pixel x/y\"}\n")
        }
        guard let action = ScreenTapAction.parse(object["action"] as? String) else {
            return httpResponse(status: 400, body: "{\"error\":\"invalid action\",\"supported\":[\"tap\",\"double_tap\",\"long_press\"]}\n")
        }
        let result = await ScreenTapTool.tapAtPixel(
            x: x, y: y, action: action, connection: connection
        )
        return await encodeJSON(["ok": ScreenTapTool.isSuccessMessage(result), "message": result])
    }

    private func buildReadClipboardResponse() async -> Data {
        let payload = await ClipboardTool.readClipboard(connection: connection)
        return jsonResponse(payload: payload)
    }

    @MainActor
    private func buildWriteClipboardResponse(body: String) -> Data {
        guard
            let data = body.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let text = object["text"] as? String
        else {
            return httpResponse(status: 400, body: "{\"error\":\"missing text\"}\n")
        }
        return jsonResponse(payload: ClipboardTool.writeClipboard(text: text, connection: connection))
    }

    private func buildPressHomeResponse() async -> Data {
        let message = await PhoneControlTool.pressHome(connection: connection)
        return jsonResponse(payload: ["ok": message.contains("已成功"), "message": message])
    }

    private func buildPressBackResponse() async -> Data {
        let message = await PhoneControlTool.pressBack(connection: connection)
        return jsonResponse(payload: ["ok": message.contains("已成功"), "message": message])
    }

    @MainActor
    private func buildAgentStatusResponse() -> Data {
        let connected = connection?.isConnected ?? false
        let tools = CustardToolRegistry.allTools
            .filter { $0.kind == .cli || $0.kind == .mcp }
            .map { tool -> [String: Any] in
                [
                    "id": tool.id,
                    "name": tool.name,
                    "kind": tool.kind.rawValue,
                    "enabled": AppPreferences.isToolEnabled(id: tool.id)
                ]
            }
        return encodeJSON([
            "agent_api_enabled": AppPreferences.agentApiEnabled,
            "phone_connected": connected,
            "port": Int(Self.defaultPort),
            "bind_address": "127.0.0.1",
            "allow_write": AppPreferences.agentAllowWrite,
            "allow_system_keys": AppPreferences.agentAllowSystemKeys,
            "skill_repository": AgentPortPaths.skillGitHubWebURL,
            "tools": tools
        ])
    }

    private func buildTypeTextResponse(body: String) async -> Data {
        guard
            let data = body.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let text = object["text"] as? String
        else {
            return httpResponse(status: 400, body: "{\"error\":\"missing text\"}\n")
        }
        let payload = await KeyboardInputTool.typeText(text, connection: connection)
        return jsonResponse(payload: payload)
    }

    private func buildScreenResponse(includeScreenshot: Bool) async -> Data {
        let disconnected = await MainActor.run {
            connection == nil || connection?.isConnected == false
        }
        if disconnected {
            return httpResponse(status: 503, body: "{\"error\":\"screen sharing not connected\"}\n")
        }
        guard let connection else {
            return httpResponse(status: 503, body: "{\"error\":\"screen sharing not connected\"}\n")
        }
        let json = await ScreenCaptureTool.captureJSON(
            connection: connection,
            includeScreenshot: includeScreenshot || AppPreferences.operationImageEnabled
        )
        guard let data = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted]),
              let body = String(data: data, encoding: .utf8) else {
            return httpResponse(status: 500, body: "{\"error\":\"encode failed\"}\n")
        }
        return httpResponse(status: 200, body: body + "\n")
    }

    private func buildScreenPNGResponse() async -> Data {
        let disconnected = await MainActor.run {
            connection == nil || connection?.isConnected == false
        }
        if disconnected {
            return httpResponse(status: 503, body: "{\"error\":\"screen sharing not connected\"}\n")
        }
        guard let connection else {
            return httpResponse(status: 503, body: "{\"error\":\"screen sharing not connected\"}\n")
        }
        guard let pngData = await ScreenCaptureTool.capturePNGData(connection: connection) else {
            return httpResponse(status: 503, body: "{\"error\":\"screenshot_unavailable\"}\n")
        }
        return pngResponse(data: pngData)
    }

    @MainActor
    private func encodeJSON(_ payload: [String: Any], status: Int = 200) -> Data {
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted]),
              let json = String(data: data, encoding: .utf8) else {
            return httpResponse(status: 500, body: "{\"error\":\"encode failed\"}\n")
        }
        return httpResponse(status: status, body: json + "\n")
    }

    private func jsonResponse(payload: [String: Any]) -> Data {
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted]),
              let json = String(data: data, encoding: .utf8) else {
            return httpResponse(status: 500, body: "{\"error\":\"encode failed\"}\n")
        }
        let ok = payload["ok"] as? Bool ?? false
        return httpResponse(status: ok ? 200 : 503, body: json + "\n")
    }

    private func numericValue(_ value: Any?) -> Double? {
        if let number = value as? Double { return number }
        if let number = value as? Int { return Double(number) }
        if let text = value as? String, let number = Double(text) { return number }
        return nil
    }

    private func httpBody(from data: Data) -> String {
        guard let text = String(data: data, encoding: .utf8),
              let range = text.range(of: "\r\n\r\n") else { return "" }
        return String(text[range.upperBound...])
    }

    private func parseHTTPHeaders(from data: Data) -> [String: String] {
        guard let text = String(data: data, encoding: .utf8) else { return [:] }
        var headers: [String: String] = [:]
        for line in text.split(separator: "\r\n").dropFirst() {
            if line.isEmpty { break }
            let parts = line.split(separator: ":", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            headers[parts[0].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()]
                = parts[1].trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return headers
    }

    private func readHTTPRequest(from conn: NWConnection) async -> Data? {
        await withCheckedContinuation { continuation in
            var buffer = Data()
            conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, _, error in
                if let data { buffer.append(data) }
                if error != nil || buffer.contains(Data("\r\n\r\n".utf8)) {
                    continuation.resume(returning: buffer.isEmpty ? nil : buffer)
                } else {
                    conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { _, _, _, _ in
                        continuation.resume(returning: buffer.isEmpty ? nil : buffer)
                    }
                }
            }
        }
    }

    private func httpStatus(from response: Data) -> Int? {
        guard let text = String(data: response, encoding: .utf8),
              text.hasPrefix("HTTP/1.1 ") else { return nil }
        return Int(text.dropFirst("HTTP/1.1 ".count).prefix(3))
    }

    private func httpResponse(status: Int, body: String) -> Data {
        let statusText: String
        switch status {
        case 200: statusText = "OK"
        case 401: statusText = "Unauthorized"
        case 403: statusText = "Forbidden"
        case 429: statusText = "Too Many Requests"
        case 503: statusText = "Service Unavailable"
        default: statusText = "Error"
        }
        let bodyData = Data(body.utf8)
        // 勿用多行 """：最后一行不会附带换行，会把头尾结束符弄成 `\r\n\r`，curl 报 Content-Length 差 2。
        var header = "HTTP/1.1 \(status) \(statusText)\r\n"
        header += "Content-Type: application/json; charset=utf-8\r\n"
        header += "Access-Control-Allow-Origin: *\r\n"
        header += "Content-Length: \(bodyData.count)\r\n"
        header += "Connection: close\r\n\r\n"
        var response = Data(header.utf8)
        response.append(bodyData)
        return response
    }

    private func pngResponse(data: Data) -> Data {
        var header = "HTTP/1.1 200 OK\r\n"
        header += "Content-Type: image/png\r\n"
        header += "Content-Length: \(data.count)\r\n"
        header += "Connection: close\r\n\r\n"
        var response = Data(header.utf8)
        response.append(data)
        return response
    }
}
