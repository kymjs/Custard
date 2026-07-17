import Foundation

enum ViewDebugSummarizer {
    private static let hierarchyMarkers = [
        "View Hierarchy:",
        "TASK",
        "ACTIVITY"
    ]

    static func summarize(dump: String, maxCharacters: Int = 7000) -> String? {
        let extracted = extractHierarchySection(from: dump)
        guard !extracted.isEmpty else { return nil }

        var lines: [String] = []
        for rawLine in extracted.split(whereSeparator: \.isNewline) {
            let line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            if isUsefulLine(line) {
                lines.append(line)
            }
            if lines.count >= 120 { break }
        }

        guard !lines.isEmpty else { return nil }

        var output = lines.joined(separator: "\n")
        if output.count > maxCharacters {
            output = String(output.prefix(maxCharacters)) + "\n...(已截断)"
        }
        return output
    }

    private static func extractHierarchySection(from dump: String) -> String {
        if let range = dump.range(of: "View Hierarchy:") {
            return String(dump[range.lowerBound...])
        }

        if let range = dump.range(of: "TASK ") {
            return String(dump[range.lowerBound...])
        }

        return dump
    }

    private static func isUsefulLine(_ line: String) -> Bool {
        if line.hasPrefix("View Hierarchy:") { return true }
        if line.contains("DecorView") { return true }
        if line.contains("android.") || line.contains("androidx.") { return true }
        if line.contains("{") && line.contains("}") { return true }
        if line.hasPrefix("TASK ") || line.hasPrefix("ACTIVITY ") { return true }
        if line.contains("mCurrentFocus") { return true }
        return line.contains("id=") || line.contains("text=") || line.contains("desc=")
    }
}
