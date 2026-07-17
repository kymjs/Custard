import AppKit
import Foundation
import SwiftUI

struct AppUpdateConfig: Decodable {
    let latestRelease: String
    let latestVersion: String
    let downloadUrl: String
    let upgradeText: String
    let forceUpgradeText: String
}

enum AppUpdateKind {
    case optional
    case forced
}

struct AppUpdatePrompt: Identifiable {
    let id = UUID()
    let kind: AppUpdateKind
    let message: String
    let downloadURL: URL
}

enum AppUpdateChecker {
    static let configURL = URL(string: "https://cdn.kymjs.com:8843/custard/config.json")!

    static var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    static func fetchPromptIfNeeded() async -> AppUpdatePrompt? {
        do {
            var request = URLRequest(url: configURL)
            request.timeoutInterval = 15
            request.cachePolicy = .reloadIgnoringLocalCacheData
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                Logger.info("AppUpdate: bad status=\((response as? HTTPURLResponse)?.statusCode ?? -1)")
                return nil
            }
            let config = try JSONDecoder().decode(AppUpdateConfig.self, from: data)
            return prompt(for: config, currentVersion: currentVersion)
        } catch {
            Logger.info("AppUpdate: fetch failed \(error.localizedDescription)")
            return nil
        }
    }

    static func prompt(for config: AppUpdateConfig, currentVersion: String) -> AppUpdatePrompt? {
        guard let downloadURL = URL(string: config.downloadUrl.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = downloadURL.scheme?.lowercased(),
              scheme == "http" || scheme == "https"
        else {
            Logger.info("AppUpdate: invalid downloadUrl")
            return nil
        }

        if compareVersions(currentVersion, config.latestVersion) == .orderedAscending {
            return AppUpdatePrompt(
                kind: .forced,
                message: normalizedUpgradeText(config.forceUpgradeText),
                downloadURL: downloadURL
            )
        }

        if compareVersions(currentVersion, config.latestRelease) == .orderedAscending {
            return AppUpdatePrompt(
                kind: .optional,
                message: normalizedUpgradeText(config.upgradeText),
                downloadURL: downloadURL
            )
        }

        return nil
    }

    /// 将字面量 `\n` 与已解码换行统一为换行，供弹窗多行展示。
    static func normalizedUpgradeText(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\\n", with: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 按数字段比较版本号，如 `1.0.5` vs `1.1.11`。无法解析的段按 0 处理。
    static func compareVersions(_ lhs: String, _ rhs: String) -> ComparisonResult {
        let left = versionComponents(lhs)
        let right = versionComponents(rhs)
        let count = max(left.count, right.count)
        for index in 0..<count {
            let a = index < left.count ? left[index] : 0
            let b = index < right.count ? right[index] : 0
            if a < b { return .orderedAscending }
            if a > b { return .orderedDescending }
        }
        return .orderedSame
    }

    private static func versionComponents(_ version: String) -> [Int] {
        version
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: ".")
            .map { segment in
                let digits = segment.prefix(while: { $0.isNumber })
                return Int(digits) ?? 0
            }
    }

    static func openDownload(url: URL) {
        NSWorkspace.shared.open(url)
    }
}

struct AppUpdateOverlay: View {
    @Environment(\.custardPalette) private var palette
    let prompt: AppUpdatePrompt
    let onUpgrade: () -> Void
    let onDismiss: (() -> Void)?

    var body: some View {
        ZStack {
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                // 吞掉点击，防止点穿到下方；无 onTap 故无法点外侧关闭。
                .onTapGesture {}

            VStack(alignment: .leading, spacing: 16) {
                Text(prompt.kind == .forced ? "必须更新" : "发现新版本")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(palette.onSurface)

                Text(prompt.message)
                    .font(.body)
                    .foregroundStyle(palette.secondaryText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)

                HStack {
                    Spacer()
                    if let onDismiss {
                        Button("稍后再说") {
                            onDismiss()
                        }
                        .keyboardShortcut(.cancelAction)
                    }
                    Button("立即更新") {
                        onUpgrade()
                    }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
            .frame(maxWidth: 420)
            .background(palette.surface)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(palette.divider, lineWidth: 1)
            )
            .padding(32)
        }
        .allowsHitTesting(true)
    }
}
