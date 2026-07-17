import SwiftUI
import AppKit

struct AgentPortSettingsView: View {
    @Environment(\.custardPalette) private var palette
    @EnvironmentObject private var agentPortManager: AgentPortManager

    @State private var agentApiEnabled = AppPreferences.agentApiEnabled
    @State private var allowWrite = AppPreferences.agentAllowWrite
    @State private var allowSystemKeys = AppPreferences.agentAllowSystemKeys
    @State private var connectionToken = AppPreferences.agentConnectionToken
    @State private var copiedSkill = false
    @State private var copiedToken = false
    @State private var showResetTokenAlert = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text("Agent 设置")
                    .font(.title2.weight(.bold))
                    .frame(maxWidth: .infinity, alignment: .center)

                VStack(spacing: 0) {
                    settingsToggleRow(
                        title: "开启本机 Agent API",
                        isOn: $agentApiEnabled
                    )

                    Divider().overlay(palette.divider)

                    settingsToggleRow(
                        title: "允许 Agent 写入操作",
                        isOn: $allowWrite
                    )

                    settingsToggleRow(
                        title: "允许 Agent 执行系统按键",
                        isOn: $allowSystemKeys
                    )

                    Text("写入操作包括点击、输入文本、写入剪贴板、打开应用；系统按键包括 Home 与 Back。权限由本页开关与服务端强制生效。")
                        .font(.caption)
                        .foregroundStyle(palette.secondaryText)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                }
                .background(palette.surface)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(palette.divider, lineWidth: 1)
                )

                agentSkillSection

                connectionTokenSection
            }
            .padding(20)
            .frame(maxWidth: 560)
        }
        .frame(maxWidth: .infinity)
        .background(palette.background)
        .navigationTitle("Agent 端口")
        .onChange(of: agentApiEnabled) { newValue in
            AppPreferences.agentApiEnabled = newValue
            agentPortManager.syncServerState()
        }
        .onChange(of: allowWrite) { newValue in
            AppPreferences.agentAllowWrite = newValue
        }
        .onChange(of: allowSystemKeys) { newValue in
            AppPreferences.agentAllowSystemKeys = newValue
        }
    }

    private func settingsToggleRow(title: String, isOn: Binding<Bool>) -> some View {
        HStack {
            Text(title)
                .font(.body)
                .foregroundStyle(palette.onSurface)
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    private var agentSkillSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("奶黄包 Skill")
                .font(.headline)
                .foregroundStyle(palette.onSurface)

            Text("从 GitHub 安装「奶黄包」Skill 套件（操控 + 读屏 + Cursor Rule + MCP）。仓库：\(AgentPortPaths.skillGitHubWebURL)")
                .font(.subheadline)
                .foregroundStyle(palette.secondaryText)

            HStack(alignment: .top, spacing: 12) {
                Text(AppPreferences.agentSkillPrompt)
                    .font(.body)
                    .foregroundStyle(palette.onSurface)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.background)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(palette.divider, lineWidth: 1)
                    )

                Button(copiedSkill ? "已复制" : "复制提示词") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(AppPreferences.agentSkillPrompt, forType: .string)
                    copiedSkill = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        copiedSkill = false
                    }
                }
                .buttonStyle(AgentPortCopyButtonStyle())
            }
        }
        .padding(16)
        .background(palette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(palette.divider, lineWidth: 1)
        )
    }

    private var connectionTokenSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("连接 Token")
                .font(.headline)
                .foregroundStyle(palette.onSurface)

            Text("首次使用时，将 Token 写入 Skill 的 scripts/config.env。API 请求审计日志：~/Library/Logs/CustardMac/agent-api.log")
                .font(.subheadline)
                .foregroundStyle(palette.secondaryText)

            HStack(spacing: 12) {
                Text(connectionToken)
                    .font(.body.monospaced())
                    .foregroundStyle(palette.onSurface)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.background)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(palette.divider, lineWidth: 1)
                    )

                VStack(spacing: 8) {
                    Button(copiedToken ? "已复制" : "复制 Token") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(connectionToken, forType: .string)
                        copiedToken = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            copiedToken = false
                        }
                    }
                    .buttonStyle(AgentPortCopyButtonStyle())

                    Button("重置 Token") {
                        showResetTokenAlert = true
                    }
                    .buttonStyle(AgentPortCopyButtonStyle())
                }
            }
        }
        .padding(16)
        .background(palette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(palette.divider, lineWidth: 1)
        )
        .alert("重置 Token", isPresented: $showResetTokenAlert) {
            Button("取消", role: .cancel) {}
            Button("重置", role: .destructive) {
                connectionToken = AppPreferences.regenerateAgentConnectionToken()
            }
        } message: {
            Text("重置后旧 Token 立即失效。请重新执行安装提示词或手动更新 config.env。")
        }
    }
}

private struct AgentPortCopyButtonStyle: ButtonStyle {
    @Environment(\.custardPalette) private var palette

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(palette.onPrimary)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(palette.primary.opacity(configuration.isPressed ? 0.85 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}
