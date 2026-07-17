import Foundation

enum UiHierarchySummarizer {
    private static let nodePattern = #"<node[^>]*/?>"#

    static func summarize(xml: String, maxCharacters: Int = 7000, maxLines: Int = 80) -> String? {
        guard let regex = try? NSRegularExpression(pattern: nodePattern) else { return nil }
        let range = NSRange(xml.startIndex..<xml.endIndex, in: xml)
        var lines: [String] = []

        for match in regex.matches(in: xml, range: range) {
            guard let nodeRange = Range(match.range, in: xml) else { continue }
            let node = String(xml[nodeRange])
            guard let summary = summarizeNode(node) else { continue }
            lines.append(summary)
            if lines.count >= maxLines { break }
        }

        guard !lines.isEmpty else { return nil }

        var output = lines.joined(separator: "\n")
        if output.count > maxCharacters {
            output = String(output.prefix(maxCharacters)) + "\n...(已截断)"
        }
        return output
    }

    private static func summarizeNode(_ node: String) -> String? {
        let text = attribute("text", in: node)
        let desc = attribute("content-desc", in: node)
        let resourceId = attribute("resource-id", in: node)
        let bounds = attribute("bounds", in: node)
        let clickable = attribute("clickable", in: node) == "true"
        let enabled = attribute("enabled", in: node) != "false"

        let label = [text, desc].first(where: { !$0.isEmpty })
        guard label != nil || clickable || !resourceId.isEmpty else { return nil }

        var parts: [String] = []
        if clickable { parts.append("[可点击]") }
        if !enabled { parts.append("[禁用]") }
        if let label, !label.isEmpty {
            parts.append("\"\(label)\"")
        }
        if !resourceId.isEmpty {
            parts.append("id=\(resourceId)")
        }
        if !bounds.isEmpty {
            parts.append("@ \(bounds)")
        }
        return parts.joined(separator: " ")
    }

    private static func attribute(_ name: String, in node: String) -> String {
        let pattern = "\(NSRegularExpression.escapedPattern(for: name))=\"([^\"]*)\""
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return "" }
        let range = NSRange(node.startIndex..<node.endIndex, in: node)
        guard
            let match = regex.firstMatch(in: node, range: range),
            match.numberOfRanges > 1,
            let valueRange = Range(match.range(at: 1), in: node)
        else { return "" }
        return String(node[valueRange])
    }
}
