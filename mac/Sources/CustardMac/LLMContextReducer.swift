import Foundation

struct LLMContextReduction {
    let messages: [ChatMessage]
    let estimatedInputTokens: Int
    let wasReduced: Bool
}

enum LLMContextReducer {
    private static let maxRequestTokens = 8_192
    private static let responseReserveTokens = 2_048
    private static let messageOverheadTokens = 12

    static func reduce(messages: [ChatMessage], systemPrompt: String) -> LLMContextReduction {
        let systemTokens = estimate(systemPrompt)
        let budget = max(1_024, maxRequestTokens - responseReserveTokens - systemTokens)
        let ranked = messages.enumerated()
            .sorted { lhs, rhs in
                let left = priority(lhs.element)
                let right = priority(rhs.element)
                if left != right { return left < right }
                return lhs.offset > rhs.offset
            }

        var selected: [(Int, ChatMessage)] = []
        var used = 0
        for (index, message) in ranked {
            let candidate = preprocessMessage(message)
            let cost = estimate(candidate) + messageOverheadTokens
            guard used + cost <= budget || selected.isEmpty && priority(message) == 0 else { continue }
            selected.append((index, candidate))
            used += cost
        }

        let ordered = selected
            .sorted { $0.0 < $1.0 }
            .map(\.1)
        let droppedImage = messages.contains { $0.imageBase64 != nil }
            && !ordered.contains { $0.imageBase64 != nil }
        return LLMContextReduction(
            messages: ordered,
            estimatedInputTokens: systemTokens + used,
            wasReduced: ordered.count != messages.count || droppedImage
        )
    }

    private static func priority(_ message: ChatMessage) -> Int {
        if message.role == .user && !message.isScreenContext && !message.isToolResult && !message.isAdbResult {
            return 0
        }
        if message.isPreprocessResult { return 1 }
        if message.isConclusion { return 2 }
        return 3
    }

    private static func preprocessMessage(_ message: ChatMessage) -> ChatMessage {
        if message.isScreenContext {
            return ChatMessage(
                id: message.id,
                role: .user,
                content: "历史屏幕信息：\(message.content)",
                isScreenContext: true
            )
        }
        if message.role == .tool {
            return ChatMessage(
                id: message.id,
                role: .user,
                content: "历史工具执行结果：\(message.content)",
                isToolResult: true
            )
        }
        if let toolCalls = message.toolCalls, !toolCalls.isEmpty {
            return ChatMessage(
                id: message.id,
                role: .assistant,
                content: message.content.isEmpty
                    ? "历史调用工具：\(toolCalls.map(\.name).joined(separator: "、"))"
                    : message.content,
                isConclusion: message.isConclusion
            )
        }
        return ChatMessage(
            id: message.id,
            role: message.role,
            content: message.content,
            isPreprocessResult: message.isPreprocessResult,
            isConclusion: message.isConclusion
        )
    }

    private static func estimate(_ message: ChatMessage) -> Int {
        estimate(message.content) + (message.reasoning.map(estimate) ?? 0)
    }

    private static func estimate(_ text: String) -> Int {
        max(1, (text.utf8.count + 2) / 3)
    }
}
