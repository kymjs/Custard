import Foundation

enum ScreenTapAction: String, CaseIterable {
    case tap
    case doubleTap = "double_tap"
    case longPress = "long_press"

    var displayName: String {
        switch self {
        case .tap: return "点击"
        case .doubleTap: return "双击"
        case .longPress: return "长按"
        }
    }

    static func parse(_ raw: String?) -> ScreenTapAction? {
        guard let raw else { return .tap }
        let normalized = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch normalized {
        case "tap", "click", "single_tap":
            return .tap
        case "double_tap", "double_click", "double":
            return .doubleTap
        case "long_press", "long":
            return .longPress
        default:
            return nil
        }
    }

    static var supportedValuesDescription: String {
        allCases.map(\.rawValue).joined(separator: " / ")
    }
}

enum ScreenTapTool {
    private static let longPressDurationMs = 1000
    private static let doubleTapIntervalNanoseconds: UInt64 = 100_000_000

    static func tapAtPixel(
        x: Double,
        y: Double,
        action: ScreenTapAction = .tap,
        connection: ConnectionManager?
    ) async -> String {
        guard let (width, height) = await resolveScreenSize(connection: connection) else {
            return "无法获取屏幕分辨率。请确保奶黄包已连接手机（屏幕共享或 ADB）。"
        }

        guard x >= 0, x < Double(width), y >= 0, y < Double(height) else {
            return "像素坐标超出屏幕范围 \(width)×\(height)。当前 x=\(x), y=\(y)"
        }
        let clampedX = Int(x.rounded())
        let clampedY = Int(y.rounded())
        Logger.info(
            "tap_screen px=(\(clampedX),\(clampedY)) size=\(width)x\(height) action=\(action.rawValue)"
        )

        switch await perform(action: action, x: clampedX, y: clampedY, connection: connection) {
        case .success:
            return """
            已\(action.displayName)屏幕像素坐标 (\(clampedX), \(clampedY))，\
            屏幕分辨率 \(width)×\(height)。
            """
        case .failure(let message):
            return "\(action.displayName)失败：\(message)"
        }
    }

    static func isSuccessMessage(_ message: String) -> Bool {
        message.hasPrefix("已点击")
            || message.hasPrefix("已双击")
            || message.hasPrefix("已长按")
    }

    private enum PerformResult {
        case success
        case failure(String)
    }

    private static func perform(
        action: ScreenTapAction,
        x: Int,
        y: Int,
        connection: ConnectionManager?
    ) async -> PerformResult {
        switch action {
        case .tap:
            if let connection, await MainActor.run(body: { connection.isConnected }) {
                await MainActor.run { connection.tap(x: Float(x), y: Float(y)) }
                Logger.info("tap_screen via screen_share (\(x), \(y))")
                return .success
            }
            return await runCommand("adb shell input tap \(x) \(y)", connection: connection)
        case .doubleTap:
            if let connection, await MainActor.run(body: { connection.isConnected }) {
                await MainActor.run { connection.tap(x: Float(x), y: Float(y)) }
                try? await Task.sleep(nanoseconds: doubleTapIntervalNanoseconds)
                await MainActor.run { connection.tap(x: Float(x), y: Float(y)) }
                Logger.info("double_tap via screen_share (\(x), \(y))")
                return .success
            }
            switch await runCommand("adb shell input tap \(x) \(y)", connection: connection) {
            case .failure(let message):
                return .failure(message)
            case .success:
                break
            }
            try? await Task.sleep(nanoseconds: doubleTapIntervalNanoseconds)
            return await runCommand("adb shell input tap \(x) \(y)", connection: connection)
        case .longPress:
            if let connection, await MainActor.run(body: { connection.isConnected }) {
                await MainActor.run {
                    connection.swipe(
                        x1: Float(x), y1: Float(y),
                        x2: Float(x), y2: Float(y),
                        durationMs: longPressDurationMs
                    )
                }
                Logger.info("long_press via screen_share (\(x), \(y))")
                return .success
            }
            return await runCommand(
                "adb shell input swipe \(x) \(y) \(x) \(y) \(longPressDurationMs)",
                connection: connection
            )
        }
    }

    private static func runCommand(
        _ command: String,
        connection: ConnectionManager?
    ) async -> PerformResult {
        let results = await AdbManager.executeCommandsAsync([command], connection: connection)
        guard let result = results.first else {
            return .failure("无执行结果")
        }
        if result.succeeded {
            return .success
        }
        return .failure("exit=\(result.exitCode)，\(result.output)")
    }

    private static func resolveScreenSize(connection: ConnectionManager?) async -> (Int, Int)? {
        if let size = await MainActor.run(body: { () -> (Int, Int)? in
            guard let info = connection?.deviceInfo, info.width > 0, info.height > 0 else {
                return nil
            }
            return (info.width, info.height)
        }) {
            return size
        }
        return await Task.detached(priority: .userInitiated) {
            do {
                let result = try AdbManager.executeCommandLine("adb shell wm size")
                guard result.succeeded else { return nil as (Int, Int)? }
                return parseWmSizeOutput(result.output)
            } catch {
                return nil
            }
        }.value
    }

    private static func parseWmSizeOutput(_ output: String) -> (Int, Int)? {
        var lastMatch: (Int, Int)?
        for line in output.split(whereSeparator: \.isNewline) {
            if let size = extractSize(from: String(line)) {
                lastMatch = size
            }
        }
        return lastMatch
    }

    private static func extractSize(from line: String) -> (Int, Int)? {
        let pattern = #"(\d+)\s*[x×]\s*(\d+)"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        guard let match = regex.firstMatch(in: line, range: range),
              match.numberOfRanges >= 3,
              let widthRange = Range(match.range(at: 1), in: line),
              let heightRange = Range(match.range(at: 2), in: line),
              let width = Int(line[widthRange]),
              let height = Int(line[heightRange]),
              width > 0, height > 0 else {
            return nil
        }
        return (width, height)
    }
}
