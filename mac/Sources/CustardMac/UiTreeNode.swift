import CoreGraphics
import Foundation

struct UiTreeNode: Identifiable {
    let id = UUID()
    let left: Int
    let top: Int
    let right: Int
    let bottom: Int
    let label: String?
    let clickable: Bool
    let editable: Bool

    var bounds: CGRect {
        CGRect(
            x: CGFloat(left),
            y: CGFloat(top),
            width: CGFloat(max(0, right - left)),
            height: CGFloat(max(0, bottom - top))
        )
    }
}

enum UiTreeParser {
    private static let boundsPattern = #"@ \[(\d+),(\d+)\]\[(\d+),(\d+)\]"#

    static func parse(_ text: String) -> [UiTreeNode] {
        guard let regex = try? NSRegularExpression(pattern: boundsPattern) else { return [] }

        var nodes: [UiTreeNode] = []
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)

        for lineSub in lines {
            let line = String(lineSub)
            let range = NSRange(line.startIndex..<line.endIndex, in: line)
            guard
                let match = regex.firstMatch(in: line, range: range),
                match.numberOfRanges == 5,
                let leftRange = Range(match.range(at: 1), in: line),
                let topRange = Range(match.range(at: 2), in: line),
                let rightRange = Range(match.range(at: 3), in: line),
                let bottomRange = Range(match.range(at: 4), in: line),
                let left = Int(line[leftRange]),
                let top = Int(line[topRange]),
                let right = Int(line[rightRange]),
                let bottom = Int(line[bottomRange])
            else { continue }

            let width = right - left
            let height = bottom - top
            guard width > 0, height > 0 else { continue }

            let clickable = line.contains("[可点击]")
            let editable = line.contains("[可编辑]")
            let label = extractQuotedLabel(from: line)

            nodes.append(
                UiTreeNode(
                    left: left,
                    top: top,
                    right: right,
                    bottom: bottom,
                    label: label,
                    clickable: clickable,
                    editable: editable
                )
            )
        }

        return nodes
    }

    private static func extractQuotedLabel(from line: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: #"\"([^\"]+)\""#) else { return nil }
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        guard
            let match = regex.firstMatch(in: line, range: range),
            match.numberOfRanges > 1,
            let valueRange = Range(match.range(at: 1), in: line)
        else { return nil }
        let value = String(line[valueRange]).trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
