import Foundation

enum AgentScreenshotStore {
    static var latestScreenURL: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = caches.appendingPathComponent("CustardMac", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("latest-screen.png")
    }

    static func savePNG(_ data: Data) -> String? {
        let url = latestScreenURL
        do {
            try data.write(to: url, options: .atomic)
            return url.path
        } catch {
            Logger.chat("AgentScreenshotStore save failed: \(error.localizedDescription)")
            return nil
        }
    }
}
