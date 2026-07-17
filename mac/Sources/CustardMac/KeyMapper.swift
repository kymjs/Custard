import AppKit

enum KeyMapper {
  static func androidKeyCode(for event: NSEvent) -> Int? {
    switch event.keyCode {
    case 51: return 67   // DELETE -> KEYCODE_DEL
    case 36: return 66   // RETURN -> KEYCODE_ENTER
    case 53: return 4    // ESCAPE -> KEYCODE_BACK
    case 117: return 112 // FORWARD_DELETE -> KEYCODE_FORWARD_DEL
    case 123: return 21  // LEFT_ARROW -> KEYCODE_DPAD_LEFT
    case 124: return 22  // RIGHT_ARROW -> KEYCODE_DPAD_RIGHT
    case 125: return 20  // DOWN_ARROW -> KEYCODE_DPAD_DOWN
    case 126: return 19  // UP_ARROW -> KEYCODE_DPAD_UP
    default:
      return nil
    }
  }

  static func typedText(for event: NSEvent) -> String? {
    guard let chars = event.characters, !chars.isEmpty else { return nil }
    guard !event.modifierFlags.contains(.command) else { return nil }
    guard !event.modifierFlags.contains(.control) else { return nil }
    return chars
  }
}
