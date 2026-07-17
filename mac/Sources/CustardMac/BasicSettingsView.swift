import SwiftUI

struct BasicSettingsView: View {
    @State private var operationImageEnabled = AppPreferences.operationImageEnabled
    @State private var accessibilityUiTreeEnabled = AppPreferences.accessibilityUiTreeEnabled
    @State private var viewDebugUiTreeEnabled = AppPreferences.viewDebugUiTreeEnabled
    @State private var uiautomatorUiTreeEnabled = AppPreferences.uiautomatorUiTreeEnabled
    @State private var showThinkingContentEnabled = AppPreferences.showThinkingContentEnabled
    @State private var debugMode = AppPreferences.debugMode
    @State private var maxLLMTurns = AppPreferences.maxLLMTurns

    var body: some View {
        Form {
            Section {
                HStack {
                    Button {
                        maxLLMTurns = max(AppPreferences.minMaxLLMTurns, maxLLMTurns - 1)
                    } label: {
                        Image(systemName: "minus")
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.bordered)
                    .disabled(maxLLMTurns <= AppPreferences.minMaxLLMTurns)

                    TextField("", value: $maxLLMTurns, format: .number)
                        .multilineTextAlignment(.center)
                        .frame(width: 60)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: maxLLMTurns) { newValue in
                            let clamped = min(
                                max(newValue, AppPreferences.minMaxLLMTurns),
                                AppPreferences.maxAllowedLLMTurns
                            )
                            if clamped != newValue {
                                maxLLMTurns = clamped
                            }
                            AppPreferences.maxLLMTurns = clamped
                        }

                    Button {
                        maxLLMTurns = min(AppPreferences.maxAllowedLLMTurns, maxLLMTurns + 1)
                    } label: {
                        Image(systemName: "plus")
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.bordered)
                    .disabled(maxLLMTurns >= AppPreferences.maxAllowedLLMTurns)
                }
                .frame(maxWidth: .infinity)
            } header: {
                Text("大模型执行轮次")
            } footer: {
                Text("每次任务最多执行 \(AppPreferences.minMaxLLMTurns)–\(AppPreferences.maxAllowedLLMTurns) 轮，默认 \(AppPreferences.defaultMaxLLMTurns) 轮。")
            }

            Section {
                Toggle("输出思考内容", isOn: $showThinkingContentEnabled)
            } footer: {
                Text("开启后，聊天视图中会展示模型的思考过程（如 reasoning 字段或 think 标签内容）。关闭后仅显示最终回复。")
            }

            Section {
                Toggle("开启操作图像", isOn: $operationImageEnabled)
            } footer: {
                Text("开启后，与大模型对话时会自动附带当前手机屏幕截图，便于视觉模型分析界面。")
            }

            Section {
                Toggle("无障碍 UI 树", isOn: $accessibilityUiTreeEnabled)
            } footer: {
                Text("通过奶黄包无障碍服务读取当前界面控件（文本、坐标、可点击属性）。需开启无障碍权限，不依赖 ADB，WiFi 投屏也可用。")
            }

            Section {
                Toggle("View Debug / Layout Inspector", isOn: $viewDebugUiTreeEnabled)
            } footer: {
                Text("通过 ADB 读取 View 层级（dumpsys activity top / dump-view-hierarchy）。需开启开发者选项和 ADB 连接，Debug 版 App 效果最佳。")
            }

            Section {
                Toggle("uiautomator UI 树", isOn: $uiautomatorUiTreeEnabled)
            } footer: {
                Text("通过 adb uiautomator dump 获取 UI XML。需 ADB 连接，可与上述方式叠加使用。")
            }

            Section {
                Picker("调试模式", selection: $debugMode) {
                    ForEach(DebugMode.allCases) { mode in
                        Text(mode.displayName).tag(mode)
                    }
                }
            } footer: {
                Text("关闭：不记录日志并删除桌面 custard.log；日志：保留日志输出，不启用单步调试；调试：日志 + 聊天界面单步调试。")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("基础设置")
        .onChange(of: operationImageEnabled) { newValue in
            AppPreferences.operationImageEnabled = newValue
        }
        .onChange(of: accessibilityUiTreeEnabled) { newValue in
            AppPreferences.accessibilityUiTreeEnabled = newValue
        }
        .onChange(of: viewDebugUiTreeEnabled) { newValue in
            AppPreferences.viewDebugUiTreeEnabled = newValue
        }
        .onChange(of: uiautomatorUiTreeEnabled) { newValue in
            AppPreferences.uiautomatorUiTreeEnabled = newValue
        }
        .onChange(of: showThinkingContentEnabled) { newValue in
            AppPreferences.showThinkingContentEnabled = newValue
        }
        .onChange(of: debugMode) { newValue in
            AppPreferences.debugMode = newValue
            Logger.applyDesktopLogPreference()
        }
    }
}
