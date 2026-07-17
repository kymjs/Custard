import Foundation

enum LLMError: LocalizedError {
    case invalidResponse
    case apiError(statusCode: Int, message: String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "大模型返回格式无效"
        case .apiError(let statusCode, let message):
            return "请求失败 (\(statusCode)): \(message)"
        }
    }
}

struct LLMPreprocessResult {
    enum Action: Equatable {
        case clarify
        case execute
    }

    let action: Action
    let displayText: String
    let tasks: [String]
    /// 模型原始回复（含思考标签），供 MiniMax-M3 等多轮推理链回传。
    let rawAssistantText: String
}

struct LLMChatResult {
    enum TaskStatus: String {
        case success
        case needsUserInput = "needs_user_input"
        case failed
    }

    let content: String
    let reasoning: String?
    let reasoningDetailsJSON: String?
    let toolCalls: [LLMToolCall]?
    let taskStatus: TaskStatus?

    init(
        content: String,
        reasoning: String?,
        reasoningDetailsJSON: String? = nil,
        toolCalls: [LLMToolCall]? = nil,
        taskStatus: TaskStatus? = nil
    ) {
        self.content = content
        self.reasoning = reasoning
        self.reasoningDetailsJSON = reasoningDetailsJSON
        self.toolCalls = toolCalls
        self.taskStatus = taskStatus
    }

    var hasToolCalls: Bool {
        guard let toolCalls else { return false }
        return !toolCalls.isEmpty
    }

    /// 用于 ADB 解析与后续轮次，始终取正文（不含思考过程）。
    var textForExecution: String {
        if hasToolCalls { return "" }
        let stripped = Self.stripThinkTags(from: content)
        if !stripped.isEmpty { return stripped }
        if let reasoning, !reasoning.isEmpty { return reasoning }
        return content
    }

    func textForDisplay(showThinking: Bool) -> String {
        if showThinking {
            if let reasoning, !reasoning.isEmpty, !content.isEmpty, content != reasoning {
                return reasoning + "\n\n" + content
            }
            if !content.isEmpty { return content }
            if let reasoning, !reasoning.isEmpty { return reasoning }
            return ""
        }

        if let reasoning, !reasoning.isEmpty, content.isEmpty {
            return ""
        }
        if let reasoning, !reasoning.isEmpty, content == reasoning {
            let stripped = Self.stripThinkTags(from: content)
            return stripped == content ? "" : stripped
        }
        return Self.stripThinkTags(from: content)
    }

    static func stripThinkTags(from text: String) -> String {
        guard !text.isEmpty else { return text }
        var result = text
        let patterns = [
            "(?is)<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>",
            "(?is)<think>[\\s\\S]*?</think>",
            "(?is)```(?:think|thinking)?\\s*\\r?\\n[\\s\\S]*?```"
        ]
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(result.startIndex..<result.endIndex, in: result)
            result = regex.stringByReplacingMatches(in: result, range: range, withTemplate: "")
        }
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

enum LLMService {
    static let preprocessSystemPrompt = """
    你负责对手机操作任务进行预处理。你会收到用户对话历史，以及可能存在的当前手机屏幕信息。
    屏幕信息只是辅助理解；即使屏幕信息不可用，也必须根据用户描述继续判断。

    你当前不是执行 Agent：禁止调用任何工具，禁止输出 locate_control / tap_screen / 坐标分析 / 执行步骤叙述。
    无论历史里是否出现过工具调用或执行过程，本阶段都只能做意图判断与任务拆分。

    你的职责：
    1. 判断用户意图是否足够明确。如果缺少必要信息、存在多个合理解释，必须先询问用户，不能猜测。
    2. 如果任务明确，将用户任务拆分为有顺序的、可独立执行的最小任务。
    3. 用户补充信息后，结合历史上下文自行判断是继续当前子任务、重新执行当前子任务，还是重新拆分完整任务。
    4. action 为 execute 时，客户端会先向用户展示拆分结果并等待确认；用户确认后才会执行。用户若补充或修改意图，会重新进入预处理。
    5. 若「当前任务状态」已给出任务列表，且用户要求继续/重试当前子任务，仍返回 action=execute，tasks 返回完整任务列表（可按用户意见微调），message 说明将如何继续。

    只能返回一个 JSON 对象，不要 markdown 代码块，不要思考过程，不要其它文字。需要澄清时返回：
    {"action":"clarify","message":"要向用户询问的问题","tasks":[]}
    可以执行时返回：
    {"action":"execute","message":"任务拆分：\n1. ...\n2. ...\n\n请确认后点击「确认执行」，或直接补充修改意见。","tasks":["第一个子任务","第二个子任务"]}

    tasks 必须是非空字符串数组。只有 action 为 clarify 时 tasks 才允许为空。
    message 会作为普通助手消息展示给用户。不要要求用户输入特殊状态词。
    """

    private static let preprocessFormatReminder = """
    你的上一条回复不是合法 JSON。请严格只返回一个 JSON 对象，不要 markdown、不要自然语言、不要工具调用。
    格式二选一：
    {"action":"clarify","message":"要向用户询问的问题","tasks":[]}
    {"action":"execute","message":"任务拆分说明","tasks":["子任务1","子任务2"]}
    """

