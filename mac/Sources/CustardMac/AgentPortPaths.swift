import Foundation

enum AgentPortPaths {
    static let skillName = "custard-phone-control"
    static let skillGitHubURL = "https://github.com/kymjs/Custard-Skill.git"
    static let skillGitHubWebURL = "https://github.com/kymjs/Custard-Skill"

    static var installedSkillPath: String {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        return ((home + "/.cursor/skills/\(skillName)") as NSString).standardizingPath
    }

    /// 从 GitHub 克隆或更新 Skill，失败时回退到 App 内置 Resources
    static func installSkillPackage(token: String) throws -> String {
        let dest = installedSkillPath
        let fm = FileManager.default
        let preserved = readConfigValues(from: dest + "/scripts/config.env")

        if commandExists("git") {
            try cloneOrUpdateFromGitHub(dest: dest)
        } else if fm.fileExists(atPath: dest + "/SKILL.md") {
            // 已安装且无 git：保留现有目录，仅更新 config
        } else if let bundled = bundledSkillResourceURL() {
            try copySkillContents(from: bundled.path, to: dest)
        } else {
            throw AgentPortInstallError.gitUnavailableAndNoBundle
        }

        if !fm.fileExists(atPath: dest + "/SKILL.md") {
            if let bundled = bundledSkillResourceURL() {
                try copySkillContents(from: bundled.path, to: dest)
            }
            if !fm.fileExists(atPath: dest + "/SKILL.md") {
                throw AgentPortInstallError.skillSourceMissing(skillGitHubWebURL)
            }
        }

        try writeConfigEnv(
            at: dest + "/scripts/config.env",
            token: token,
            skillDir: dest,
            host: preserved.host,
            port: preserved.port
        )

        let toolScript = dest + "/scripts/custard-tool"
        let installScript = dest + "/install.sh"
        if fm.fileExists(atPath: toolScript) {
            try fm.setAttributes([.posixPermissions: 0o755], ofItemAtPath: toolScript)
        }
        if fm.fileExists(atPath: installScript) {
            try fm.setAttributes([.posixPermissions: 0o755], ofItemAtPath: installScript)
        }

        runPostInstallScript(at: dest, token: token)

        return dest
    }

    private static func runPostInstallScript(at dest: String, token: String) {
        let installScript = dest + "/install.sh"
        guard FileManager.default.fileExists(atPath: installScript) else { return }
        let escapedDest = dest.replacingOccurrences(of: "\"", with: "\\\"")
        let escapedToken = token.replacingOccurrences(of: "\"", with: "\\\"")
        _ = runShell("bash \"\(escapedDest)/install.sh\" \"\(escapedToken)\" --skip-clone")
    }

    static var isScreenSkillInstalled: Bool {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        let path = ((home + "/.cursor/skills/android-phone-screen") as NSString).standardizingPath
        return FileManager.default.fileExists(atPath: path + "/SKILL.md")
    }

    static var isSkillInstalled: Bool {
        FileManager.default.fileExists(atPath: installedSkillPath + "/SKILL.md")
    }

    private static func bundledSkillResourceURL() -> URL? {
        if let url = Bundle.main.resourceURL?.appendingPathComponent("custard-phone-control", isDirectory: true),
           FileManager.default.fileExists(atPath: url.appendingPathComponent("SKILL.md").path) {
            return url
        }
        let path = Bundle.main.bundlePath + "/Contents/Resources/custard-phone-control"
        if FileManager.default.fileExists(atPath: path + "/SKILL.md") {
            return URL(fileURLWithPath: path, isDirectory: true)
        }
        return nil
    }

