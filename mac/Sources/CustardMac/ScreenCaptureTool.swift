import CoreGraphics
import Foundation

@MainActor
enum ScreenCaptureTool {
    static func capture(
        connection: ConnectionManager,
        includeScreenshot: Bool,
        collectUiSources: Bool = true,
        collectAdbUiSources: Bool? = nil,
        uiCaptureTimeoutSeconds: UInt64 = 12
    ) async -> ScreenCapturePayload {
        let start = Date()
        let shouldCollectAdb = collectAdbUiSources ?? collectUiSources
        Logger.chat(
            "get_screen: begin includeScreenshot=\(includeScreenshot) "
            + "collectUiSources=\(collectUiSources) collectAdbUiSources=\(shouldCollectAdb)"
        )

        let deviceInfo = connection.deviceInfo
        let snapshotImage = connection.currentImage
        let screenConnected = connection.isConnected
        let connectedViaAdb = connection.connectedViaAdb
        let isBlackScreen = isScreenCaptureBlocked(
            connection: connection,
            snapshotImage: snapshotImage
        )

        let shouldIncludeScreenshot = includeScreenshot
            && snapshotImage != nil
            && !isBlackScreen

        async let encodedScreenshot: (base64: String, path: String?)? = {
            guard shouldIncludeScreenshot, let snapshotImage else { return nil }
            return await encodeScreenshot(snapshotImage)
        }()

        var accessibilityTree: String?
        var accessibilityError: String?
        let forceUiTree = isBlackScreen
        let shouldCollectAccessibility = collectUiSources
            && (forceUiTree || AppPreferences.accessibilityUiTreeEnabled)

        if shouldCollectAccessibility {
            if connection.isConnected {
                if let tree = await connection.requestAccessibilityUiTree(timeoutSeconds: 5) {
                    accessibilityTree = tree
                } else {
                    try? await Task.sleep(nanoseconds: 200_000_000)
                    if let tree = await connection.requestAccessibilityUiTree(timeoutSeconds: 5) {
                        accessibilityTree = tree
                    } else {
                        accessibilityError = "请求超时或连接未就绪"
                    }
                }
            } else {
                accessibilityError = "屏幕共享未连接"
            }
        }

        Logger.chat(
            "get_screen: ui sources a11y=\(shouldCollectAccessibility) "
            + "viewDebug=\(shouldCollectAdb && (AppPreferences.viewDebugUiTreeEnabled || forceUiTree)) "
            + "uiautomator=\(shouldCollectAdb && (AppPreferences.uiautomatorUiTreeEnabled || forceUiTree)) "
            + "a11yTreeLen=\(accessibilityTree?.count ?? 0) blackScreen=\(isBlackScreen)"
        )

        let uiCapture: ScreenContextProvider.UiCaptureResult
        if collectUiSources {
            uiCapture = await captureUiSectionsWithTimeout(
                deviceInfo: deviceInfo,
                accessibilityTree: accessibilityTree,
                accessibilityError: accessibilityError,
                screenConnected: screenConnected,
                connectedViaAdb: connectedViaAdb,
                forceAllSources: forceUiTree,
                collectAdbSources: shouldCollectAdb,
                timeoutSeconds: uiCaptureTimeoutSeconds
            )
        } else {
            uiCapture = ScreenContextProvider.UiCaptureResult(
                sections: ["界面元素: 本次对话 Activity/UI 树采集已失败，已跳过后续采集。"],
                hasSuccessfulSource: false
            )
        }

        let encoded = await encodedScreenshot
        let screenshotBase64 = encoded?.base64
        let screenshotPath = encoded?.path

        var description = ScreenContextProvider.buildDescription(
            deviceInfo: deviceInfo,
            uiSections: uiCapture.sections,
            hasLiveFrame: snapshotImage != nil,
            includeScreenshot: includeScreenshot,
            screenshotAttached: screenshotBase64 != nil,
            screenCaptureBlocked: isBlackScreen
        )
        description = appendScreenshotPath(description, path: screenshotPath)

        let sectionKinds = [
            uiCapture.sections.contains(where: { $0.contains("无障碍") }) ? "a11y" : nil,
            uiCapture.sections.contains(where: { $0.contains("View Debug") }) ? "viewDebug" : nil,
            uiCapture.sections.contains(where: { $0.contains("uiautomator") }) ? "uiautomator" : nil
        ].compactMap { $0 }.joined(separator: ",")

        let hasSuccessfulUiSource = uiCapture.hasSuccessfulSource
            || (accessibilityTree?.isEmpty == false)

        Logger.chat(
            "get_screen: done elapsed=\(String(format: "%.2f", Date().timeIntervalSince(start)))s "
            + "textLen=\(description.count) screenshot=\(screenshotBase64 != nil) "
            + "path=\(screenshotPath ?? "nil") blackScreen=\(isBlackScreen) "
            + "uiKinds=[\(sectionKinds)] sections=\(uiCapture.sections.count) "
            + "uiSourceSuccess=\(hasSuccessfulUiSource)"
        )

        let packageName = packageNameFromUiSections(uiCapture.sections)
        let uiTreeSummary = mergedUiTreeSummary(
            sections: uiCapture.sections,
            accessibilityTree: accessibilityTree
        )
        return ScreenCapturePayload(
            description: description,
            imageBase64: screenshotBase64,
            screenshotPath: screenshotPath,
            hasSuccessfulUiSource: hasSuccessfulUiSource,
            packageName: packageName,
            screenWidth: deviceInfo?.width ?? 0,
            screenHeight: deviceInfo?.height ?? 0,
            uiTreeSummary: uiTreeSummary
        )
    }

