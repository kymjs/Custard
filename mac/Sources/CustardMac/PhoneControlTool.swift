import Foundation

enum PhoneControlTool {
    private static let homeKeyCode = 3
    private static let backKeyCode = 4

    static func pressHome(connection: ConnectionManager?) async -> String {
        await executeKeyEvent(keyCode: homeKeyCode, label: "Home", connection: connection)
    }

    static func pressBack(connection: ConnectionManager?) async -> String {
        await executeKeyEvent(keyCode: backKeyCode, label: "Back", connection: connection)
    }

    private static func executeKeyEvent(
        keyCode: Int,
        label: String,
        connection: ConnectionManager?
    ) async -> String {
        let command = "adb shell input keyevent \(keyCode)"
        let results = await AdbManager.executeCommandsAsync([command], connection: connection)
        guard let result = results.first else {
            return "执行 \(label) 键失败：无执行结果。"
        }
        if result.succeeded {
            return "已成功按下 \(label) 键。\(result.output)"
        }
        return "按下 \(label) 键失败（exit=\(result.exitCode)）：\(result.output)"
    }
}