    private static func cloneOrUpdateFromGitHub(dest: String) throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: dest + "/.git") {
            _ = runShell("cd \"\(dest)\" && git pull --ff-only")
            return
        }

        let parent = (dest as NSString).deletingLastPathComponent
        try fm.createDirectory(atPath: parent, withIntermediateDirectories: true)

        if fm.fileExists(atPath: dest) {
            // 保留 config.env，其余删除后重新 clone
            let backupConfig = dest + "/scripts/config.env"
            let tempConfig = NSTemporaryDirectory() + "custard-skill-config.env"
            if fm.fileExists(atPath: backupConfig) {
                try? fm.removeItem(atPath: tempConfig)
                try fm.copyItem(atPath: backupConfig, toPath: tempConfig)
            }
            try fm.removeItem(atPath: dest)
            let status = runShell("git clone --depth 1 \"\(skillGitHubURL)\" \"\(dest)\"")
            guard status == 0 else {
                throw AgentPortInstallError.gitCloneFailed(status)
            }
            if fm.fileExists(atPath: tempConfig) {
                try fm.createDirectory(atPath: dest + "/scripts", withIntermediateDirectories: true)
                try? fm.removeItem(atPath: backupConfig)
                try fm.copyItem(atPath: tempConfig, toPath: backupConfig)
            }
        } else {
            let status = runShell("git clone --depth 1 \"\(skillGitHubURL)\" \"\(dest)\"")
            guard status == 0 else {
                throw AgentPortInstallError.gitCloneFailed(status)
            }
        }
    }

    private static func copySkillContents(from source: String, to dest: String) throws {
        let fm = FileManager.default
        let parent = (dest as NSString).deletingLastPathComponent
        try fm.createDirectory(atPath: parent, withIntermediateDirectories: true)
        if fm.fileExists(atPath: dest) {
            let configBackup = dest + "/scripts/config.env"
            let tempConfig = NSTemporaryDirectory() + "custard-skill-config.env"
            if fm.fileExists(atPath: configBackup) {
                try? fm.removeItem(atPath: tempConfig)
                try fm.copyItem(atPath: configBackup, toPath: tempConfig)
            }
            try fm.removeItem(atPath: dest)
            try fm.copyItem(atPath: source, toPath: dest)
            if fm.fileExists(atPath: tempConfig) {
                try fm.createDirectory(atPath: dest + "/scripts", withIntermediateDirectories: true)
                try? fm.removeItem(atPath: configBackup)
                try fm.copyItem(atPath: tempConfig, toPath: configBackup)
            }
        } else {
            try fm.copyItem(atPath: source, toPath: dest)
        }
    }

    private struct ConfigValues {
        var host: String
        var port: String
    }

    private static func readConfigValues(from path: String) -> ConfigValues {
        var host = "127.0.0.1"
        var port = String(AppPreferences.agentToolServerPort)
        guard let content = try? String(contentsOfFile: path, encoding: .utf8) else {
            return ConfigValues(host: host, port: port)
        }
        for line in content.split(separator: "\n") {
            let parts = line.split(separator: "=", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            switch parts[0].trimmingCharacters(in: .whitespaces) {
            case "CUSTARD_TOOL_HOST": host = parts[1].trimmingCharacters(in: .whitespaces)
            case "CUSTARD_TOOL_PORT": port = parts[1].trimmingCharacters(in: .whitespaces)
            default: break
            }
        }
        return ConfigValues(host: host, port: port)
    }

    private static func writeConfigEnv(
        at path: String,
        token: String,
        skillDir: String,
        host: String,
        port: String
    ) throws {
        let fm = FileManager.default
        let dir = (path as NSString).deletingLastPathComponent
        try fm.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let content = "CUSTARD_AGENT_TOKEN=\(token)\nCUSTARD_TOOL_HOST=\(host)\nCUSTARD_TOOL_PORT=\(port)\nCUSTARD_SKILL_DIR=\(skillDir)\nCUSTARD_TOOL_SOURCE=agent\n"
        try content.write(toFile: path, atomically: true, encoding: .utf8)
    }

    private static func commandExists(_ name: String) -> Bool {
        runShell("command -v \(name)") == 0
    }

    @discardableResult
    private static func runShell(_ script: String) -> Int32 {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/zsh")
        process.arguments = ["-lc", script]
        do {
            try process.run()
            process.waitUntilExit()
            return process.terminationStatus
        } catch {
            return -1
        }
    }
}

enum AgentPortInstallError: LocalizedError {
    case skillSourceMissing(String)
    case gitUnavailableAndNoBundle
    case gitCloneFailed(Int32)

    var errorDescription: String? {
        switch self {
        case .skillSourceMissing(let hint):
            return "找不到 Skill 包。请从 \(hint) 克隆，或检查网络与 git。"
        case .gitUnavailableAndNoBundle:
            return "未安装 git 且 App 内无 Skill 资源。请安装 git 后重试，或手动克隆 \(AgentPortPaths.skillGitHubWebURL)。"
        case .gitCloneFailed(let code):
            return "git clone 失败（exit \(code)）。请检查网络或仓库 \(AgentPortPaths.skillGitHubWebURL) 是否可访问。"
        }
    }
}