    static let systemPrompt = """
    你是 Custard（奶黄包）手机控制助手。Custard 是一个通过 Mac 电脑端远程控制 Android 手机的系统：

    - 手机端运行屏幕共享服务，Mac 端可实时查看手机屏幕，并通过触摸、按键、文本输入协议直接操控界面。
    - Mac 端还接入了 ADB（Android Debug Bridge），可执行 adb shell 命令完成安装应用、启动 Activity、读取 logcat 等操作。
    - 屏幕共享通道可独立工作（尤其 WiFi 直连时）：即使 adb devices 为空，Mac 仍会自动将 `adb shell input tap/swipe/text/keyevent` 转为屏幕共享协议执行。
    - 用户在聊天输入框中描述的任务，就是你需要帮其完成的手机操作目标。

    - 你可随时调用工具 **get_screen** 获取当前手机屏幕（分辨率、前台 Activity、UI 元素摘要，可选截图）。\
    任务预处理阶段会先获取一次 get_screen；执行当前子任务时可在界面发生变化或信息不足时再次调用。**不要连续多次调用 get_screen**——已有截图和 UI 摘要后应直接操作。
    - 需要精确点击按钮、文案、圆形/方形勾选框等控件时：先 `get_screen` 并带截图，从返回中读取 **screenshot_path**，\
    再调用 **locate_control**（传入 image_path=screenshot_path 与控件 description），用返回的 **x / y 像素坐标**调用 **tap_screen**。\
    locate_control 必须显式传入截图路径，不要省略。
    - **禁止**对协议勾选框、小图标、小按钮凭截图或 UI bounds「心算」直接调用 tap_screen；\
    坐标不确定时必须走 locate_control。大而醒目的全宽主按钮在 UI 树有明确像素 bounds 时可直接用其中心坐标点击。
    - 若上下文提供「已知点击坐标经验」且当前为同一应用同一页面、控件描述匹配，可优先用经验坐标调用 tap_screen；\
    若点击后界面未达预期，必须重新 locate_control。
    - 在微信等搜索联系人时，locate_control 的 description 必须包含精确联系人名并用引号包裹，\
    例如：联系人列表第一项"张涛"（排除昵称行）。禁止仅用「绿色文字」「头像」等视觉描述而不带姓名原文。
    - 若 get_screen 返回的 UI 树中有目标联系人名且带 @ [left,top][right,bottom]，\
    优先直接用该 bounds 中心坐标 tap_screen，无需 locate_control。
    - 点击/勾选后必须再调用 get_screen 确认界面确已变化；**禁止**在未确认前声称「已勾选/已登录成功」。
    - 协议勾选框是 toggle：若截图或 locate_control 显示已勾选（有勾/实心圆/高亮），**禁止**再次点击同一勾选框，否则会取消勾选。
    - 返回桌面请调用 **press_home**；返回上一页请调用 **press_back**（优先于手写 keyevent adb 命令）。
    - 查看已安装应用请调用 **list_installed_apps**；打开应用请调用 **open_app**（传入包名或应用名，会先校验是否已安装）。
    - 点击屏幕请调用 **tap_screen**，传入 **x / y（屏幕像素坐标）**；\
    可选 **action**：`tap` 单击、`double_tap` 双击、`long_press` 长按（默认 tap）。
    - 读取手机剪贴板请调用 **read_clipboard**；写入手机剪贴板请调用 **write_clipboard**（传入 text）。
    - 向当前输入框模拟键盘输入请调用 **type_text**（传入 text）；输入前请先用 tap_screen 聚焦目标输入框。
    - 界面元素可来自多种可选来源（在「基础设置」中配置）：无障碍 UI 树（推荐）、View Debug、uiautomator dump。
    - UI 元素包含文本、resource-id、屏幕坐标 bounds，请优先依据坐标和属性决定点击位置。
    - 兼容旧格式：也可在回复中单独一行输出 [REQUEST_SCREEN] 请求屏幕（等效于 get_screen）。

    你的工作方式：
    1. 理解用户任务，调用 get_screen 查看当前界面，再拆解步骤。
    2. 结合屏幕信息决定操作，无需让用户描述界面。
    3. 优先给出具体、可执行的 adb 命令（用 ```adb ...``` 代码块包裹完整命令行）。Mac 端会自动执行这些命令，并将输出结果反馈给你。点击/滑动/输入优先使用 `adb shell input tap/swipe/text/keyevent`。
    4. 每次回复只给出当前步骤需要的命令；收到执行结果后再决定下一步，必要时再次 get_screen。
    5. 若更适合界面操作，说明应点击/滑动的位置或输入的文字，Mac 端会配合执行。
    6. 回复使用中文，简洁明确；先简述计划，再给出命令或操作步骤。
    7. 不要假设设备已 root；避免破坏性操作；涉及敏感权限时提醒用户确认。
    - 当前模型调用只负责一个明确的子任务，不要主动执行其它子任务。
    - 如果执行当前子任务所需的信息不足，必须直接向用户提问，并在回复开头加入 [NEEDS_USER_INPUT]；不要猜测。
    - 没有工具调用的最终回复必须只返回 JSON：{"status":"success|needs_user_input|failed","message":"面向用户的简短结果"}。
    - 不得用「上一子任务已完成」作为本子任务 success 的理由；本子任务必须对本步目标完成实际操作（点击/输入/开应用等）后才能返回 status=success。
    - 若本轮未对本子任务做任何推进性操作，禁止返回 status=success；应继续调用工具，或返回 needs_user_input / failed。
    """

    private static let locateControlSystemPrompt = """
    你是 UI 控件定位助手。用户消息会给出原图像素宽高；截图上已叠加青色 10% 间距网格线（竖线/横线对应宽或高的 10、20…90%）。
    只输出一个 JSON 对象，不要其它文字、markdown、或思考过程。

    坐标系：原图左上角为 (0,0)，向右为 +x，向下为 +y。必须返回绝对像素整数；禁止百分比、0–1 或 0–1000 归一化坐标。
    定位步骤：先用网格估测目标中心的相对位置（例如底栏最右约 x≈90%、y≈95%），再换算：
    x = round(相对x百分比 / 100 * image_width)，y = round(相对y百分比 / 100 * image_height)。
    结果必须落在 [0, image_width-1] 与 [0, image_height-1] 内。

    优先返回可点击中心点：
    {"x": <int>, "y": <int>}
    更有把握时也可返回像素包围盒（客户端取几何中心）：
    {"left": <int>, "top": <int>, "right": <int>, "bottom": <int>}
    找不到：{"error":"not_found","message":"<原因>","hint":"请给出控件在截图中更详细的描述（相对位置、文案原文、形状颜色等）"}

    必须对准目标控件本体中心（按钮对准按钮中心；勾选框对准方框/圆点中心）。
    底部导航、页签等贴底控件的 y 通常接近图像高度的约 90%–98%，不要估到中部内容区。
    若目标是协议/隐私/已阅读/同意前的勾选框，必须返回文字左侧小圆圈或小方框本体中心，\
    不要返回「我已阅读并同意」文字、整行协议文本、文字起点或弹窗按钮。若圆圈/方框位于文字左侧，\
    x 应显著小于文字起点；包围盒只框住圆圈/方框本体。
    列表/搜索结果场景：同一屏幕可能有多行相似文字；必须定位 description 指定的那一行，\
    以该行可点击区域（整行或姓名文字）中心为准，不要取相邻行。
    估 Y 坐标时：列表第一项通常在分区标题（如「联系人」）正下方第一行，\
    不要误取第二行或更下方网络搜索结果。
    """

