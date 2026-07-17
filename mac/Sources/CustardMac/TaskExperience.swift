import Foundation

struct TaskExperience: Codable, Identifiable, Equatable {
    let id: UUID
    let category: String
    let goal: String
    let steps: [String]
    var updatedAt: Date
    var useCount: Int

    init(
        id: UUID = UUID(),
        category: String,
        goal: String,
        steps: [String],
        updatedAt: Date = Date(),
        useCount: Int = 0
    ) {
        self.id = id
        self.category = category
        self.goal = goal
        self.steps = steps
        self.updatedAt = updatedAt
        self.useCount = useCount
    }
}

struct TaskExperienceClassification {
    let category: String
}

struct TaskExperienceSummary {
    let category: String
    let goal: String
    let steps: [String]
}

final class TaskExperienceStore {
    private static let maxExperiences = 100
    private static let maxStepsPerExperience = 30
    private static let maxTextLength = 500

    private let fileURL: URL
    private var experiences: [TaskExperience]

    init() {
        let fileManager = FileManager.default
        let applicationSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.urls(for: .libraryDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        let directory = applicationSupport.appendingPathComponent("CustardMac", isDirectory: true)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("task-experiences.json")
        experiences = Self.load(from: fileURL)
    }

    func matching(category: String, limit: Int = 5) -> [TaskExperience] {
        let normalized = normalize(category)
        let matches = experiences
            .filter { normalize($0.category) == normalized }
            .sorted { $0.updatedAt > $1.updatedAt }
            .prefix(limit)
        let result = Array(matches)
        if !result.isEmpty {
            let ids = Set(result.map(\.id))
            experiences = experiences.map { item in
                guard ids.contains(item.id) else { return item }
                var updated = item
                updated.useCount += 1
                updated.updatedAt = Date()
                return updated
            }
            save()
        }
        return result
    }

    func save(summary: TaskExperienceSummary) {
        let category = normalize(summary.category)
        let goal = clean(summary.goal)
        let steps = summary.steps
            .map(clean)
            .filter { !$0.isEmpty }
            .prefix(Self.maxStepsPerExperience)
        guard !category.isEmpty, !goal.isEmpty, !steps.isEmpty else { return }

        let newExperience = TaskExperience(
            category: String(category.prefix(Self.maxTextLength)),
            goal: String(goal.prefix(Self.maxTextLength)),
            steps: steps.map { String($0.prefix(Self.maxTextLength)) }
        )
        experiences.removeAll {
            normalize($0.category) == category && normalize($0.goal) == normalize(goal)
        }
        experiences.insert(newExperience, at: 0)
        experiences = Array(experiences.prefix(Self.maxExperiences))
        save()
    }

    private func save() {
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

    private static func load(from url: URL) -> [TaskExperience] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode([TaskExperience].self, from: data)) ?? []
    }

    private func clean(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func normalize(_ value: String) -> String {
        clean(value).lowercased()
    }
}
