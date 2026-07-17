import Foundation

enum InstalledAppTool {
    struct AppInfo: Codable, Equatable {
        let packageName: String
        let label: String
    }

    struct AppListPayload: Codable {
        let apps: [AppInfo]
        let count: Int
    }

    private enum ToolError: Error {
        case message(String)
    }

    private static func fetchInstalledApps() async -> Result<AppListPayload, ToolError> {
        await Task.detached(priority: .userInitiated) {
            do {
                let result = try AdbManager.executeCommandLine("adb shell pm list packages -3 -l")
                guard result.succeeded else {
                    return .failure(.message("获取应用列表失败（exit=\(result.exitCode)）：\(result.output)"))
                }
                let apps = parsePackageListOutput(result.output)
                return .success(AppListPayload(apps: apps, count: apps.count))
            } catch {
                return .failure(.message(error.localizedDescription))
            }
        }.value
    }

    static func listInstalledAppsText() async -> String {
        switch await fetchInstalledApps() {
        case .success(let payload):
            guard let data = try? JSONEncoder().encode(payload),
                  let json = String(data: data, encoding: .utf8) else {
                return formatAppsAsText(payload.apps)
            }
            return json
        case .failure(let error):
            if case .message(let message) = error {
                return message
            }
            return "获取应用列表失败。"
        }
    }

    static func openApp(packageOrName: String) async -> String {
        let query = packageOrName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            return "请提供要打开的应用包名或名称。"
        }

        switch await fetchInstalledApps() {
        case .failure(let error):
            if case .message(let message) = error {
                return message
            }
            return "获取应用列表失败。"
        case .success(let payload):
            if let app = resolveApp(query: query, in: payload.apps) {
                return await launch(app: app)
            }

            let containsMatches = payload.apps.filter {
                $0.label.localizedCaseInsensitiveContains(query)
                    || $0.packageName.localizedCaseInsensitiveContains(query)
            }
            if containsMatches.count > 1 {
                let candidates = containsMatches.prefix(10).map { "- \($0.label) (\($0.packageName))" }
                return """
                找到多个匹配应用，请指定更精确的名称或直接使用包名：
                \(candidates.joined(separator: "\n"))
                """
            }
            return "未找到应用「\(query)」。请先用 list_installed_apps 查看已安装应用名称和包名。"
        }
    }

    private static func resolveApp(query: String, in apps: [AppInfo]) -> AppInfo? {
        if let exact = apps.first(where: { $0.packageName == query }) {
            return exact
        }

        let lowered = query.lowercased()
        let labelMatches = apps.filter { $0.label.lowercased() == lowered }
        if labelMatches.count == 1 { return labelMatches[0] }

        let containsMatches = apps.filter {
            $0.label.localizedCaseInsensitiveContains(query)
                || $0.packageName.localizedCaseInsensitiveContains(query)
        }
        if containsMatches.count == 1 { return containsMatches[0] }
        return nil
    }

    private static func launch(app: AppInfo) async -> String {
        await Task.detached(priority: .userInitiated) {
            do {
                let command = "adb shell monkey -p \(app.packageName) -c android.intent.category.LAUNCHER 1"
                let result = try AdbManager.executeCommandLine(command)
                if result.succeeded {
                    return "已打开应用：\(app.label) (\(app.packageName))"
                }
                return "打开应用失败（exit=\(result.exitCode)）：\(result.output)"
            } catch {
                return "打开应用失败：\(error.localizedDescription)"
            }
        }.value
    }

    private static func parsePackageListOutput(_ output: String) -> [AppInfo] {
        output
            .split(whereSeparator: \.isNewline)
            .compactMap { line -> AppInfo? in
                let trimmed = String(line).trimmingCharacters(in: .whitespacesAndNewlines)
                guard trimmed.hasPrefix("package:") else { return nil }
                let rest = String(trimmed.dropFirst("package:".count))
                guard let spaceIndex = rest.firstIndex(of: " ") else {
                    let packageName = rest.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !packageName.isEmpty else { return nil }
                    return AppInfo(packageName: packageName, label: packageName)
                }
                let packageName = String(rest[..<spaceIndex]).trimmingCharacters(in: .whitespacesAndNewlines)
                let label = String(rest[rest.index(after: spaceIndex)...])
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard !packageName.isEmpty else { return nil }
                return AppInfo(packageName: packageName, label: label.isEmpty ? packageName : label)
            }
            .sorted { $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending }
    }

    private static func formatAppsAsText(_ apps: [AppInfo]) -> String {
        apps.map { "\($0.label) (\($0.packageName))" }.joined(separator: "\n")
    }
}
