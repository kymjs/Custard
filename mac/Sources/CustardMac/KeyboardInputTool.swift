import Foundation

enum KeyboardInputTool {
    private static let custardPackage = "com.kymjs.custard"
    private static let custardIME = "com.kymjs.custard/.AdbIME"

    static func typeText(_ text: String, connection: ConnectionManager?) async -> [String: Any] {
        guard !text.isEmpty else {
            return [
                "ok": false,
                "message": "请提供要输入的文本内容。"
            ]
        }

        let hasNonAscii = text.unicodeScalars.contains(where: { $0.value > 127 })

        // 1. ASCII：优先 adb shell input text（最快，无额外依赖）
        if !hasNonAscii, let adbResult = await attemptAdbInput(text, connection: connection) {
            return adbResult
        }

        // 2. 中文 / ASCII 失败：奶黄包内置 IME 广播（Android 16+ 需 -p 显式包名）
        if let keyboardResult = await attemptCustardImeInput(text, connection: connection) {
            return keyboardResult
        }

        // 3. 回退：剪贴板 + 输入框长按菜单粘贴（需屏幕共享连接）
        if let connection, await MainActor.run(body: { connection.isConnected }) {
            Logger.info("type_text custard ime unavailable, trying clipboard+paste len=\(text.count)")
            return await attemptClipboardPaste(text, connection: connection)
        }

        if hasNonAscii {
            return [
                "ok": false,
                "message": "中文输入失败：请确认奶黄包已安装并通过 ADB 连接，或连接奶黄包后重试长按粘贴。"
            ]
        }

        return [
            "ok": false,
            "message": "ADB 输入失败，且未连接屏幕共享。请确认手机已通过 ADB 连接并安装奶黄包，或先连接奶黄包后再试。"
        ]
    }

    private static func attemptAdbInput(
        _ text: String,
        connection: ConnectionManager?
    ) async -> [String: Any]? {
        let escaped = escapeForAdbInput(text)
        let command = "adb shell input text \(escaped)"
        let results = await AdbManager.executeCommandsAsync([command], connection: connection)
        guard let result = results.first else {
            Logger.warn("type_text adb no result len=\(text.count)")
            return nil
        }
        guard result.succeeded else {
            Logger.warn("type_text adb failed exit=\(result.exitCode) len=\(text.count) output=\(result.output)")
            return nil
        }
        Logger.info("type_text via adb len=\(text.count)")
        return [
            "ok": true,
            "message": "已通过 ADB 输入文本（\(text.count) 字符）。",
            "length": text.count,
            "channel": "adb"
        ]
    }

    private static func attemptCustardImeInput(
        _ text: String,
        connection: ConnectionManager?
    ) async -> [String: Any]? {
        let setupCommands = [
            "adb shell settings put secure show_ime_with_hard_keyboard 1",
            "adb shell ime enable \(custardIME)",
            "adb shell ime set \(custardIME)"
        ]
        for command in setupCommands {
            let results = await AdbManager.executeCommandsAsync([command], connection: connection)
            guard let result = results.first, result.succeeded else {
                Logger.warn("type_text custard ime setup failed command=\(command)")
                return nil
            }
        }

        // 等待 IME 与输入框建立 InputConnection，防止吞字
        try? await Task.sleep(nanoseconds: 1_000_000_000)

        let base64 = Data(text.utf8).base64EncodedString()
        let command = "adb shell am broadcast -a ADB_INPUT_B64 --es msg \(base64) -p \(custardPackage)"
        let results = await AdbManager.executeCommandsAsync([command], connection: connection)
        guard let result = results.first, result.succeeded else {
            Logger.warn("type_text custard ime broadcast failed len=\(text.count)")
            return nil
        }

        Logger.info("type_text via custard_ime len=\(text.count) output=\(result.output)")
        return [
            "ok": true,
            "message": "已通过奶黄包内置输入法输入文本（\(text.count) 字符）。请确认已用 tap_screen 聚焦目标输入框。",
            "length": text.count,
            "channel": "custard_ime"
        ]
    }

    private static func attemptClipboardPaste(
        _ text: String,
        connection: ConnectionManager
    ) async -> [String: Any] {
        await MainActor.run { connection.sendClipboardToDevice(text) }
        try? await Task.sleep(nanoseconds: 400_000_000)
        guard let result = await connection.sendPasteAndAwaitResult(timeoutSeconds: 5) else {
            Logger.warn("type_text clipboard+paste timeout len=\(text.count)")
            return [
                "ok": false,
                "message": "粘贴超时：未收到手机端确认。请先用 tap_screen 点击输入框，并确认奶黄包无障碍权限已开启。",
                "length": text.count,
                "channel": "clipboard_paste"
            ]
        }
        Logger.info("type_text via clipboard+paste ok=\(result.0) len=\(text.count) detail=\(result.1)")
        return [
            "ok": result.0,
            "message": result.0
                ? "ADB 输入未生效，已改用剪贴板并在输入框长按菜单中粘贴（\(text.count) 字符）。"
                : "粘贴失败：\(result.1)。请先用 tap_screen 选中目标输入框，重新获取输入框坐标后，再次调用 tap_screen 长按输入框，然后选择菜单中的粘贴选项。",
            "length": text.count,
            "channel": "clipboard_paste"
        ]
    }

    static func typeTextJSON(_ text: String, connection: ConnectionManager?) async -> String {
        guard
            let data = try? JSONSerialization.data(
                withJSONObject: await typeText(text, connection: connection),
                options: [.prettyPrinted]
            ),
            let json = String(data: data, encoding: .utf8)
        else {
            return "{\"ok\":false,\"message\":\"encode failed\"}"
        }
        return json
    }

    private static func escapeForAdbInput(_ text: String) -> String {
        var result = ""
        for character in text {
            switch character {
            case " ": result += "%s"
            case "%": result += "%"
            case "\\": result += "\\\\"
            case "'": result += "\\'"
            case "\"": result += "\\\""
            case "&": result += "\\&"
            case "|": result += "\\|"
            case ";": result += "\\;"
            case "<": result += "\\<"
            case ">": result += "\\>"
            case "(": result += "\\("
            case ")": result += "\\)"
            case "{": result += "\\{"
            case "}": result += "\\}"
            default: result.append(character)
            }
        }
        return result
    }
}