    static func chat(
        messages: [ChatMessage],
        taskContext: String? = nil
    ) async throws -> LLMChatResult {
        let provider = AppPreferences.modelProvider

        if provider == .anthropic {
            return try await chatAnthropic(messages: messages, taskContext: taskContext)
        }

        guard let config = ModelRuntimeConfig.build() else {
            throw LLMError.apiError(
                statusCode: 0,
                message: AppPreferences.validateModelConfig() ?? "模型配置不完整"
            )
        }

        return try await chatOpenAICompatible(
            messages: messages,
            config: config,
            taskContext: taskContext
        )
    }

    static func classifyTask(_ task: String) async throws -> TaskExperienceClassification {
        let text = try await requestStructuredText(
            systemPrompt: """
            你负责给手机操作任务归类。只返回 JSON，不要 markdown：
            {"category":"简短、稳定、可复用的任务类别"}
            类别应概括任务目的，不要包含具体应用名称、账号、联系人或临时数据。
            """,
            userText: task
        )
        guard let object = jsonObject(from: text),
              let category = object["category"] as? String,
              !category.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw LLMError.invalidResponse
        }
        return TaskExperienceClassification(category: category)
    }

    static func summarizeSuccessfulTask(
        goal: String,
        category: String,
        messages: [ChatMessage]
    ) async throws -> TaskExperienceSummary {
        let toolNamesByCallID = Dictionary(
            uniqueKeysWithValues: messages
                .flatMap { $0.toolCalls ?? [] }
                .map { ($0.id, $0.name) }
        )
        let transcript = messages
            .filter {
                !$0.isScreenContext
                    && (!$0.content.isEmpty || !($0.toolCalls?.isEmpty ?? true) || $0.isToolResult)
            }
            .suffix(80)
            .map { message -> String in
                if let toolCalls = message.toolCalls, !toolCalls.isEmpty {
                    return toolCalls.map { call in
                        let arguments = Self.experienceSafeArguments(
                            call.argumentsJSON,
                            toolName: call.name
                        )
                        return "\(message.role.rawValue): 尝试调用 \(call.name)，参数：\(arguments)"
                    }.joined(separator: "\n")
                }
                if message.isToolResult {
                    let toolName = message.toolCallId.flatMap { toolNamesByCallID[$0] } ?? "未知工具"
                    return "工具结果 \(toolName)：\(Self.experienceSafeText(message.content))"
                }
                if message.isAdbResult {
                    return "ADB 执行记录：\(Self.experienceSafeText(message.content))"
                }
                return "\(message.role.rawValue): \(message.content)"
            }
            .joined(separator: "\n")
        let text = try await requestStructuredText(
            systemPrompt: """
            你负责总结已经成功完成的手机操作任务。只返回 JSON，不要 markdown：
            {"category":"任务类别","goal":"概括后的任务目标","steps":["按实际执行顺序的操作步骤"]}
            执行记录包含工具调用、失败结果、重试和替代方案；请识别最终成功的方法，记录必要的失败规避经验和最终成功步骤，删除无效的重复操作。
            只记录可复用的操作步骤，不记录账号、密码、完整剪贴板内容、截图、API Key 或临时个人信息。
            """,
            userText: "原始目标：\(goal)\n任务类别：\(category)\n执行记录：\n\(transcript)"
        )
        guard let object = jsonObject(from: text),
              let resultCategory = object["category"] as? String,
              let resultGoal = object["goal"] as? String,
              let steps = object["steps"] as? [String],
              !resultCategory.isEmpty, !resultGoal.isEmpty, !steps.isEmpty else {
            throw LLMError.invalidResponse
        }
        return TaskExperienceSummary(category: resultCategory, goal: resultGoal, steps: steps)
    }