    private static func mergedUiTreeSummary(
        sections: [String],
        accessibilityTree: String?
    ) -> String? {
        var parts: [String] = []
        if let accessibilityTree, !accessibilityTree.isEmpty {
            parts.append(accessibilityTree)
        }
        for section in sections {
            if section.hasPrefix("uiautomator UI 树:\n") {
                parts.append(String(section.dropFirst("uiautomator UI 树:\n".count)))
            }
        }
        if parts.isEmpty {
            return sections.first(where: {
                $0.contains("无障碍 UI 树:") || $0.contains("uiautomator UI 树:")
            })
        }
        return parts.joined(separator: "\n")
    }

    private static func packageNameFromUiSections(_ sections: [String]) -> String? {
        let prefix = "前台应用包名: "
        for section in sections {
            if section.hasPrefix(prefix) {
                let name = String(section.dropFirst(prefix.count))
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty { return name }
            }
            if let activityLine = section.split(separator: "\n").first(where: { $0.hasPrefix(prefix) }) {
                let name = String(activityLine.dropFirst(prefix.count))
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty { return name }
            }
        }
        if let activitySection = sections.first(where: { $0.hasPrefix("前台界面: ") }) {
            let activity = String(activitySection.dropFirst("前台界面: ".count))
            return TapCoordinateMatcher.packageName(fromForegroundActivity: activity)
        }
        return nil
    }

    static func captureJSON(
        connection: ConnectionManager,
        includeScreenshot: Bool
    ) async -> [String: Any] {
        let payload = await capture(connection: connection, includeScreenshot: includeScreenshot)
        var json: [String: Any] = [
            "description": payload.description,
            "has_screenshot": payload.imageBase64 != nil,
            "screen_capture_blocked": connection.isScreenCaptureBlocked
                || (connection.currentImage.map { BlackFrameDetector.isMostlyBlack($0) } ?? false)
        ]
        if let base64 = payload.imageBase64 {
            json["screenshot_base64"] = base64
        }
        if let path = payload.screenshotPath {
            json["screenshot_path"] = path
        }
        if let info = connection.deviceInfo {
            json["width"] = info.width
            json["height"] = info.height
        }
        json["connected"] = connection.isConnected
        return json
    }

    static func capturePNGData(connection: ConnectionManager) async -> Data? {
        guard let image = connection.currentImage else { return nil }
        if isScreenCaptureBlocked(connection: connection, snapshotImage: image) {
            return nil
        }
        return await Task.detached(priority: .userInitiated) {
            ScreenContextProvider.pngData(from: image)
        }.value
    }

    private static func isScreenCaptureBlocked(
        connection: ConnectionManager,
        snapshotImage: CGImage?
    ) -> Bool {
        if connection.isScreenCaptureBlocked { return true }
        guard let snapshotImage else { return false }
        return BlackFrameDetector.isMostlyBlack(snapshotImage)
    }

    private static func encodeScreenshot(_ image: CGImage) async -> (base64: String, path: String?)? {
        await Task.detached(priority: .userInitiated) {
            guard let png = ScreenContextProvider.pngData(from: image) else { return nil }
            let base64 = png.base64EncodedString()
            let path = AgentScreenshotStore.savePNG(png)
            return (base64, path)
        }.value
    }

    private static func appendScreenshotPath(_ description: String, path: String?) -> String {
        guard let path, !path.isEmpty else { return description }
        return description + "\nscreenshot_path: \(path)"
    }

    private static func captureUiSectionsWithTimeout(
        deviceInfo: DeviceInfo?,
        accessibilityTree: String?,
        accessibilityError: String?,
        screenConnected: Bool,
        connectedViaAdb: Bool,
        forceAllSources: Bool,
        collectAdbSources: Bool,
        timeoutSeconds: UInt64
    ) async -> ScreenContextProvider.UiCaptureResult {
        let captureStart = Date()
        let timeoutMessage = ScreenContextProvider.UiCaptureResult(
            sections: ["界面信息采集超时（ADB 响应较慢），已返回部分信息。"],
            hasSuccessfulSource: accessibilityTree?.isEmpty == false
        )

        return await withTaskGroup(of: ScreenContextProvider.UiCaptureResult.self) { group in
            group.addTask {
                let result = await ScreenContextProvider.captureUiSectionsAsync(
                    deviceInfo: deviceInfo,
                    accessibilityTree: accessibilityTree,
                    accessibilityError: accessibilityError,
                    screenConnected: screenConnected,
                    connectedViaAdb: connectedViaAdb,
                    forceAllSources: forceAllSources,
                    collectAdbSources: collectAdbSources
                )
                Logger.chat(
                    "get_screen ui capture sections=\(result.sections.count) "
                    + "elapsed=\(String(format: "%.2f", Date().timeIntervalSince(captureStart)))s"
                )
                return result
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: timeoutSeconds * 1_000_000_000)
                return timeoutMessage
            }

            let first = await group.next() ?? timeoutMessage
            group.cancelAll()
            if first.sections.first?.contains("界面信息采集超时") == true,
               accessibilityTree?.isEmpty == false {
                var sections = first.sections
                if let accessibilityTree {
                    sections.append("无障碍 UI 树:\n\(accessibilityTree)")
                }
                return ScreenContextProvider.UiCaptureResult(
                    sections: sections,
                    hasSuccessfulSource: true
                )
            }
            return first
        }
    }
}
