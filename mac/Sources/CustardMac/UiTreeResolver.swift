import Foundation

struct UiTreeResolveResult {
    let text: String?
    let source: String
}

enum UiTreeResolver {
    static func resolve(
        connection: ConnectionManager?,
        cachedTree: String?,
        preferAdbFallback: Bool = true,
        accessibilityTimeoutSeconds: Double = 5,
        retryAccessibility: Bool = true
    ) async -> UiTreeResolveResult {
        var parts: [String] = []
        var sources: [String] = []

        if let connection {
            var accessibilityTree = await connection.requestAccessibilityUiTree(
                timeoutSeconds: accessibilityTimeoutSeconds
            )
            if accessibilityTree == nil, retryAccessibility {
                try? await Task.sleep(nanoseconds: 200_000_000)
                accessibilityTree = await connection.requestAccessibilityUiTree(
                    timeoutSeconds: accessibilityTimeoutSeconds
                )
            }
            if let accessibilityTree, !accessibilityTree.isEmpty {
                parts.append(accessibilityTree)
                sources.append("accessibility")
            }
        }

        if preferAdbFallback {
            let useUiautomator = AppPreferences.uiautomatorUiTreeEnabled
                || AppPreferences.accessibilityUiTreeEnabled
            if useUiautomator,
               let uiautomatorTree = await AdbManager.fetchUiHierarchySummaryAsync(timeoutSeconds: 5) {
                parts.append(uiautomatorTree)
                sources.append("uiautomator")
            }
        }

        if parts.isEmpty, let cachedTree, !cachedTree.isEmpty {
            let cleaned = stripSectionPrefix(from: cachedTree)
            if !cleaned.isEmpty {
                Logger.chat("UiTreeResolver: source=cache len=\(cleaned.count)")
                return UiTreeResolveResult(text: cleaned, source: "cache")
            }
        }

        let merged = parts.joined(separator: "\n")
        guard !merged.isEmpty else {
            return UiTreeResolveResult(text: nil, source: "none")
        }
        Logger.chat(
            "UiTreeResolver: source=\(sources.joined(separator: "+")) len=\(merged.count)"
        )
        return UiTreeResolveResult(text: merged, source: sources.joined(separator: "+"))
    }

    private static func stripSectionPrefix(from section: String) -> String {
        for prefix in ["无障碍 UI 树:\n", "uiautomator UI 树:\n"] {
            if section.hasPrefix(prefix) {
                return String(section.dropFirst(prefix.count))
            }
        }
        return section
    }
}
