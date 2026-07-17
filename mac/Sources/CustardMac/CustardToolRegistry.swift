import Foundation

enum CustardToolKind: String, CaseIterable, Identifiable {
    case cli = "CLI"
    case mcp = "MCP"
    case skill = "SKILL"

    var id: String { rawValue }
}

struct CustardToolDefinition: Identifiable, Equatable {
    let id: String
    let kind: CustardToolKind
    let name: String
    let description: String
}

enum CustardToolRegistry {
    static let getScreenDescription = """
    获取当前 Android 手机屏幕状态：分辨率、连接诊断、前台 Activity、UI 元素摘要；可选附带原始尺寸 PNG 截图。
    """

    static let allTools: [CustardToolDefinition] = [
        CustardToolDefinition(
            id: "cli.get_screen",
            kind: .cli,
            name: "custard-screen",
            description: "命令行工具，通过本地 HTTP 服务获取手机屏幕 JSON，支持 --screenshot 附带截图。"
        ),
        CustardToolDefinition(
            id: "cli.open_app",
            kind: .cli,
            name: "custard-open-app",
            description: "命令行工具，通过 ADB 直接打开指定包名应用的主界面，成功时无输出。"
        ),
        CustardToolDefinition(
            id: "mcp.get_screen",
            kind: .mcp,
            name: "get_screen",
            description: "MCP 工具，供 Cursor 等 IDE 通过 MCP 协议调用，内部请求奶黄包本地屏幕服务。"
        ),
        CustardToolDefinition(
            id: "mcp.list_installed_apps",
            kind: .mcp,
            name: "list_installed_apps",
            description: "MCP 工具，通过 ADB 获取手机已安装第三方应用的名称与包名列表。"
        ),
        CustardToolDefinition(
            id: "mcp.open_app",
            kind: .mcp,
            name: "open_app",
            description: "MCP 工具，匹配已安装应用并通过 ADB 打开指定应用（包名或应用名）。"
        ),
        CustardToolDefinition(
            id: "mcp.tap_screen",
            kind: .mcp,
            name: "tap_screen",
            description: "MCP 工具，按屏幕像素坐标点击，支持 tap / double_tap / long_press。"
        ),
        CustardToolDefinition(
            id: "mcp.read_clipboard",
            kind: .mcp,
            name: "read_clipboard",
            description: "MCP 工具，读取 Android 手机当前剪贴板文本。需屏幕共享已连接。"
        ),
        CustardToolDefinition(
            id: "mcp.write_clipboard",
            kind: .mcp,
            name: "write_clipboard",
            description: "MCP 工具，向 Android 手机剪贴板写入文本。需屏幕共享已连接。"
        ),
        CustardToolDefinition(
            id: "mcp.type_text",
            kind: .mcp,
            name: "type_text",
            description: "MCP 工具，模拟键盘向手机当前焦点输入框输入文本，支持中文。需屏幕共享已连接。"
        ),
        CustardToolDefinition(
            id: "mcp.press_home",
            kind: .mcp,
            name: "press_home",
            description: "MCP 工具，按下 Android 手机的 Home 键，返回系统桌面。"
        ),
        CustardToolDefinition(
            id: "mcp.press_back",
            kind: .mcp,
            name: "press_back",
            description: "MCP 工具，按下 Android 手机的 Back 键，返回上一页或关闭当前界面。"
        ),
        CustardToolDefinition(
            id: LLMTools.getScreenName,
            kind: .skill,
            name: LLMTools.getScreenName,
            description: getScreenDescription + "供奶黄包内置大模型按需调用。"
        ),
        CustardToolDefinition(
            id: LLMTools.pressHomeName,
            kind: .skill,
            name: LLMTools.pressHomeName,
            description: "按下 Android 手机的 Home 键，返回系统桌面。供内置大模型按需调用。"
        ),
        CustardToolDefinition(
            id: LLMTools.pressBackName,
            kind: .skill,
            name: LLMTools.pressBackName,
            description: "按下 Android 手机的 Back 键，返回上一页或关闭当前界面。供内置大模型按需调用。"
        ),
        CustardToolDefinition(
            id: LLMTools.listInstalledAppsName,
            kind: .skill,
            name: LLMTools.listInstalledAppsName,
            description: "获取手机已安装第三方应用的名称与包名。供内置大模型按需调用。"
        ),
        CustardToolDefinition(
            id: LLMTools.openAppName,
            kind: .skill,
            name: LLMTools.openAppName,
            description: "打开指定应用（包名或应用名，支持模糊匹配）。供内置大模型按需调用。"
        ),
        CustardToolDefinition(
            id: LLMTools.tapScreenName,
            kind: .skill,
            name: LLMTools.tapScreenName,
            description: "按屏幕像素坐标触摸，支持单击/双击/长按。供内置大模型按需调用。"
        ),
        CustardToolDefinition(
            id: LLMTools.readClipboardName,
            kind: .skill,
            name: LLMTools.readClipboardName,
            description: "读取 Android 手机剪贴板文本。供内置大模型按需调用。"
        ),
        CustardToolDefinition(
            id: LLMTools.writeClipboardName,
            kind: .skill,
            name: LLMTools.writeClipboardName,
            description: "向 Android 手机剪贴板写入文本。供内置大模型按需调用。"
        ),
        CustardToolDefinition(
            id: LLMTools.typeTextName,
            kind: .skill,
            name: LLMTools.typeTextName,
            description: "模拟键盘向手机当前焦点输入框输入文本。供内置大模型按需调用。"
        ),
        CustardToolDefinition(
            id: LLMTools.locateControlName,
            kind: .skill,
            name: LLMTools.locateControlName,
            description: "在指定截图中定位控件并返回中心点像素坐标。入参为本地截图路径与控件描述。供内置大模型按需调用。"
        )
    ]

    static var enabledLLMToolDefinitions: [[String: Any]] {
        LLMTools.openAIToolDefinitions.filter { definition in
            guard
                let function = definition["function"] as? [String: Any],
                let name = function["name"] as? String
            else { return false }
            return AppPreferences.isToolEnabled(id: name)
        }
    }

    static let toolDisabledMessage = "该工具已被用户禁用，无法调用。请告知用户前往首页「工具(MCP/SKILL/CLI)」中开启后再试。"

    static func disabledToolResult(for toolName: String) -> String {
        "工具 \(toolName) 已被用户禁用。\(toolDisabledMessage)"
    }
}
