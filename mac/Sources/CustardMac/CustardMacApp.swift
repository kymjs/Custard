import SwiftUI

@main
struct CustardMacApp: App {
    init() {
        Logger.applyDesktopLogPreference()
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        Logger.info("奶黄包 started version=\(version) path=\(Bundle.main.bundlePath) log=\(Logger.filePath)")
    }


    var body: some Scene {
        WindowGroup {
            ContentView()
                .custardThemed()
        }
        .windowStyle(.titleBar)
        .defaultSize(width: 1100, height: 800)
    }
}
