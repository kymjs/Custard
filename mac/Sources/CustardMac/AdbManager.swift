import Foundation

enum AdbManager {
    static let androidPackageName = "com.kymjs.custard"
    static let bundledApkRelativePath = "android/app-release.apk"

    struct AdbDevice {
        let serial: String
        let state: String
    }

    struct AdbDiagnostics {
        let adbPath: String?
        let devicesRaw: String
        let devices: [AdbDevice]
        let cachedSerial: String?
        let forwardList: String
        let connectedSerial: String?
    }

    /// USB 隧道建立成功后缓存的设备序列号，避免 adb devices 短暂为空时误判
    private static var cachedSerial: String?
    private static let cacheLock = NSLock()

    static func setCachedSerial(_ serial: String?) {
        cacheLock.lock()
        cachedSerial = serial
        cacheLock.unlock()
    }

    static func getCachedSerial() -> String? {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        return cachedSerial
    }

    static func clearCachedSerial() {
        setCachedSerial(nil)
    }

    static func findAdbPath() -> String? {
        let candidates = [
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
            "\(NSHomeDirectory())/Library/Android/sdk/platform-tools/adb"
        ]
        for path in candidates {
            if FileManager.default.isExecutableFile(atPath: path) {
                return path
            }
        }
        return resolveFromPath("adb")
    }

    static func listDevices() -> [AdbDevice] {
        guard let adb = findAdbPath() else {
            Logger.warn("listDevices: adb not found")
            return []
        }
        let result = run(adb: adb, arguments: ["devices"])
        let devices = parseDevicesOutput(result.output)
        Logger.info("listDevices: exit=\(result.exitCode) raw=\(result.output.replacingOccurrences(of: "\n", with: " | ")) parsed=\(devices.count)")
        return devices
    }

