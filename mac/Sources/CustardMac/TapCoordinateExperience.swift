import Foundation

struct TapCoordinateExperience: Codable, Identifiable, Equatable {
    let id: UUID
    let packageName: String
    let pageLabel: String
    let controlLabel: String
    let x: Int
    let y: Int
    let screenWidth: Int
    let screenHeight: Int
    var updatedAt: Date
    var successCount: Int

    init(
        id: UUID = UUID(),
        packageName: String,
        pageLabel: String,
        controlLabel: String,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        updatedAt: Date = Date(),
        successCount: Int = 1
    ) {
        self.id = id
        self.packageName = packageName
        self.pageLabel = pageLabel
        self.controlLabel = controlLabel
        self.x = x
        self.y = y
        self.screenWidth = screenWidth
        self.screenHeight = screenHeight
        self.updatedAt = updatedAt
        self.successCount = successCount
    }
}

struct TapCoordinateSummary {
    let packageName: String
    let pageLabel: String
    let controlLabel: String
    let x: Int
    let y: Int
    let screenWidth: Int
    let screenHeight: Int
}

struct LastLocateResult {
    let description: String
    let x: Double
    let y: Double
}

struct PendingTapExperience {
    let controlLabel: String
    let x: Double
    let y: Double
    let screenWidth: Int
    let screenHeight: Int
    let packageName: String
    let beforeUiSummary: String
}

struct TapHitVerification {
    let hit: Bool
    let packageName: String
    let pageLabel: String
    let controlLabel: String
}

enum TapCoordinateMatcher {
    static let matchTolerancePx = 24.0

    static func withinTolerance(x1: Double, y1: Double, x2: Double, y2: Double) -> Bool {
        hypot(x1 - x2, y1 - y2) <= matchTolerancePx
    }

    static func parseLocateCoordinates(_ content: String) -> (Double, Double)? {
        guard let start = content.firstIndex(of: "{"),
              let end = content.lastIndex(of: "}") else { return nil }
        let jsonText = String(content[start...end])
        guard let data = jsonText.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if let error = object["error"] as? String, !error.isEmpty {
            return nil
        }
        if let left = numericValue(object["left"]),
           let top = numericValue(object["top"]),
           let right = numericValue(object["right"]),
           let bottom = numericValue(object["bottom"]),
           right > left, bottom > top {
            return ((left + right) / 2.0, (top + bottom) / 2.0)
        }
        guard let x = numericValue(object["x"]),
              let y = numericValue(object["y"]) else { return nil }
        return (x, y)
    }

    static func truncateUi(_ text: String, limit: Int = 1500) -> String {
        if text.count <= limit { return text }
        return String(text.prefix(limit)) + "…"
    }

    static func packageName(fromForegroundActivity activity: String?) -> String? {
        guard let activity, !activity.isEmpty else { return nil }
        guard let slash = activity.firstIndex(of: "/") else { return nil }
        let before = String(activity[..<slash])
        guard let regex = try? NSRegularExpression(
            pattern: "[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+"
        ) else { return nil }
        let range = NSRange(before.startIndex..<before.endIndex, in: before)
        guard let match = regex.firstMatch(in: before, range: range),
              let swiftRange = Range(match.range, in: before) else { return nil }
        return String(before[swiftRange])
    }

    private static func numericValue(_ value: Any?) -> Double? {
        switch value {
        case let number as NSNumber:
            return number.doubleValue
        case let int as Int:
            return Double(int)
        case let double as Double:
            return double
        case let string as String:
            return Double(string.trimmingCharacters(in: .whitespacesAndNewlines))
        default:
            return nil
        }
    }
}

final class TapCoordinateStore {
    private static let maxExperiencesPerPackage = 200
    private static let maxTextLength = 200

    private let fileURL: URL
    private var experiences: [TapCoordinateExperience]

