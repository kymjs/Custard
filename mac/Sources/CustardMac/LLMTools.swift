import Foundation

struct LLMToolCall: Equatable, Codable {
    let id: String
    let name: String
    let argumentsJSON: String
}

enum LLMTools {
    static let getScreenName = "get_screen"
    static let pressHomeName = "press_home"
    static let pressBackName = "press_back"
    static let listInstalledAppsName = "list_installed_apps"
    static let openAppName = "open_app"
    static let tapScreenName = "tap_screen"
    static let readClipboardName = "read_clipboard"
    static let writeClipboardName = "write_clipboard"
    static let typeTextName = "type_text"
    static let locateControlName = "locate_control"

    static let openAIToolDefinitions: [[String: Any]] = [
        [
            "type": "function",
            "function": [
                "name": getScreenName,
                "description": """
                获取当前 Android 手机屏幕状态：分辨率、连接诊断、前台 Activity、UI 元素摘要；\
                可选附带原始尺寸 PNG 截图。截图时会在返回中包含 screenshot_path 本地路径供 locate_control 使用。\
                开始任务前、执行操作后、或需要确认界面变化时调用。
                """,
                "parameters": [
                    "type": "object",
                    "properties": [
                        "include_screenshot": [
                            "type": "boolean",
                            "description": "是否附带截图。视觉分析或不确定点击位置时建议 true。"
                        ]
                    ]
                ] as [String: Any]
            ] as [String: Any]
        ],
        [
            "type": "function",
            "function": [
                "name": pressHomeName,
                "description": "按下 Android 手机的 Home 键，返回系统桌面。",
                "parameters": [
                    "type": "object",
                    "properties": [:] as [String: Any]
                ] as [String: Any]
            ] as [String: Any]
        ],
        [
            "type": "function",
            "function": [
                "name": pressBackName,
                "description": "按下 Android 手机的 Back 键，返回上一页或关闭当前界面。",
                "parameters": [
                    "type": "object",
                    "properties": [:] as [String: Any]
                ] as [String: Any]
            ] as [String: Any]
        ],
        [
            "type": "function",
            "function": [
                "name": listInstalledAppsName,
                "description": """
                获取当前手机已安装的第三方应用列表，包含应用名称与包名。需要 ADB 连接。\
                打开应用前可先调用此工具确认包名。
                """,
                "parameters": [
                    "type": "object",
                    "properties": [:] as [String: Any]
                ] as [String: Any]
            ] as [String: Any]
        ],
        [
            "type": "function",
            "function": [
                "name": openAppName,
                "description": """
                打开指定 Android 应用。会先获取已安装应用列表并匹配包名或应用名称，\
                匹配成功后通过 ADB 启动应用。
                """,
                "parameters": [
                    "type": "object",
                    "properties": [
                        "package_or_name": [
                            "type": "string",
                            "description": "应用包名（如 com.tencent.mm）或应用名称（如 微信），支持模糊匹配。"
                        ]
                    ],
                    "required": ["package_or_name"]
                ] as [String: Any]
            ] as [String: Any]
        ],
        [
            "type": "function",
            "function": [
                "name": tapScreenName,
                "description": """
                点击手机屏幕指定位置。必须使用屏幕实际像素坐标，左上角为 (0, 0)，\
                x 不得超过屏幕宽度，y 不得超过屏幕高度。\
                通过 action 指定点击、双击或长按。
                """,
                "parameters": [
                    "type": "object",
                    "properties": [
                        "x": [
                            "type": "number",
                            "description": "横向像素坐标，0 为屏幕最左。"
                        ],
                        "y": [
                            "type": "number",
                            "description": "纵向像素坐标，0 为屏幕最上。"
                        ],
                        "action": [
                            "type": "string",
                            "enum": ["tap", "double_tap", "long_press"],
                            "description": "触摸动作：tap=单击，double_tap=双击，long_press=长按。默认 tap。"
                        ]
                    ],
                "required": ["x", "y"]
                ] as [String: Any]
            ] as [String: Any]
        ],
        [
            "type": "function",
            "function": [
                "name": readClipboardName,
                "description": "读取当前 Android 手机的剪贴板文本内容。需要奶黄包屏幕共享已连接。",
                "parameters": [
                    "type": "object",
                    "properties": [:] as [String: Any]
                ] as [String: Any]
            ] as [String: Any]
        ],
        [
            "type": "function",
            "function": [
                "name": writeClipboardName,
                "description": "向 Android 手机剪贴板写入文本内容。需要奶黄包屏幕共享已连接。",
                "parameters": [
                    "type": "object",
                    "properties": [
                        "text": [
                            "type": "string",
                            "description": "要写入手机剪贴板的文本内容。"
                        ]
                    ],
                    "required": ["text"]
                ] as [String: Any]
            ] as [String: Any]
        ],
        [
            "type": "function",
            "function": [
                "name": typeTextName,
                "description": """
                在当前获得焦点的输入框中模拟键盘输入文本。优先通过屏幕共享通道发送，支持中文；\
                需确保目标输入框已聚焦（可先 tap_screen 点击输入框）。
                """,
                "parameters": [
                    "type": "object",
                    "properties": [
                        "text": [
                            "type": "string",
                            "description": "要输入到手机当前焦点控件的文本内容。"
                        ]
                    ],
                    "required": ["text"]
                ] as [String: Any]
            ] as [String: Any]
        ],
        [
            "type": "function",
            "function": [
                "name": locateControlName,
                "description": """
                在指定截图中定位控件，返回其中心点的像素坐标（x / y），\
                可直接用于 tap_screen。必须显式传入 get_screen 返回的 screenshot_path。\
                适用于登录按钮、协议勾选框、文案、圆形/方形 checkbox 等。
                """,
                "parameters": [
                    "type": "object",
                    "properties": [
                        "image_path": [
                            "type": "string",
                            "description": "本地截图文件绝对路径，通常为 get_screen 返回的 screenshot_path。"
                        ],
                        "description": [
                            "type": "string",
                            "description": "图中一定存在的控件描述，如「登录按钮」「协议勾选框」「同意并继续」。"
                        ]
                    ],
                    "required": ["image_path", "description"]
                ] as [String: Any]
            ] as [String: Any]
        ]
    ]

