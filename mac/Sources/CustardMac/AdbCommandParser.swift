import Foundation

enum AdbCommandParser {
    private static let codeBlockPattern = #"```(?:adb|bash|shell|sh)?\s*\r?\n([\s\S]*?)```"#

    static func extractCommands(from text: String) -> [String] {
        var commands: [String] = []
        var seen = Set<String>()

        if let regex = try? NSRegularExpression(pattern: codeBlockPattern) {
            let range = NSRange(text.startIndex..<text.endIndex, in: text)
            for match in regex.matches(in: text, range: range) {
                guard match.numberOfRanges > 1,
                      let blockRange = Range(match.range(at: 1), in: text) else { continue }
                appendCommands(from: String(text[blockRange]), to: &commands, seen: &seen)
            }
        }

        for line in text.split(whereSeparator: \.isNewline) {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard trimmed.hasPrefix("adb ") else { continue }
            addCommand(trimmed, to: &commands, seen: &seen)
        }

        return commands
    }

    private static func appendCommands(
        from block: String,
        to commands: inout [String],
        seen: inout Set<String>
    ) {
        for line in block.split(whereSeparator: \.isNewline) {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else { continue }

            if trimmed.hasPrefix("adb ") {
                addCommand(trimmed, to: &commands, seen: &seen)
            } else if trimmed.hasPrefix("$ adb ") {
                addCommand(String(trimmed.dropFirst(2)), to: &commands, seen: &seen)
            }
        }
    }

    private static func addCommand(
        _ command: String,
        to commands: inout [String],
        seen: inout Set<String>
    ) {
        guard seen.insert(command).inserted else { return }
        commands.append(command)
    }
}
