import Foundation

enum AgentAuditLog {
    private static let queue = DispatchQueue(label: "custard.agent.audit", qos: .utility)

    static func record(method: String, path: String, status: Int, tokenPrefix: String?) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
        let tokenPart = tokenPrefix.map { " token=\($0)..." } ?? ""
        let line = "[\(timestamp)] \(method) \(path) status=\(status)\(tokenPart)\n"
        Logger.info("AgentAPI \(method) \(path) status=\(status)\(tokenPart)")
        queue.async {
            guard let url = logFileURL else { return }
            if !FileManager.default.fileExists(atPath: url.path) {
                FileManager.default.createFile(atPath: url.path, contents: nil)
            }
            guard let handle = try? FileHandle(forWritingTo: url) else { return }
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            if let data = line.data(using: .utf8) {
                try? handle.write(contentsOf: data)
            }
        }
    }

    private static var logFileURL: URL? {
        guard let dir = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first else {
            return nil
        }
        let folder = dir.appendingPathComponent("Logs/CustardMac", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent("agent-api.log")
    }
}