    init() {
        let fileManager = FileManager.default
        let applicationSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.urls(for: .libraryDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        let directory = applicationSupport.appendingPathComponent("CustardMac", isDirectory: true)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("tap-coordinate-experiences.json")
        experiences = Self.load(from: fileURL)
    }

    func matching(packageName: String, pageLabel: String, limit: Int = 8) -> [TapCoordinateExperience] {
        let pkg = normalize(packageName)
        let page = normalize(pageLabel)
        guard !pkg.isEmpty, !page.isEmpty else { return [] }
        return experiences
            .filter { normalize($0.packageName) == pkg && normalize($0.pageLabel) == page }
            .sorted {
                if $0.successCount != $1.successCount {
                    return $0.successCount > $1.successCount
                }
                return $0.updatedAt > $1.updatedAt
            }
            .prefix(limit)
            .map { $0 }
    }

    func save(summary: TapCoordinateSummary) {
        let packageName = normalize(summary.packageName)
        let pageLabel = clean(summary.pageLabel)
        let controlLabel = clean(summary.controlLabel)
        guard !packageName.isEmpty, !pageLabel.isEmpty, !controlLabel.isEmpty else { return }
        guard summary.screenWidth > 0, summary.screenHeight > 0 else { return }
        guard (0..<summary.screenWidth).contains(summary.x),
              (0..<summary.screenHeight).contains(summary.y) else { return }

        let existing = experiences.first {
            normalize($0.packageName) == packageName
                && normalize($0.pageLabel) == normalize(pageLabel)
                && normalize($0.controlLabel) == normalize(controlLabel)
        }
        experiences.removeAll {
            normalize($0.packageName) == packageName
                && normalize($0.pageLabel) == normalize(pageLabel)
                && normalize($0.controlLabel) == normalize(controlLabel)
        }
        experiences.insert(
            TapCoordinateExperience(
                id: existing?.id ?? UUID(),
                packageName: String(packageName.prefix(Self.maxTextLength)),
                pageLabel: String(pageLabel.prefix(Self.maxTextLength)),
                controlLabel: String(controlLabel.prefix(Self.maxTextLength)),
                x: summary.x,
                y: summary.y,
                screenWidth: summary.screenWidth,
                screenHeight: summary.screenHeight,
                successCount: (existing?.successCount ?? 0) + 1
            ),
            at: 0
        )
        experiences = trimPerPackage(experiences)
        persist()
    }

    private func trimPerPackage(_ items: [TapCoordinateExperience]) -> [TapCoordinateExperience] {
        var counts: [String: Int] = [:]
        return items.filter { item in
            let pkg = normalize(item.packageName)
            let next = (counts[pkg] ?? 0) + 1
            counts[pkg] = next
            return next <= Self.maxExperiencesPerPackage
        }
    }

    func formatForPrompt(_ items: [TapCoordinateExperience]) -> String {
        items.map { item in
            "- \(item.controlLabel) → 像素 (\(item.x), \(item.y))"
                + "（记录时分辨率 \(item.screenWidth)x\(item.screenHeight)），成功 \(item.successCount) 次"
        }.joined(separator: "\n")
    }

    private func persist() {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(experiences) else { return }

        let temporaryURL = fileURL.appendingPathExtension("tmp")
        do {
            try data.write(to: temporaryURL, options: .atomic)
            _ = try FileManager.default.replaceItemAt(fileURL, withItemAt: temporaryURL)
        } catch {
            try? data.write(to: fileURL, options: .atomic)
            try? FileManager.default.removeItem(at: temporaryURL)
        }
    }

    private static func load(from url: URL) -> [TapCoordinateExperience] {
        guard let data = try? Data(contentsOf: url),
              let raw = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return raw.compactMap { item -> TapCoordinateExperience? in
            if let encoded = try? JSONSerialization.data(withJSONObject: item),
               let decoded = try? decoder.decode(TapCoordinateExperience.self, from: encoded) {
                return decoded
            }
            // Migrate legacy percent-based records.
            guard let packageName = item["packageName"] as? String,
                  let pageLabel = item["pageLabel"] as? String,
                  let controlLabel = item["controlLabel"] as? String,
                  let screenWidth = item["screenWidth"] as? Int,
                  let screenHeight = item["screenHeight"] as? Int,
                  screenWidth > 0, screenHeight > 0 else { return nil }
            let x: Int
            let y: Int
            if let px = item["x"] as? Int, let py = item["y"] as? Int {
                x = px
                y = py
            } else if let xPercent = item["xPercent"] as? Double,
                      let yPercent = item["yPercent"] as? Double {
                x = Int(xPercent / 100.0 * Double(screenWidth))
                y = Int(yPercent / 100.0 * Double(screenHeight))
            } else {
                return nil
            }
            let id = (item["id"] as? String).flatMap(UUID.init(uuidString:)) ?? UUID()
            let successCount = item["successCount"] as? Int ?? 1
            return TapCoordinateExperience(
                id: id,
                packageName: packageName,
                pageLabel: pageLabel,
                controlLabel: controlLabel,
                x: x,
                y: y,
                screenWidth: screenWidth,
                screenHeight: screenHeight,
                successCount: successCount
            )
        }
    }

    private func clean(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func normalize(_ value: String) -> String {
        clean(value).lowercased()
    }
}