    private static func parseDevicesOutput(_ output: String) -> [AdbDevice] {
        output
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && !$0.hasPrefix("List of devices") }
            .compactMap { line -> AdbDevice? in
                let parts = line.split(separator: "\t", omittingEmptySubsequences: false)
                guard parts.count >= 2 else { return nil }
                return AdbDevice(serial: String(parts[0]), state: String(parts[1]))
            }
    }

    static func collectDiagnostics() -> AdbDiagnostics {
        let adbPath = findAdbPath()
        var devicesRaw = ""
        var devices: [AdbDevice] = []
        var forwardList = ""

        if let adb = adbPath {
            let devicesResult = run(adb: adb, arguments: ["devices"])
            devicesRaw = devicesResult.output
            devices = parseDevicesOutput(devicesResult.output)
            let forwardResult = run(adb: adb, arguments: ["forward", "--list"])
            forwardList = forwardResult.output
        }

        return AdbDiagnostics(
            adbPath: adbPath,
            devicesRaw: devicesRaw,
            devices: devices,
            cachedSerial: getCachedSerial(),
            forwardList: forwardList,
            connectedSerial: connectedDeviceSerial()
        )
    }

    static func logDiagnostics(context: String) {
        let d = collectDiagnostics()
        Logger.info(
            "[ADB诊断/\(context)] path=\(d.adbPath ?? "未找到") " +
            "devices=[\(d.devices.map { "\($0.serial)(\($0.state))" }.joined(separator: ", "))] " +
            "cached=\(d.cachedSerial ?? "nil") forward=[\(d.forwardList.replacingOccurrences(of: "\n", with: "; "))] " +
            "connected=\(d.connectedSerial ?? "nil")"
        )
    }

    static func diagnosticsSummary(
        screenConnected: Bool,
        connectedViaAdb: Bool
    ) -> String {
        let d = collectDiagnostics()
        var lines: [String] = []

        if screenConnected {
            lines.append("屏幕共享: 已连接 (\(connectedViaAdb ? "USB/ADB 隧道" : "WiFi 直连"))")
        } else {
            lines.append("屏幕共享: 未连接")
        }

        if let serial = d.connectedSerial {
            lines.append("ADB: 已连接 (serial=\(serial))")
        } else if let cached = d.cachedSerial {
            lines.append("ADB: adb devices 当前为空，但 USB 隧道缓存 serial=\(cached)")
        } else {
            lines.append("ADB: 未检测到设备")
            if screenConnected && !connectedViaAdb {
                lines.append("说明: WiFi 直连模式下屏幕共享与 ADB 独立；点击/滑动/输入可经屏幕共享通道执行，无需 ADB")
            } else if screenConnected && connectedViaAdb {
                lines.append("说明: 屏幕共享已连接但 adb devices 为空，请检查 USB 调试授权或运行 adb kill-server && adb start-server")
            }
        }

        if d.adbPath == nil {
            lines.append("adb 路径: 未找到 (请安装 Android Platform Tools)")
        } else {
            lines.append("adb 路径: \(d.adbPath!)")
        }

        return lines.joined(separator: "\n")
    }

    static func connectedDeviceSerial() -> String? {
        if let serial = listDevices().first(where: { $0.state == "device" })?.serial {
            setCachedSerial(serial)
            return serial
        }
        return getCachedSerial()
    }

    /// 检测已连接设备的 ADB 类型：含 ":" 为 WiFi ADB，否则为 USB
    static func detectConnectionType() -> ConnectionType? {
        guard let serial = connectedDeviceSerial() else { return nil }
        return serial.contains(":") ? .wifi : .usb
    }

    /// 从 WiFi ADB 序列号解析 IP 和端口，如 192.168.1.100:5555
    static func parseWifiEndpoint(from serial: String) -> (host: String, port: String)? {
        let parts = serial.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2, !parts[0].isEmpty else { return nil }
        return (host: parts[0], port: parts[1])
    }

    /// scrcpy 风格隧道：adb forward tcp:PORT localabstract:custard
    @discardableResult
    static func setupUsbTunnel(
        local: UInt16 = Protocol.defaultPort,
        socketName: String = Protocol.adbSocketName
    ) throws -> String {
        Logger.info("setupUsbTunnel local=\(local) abstract=\(socketName)")
        guard let adb = findAdbPath() else { throw AdbError.notFound }

        let devices = listDevices()
        Logger.info("adb devices: \(devices.map { "\($0.serial)(\($0.state))" }.joined(separator: ", "))")

        guard let serial = devices.first(where: { $0.state == "device" })?.serial else {
            if devices.contains(where: { $0.state == "unauthorized" }) { throw AdbError.unauthorized }
            if devices.isEmpty { throw AdbError.noDevice }
            throw AdbError.badState(devices.map { "\($0.serial)(\($0.state))" }.joined(separator: ", "))
        }

        _ = run(adb: adb, arguments: ["-s", serial, "forward", "--remove", "tcp:\(local)"])
        let result = run(
            adb: adb,
            arguments: ["-s", serial, "forward", "tcp:\(local)", "localabstract:\(socketName)"]
        )
        Logger.info("adb forward abstract exit=\(result.exitCode) output=\(result.output)")

        guard result.exitCode == 0, !result.output.lowercased().contains("error") else {
            throw AdbError.forwardFailed(result.output)
        }
        setCachedSerial(serial)
        return adb
    }

    /// 唤醒手机端 App，方便用户点击「开始共享屏幕」
    static func wakeAndroidApp() {
        guard let adb = findAdbPath(), let serial = connectedDeviceSerial() else { return }
        let result = run(
            adb: adb,
            arguments: [
                "-s", serial, "shell", "am", "start",
                "-n", "\(androidPackageName)/.MainActivity",
                "-a", "android.intent.action.MAIN"
            ]
        )
        Logger.info("wakeAndroidApp exit=\(result.exitCode) output=\(result.output)")
    }

    static func isCustardAppInstalled() -> Bool {
        guard let adb = findAdbPath(), let serial = connectedDeviceSerial() else { return false }
        let result = run(
            adb: adb,
            arguments: ["-s", serial, "shell", "pm", "path", androidPackageName]
        )
        return result.exitCode == 0 && result.output.contains("package:")
    }

    static func bundledAndroidApkPath() -> String? {
        let candidates: [String] = [
            Bundle.main.resourceURL?
                .appendingPathComponent(bundledApkRelativePath).path,
            Bundle.main.bundlePath + "/Contents/Resources/" + bundledApkRelativePath,
            Bundle.module.url(
                forResource: "app-release",
                withExtension: "apk",
                subdirectory: "android"
            )?.path
        ].compactMap { $0 }

        for path in candidates {
            if FileManager.default.isReadableFile(atPath: path) {
                return path
            }
        }
        return nil
    }

    /// ADB 设备就绪后，若未安装 Custard Android 端则自动安装内置 APK
    @discardableResult
    static func installCustardAppIfNeeded() throws -> Bool {
        if isCustardAppInstalled() {
            Logger.info("Custard Android app already installed")
            return false
        }

        guard let apkPath = bundledAndroidApkPath() else {
            Logger.warn("bundled Android APK not found")
            throw AdbError.apkNotFound
        }
        guard let adb = findAdbPath() else { throw AdbError.notFound }
        guard let serial = connectedDeviceSerial() else { throw AdbError.noDevice }

        Logger.info("installing Custard Android app from \(apkPath)")
        let result = run(
            adb: adb,
            arguments: ["-s", serial, "install", "-r", apkPath],
            timeoutSeconds: 120
        )
        guard result.exitCode == 0 else {
            throw AdbError.installFailed(result.output)
        }
        Logger.info("Custard Android app installed successfully")
        return true
    }

    static func removeForward(local: UInt16 = Protocol.defaultPort) {
        guard let adb = findAdbPath(), let serial = connectedDeviceSerial() else { return }
        _ = run(adb: adb, arguments: ["-s", serial, "forward", "--remove", "tcp:\(local)"])
        clearCachedSerial()
    }

    struct ExecutionResult {
        let command: String
        let output: String
        let exitCode: Int32
        let blocked: Bool

        var succeeded: Bool { !blocked && exitCode == 0 }
    }

    static func executeCommandLine(
        _ commandLine: String,
        timeoutSeconds: TimeInterval = 10
    ) throws -> ExecutionResult {
        let trimmed = commandLine.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw AdbError.emptyCommand
        }

        if isBlocked(trimmed) {
            return ExecutionResult(
                command: trimmed,
                output: "已阻止执行潜在危险命令",
                exitCode: 1,
                blocked: true
            )
        }

        guard let adb = findAdbPath() else {
            Logger.warn("executeCommandLine: adb not found for \(trimmed)")
            throw AdbError.notFound
        }
        guard let serial = connectedDeviceSerial() else {
            logDiagnostics(context: "execute-no-device")
            throw AdbError.noDevice
        }

        let withoutPrefix: String
        if trimmed.hasPrefix("adb ") {
            withoutPrefix = String(trimmed.dropFirst(4))
        } else if trimmed == "adb" {
            withoutPrefix = ""
        } else {
            throw AdbError.invalidCommand(trimmed)
        }

        let args = parseArguments(withoutPrefix)
        Logger.info("execute adb -s \(serial) \(args.joined(separator: " "))")
        let result = run(adb: adb, arguments: ["-s", serial] + args, timeoutSeconds: timeoutSeconds)
        if result.exitCode != 0 {
            Logger.warn("adb command failed exit=\(result.exitCode) output=\(result.output)")
        }
        return ExecutionResult(
            command: trimmed,
            output: result.output.isEmpty ? "(无输出)" : result.output,
            exitCode: result.exitCode,
            blocked: false
        )
    }

    /// Runs ADB off the main thread; socket fallback briefly hops to MainActor.
    static func executeCommandsAsync(
        _ commands: [String],
        connection: ConnectionManager? = nil
    ) async -> [ExecutionResult] {
        var results: [ExecutionResult] = []
        results.reserveCapacity(commands.count)
        for command in commands {
            results.append(await executeCommandWithFallbackAsync(command, connection: connection))
        }
        return results
    }

    private static func executeCommandWithFallbackAsync(
        _ command: String,
        connection: ConnectionManager?
    ) async -> ExecutionResult {
        let adbOutcome: Result<ExecutionResult, Error> = await Task.detached(priority: .userInitiated) {
            Result { try executeCommandLine(command) }
        }.value

        switch adbOutcome {
        case .success(let result) where result.succeeded:
            return result
        case .success(let result):
            if let socketResult = await socketFallback(command: command, connection: connection) {
                Logger.info("adb exit=\(result.exitCode), socket fallback succeeded for: \(command)")
                return socketResult
            }
            return result
        case .failure(let error):
            Logger.error(error, context: "execute adb command")
            if let socketResult = await socketFallback(command: command, connection: connection) {
                Logger.info("adb failed, socket fallback succeeded for: \(command)")
                return socketResult
            }
            return ExecutionResult(
                command: command,
                output: error.localizedDescription,
                exitCode: 1,
                blocked: false
            )
        }
    }

    private static func socketFallback(
        command: String,
        connection: ConnectionManager?
    ) async -> ExecutionResult? {
        guard let connection else { return nil }
        return await MainActor.run {
            guard connection.isConnected, SocketCommandExecutor.canHandle(command) else {
                return nil
            }
            return SocketCommandExecutor.execute(command, connection: connection)
        }
    }

    static func fetchForegroundActivityAsync() async -> String? {
        await Task.detached(priority: .userInitiated) {
            fetchForegroundActivity()
        }.value
    }

    static func parseArgumentsPublic(_ line: String) -> [String] {
        parseArguments(line)
    }

    static func formatExecutionResults(_ results: [ExecutionResult]) -> String {
        guard !results.isEmpty else { return "" }
        return results.enumerated().map { index, result in
            var lines = ["\(index + 1). `$ \(result.command)`"]
            lines.append("   exit=\(result.exitCode)")
            for outputLine in result.output.split(whereSeparator: \.isNewline) {
                lines.append("   \(outputLine)")
            }
            return lines.joined(separator: "\n")
        }.joined(separator: "\n\n")
    }

    static func fetchForegroundActivity(timeoutSeconds: TimeInterval = 3) -> String? {
        guard connectedDeviceSerial() != nil else { return nil }

        // dumpsys activity top 输出量小，比 dumpsys window 快得多（无线 ADB 上后者可能卡死）
        if let top = try? executeCommandLine(
            "adb shell dumpsys activity top",
            timeoutSeconds: timeoutSeconds
        ) {
            for line in top.output.split(whereSeparator: \.isNewline) {
                let trimmed = String(line).trimmingCharacters(in: .whitespaces)
                if trimmed.hasPrefix("ACTIVITY ") {
                    return simplifyActivityLine(trimmed)
                }
            }
        }

        if let window = try? executeCommandLine(
            "adb shell dumpsys window displays",
            timeoutSeconds: timeoutSeconds
        ) {
            if let line = window.output
                .split(whereSeparator: \.isNewline)
                .first(where: { $0.contains("mCurrentFocus") || $0.contains("mFocusedApp") })
                .map({ String($0).trimmingCharacters(in: .whitespacesAndNewlines) }),
               !line.contains("null") {
                return simplifyActivityLine(line)
            }
        }

        return nil
    }

    static func fetchUiHierarchySummary(timeoutSeconds: TimeInterval = 5) -> String? {
        guard connectedDeviceSerial() != nil else { return nil }
        guard let dump = try? executeCommandLine(
            "adb shell uiautomator dump /sdcard/custard_uidump.xml",
            timeoutSeconds: timeoutSeconds
        ),
              dump.succeeded else { return nil }
        guard let cat = try? executeCommandLine(
            "adb shell cat /sdcard/custard_uidump.xml",
            timeoutSeconds: timeoutSeconds
        ),
              cat.succeeded else { return nil }
        return UiHierarchySummarizer.summarize(xml: cat.output)
    }

    static func fetchUiHierarchySummaryAsync(timeoutSeconds: TimeInterval = 5) async -> String? {
        await Task.detached(priority: .userInitiated) {
            fetchUiHierarchySummary(timeoutSeconds: timeoutSeconds)
        }.value
    }

    static func isDeveloperOptionsEnabled() -> Bool {
        guard let result = try? executeCommandLine("adb shell settings get global development_settings_enabled"),
              result.succeeded else { return false }
        return result.output.trimmingCharacters(in: .whitespacesAndNewlines) == "1"
    }

    static func fetchViewDebugHierarchy(timeoutSeconds: TimeInterval = 5) -> String? {
        guard connectedDeviceSerial() != nil else { return nil }

        if let dump = try? executeCommandLine(
            "adb shell cmd activity dump-view-hierarchy",
            timeoutSeconds: timeoutSeconds
        ),
           dump.succeeded,
           !dump.output.isEmpty,
           !dump.output.localizedCaseInsensitiveContains("error") {
            return ViewDebugSummarizer.summarize(dump: dump.output)
        }

        guard let top = try? executeCommandLine(
            "adb shell dumpsys activity top",
            timeoutSeconds: timeoutSeconds
        ),
              top.succeeded else { return nil }

        return ViewDebugSummarizer.summarize(dump: top.output)
    }

    static func viewDebugAvailabilityHint() -> String? {
        guard connectedDeviceSerial() != nil else {
            return "View Debug 需要 ADB 连接"
        }
        if !isDeveloperOptionsEnabled() {
            return "请在手机上开启「开发者选项」（设置 → 关于手机 → 连续点击版本号）"
        }
        return nil
    }

    private static func simplifyActivityLine(_ line: String) -> String {
        if let range = line.range(of: "u0 ") {
            return String(line[range.upperBound...])
                .trimmingCharacters(in: CharacterSet(charactersIn: "}/"))
        }
        return line
    }

    private static func isBlocked(_ command: String) -> Bool {
        let lower = command.lowercased()
        let blockedPatterns = [
            " reboot",
            " shell reboot",
            " shell wipe",
            " shell rm -rf /",
            " shell rm -rf /*",
            " shell format",
            " uninstall --user 0 com.android",
        ]
        return blockedPatterns.contains { lower.contains($0) }
    }

    private static func parseArguments(_ line: String) -> [String] {
        guard !line.isEmpty else { return [] }

        var args: [String] = []
        var current = ""
        var inQuote: Character?

        for char in line {
            if let quote = inQuote {
                if char == quote {
                    inQuote = nil
                } else {
                    current.append(char)
                }
            } else if char == "\"" || char == "'" {
                inQuote = char
            } else if char.isWhitespace {
                if !current.isEmpty {
                    args.append(current)
                    current = ""
                }
            } else {
                current.append(char)
            }
        }

        if !current.isEmpty {
            args.append(current)
        }
        return args
    }

    private static func resolveFromPath(_ command: String) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/which")
        process.arguments = [command]
        process.environment = shellEnvironment()
        let pipe = Pipe()
        process.standardOutput = pipe
        guard (try? process.run()) != nil else { return nil }
        process.waitUntilExit()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        guard let path = String(data: data, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
              !path.isEmpty else { return nil }
        return path
    }

    private static func shellEnvironment() -> [String: String] {
        var env = ProcessInfo.processInfo.environment
        let extraPaths = [
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "\(NSHomeDirectory())/Library/Android/sdk/platform-tools"
        ]
        let currentPath = env["PATH"] ?? "/usr/bin:/bin:/usr/sbin:/sbin"
        env["PATH"] = (extraPaths + [currentPath]).joined(separator: ":")
        env["HOME"] = env["HOME"] ?? NSHomeDirectory()
        return env
    }

    private struct CommandResult {
        let output: String
        let exitCode: Int32
    }

    @discardableResult
    private static func run(
        adb: String,
        arguments: [String],
        timeoutSeconds: TimeInterval = 10
    ) -> CommandResult {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: adb)
        process.arguments = arguments
        process.environment = shellEnvironment()
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        guard (try? process.run()) != nil else {
            return CommandResult(output: "failed to launch adb", exitCode: -1)
        }

        let semaphore = DispatchSemaphore(value: 0)
        var exitCode: Int32 = -1
        DispatchQueue.global(qos: .utility).async {
            process.waitUntilExit()
            exitCode = process.terminationStatus
            semaphore.signal()
        }

        if semaphore.wait(timeout: .now() + timeoutSeconds) == .timedOut {
            process.terminate()
            let cmd = arguments.joined(separator: " ")
            Logger.warn("adb timeout (\(timeoutSeconds)s): \(cmd)")
            return CommandResult(
                output: "adb 命令超时 (\(Int(timeoutSeconds)) 秒)",
                exitCode: -2
            )
        }

        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        let output = String(data: data, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return CommandResult(output: output, exitCode: exitCode)
    }
}

enum AdbError: LocalizedError {
    case notFound
    case noDevice
    case unauthorized
    case badState(String)
    case forwardFailed(String)
    case emptyCommand
    case invalidCommand(String)
    case apkNotFound
    case installFailed(String)

    var errorDescription: String? {
        switch self {
        case .notFound: return "未找到 adb，请安装 Android Platform Tools"
        case .noDevice: return "未检测到 ADB 设备（屏幕共享可能已连接，触摸类命令会尝试经共享通道执行）"
        case .unauthorized: return "手机未授权此电脑，请点击「允许 USB 调试」"
        case .badState(let d): return "设备状态异常: \(d)"
        case .forwardFailed(let d): return "ADB 隧道建立失败: \(d)"
        case .emptyCommand: return "ADB 命令为空"
        case .invalidCommand(let command): return "无效的 ADB 命令: \(command)"
        case .apkNotFound: return "未找到内置 Android APK，请重新安装奶黄包或联系开发者"
        case .installFailed(let detail): return "安装手机端应用失败: \(detail)"
        }
    }
}
