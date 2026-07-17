import Foundation

struct ChatMessage: Identifiable, Equatable {
    enum Role: String, Codable {
        case user
        case assistant
        case system
        case tool
    }

    let id: UUID
    let role: Role
    let content: String
    let isError: Bool
    let isAdbResult: Bool
    let isScreenContext: Bool
    let isToolResult: Bool
    let imageBase64: String?
    let reasoning: String?
    let reasoningDetailsJSON: String?
    /// 回传大模型 API 时使用的完整 assistant 正文（如含思考标签的预处理原始回复）。
    let apiReplayContent: String?
    let toolCallId: String?
    let toolCalls: [LLMToolCall]?
    let isPreprocessResult: Bool
    let isConclusion: Bool

    init(
        id: UUID = UUID(),
        role: Role,
        content: String,
        isError: Bool = false,
        isAdbResult: Bool = false,
        isScreenContext: Bool = false,
        isToolResult: Bool = false,
        imageBase64: String? = nil,
        reasoning: String? = nil,
        reasoningDetailsJSON: String? = nil,
        apiReplayContent: String? = nil,
        toolCallId: String? = nil,
        toolCalls: [LLMToolCall]? = nil,
        isPreprocessResult: Bool = false,
        isConclusion: Bool = false
    ) {
        self.id = id
        self.role = role
        self.content = content
        self.isError = isError
        self.isAdbResult = isAdbResult
        self.isScreenContext = isScreenContext
        self.isToolResult = isToolResult
        self.imageBase64 = imageBase64
        self.reasoning = reasoning
        self.reasoningDetailsJSON = reasoningDetailsJSON
        self.apiReplayContent = apiReplayContent
        self.toolCallId = toolCallId
        self.toolCalls = toolCalls
        self.isPreprocessResult = isPreprocessResult
        self.isConclusion = isConclusion
    }

    func displayText(showThinking: Bool) -> String {
        guard role == .assistant, !isError else { return content }
        let text = LLMChatResult(content: content, reasoning: reasoning, toolCalls: toolCalls)
            .textForDisplay(showThinking: showThinking)
        if !text.isEmpty {
            return text
        }
        // 关闭思考后正文可能被剥空；有工具调用时展示调用摘要，勿回退到含 <think> 的原文。
        if let toolCalls, !toolCalls.isEmpty {
            let names = toolCalls.map(\.name).joined(separator: ", ")
            return "调用工具: \(names)"
        }
        if showThinking && !content.isEmpty {
            return content
        }
        return text
    }

    /// Keeps the same `id` so SwiftUI list identity (and scroll position) stays stable.
    func replacingImageBase64(_ imageBase64: String?) -> ChatMessage {
        ChatMessage(
            id: id,
            role: role,
            content: content,
            isError: isError,
            isAdbResult: isAdbResult,
            isScreenContext: isScreenContext,
            isToolResult: isToolResult,
            imageBase64: imageBase64,
            reasoning: reasoning,
            reasoningDetailsJSON: reasoningDetailsJSON,
            apiReplayContent: apiReplayContent,
            toolCallId: toolCallId,
            toolCalls: toolCalls,
            isPreprocessResult: isPreprocessResult,
            isConclusion: isConclusion
        )
    }

    /// 聊天 UI 是否展示：助手整段输出仅为命令代码块时隐藏（仍保留在会话上下文中）。
    func shouldDisplayInChat(showThinking: Bool) -> Bool {
        guard role == .assistant, !isError else { return true }
        return !Self.isSoleCommandCodeBlock(displayText(showThinking: showThinking))
    }

    private static let soleCommandCodeBlockPattern =
        #"^```(?:adb|bash|shell|sh)?[ \t]*\r?\n[\s\S]*?```$"#

    static func isSoleCommandCodeBlock(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        guard let regex = try? NSRegularExpression(pattern: soleCommandCodeBlockPattern) else {
            return false
        }
        let range = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
        guard let match = regex.firstMatch(in: trimmed, options: [], range: range) else {
            return false
        }
        return match.range.length == range.length
    }
}
