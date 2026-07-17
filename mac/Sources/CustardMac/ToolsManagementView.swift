import SwiftUI

struct ToolsManagementView: View {
    @Environment(\.custardPalette) private var palette
    @State private var enabledStates: [String: Bool] = [:]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("关闭开关后，对应工具将不再可用。SKILL 类工具关闭后不再提供给内置大模型；CLI/MCP 关闭后外部脚本无法调用屏幕服务。")
                    .font(.subheadline)
                    .foregroundStyle(palette.secondaryText)
                    .padding(.bottom, 4)

                ForEach(CustardToolRegistry.allTools) { tool in
                    ToolRow(
                        tool: tool,
                        isEnabled: binding(for: tool.id)
                    )
                }
            }
            .padding(20)
        }
        .background(palette.background)
        .navigationTitle("工具(MCP/SKILL/CLI)")
        .onAppear {
            reloadEnabledStates()
        }
    }

    private func binding(for id: String) -> Binding<Bool> {
        Binding(
            get: { enabledStates[id] ?? AppPreferences.isToolEnabled(id: id) },
            set: { newValue in
                enabledStates[id] = newValue
                AppPreferences.setToolEnabled(id: id, enabled: newValue)
            }
        )
    }

    private func reloadEnabledStates() {
        var states: [String: Bool] = [:]
        for tool in CustardToolRegistry.allTools {
            states[tool.id] = AppPreferences.isToolEnabled(id: tool.id)
        }
        enabledStates = states
    }
}

private struct ToolRow: View {
    @Environment(\.custardPalette) private var palette

    let tool: CustardToolDefinition
    @Binding var isEnabled: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 8) {
                Text(tool.kind.rawValue)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(palette.onPrimaryContainer)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(palette.primaryContainer)
                    .clipShape(Capsule())

                VStack(alignment: .leading, spacing: 4) {
                    Text(tool.name)
                        .font(.headline)
                        .foregroundStyle(palette.onSurface)
                    Text(tool.description)
                        .font(.subheadline)
                        .foregroundStyle(palette.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            Spacer(minLength: 12)

            Toggle("", isOn: $isEnabled)
                .labelsHidden()
                .toggleStyle(.switch)
        }
        .padding(16)
        .background(palette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(palette.divider, lineWidth: 1)
        )
        .opacity(isEnabled ? 1 : 0.72)
    }
}
