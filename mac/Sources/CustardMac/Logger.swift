import Foundation

enum Logger {
    private static let logURL: URL = {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Desktop/custard.log")
    }()

    private static let queue = DispatchQueue(label: "com.kymjs.custard.logger")
    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    static var filePath: String { logURL.path }

    /// 按「在桌面展示日志文件」开关同步：关闭时删除已有 ~/Desktop/custard.log；开启时每次启动清空该文件。
    static func applyDesktopLogPreference() {
        queue.sync {
            guard FileManager.default.fileExists(atPath: logURL.path) else { return }
            if AppPreferences.logEnabled {
                if let handle = try? FileHandle(forWritingTo: logURL) {
                    try? handle.truncate(atOffset: 0)
                    try? handle.close()
                }
            } else {
                try? FileManager.default.removeItem(at: logURL)
            }
        }
    }

    /// Chat / LLM 调试日志；仅在开启桌面日志时写入 ~/Desktop/custard.log 并 print。
    static func chat(_ message: String) {
        write("CHAT", message)
    }

    static func info(_ message: String) {
        write("INFO", message)
    }

    static func warn(_ message: String) {
        write("WARN", message)
    }

    static func error(_ message: String) {
        write("ERROR", message)
    }

    static func error(_ error: Error, context: String = "") {
        let prefix = context.isEmpty ? "" : "\(context): "
        write("ERROR", "\(prefix)\(error)")
        if let localized = error as? LocalizedError, let desc = localized.errorDescription {
            write("ERROR", "  detail: \(desc)")
        }
    }

    private static func write(_ level: String, _ message: String) {
        guard AppPreferences.logEnabled else { return }

        let timestamp = dateFormatter.string(from: Date())
        let line = "[\(timestamp)] [\(level)] \(message)\n"
        let writeBlock = {
            if let data = line.data(using: .utf8) {
                if FileManager.default.fileExists(atPath: logURL.path) {
                    if let handle = try? FileHandle(forWritingTo: logURL) {
                        handle.seekToEndOfFile()
                        handle.write(data)
                        try? handle.close()
                    }
                } else {
                    try? data.write(to: logURL)
                }
            }
        }
        if level == "WARN" || level == "ERROR" {
            queue.sync(execute: writeBlock)
        } else {
            queue.async(execute: writeBlock)
        }
        if level == "CHAT" || level == "WARN" || level == "ERROR" {
            print(line, terminator: "")
        } else {
            #if DEBUG
            print(line, terminator: "")
            #endif
        }
    }
}
