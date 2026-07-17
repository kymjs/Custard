import Foundation

enum Protocol {
    static let magic = "CUSTARD"
    static let defaultPort: UInt16 = 27183
    static let adbLocalHost = "127.0.0.1"
    static let adbSocketName = "custard"

    static let codecH264: UInt8 = 1

    static let msgDeviceInfo: UInt8 = 0
    static let msgVideoFrame: UInt8 = 1
    static let msgTouch: UInt8 = 2
    static let msgKey: UInt8 = 3
    static let msgText: UInt8 = 4
    static let msgClipboardSet: UInt8 = 5
    static let msgClipboardGet: UInt8 = 6
    static let msgClipboard: UInt8 = 7
    static let msgUiTreeGet: UInt8 = 8
    static let msgUiTree: UInt8 = 9
    static let msgTextResult: UInt8 = 10

    static let actionDown: UInt8 = 0
    static let actionUp: UInt8 = 1
    static let actionMove: UInt8 = 2

    static let flagKeyframe: UInt8 = 1
}

enum ConnectionPreferences {
    static var lastWifiHost: String {
        get { AppPreferences.wifiHost }
        set { AppPreferences.wifiHost = newValue }
    }

    static var lastWifiPort: String {
        get { AppPreferences.wifiPort }
        set { AppPreferences.wifiPort = newValue }
    }

    static func saveWifiConnection(host: String, port: String) {
        AppPreferences.saveWifiConnection(host: host, port: port)
    }
}

struct DeviceInfo {
    let width: Int
    let height: Int
    let codec: UInt8
}