    static func classifyPageLabel(packageName: String, uiSummary: String) async throws -> String {
        let text = try await requestStructuredText(
            systemPrompt: """
            你负责给当前手机界面起一个稳定、可复用的页面标签。只返回 JSON，不要 markdown：
            {"pageLabel":"简短页面标签"}
            标签应概括页面用途（如「登录页」「设置-账号」「首页」），不要包含账号、验证码、临时文案或时间戳。
            """,
            userText: "应用包名：\(packageName)\n界面摘要：\n\(TapCoordinateMatcher.truncateUi(uiSummary))"
        )
        guard let object = jsonObject(from: text),
              let label = object["pageLabel"] as? String,
              !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw LLMError.invalidResponse
        }
        return label.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func verifyTapHit(
        controlLabel: String,
        packageName: String,
        beforeUiSummary: String,
        afterUiSummary: String
    ) async throws -> TapHitVerification {
        let text = try await requestStructuredText(
            systemPrompt: """
            你负责判断一次屏幕点击是否点到了目标控件（业务层面成功）。只返回 JSON，不要 markdown：
            {"hit":true或false,"packageName":"应用包名","pageLabel":"点击时所在页面的稳定标签","controlLabel":"被点击控件的稳定标签"}
            hit=true 仅当界面变化或控件状态表明目标控件确实被正确点击；点错、无效果、点到邻近控件均为 false。
            pageLabel / controlLabel 应简短可复用，不含账号等临时信息。
            """,
            userText: """
            目标控件：\(controlLabel)
            应用包名：\(packageName)
            点击前界面：
            \(TapCoordinateMatcher.truncateUi(beforeUiSummary))
            点击后界面：
            \(TapCoordinateMatcher.truncateUi(afterUiSummary))
            """
        )
        guard let object = jsonObject(from: text) else {
            throw LLMError.invalidResponse
        }
        let hit: Bool
        if let boolHit = object["hit"] as? Bool {
            hit = boolHit
        } else if let numberHit = object["hit"] as? NSNumber {
            hit = numberHit.boolValue
        } else {
            hit = false
        }
        let resultPackage = (object["packageName"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let pageLabel = (object["pageLabel"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let resultControl = (object["controlLabel"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return TapHitVerification(
            hit: hit,
            packageName: resultPackage.isEmpty ? packageName : resultPackage,
            pageLabel: pageLabel,
            controlLabel: resultControl.isEmpty ? controlLabel : resultControl
        )
    }

    private static func experienceSafeArguments(_ arguments: String, toolName: String) -> String {
        if toolName == LLMTools.typeTextName || toolName == LLMTools.writeClipboardName {
            return "敏感文本已省略"
        }
        return experienceSafeText(arguments, limit: 600)
    }

    private static func experienceSafeText(_ text: String, limit: Int = 1200) -> String {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map { line in
            let value = String(line)
            if value.localizedCaseInsensitiveContains("剪贴板")
                || value.localizedCaseInsensitiveContains("password")
                || value.localizedCaseInsensitiveContains("api_key")
                || value.localizedCaseInsensitiveContains("token") {
                return "敏感内容已省略"
            }
            return value
        }
        var result = lines.joined(separator: "\n")
        let patterns = [
            "(?i)(authorization|bearer|api[-_]?key|secret|password|token)\\s*[:=]\\s*[^\\s,;]+"
        ]
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(result.startIndex..<result.endIndex, in: result)
            result = regex.stringByReplacingMatches(
                in: result,
                range: range,
                withTemplate: "$1=敏感内容已省略"
            )
        }
        if result.count > limit {
            result = String(result.prefix(limit)) + "…"
        }
        return result
    }

    static func preprocess(
        messages: [ChatMessage],
        stateDescription: String?
    ) async throws -> LLMPreprocessResult {
        let systemPrompt = stateDescription.map {
            preprocessSystemPrompt + "\n\n当前任务状态：\n" + $0
        } ?? preprocessSystemPrompt
        let filtered = messagesForPreprocess(messages)
        let reduction = LLMContextReducer.reduce(messages: filtered, systemPrompt: systemPrompt)
        Logger.chat(
            "preprocess context originalMessages=\(messages.count) "
            + "filteredMessages=\(filtered.count) "
            + "sentMessages=\(reduction.messages.count) "
            + "estimatedInputTokens=\(reduction.estimatedInputTokens) "
            + "reduced=\(reduction.wasReduced)"
        )

        var requestMessages = reduction.messages
        var text = try await requestPreprocessText(messages: requestMessages, systemPrompt: systemPrompt)
        Logger.chat("LLM preprocess content:\n\(text)")
        do {
            return try parsePreprocessResult(text)
        } catch {
            Logger.chat("preprocess parse failed, retrying with format reminder")
            requestMessages.append(ChatMessage(role: .assistant, content: text))
            requestMessages.append(ChatMessage(role: .user, content: preprocessFormatReminder))
            text = try await requestPreprocessText(messages: requestMessages, systemPrompt: systemPrompt)
            Logger.chat("LLM preprocess retry content:\n\(text)")
            return try parsePreprocessResult(text)
        }
    }

    /// 预处理只需要用户意图与拆分结果，排除 Agent 执行/工具历史，避免模型切到执行角色。
    private static func messagesForPreprocess(_ messages: [ChatMessage]) -> [ChatMessage] {
        var kept: [ChatMessage] = []
        var latestScreen: ChatMessage?
        for message in messages {
            if message.isScreenContext {
                latestScreen = message
                continue
            }
            if message.isToolResult || message.role == .tool || message.isAdbResult {
                continue
            }
            if let toolCalls = message.toolCalls, !toolCalls.isEmpty {
                continue
            }
            if message.role == .user {
                kept.append(message)
                continue
            }
            if message.isPreprocessResult {
                kept.append(message)
            }
        }
        if let latestScreen {
            kept.append(latestScreen)
        }
        return kept
    }

    private static func requestPreprocessText(
        messages: [ChatMessage],
        systemPrompt: String
    ) async throws -> String {
        if AppPreferences.modelProvider == .anthropic {
            return try await preprocessAnthropic(messages: messages, systemPrompt: systemPrompt)
        }
        guard let config = ModelRuntimeConfig.build() else {
            throw LLMError.apiError(
                statusCode: 0,
                message: AppPreferences.validateModelConfig() ?? "模型配置不完整"
            )
        }
        return try await preprocessOpenAICompatible(
            messages: messages,
            systemPrompt: systemPrompt,
            config: config
        )
    }

    static func locateControlVision(
        imageBase64: String,
        imageWidth: Int,
        imageHeight: Int,
        description: String
    ) async throws -> String {
        let provider = AppPreferences.modelProvider
        if provider == .anthropic {
            return try await locateControlVisionAnthropic(
                imageBase64: imageBase64,
                imageWidth: imageWidth,
                imageHeight: imageHeight,
                description: description
            )
        }

        guard let config = ModelRuntimeConfig.build() else {
            throw LLMError.apiError(
                statusCode: 0,
                message: AppPreferences.validateModelConfig() ?? "模型配置不完整"
            )
        }

        let userText = """
        原图像素尺寸：image_width=\(imageWidth)，image_height=\(imageHeight)。
        图上青色网格为相对位置辅助（10% 间距）；请先估测相对位置，再换算为该尺寸下的绝对像素整数。
        只返回 JSON；禁止百分比或归一化坐标。
        控件描述：\(description)
        """

        var body: [String: Any] = [
            "model": config.model,
            "messages": [
                ["role": "system", "content": locateControlSystemPrompt],
                [
                    "role": "user",
                    "content": [
                        ["type": "text", "text": userText],
                        [
                            "type": "image_url",
                            "image_url": [
                            "url": "data:image/png;base64,\(imageBase64)"
                            ]
                        ]
                    ]
                ]
            ]
        ]
        for (key, value) in openAICompatibleBodyExtras(provider: config.provider, model: config.model) {
            body[key] = value
        }
        let bodyData = try JSONSerialization.data(withJSONObject: body)

        var request = URLRequest(url: config.endpoint)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.timeoutInterval = 180
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in config.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }

        Logger.chat("locate_control vision request model=\(config.model) size=\(imageWidth)x\(imageHeight)")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw LLMError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            let message = parseErrorMessage(from: data) ?? String(data: data, encoding: .utf8) ?? "未知错误"
            throw LLMError.apiError(statusCode: http.statusCode, message: message)
        }

        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let choices = json["choices"] as? [[String: Any]],
            let first = choices.first
        else {
            throw LLMError.invalidResponse
        }

        let result = try parseAssistantResult(from: first, rawData: data)
        let text = result.content.isEmpty ? (result.reasoning ?? "") : result.content
        if text.isEmpty {
            throw LLMError.invalidResponse
        }
        Logger.chat("locate_control vision LLM content:\n\(text)")
        return text
    }

    private static func preprocessOpenAICompatible(
        messages: [ChatMessage],
        systemPrompt: String,
        config: ModelRuntimeConfig
    ) async throws -> String {
        var apiMessages: [[String: Any]] = [
            ["role": "system", "content": systemPrompt]
        ]
        for message in messages where message.role != .system {
            apiMessages.append(buildOpenAIMessage(message))
        }
        let body: [String: Any] = {
            var payload: [String: Any] = [
                "model": config.model,
                "messages": apiMessages
            ]
            for (key, value) in openAICompatibleBodyExtras(provider: config.provider, model: config.model) {
                payload[key] = value
            }
            return payload
        }()
        let bodyData = try JSONSerialization.data(withJSONObject: body)
        var request = URLRequest(url: config.endpoint)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.timeoutInterval = 300
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in config.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw LLMError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            let message = parseErrorMessage(from: data) ?? String(data: data, encoding: .utf8) ?? "未知错误"
            throw LLMError.apiError(statusCode: http.statusCode, message: message)
        }
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let choices = json["choices"] as? [[String: Any]],
            let first = choices.first
        else {
            throw LLMError.invalidResponse
        }
        let result = try parseAssistantResult(from: first, rawData: data)
        let text = result.content.isEmpty ? (result.reasoning ?? "") : result.content
        guard !text.isEmpty else { throw LLMError.invalidResponse }
        return text
    }

    private static func preprocessAnthropic(
        messages: [ChatMessage],
        systemPrompt: String
    ) async throws -> String {
        let apiKey = AppPreferences.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = AppPreferences.resolvedModelName
        guard !apiKey.isEmpty, !model.isEmpty else {
            throw LLMError.apiError(statusCode: 0, message: "请填写 API Key 和模型名称")
        }
        let apiMessages = messages
            .filter { $0.role != .system }
            .map { buildAnthropicMessage($0) }
        let body: [String: Any] = [
            "model": model,
            "max_tokens": 2048,
            "system": systemPrompt,
            "messages": apiMessages
        ]
        let bodyData = try JSONSerialization.data(withJSONObject: body)
        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.timeoutInterval = 300
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw LLMError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            let message = parseErrorMessage(from: data) ?? String(data: data, encoding: .utf8) ?? "未知错误"
            throw LLMError.apiError(statusCode: http.statusCode, message: message)
        }
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let blocks = json["content"] as? [[String: Any]]
        else {
            throw LLMError.invalidResponse
        }
        let text = blocks.compactMap { $0["text"] as? String }.joined(separator: "\n")
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw LLMError.invalidResponse
        }
        return text
    }

