import Foundation

/// 当 ADB 不可用时，将常见 `adb shell input` 命令转为屏幕共享 socket 协议执行。
@MainActor
enum SocketCommandExecutor {
    static func execute(
        _ commandLine: String,
        connection: ConnectionManager
    ) -> AdbManager.ExecutionResult? {
        guard let action = parseInputCommand(commandLine) else { return nil }

        Logger.info("socket fallback: \(commandLine) -> \(action)")
        switch action {
        case .tap(let x, let y):
            connection.tap(x: x, y: y)
            return success(commandLine, "已通过屏幕共享通道模拟点击 (\(Int(x)), \(Int(y)))")

        case .swipe(let x1, let y1, let x2, let y2, let durationMs):
            connection.swipe(x1: x1, y1: y1, x2: x2, y2: y2, durationMs: durationMs)
            return success(
                commandLine,
                "已通过屏幕共享通道模拟滑动 (\(Int(x1)),\(Int(y1)))→(\(Int(x2)),\(Int(y2)))"
            )

        case .text(let text):
            connection.sendText(text)
            return success(commandLine, "已通过屏幕共享通道输入文本")

        case .keyEvent(let keyCode):
            connection.tapAndroidKey(keyCode)
            return success(commandLine, "已通过屏幕共享通道发送按键 keycode=\(keyCode)")
        }
    }

    static func canHandle(_ commandLine: String) -> Bool {
        parseInputCommand(commandLine) != nil
    }

    private enum InputAction {
        case tap(x: Float, y: Float)
        case swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int)
        case text(String)
        case keyEvent(Int)
    }

    private static func parseInputCommand(_ commandLine: String) -> InputAction? {
        let trimmed = commandLine.trimmingCharacters(in: .whitespacesAndNewlines)
        let args: [String]
        if trimmed.hasPrefix("adb ") {
            args = AdbManager.parseArgumentsPublic(String(trimmed.dropFirst(4)))
        } else {
            return nil
        }

        guard args.count >= 2, args[0] == "shell", args[1] == "input" else { return nil }

        switch args[2] {
        case "tap":
            guard args.count >= 5,
                  let x = Float(args[3]),
                  let y = Float(args[4]) else { return nil }
            return .tap(x: x, y: y)

        case "swipe":
            guard args.count >= 7,
                  let x1 = Float(args[3]),
                  let y1 = Float(args[4]),
                  let x2 = Float(args[5]),
                  let y2 = Float(args[6]) else { return nil }
            let duration = args.count >= 8 ? (Int(args[7]) ?? 300) : 300
            return .swipe(x1: x1, y1: y1, x2: x2, y2: y2, durationMs: duration)

        case "text":
            guard args.count >= 4 else { return nil }
            let raw = args[3...].joined(separator: " ")
            return .text(decodeInputText(raw))

        case "keyevent":
            guard args.count >= 4, let code = parseKeyCode(args[3]) else { return nil }
            return .keyEvent(code)

        default:
            return nil
        }
    }

    private static func decodeInputText(_ raw: String) -> String {
        var text = raw
        if (text.hasPrefix("\"") && text.hasSuffix("\""))
            || (text.hasPrefix("'") && text.hasSuffix("'")) {
            text = String(text.dropFirst().dropLast())
        }
        return text
            .replacingOccurrences(of: "%s", with: " ")
            .replacingOccurrences(of: "%S", with: " ")
    }

    private static func parseKeyCode(_ token: String) -> Int? {
        if let value = Int(token) { return value }
        let upper = token.uppercased()
        let map: [String: Int] = [
            "KEYCODE_HOME": 3,
            "KEYCODE_BACK": 4,
            "KEYCODE_ENTER": 66,
            "KEYCODE_DEL": 67,
            "KEYCODE_MENU": 82,
        ]
        return map[upper]
    }

    private static func success(_ command: String, _ message: String) -> AdbManager.ExecutionResult {
        AdbManager.ExecutionResult(
            command: command,
            output: message,
            exitCode: 0,
            blocked: false
        )
    }
}
