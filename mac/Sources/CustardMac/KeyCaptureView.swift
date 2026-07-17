import AppKit
import SwiftUI

struct KeyCaptureView: NSViewRepresentable {
    let onKeyDown: (NSEvent) -> Bool
    let onInsertText: (String) -> Void
    /// Increment to request keyboard focus for phone input (e.g. after clicking the screen).
    var focusTrigger: Int = 0

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeNSView(context: Context) -> KeyCaptureNSView {
        let view = KeyCaptureNSView()
        view.onKeyDown = onKeyDown
        view.onInsertText = onInsertText
        return view
    }

    func updateNSView(_ nsView: KeyCaptureNSView, context: Context) {
        nsView.onKeyDown = onKeyDown
        nsView.onInsertText = onInsertText
        guard focusTrigger != context.coordinator.lastFocusTrigger else { return }
        context.coordinator.lastFocusTrigger = focusTrigger
        DispatchQueue.main.async {
            nsView.window?.makeFirstResponder(nsView)
        }
    }

    final class Coordinator {
        var lastFocusTrigger = 0
    }
}

final class KeyCaptureNSView: NSView {
    var onKeyDown: ((NSEvent) -> Bool)?
    var onInsertText: ((String) -> Void)?

    override var acceptsFirstResponder: Bool { true }

    override func keyDown(with event: NSEvent) {
        if onKeyDown?(event) != true {
            super.keyDown(with: event)
        }
    }

    override func insertText(_ insertString: Any) {
        guard let text = insertString as? String, !text.isEmpty else { return }
        onInsertText?(text)
    }
}