    private static func parsePreprocessResult(_ text: String) throws -> LLMPreprocessResult {
        let normalized = LLMChatResult.stripThinkTags(from: text)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let start = normalized.firstIndex(of: "{"),
           let end = normalized.lastIndex(of: "}"),
           start < end {
            let jsonText = String(normalized[start...end])
            if let data = jsonText.data(using: .utf8),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let action = object["action"] as? String {
                let message = (object["message"] as? String) ?? ""
                let tasks = (object["tasks"] as? [String] ?? [])
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }

                switch action.lowercased() {
                case "clarify":
                    guard !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                        throw LLMError.invalidResponse
                    }
                    return LLMPreprocessResult(
                        action: .clarify,
                        displayText: message,
                        tasks: [],
                        rawAssistantText: text
                    )
                case "execute":
                    guard !tasks.isEmpty else { throw LLMError.invalidResponse }
                    let display = message.isEmpty
                        ? tasks.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n")
                        : message
                    return LLMPreprocessResult(
                        action: .execute,
                        displayText: display,
                        tasks: tasks,
                        rawAssistantText: text
                    )
                default:
                    break
                }
            }
        }
        if let recovered = recoverPreprocessFromPlainText(normalized) {
            return recovered
        }
        throw LLMError.invalidResponse
    }

    /// 模型偶发返回纯文本编号列表时，尽量恢复为 execute 结果。
    private static func recoverPreprocessFromPlainText(_ text: String) -> LLMPreprocessResult? {
        let pattern = #"^\s*\d+[\.、\)]\s*(.+)$"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .anchorsMatchLines) else {
            return nil
        }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        let matches = regex.matches(in: text, options: [], range: range)
        var tasks: [String] = []
        for match in matches {
            guard match.numberOfRanges >= 2,
                  let taskRange = Range(match.range(at: 1), in: text) else { continue }
            let task = String(text[taskRange]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !task.isEmpty {
                tasks.append(task)
            }
        }
        guard tasks.count >= 2 else { return nil }
        let display = text.contains("确认执行")
            ? text
            : tasks.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n")
                + "\n\n请确认后点击「确认执行」，或直接补充修改意见。"
        return LLMPreprocessResult(
            action: .execute,
            displayText: display,
            tasks: tasks,
            rawAssistantText: text
        )
    }

    private static func isMiniMaxReasoningModel(provider: ModelProvider, model: String) -> Bool {
        guard provider == .minimax else { return false }
        let normalized = model.lowercased()
        return normalized.contains("minimax-m3")
            || normalized.contains("minimax-m2")
    }

    private static func openAICompatibleBodyExtras(provider: ModelProvider, model: String) -> [String: Any] {
        guard isMiniMaxReasoningModel(provider: provider, model: model) else { return [:] }
        return [
            "max_completion_tokens": 4096,
            "reasoning_split": true
        ]
    }

    private static func encodeReasoningDetails(_ details: [[String: Any]]) -> String? {
        guard !details.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: details),
              let json = String(data: data, encoding: .utf8) else {
            return nil
        }
        return json
    }

    private static func decodeReasoningDetails(_ json: String?) -> [[String: Any]]? {
        guard let json,
              let data = json.data(using: .utf8),
              let details = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              !details.isEmpty else {
            return nil
        }
        return details
    }

    private static func reasoningText(from details: [[String: Any]]) -> String? {
        let parts = details.compactMap { detail -> String? in
            if let text = detail["text"] as? String {
                return text.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            if let thinking = detail["thinking"] as? String {
                return thinking.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            return nil
        }.filter { !$0.isEmpty }
        guard !parts.isEmpty else { return nil }
        return parts.joined(separator: "\n\n")
    }

    private static func appendMiniMaxAssistantFields(
        to payload: inout [String: Any],
        content: String,
        reasoning: String?,
        reasoningDetailsJSON: String?
    ) {
        if !content.isEmpty {
            payload["content"] = content
        } else {
            payload["content"] = NSNull()
        }
        if let reasoning, !reasoning.isEmpty {
            payload["reasoning_content"] = reasoning
        }
        if let details = decodeReasoningDetails(reasoningDetailsJSON) {
            payload["reasoning_details"] = details
        }
    }

    private static func locateControlVisionAnthropic(
        imageBase64: String,
        imageWidth: Int,
        imageHeight: Int,
        description: String
    ) async throws -> String {
        let apiKey = AppPreferences.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = AppPreferences.resolvedModelName
        guard !apiKey.isEmpty, !model.isEmpty else {
            throw LLMError.apiError(statusCode: 0, message: "请填写 API Key 和模型名称")
        }

        let userText = """
        原图像素尺寸：image_width=\(imageWidth)，image_height=\(imageHeight)。
        图上青色网格为相对位置辅助（10% 间距）；请先估测相对位置，再换算为该尺寸下的绝对像素整数。
        只返回 JSON；禁止百分比或归一化坐标。
        控件描述：\(description)
        """

        let body: [String: Any] = [
            "model": model,
            "max_tokens": 512,
            "system": locateControlSystemPrompt,
            "messages": [
                [
                    "role": "user",
                    "content": [
                        [
                            "type": "image",
                            "source": [
                                "type": "base64",
                                "media_type": "image/png",
                                "data": imageBase64
                            ]
                        ],
                        [
                            "type": "text",
                            "text": userText
                        ]
                    ]
                ]
            ]
        ]
        let bodyData = try JSONSerialization.data(withJSONObject: body)

        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.timeoutInterval = 180
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw LLMError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            let message = parseErrorMessage(from: data) ?? String(data: data, encoding: .utf8) ?? "未知错误"
            throw LLMError.apiError(statusCode: http.statusCode, message: message)
        }

        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let contentBlocks = json["content"] as? [[String: Any]]
        else {
            throw LLMError.invalidResponse
        }

        let text = contentBlocks
            .compactMap { $0["text"] as? String }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            throw LLMError.invalidResponse
        }
        Logger.chat("locate_control vision LLM content:\n\(text)")
        return text
    }

    private static func chatOpenAICompatible(
        messages: [ChatMessage],
        config: ModelRuntimeConfig,
        taskContext: String? = nil
    ) async throws -> LLMChatResult {
        let effectiveSystemPrompt = taskContext.map {
            systemPrompt + "\n\n当前只执行这个子任务：\n" + $0
        } ?? systemPrompt
        var apiMessages: [[String: Any]] = [
            ["role": "system", "content": effectiveSystemPrompt]
        ]
        for message in messages where message.role != .system {
            apiMessages.append(buildOpenAIMessage(message))
        }

        var body: [String: Any] = [
            "model": config.model,
            "messages": apiMessages
        ]
        for (key, value) in openAICompatibleBodyExtras(provider: config.provider, model: config.model) {
            body[key] = value
        }
        let enabledTools = CustardToolRegistry.enabledLLMToolDefinitions
        if !enabledTools.isEmpty {
            body["tools"] = enabledTools
            body["tool_choice"] = "auto"
        }
        let bodyData = try JSONSerialization.data(withJSONObject: body)

        var request = URLRequest(url: config.endpoint)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.timeoutInterval = 300
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in config.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }

        Logger.chat(
            "LLM request provider=\(config.provider.rawValue) model=\(config.model) "
            + "messages=\(apiMessages.count) bodyBytes=\(bodyData.count) "
            + "endpoint=\(config.endpoint.absoluteString)"
        )

        let requestStart = Date()
        var lastError: Error?
        for attempt in 1...2 {
            if attempt > 1 {
                Logger.chat("LLM empty response retry attempt=\(attempt)")
            }
            let (data, response) = try await URLSession.shared.data(for: request)
            let elapsed = Date().timeIntervalSince(requestStart)
            guard let http = response as? HTTPURLResponse else {
                throw LLMError.invalidResponse
            }
            Logger.chat("LLM response status=\(http.statusCode) bytes=\(data.count) elapsed=\(String(format: "%.2f", elapsed))s attempt=\(attempt)")

            guard (200...299).contains(http.statusCode) else {
                let message = parseErrorMessage(from: data) ?? String(data: data, encoding: .utf8) ?? "未知错误"
                Logger.chat("LLM error body: \(Self.previewString(data))")
                throw LLMError.apiError(statusCode: http.statusCode, message: message)
            }

            guard
                let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                let choices = json["choices"] as? [[String: Any]],
                let first = choices.first
            else {
                Logger.chat("LLM invalid JSON structure: \(Self.previewString(data))")
                throw LLMError.invalidResponse
            }

            do {
                let result = normalizedChatResult(
                    try parseAssistantResult(from: first, rawData: data)
                )
                logChatResult(result)
                return result
            } catch LLMError.invalidResponse {
                lastError = LLMError.invalidResponse
                if attempt == 2 { throw LLMError.invalidResponse }
            }
        }
        throw lastError ?? LLMError.invalidResponse
    }

    private static func parseAssistantResult(from choice: [String: Any], rawData: Data? = nil) throws -> LLMChatResult {
        let message = choice["message"] as? [String: Any] ?? choice
        let messageKeys = message.keys.sorted().joined(separator: ", ")
        Logger.chat("LLM choice keys=[\(choice.keys.sorted().joined(separator: ", "))] message keys=[\(messageKeys)]")

        let content = parseTextContent(message["content"])
        let reasoningDetails = message["reasoning_details"] as? [[String: Any]]
        let reasoningFromDetails = reasoningDetails.flatMap { reasoningText(from: $0) }
        let reasoning = parseOptionalText(message["reasoning_content"])
            ?? parseOptionalText(message["reasoning"])
            ?? parseOptionalText(message["thinking"])
            ?? reasoningFromDetails
            ?? parseOptionalText(choice["reasoning_content"])
            ?? parseOptionalText(choice["reasoning"])
            ?? parseOptionalText(choice["thinking"])
        let reasoningDetailsJSON = reasoningDetails.flatMap { encodeReasoningDetails($0) }
        let toolCalls = LLMTools.parseToolCalls(message["tool_calls"])

        if content.isEmpty && (reasoning?.isEmpty ?? true) && toolCalls.isEmpty {
            if let fallback = parseOptionalText(choice["text"]), !fallback.isEmpty {
                Logger.chat("LLM using choice.text fallback len=\(fallback.count)")
                return LLMChatResult(content: fallback, reasoning: nil)
            }
            Logger.chat("LLM parse failed, raw: \(previewString(rawData ?? Data()))")
            throw LLMError.invalidResponse
        }

        return LLMChatResult(
            content: content,
            reasoning: reasoning?.isEmpty == true ? nil : reasoning,
            reasoningDetailsJSON: reasoningDetailsJSON,
            toolCalls: toolCalls.isEmpty ? nil : toolCalls
        )
    }

    private static func logChatResult(_ result: LLMChatResult) {
        Logger.chat(
            "LLM parsed contentLen=\(result.content.count) reasoningLen=\(result.reasoning?.count ?? 0) "
            + "toolCalls=\(result.toolCalls?.count ?? 0)"
        )
        if !result.content.isEmpty {
            Logger.chat("LLM content:\n\(result.content)")
        }
        if let reasoning = result.reasoning, !reasoning.isEmpty {
            Logger.chat("LLM reasoning:\n\(reasoning)")
        }
        if let toolCalls = result.toolCalls, !toolCalls.isEmpty {
            for call in toolCalls {
                Logger.chat(
                    "LLM tool_call id=\(call.id) name=\(call.name) args=\(call.argumentsJSON)"
                )
            }
        }
    }

    private static func parseOptionalText(_ value: Any?) -> String? {
        let text = parseTextContent(value)
        return text.isEmpty ? nil : text
    }

    private static func parseTextContent(_ value: Any?) -> String {
        if let string = value as? String {
            return string.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let parts = value as? [[String: Any]] {
            return parts.compactMap { part -> String? in
                guard part["type"] as? String != "image_url" else { return nil }
                return part["text"] as? String
            }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return ""
    }

    private static func chatAnthropic(
        messages: [ChatMessage],
        taskContext: String? = nil
    ) async throws -> LLMChatResult {
        let apiKey = AppPreferences.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = AppPreferences.resolvedModelName
        guard !apiKey.isEmpty, !model.isEmpty else {
            throw LLMError.apiError(statusCode: 0, message: "请填写 API Key 和模型名称")
        }
        let apiMessages: [[String: Any]] = messages
            .filter { $0.role != .system }
            .map { buildAnthropicMessage($0) }

        let body: [String: Any] = [
            "model": model,
            "max_tokens": 4096,
            "system": taskContext.map {
                systemPrompt + "\n\n当前只执行这个子任务：\n" + $0
            } ?? systemPrompt,
            "messages": apiMessages
        ]
        let bodyData = try JSONSerialization.data(withJSONObject: body)

        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw LLMError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            let message = parseErrorMessage(from: data) ?? String(data: data, encoding: .utf8) ?? "未知错误"
            throw LLMError.apiError(statusCode: http.statusCode, message: message)
        }

        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let contentBlocks = json["content"] as? [[String: Any]],
            let first = contentBlocks.first,
            let text = first["text"] as? String
        else {
            throw LLMError.invalidResponse
        }
        let result = normalizedChatResult(
            LLMChatResult(content: text.trimmingCharacters(in: .whitespacesAndNewlines), reasoning: nil)
        )
        logChatResult(result)
        return result
    }

    private static func normalizedChatResult(_ result: LLMChatResult) -> LLMChatResult {
        guard !result.hasToolCalls,
              let parsed = parseTaskStatusObject(from: result.content) else {
            if !result.hasToolCalls {
                Logger.chat("taskStatus parsed=nil reason=no_valid_status_json")
            }
            return result
        }
        Logger.chat("taskStatus parsed=\(parsed.status.rawValue) messageLen=\(parsed.message.count)")
        return LLMChatResult(
            content: parsed.message,
            reasoning: result.reasoning,
            reasoningDetailsJSON: result.reasoningDetailsJSON,
            toolCalls: result.toolCalls,
            taskStatus: parsed.status
        )
    }

    /// Prefer the last valid `{"status","message"}` object after stripping think blocks.
    private static func parseTaskStatusObject(
        from text: String
    ) -> (status: LLMChatResult.TaskStatus, message: String)? {
        let stripped = LLMChatResult.stripThinkTags(from: text)
        guard !stripped.isEmpty else { return nil }

        var lastMatch: (status: LLMChatResult.TaskStatus, message: String)?
        var searchStart = stripped.startIndex
        while searchStart < stripped.endIndex,
              let braceStart = stripped[searchStart...].firstIndex(of: "{") {
            guard let object = extractBalancedJsonObject(from: stripped, startingAt: braceStart) else {
                searchStart = stripped.index(after: braceStart)
                continue
            }
            let (jsonText, nextIndex) = object
            if let data = jsonText.data(using: .utf8),
               let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let statusText = dict["status"] as? String,
               let status = LLMChatResult.TaskStatus(rawValue: statusText),
               let message = dict["message"] as? String {
                lastMatch = (status, message)
            }
            searchStart = nextIndex
        }
        return lastMatch
    }

    private static func extractBalancedJsonObject(
        from text: String,
        startingAt start: String.Index
    ) -> (json: String, nextIndex: String.Index)? {
        guard start < text.endIndex, text[start] == "{" else { return nil }
        var depth = 0
        var inString = false
        var escape = false
        var index = start
        while index < text.endIndex {
            let ch = text[index]
            if inString {
                if escape {
                    escape = false
                } else if ch == "\\" {
                    escape = true
                } else if ch == "\"" {
                    inString = false
                }
            } else {
                switch ch {
                case "\"":
                    inString = true
                case "{":
                    depth += 1
                case "}":
                    depth -= 1
                    if depth == 0 {
                        let next = text.index(after: index)
                        return (String(text[start..<next]), next)
                    }
                default:
                    break
                }
            }
            index = text.index(after: index)
        }
        return nil
    }

    private static func requestStructuredText(
        systemPrompt: String,
        userText: String
    ) async throws -> String {
        let provider = AppPreferences.modelProvider
        if provider == .anthropic {
            let apiKey = AppPreferences.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
            let model = AppPreferences.resolvedModelName
            guard !apiKey.isEmpty, !model.isEmpty else {
                throw LLMError.apiError(statusCode: 0, message: "请填写 API Key 和模型名称")
            }
            let body: [String: Any] = [
                "model": model,
                "max_tokens": 1024,
                "system": systemPrompt,
                "messages": [["role": "user", "content": userText]]
            ]
            let data = try JSONSerialization.data(withJSONObject: body)
            var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
            request.httpMethod = "POST"
            request.httpBody = data
            request.timeoutInterval = 120
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
            let (responseData, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                throw LLMError.invalidResponse
            }
            guard let json = try JSONSerialization.jsonObject(with: responseData) as? [String: Any],
                  let blocks = json["content"] as? [[String: Any]],
                  let text = blocks.compactMap({ $0["text"] as? String }).first else {
                throw LLMError.invalidResponse
            }
            return text
        }

        guard let config = ModelRuntimeConfig.build() else {
            throw LLMError.apiError(statusCode: 0, message: AppPreferences.validateModelConfig() ?? "模型配置不完整")
        }
        let body: [String: Any] = [
            "model": config.model,
            "messages": [
                ["role": "system", "content": systemPrompt],
                ["role": "user", "content": userText]
            ]
        ]
        let bodyData = try JSONSerialization.data(withJSONObject: body)
        var request = URLRequest(url: config.endpoint)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.timeoutInterval = 120
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in config.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = json["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any],
              let text = message["content"] as? String else {
            throw LLMError.invalidResponse
        }
        return text
    }

    private static func jsonObject(from text: String) -> [String: Any]? {
        let stripped = LLMChatResult.stripThinkTags(from: text)
        guard let start = stripped.firstIndex(of: "{"),
              let extracted = extractBalancedJsonObject(from: stripped, startingAt: start) else {
            return nil
        }
        guard let data = extracted.json.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private static func parseErrorMessage(from data: Data) -> String? {
        guard
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        if let error = json["error"] as? [String: Any],
           let message = error["message"] as? String {
            return message
        }
        if let message = json["message"] as? String {
            return message
        }
        return nil
    }

    private static func previewString(_ data: Data, limit: Int = 800) -> String {
        guard let text = String(data: data, encoding: .utf8) else { return "(binary \(data.count) bytes)" }
        if text.count <= limit { return text }
        let end = text.index(text.startIndex, offsetBy: limit)
        return String(text[..<end]) + "..."
    }

    private static func buildOpenAIMessage(_ message: ChatMessage) -> [String: Any] {
        if message.role == .tool, let toolCallId = message.toolCallId {
            return [
                "role": "tool",
                "tool_call_id": toolCallId,
                "content": message.content
            ]
        }

        let replayContent = message.apiReplayContent.flatMap { $0.isEmpty ? nil : $0 } ?? message.content

        if message.role == .assistant, let toolCalls = message.toolCalls, !toolCalls.isEmpty {
            var payload: [String: Any] = [
                "role": "assistant",
                "tool_calls": toolCalls.map { call in
                    [
                        "id": call.id,
                        "type": "function",
                        "function": [
                            "name": call.name,
                            "arguments": call.argumentsJSON
                        ] as [String: Any]
                    ] as [String: Any]
                }
            ]
            appendMiniMaxAssistantFields(
                to: &payload,
                content: replayContent,
                reasoning: message.reasoning,
                reasoningDetailsJSON: message.reasoningDetailsJSON
            )
            return payload
        }

        if message.role == .assistant {
            if message.reasoning != nil || message.reasoningDetailsJSON != nil {
                var payload: [String: Any] = ["role": "assistant"]
                appendMiniMaxAssistantFields(
                    to: &payload,
                    content: replayContent,
                    reasoning: message.reasoning,
                    reasoningDetailsJSON: message.reasoningDetailsJSON
                )
                return payload
            }
        }

        guard let base64 = message.imageBase64 else {
            let contentToSend = message.role == .assistant ? replayContent : message.content
            return [
                "role": message.role.rawValue,
                "content": contentToSend
            ]
        }

        return [
            "role": message.role.rawValue,
            "content": [
                [
                    "type": "text",
                    "text": message.content
                ],
                [
                    "type": "image_url",
                    "image_url": [
                        "url": "data:image/png;base64,\(base64)"
                    ]
                ]
            ]
        ]
    }

    private static func buildAnthropicMessage(_ message: ChatMessage) -> [String: Any] {
        guard let base64 = message.imageBase64 else {
            return [
                "role": message.role.rawValue,
                "content": message.content
            ]
        }

        return [
            "role": message.role.rawValue,
            "content": [
                [
                    "type": "image",
                    "source": [
                        "type": "base64",
                                "media_type": "image/png",
                        "data": base64
                    ]
                ],
                [
                    "type": "text",
                    "text": message.content
                ]
            ]
        ]
    }
}
