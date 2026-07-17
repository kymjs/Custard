import AppKit
import CoreGraphics
import Foundation

enum ScreenContextProvider {
    struct UiCaptureResult {
        let sections: [String]
        let hasSuccessfulSource: Bool
    }

    static func captureUiSectionsAsync(
        deviceInfo: DeviceInfo?,
        accessibilityTree: String?,
        accessibilityError: String?,
        screenConnected: Bool = false,
        connectedViaAdb: Bool = false,
        forceAllSources: Bool = false,
        collectAdbSources: Bool = true
    ) async -> UiCaptureResult {
        var sections: [String] = []
        var sourceSuccesses: [Bool] = []

        sections.append(
            chatDiagnosticsSummary(
                screenConnected: screenConnected,
                connectedViaAdb: connectedViaAdb
            )
        )

        let useAccessibility = forceAllSources || AppPreferences.accessibilityUiTreeEnabled
        let useViewDebug = collectAdbSources && (forceAllSources || AppPreferences.viewDebugUiTreeEnabled)
        let useUiautomator = collectAdbSources && (forceAllSources || AppPreferences.uiautomatorUiTreeEnabled)

        async let foregroundActivity: String? = collectAdbSources
            ? Task.detached(priority: .userInitiated) {
                AdbManager.fetchForegroundActivity(timeoutSeconds: 3)
            }.value
            : nil
        async let uiautomatorHierarchy: String? = useUiautomator
            ? Task.detached(priority: .userInitiated) {
                AdbManager.fetchUiHierarchySummary(timeoutSeconds: 5)
            }.value
            : nil
        async let viewDebugHierarchy: String? = useViewDebug
            ? Task.detached(priority: .userInitiated) {
                AdbManager.fetchViewDebugHierarchy(timeoutSeconds: 5)
            }.value
            : nil

        if collectAdbSources {
            if let activity = await foregroundActivity {
                sections.append("前台界面: \(activity)")
                if let packageName = TapCoordinateMatcher.packageName(fromForegroundActivity: activity) {
                    sections.append("前台应用包名: \(packageName)")
                }
            }
        } else {
            sections.append("ADB UI 采集: 已跳过（连续失败），仍保留无障碍 UI 树。")
        }

        if useAccessibility {
            if let accessibilityTree, !accessibilityTree.isEmpty {
                sections.append("无障碍 UI 树:\n\(accessibilityTree)")
                sourceSuccesses.append(true)
            } else if let accessibilityError {
                sections.append("无障碍 UI 树: \(accessibilityError)")
                sourceSuccesses.append(false)
            } else {
                sections.append("无障碍 UI 树: 未获取（请确认已连接屏幕共享且无障碍服务已开启）")
                sourceSuccesses.append(false)
            }
        } else {
            sections.append("无障碍 UI 树: 未启用")
            sourceSuccesses.append(false)
        }

        if useViewDebug {
            if let hint = AdbManager.viewDebugAvailabilityHint() {
                sections.append("View Debug / Layout Inspector: \(hint)")
                sourceSuccesses.append(false)
            } else if let hierarchy = await viewDebugHierarchy {
                sections.append("View Debug / Layout Inspector:\n\(hierarchy)")
                sourceSuccesses.append(!hierarchy.isEmpty)
            } else {
                sections.append(
                    "View Debug / Layout Inspector: 未获取到视图层级。"
                    + "请使用 Debug 版 App，并在开发者选项中开启「USB 调试」等相关选项后重试。"
                )
                sourceSuccesses.append(false)
            }
        } else if collectAdbSources {
            sections.append("View Debug / Layout Inspector: 未启用")
            sourceSuccesses.append(false)
        }

        if useUiautomator {
            if let hierarchy = await uiautomatorHierarchy {
                sections.append("uiautomator UI 树:\n\(hierarchy)")
                sourceSuccesses.append(!hierarchy.isEmpty)
            } else if AdbManager.connectedDeviceSerial() == nil {
                sections.append("uiautomator UI 树: 无法通过 ADB 获取（请确认 USB/WiFi ADB 已连接）")
                sourceSuccesses.append(false)
            } else {
                sections.append("uiautomator UI 树: dump 失败或未返回内容")
                sourceSuccesses.append(false)
            }
        } else if collectAdbSources {
            sections.append("uiautomator UI 树: 未启用")
            sourceSuccesses.append(false)
        }

        if sections.isEmpty, deviceInfo != nil {
            sections.append("界面元素: 暂无可用数据")
        }

        return UiCaptureResult(
            sections: sections,
            hasSuccessfulSource: sourceSuccesses.contains(true)
        )
    }

    /// 聊天上下文用轻量诊断，避免重复 adb devices / forward 查询拖慢采集
    static func chatDiagnosticsSummary(
        screenConnected: Bool,
        connectedViaAdb: Bool
    ) -> String {
        var lines: [String] = []
        if screenConnected {
            lines.append("屏幕共享: 已连接 (\(connectedViaAdb ? "USB/ADB 隧道" : "WiFi 直连"))")
        } else {
            lines.append("屏幕共享: 未连接")
        }
        if let serial = AdbManager.connectedDeviceSerial() {
            lines.append("ADB: 已连接 (serial=\(serial))")
        } else {
            lines.append("ADB: 未检测到设备")
            if screenConnected && !connectedViaAdb {
                lines.append("说明: WiFi 直连模式下点击/滑动/输入可经屏幕共享通道执行")
            }
        }
        return lines.joined(separator: "\n")
    }

    static func buildDescription(
        deviceInfo: DeviceInfo?,
        uiSections: [String],
        hasLiveFrame: Bool,
        includeScreenshot: Bool,
        screenshotAttached: Bool,
        screenCaptureBlocked: Bool = false
    ) -> String {
        var sections = ["【当前手机屏幕信息】"]

        if screenCaptureBlocked {
            sections.append(
                "录屏状态: 当前应用禁止录屏（FLAG_SECURE），画面为黑屏。已优先采集 UI 树供操作参考。"
            )
        }

        if let info = deviceInfo {
            sections.append("分辨率: \(info.width) × \(info.height)")
        } else {
            sections.append("分辨率: 未知（尚未收到设备信息）")
        }

        sections.append(contentsOf: uiSections)

        if screenshotAttached {
            sections.append("已附带当前屏幕截图，请结合图像分析界面。")
        } else if includeScreenshot && screenCaptureBlocked {
            sections.append("未附带截图（应用禁止录屏，黑屏无参考价值）。")
        } else if includeScreenshot {
            sections.append("屏幕截图编码失败。")
        } else if hasLiveFrame && !screenCaptureBlocked {
            sections.append("实时画面可用。如需查看截图，请在回复中单独一行输出 [REQUEST_SCREEN]。")
        } else {
            sections.append("暂无实时画面（等待屏幕共享帧）。")
        }

        return sections.joined(separator: "\n")
    }

    static func pngBase64(from image: CGImage) -> String? {
        guard let png = pngData(from: image) else {
            return nil
        }
        return png.base64EncodedString()
    }

    static func pngData(from image: CGImage) -> Data? {
        let width = image.width
        let height = image.height
        guard width > 0, height > 0 else { return nil }

        let nsImage = NSImage(
            cgImage: image,
            size: NSSize(width: width, height: height)
        )
        guard
            let tiff = nsImage.tiffRepresentation,
            let rep = NSBitmapImageRep(data: tiff),
            let png = rep.representation(using: .png, properties: [:])
        else { return nil }

        return png
    }
}