    static func parseGetScreenArguments(_ json: String) -> GetScreenArguments {
        guard
            let data = json.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return GetScreenArguments(includeScreenshot: nil)
        }
        let includeScreenshot = object["include_screenshot"] as? Bool
        return GetScreenArguments(includeScreenshot: includeScreenshot)
    }

    static func parseOpenAppArguments(_ json: String) -> OpenAppArguments {
        guard
            let data = json.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return OpenAppArguments(packageOrName: "")
        }
        let value = object["package_or_name"] as? String ?? ""
        return OpenAppArguments(packageOrName: value)
    }

    static func parseTapScreenArguments(_ json: String) -> TapScreenArguments {
        guard
            let data = json.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return TapScreenArguments(x: nil, y: nil, action: nil)
        }
        let x = numericValue(object["x"])
        let y = numericValue(object["y"])
        let action = object["action"] as? String
        return TapScreenArguments(x: x, y: y, action: action)
    }

    static func parseWriteClipboardArguments(_ json: String) -> WriteClipboardArguments {
        guard
            let data = json.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return WriteClipboardArguments(text: "")
        }
        let text = object["text"] as? String ?? ""
        return WriteClipboardArguments(text: text)
    }

    static func parseLocateControlArguments(_ json: String) -> LocateControlArguments {
        guard
            let data = json.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return LocateControlArguments(imagePath: "", description: "")
        }
        let imagePath = object["image_path"] as? String ?? ""
        let description = object["description"] as? String ?? ""
        return LocateControlArguments(imagePath: imagePath, description: description)
    }

    private static func numericValue(_ value: Any?) -> Double? {
        if let number = value as? Double { return number }
        if let number = value as? Int { return Double(number) }
        if let text = value as? String, let number = Double(text) { return number }
        return nil
    }

    static func parseToolCalls(_ value: Any?) -> [LLMToolCall] {
        guard let rawCalls = value as? [[String: Any]] else { return [] }
        return rawCalls.compactMap { raw in
            guard
                let id = raw["id"] as? String,
                let function = raw["function"] as? [String: Any],
                let name = function["name"] as? String
            else { return nil }
            let arguments = function["arguments"] as? String ?? "{}"
            return LLMToolCall(id: id, name: name, argumentsJSON: arguments)
        }
    }
}

struct GetScreenArguments {
    let includeScreenshot: Bool?
}

struct OpenAppArguments {
    let packageOrName: String
}

struct TapScreenArguments {
    let x: Double?
    let y: Double?
    let action: String?
}

struct WriteClipboardArguments {
    let text: String
}

struct LocateControlArguments {
    let imagePath: String
    let description: String
}

struct ScreenCapturePayload {
    let description: String
    let imageBase64: String?
    let screenshotPath: String?
    let hasSuccessfulUiSource: Bool
    let packageName: String?
    let screenWidth: Int
    let screenHeight: Int
    let uiTreeSummary: String?

    init(
        description: String,
        imageBase64: String?,
        screenshotPath: String? = nil,
        hasSuccessfulUiSource: Bool = false,
        packageName: String? = nil,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        uiTreeSummary: String? = nil
    ) {
        self.description = description
        self.imageBase64 = imageBase64
        self.screenshotPath = screenshotPath
        self.hasSuccessfulUiSource = hasSuccessfulUiSource
        self.packageName = packageName
        self.screenWidth = screenWidth
        self.screenHeight = screenHeight
        self.uiTreeSummary = uiTreeSummary
    }
}
