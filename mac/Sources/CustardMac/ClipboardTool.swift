import Foundation

@MainActor
enum ClipboardTool {
    static func readClipboard(connection: ConnectionManager?) async -> [String: Any] {
        guard let connection, connection.isConnected else {
            return [
                "ok": false,
                "message": "屏幕共享未连接，无法读取手机剪贴板。请先启动奶黄包并连接手机。"
            ]
        }

        guard let text = await connection.requestClipboardText() else {
            return [
                "ok": false,
                "message": "读取手机剪贴板超时或失败。"
            ]
        }

        return [
            "ok": true,
            "text": text,
            "empty": text.isEmpty
        ]
    }

    static func writeClipboard(text: String, connection: ConnectionManager?) -> [String: Any] {
        guard let connection, connection.isConnected else {
            return [
                "ok": false,
                "message": "屏幕共享未连接，无法写入手机剪贴板。请先启动奶黄包并连接手机。"
            ]
        }

        connection.sendClipboardToDevice(text)
        return [
            "ok": true,
            "message": "已写入手机剪贴板。",
            "length": text.count
        ]
    }

    static func readClipboardText(connection: ConnectionManager?) async -> String {
        encodeJSON(await readClipboard(connection: connection))
    }

    static func writeClipboardText(text: String, connection: ConnectionManager?) -> String {
        encodeJSON(writeClipboard(text: text, connection: connection))
    }

    private static func encodeJSON(_ object: [String: Any]) -> String {
        guard
            let data = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted]),
            let json = String(data: data, encoding: .utf8)
        else {
            return "{\"ok\":false,\"message\":\"encode failed\"}"
        }
        return json
    }
}
