import Foundation

/// 串行化外部 Agent HTTP 请求，并做简单频率限制
actor AgentRequestGate {
    static let shared = AgentRequestGate()

    private let maxRequestsPerMinute = 120
    private var recentRequestTimes: [Date] = []

    func acquire() throws {
        let now = Date()
        recentRequestTimes.removeAll { now.timeIntervalSince($0) > 60 }
        guard recentRequestTimes.count < maxRequestsPerMinute else {
            throw AgentRequestGateError.rateLimited
        }
        recentRequestTimes.append(now)
    }

    func process<T>(_ work: @Sendable () async throws -> T) async throws -> T {
        try acquire()
        return try await work()
    }
}

enum AgentRequestGateError: LocalizedError {
    case rateLimited

    var errorDescription: String? {
        switch self {
        case .rateLimited:
            return "请求过于频繁，请稍后重试（限制 120 次/分钟）。"
        }
    }
}
