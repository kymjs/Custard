import Foundation

enum DebugMode: String, CaseIterable, Identifiable {
    case off
    case log
    case debug

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .off: return "关闭"
        case .log: return "日志"
        case .debug: return "调试"
        }
    }

    var logEnabled: Bool { self != .off }
    var stepDebugEnabled: Bool { self == .debug }
}
