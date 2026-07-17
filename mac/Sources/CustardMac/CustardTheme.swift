import SwiftUI

struct CustardPalette {
    let primary: Color
    let onPrimary: Color
    let primaryContainer: Color
    let onPrimaryContainer: Color
    let tertiary: Color
    let onTertiary: Color
    let background: Color
    let onBackground: Color
    let surface: Color
    let onSurface: Color
    let secondaryText: Color
    let error: Color
    let divider: Color

    static let light = CustardPalette(
        primary: Color(red: 1.0, green: 0.820, blue: 0.400),
        onPrimary: Color(red: 0.129, green: 0.129, blue: 0.129),
        primaryContainer: Color(red: 1.0, green: 0.910, blue: 0.702),
        onPrimaryContainer: Color(red: 0.239, green: 0.173, blue: 0.0),
        tertiary: Color(red: 0.973, green: 0.694, blue: 0.584),
        onTertiary: Color(red: 0.129, green: 0.129, blue: 0.129),
        background: Color(red: 1.0, green: 0.973, blue: 0.882),
        onBackground: Color(red: 0.129, green: 0.129, blue: 0.129),
        surface: Color(red: 1.0, green: 0.973, blue: 0.882),
        onSurface: Color(red: 0.129, green: 0.129, blue: 0.129),
        secondaryText: Color(red: 0.286, green: 0.271, blue: 0.212),
        error: Color(red: 0.690, green: 0.0, blue: 0.125),
        divider: Color(red: 0.910, green: 0.878, blue: 0.816)
    )

    static let dark = CustardPalette(
        primary: Color(red: 1.0, green: 0.878, blue: 0.510),
        onPrimary: Color(red: 0.239, green: 0.173, blue: 0.0),
        primaryContainer: Color(red: 0.333, green: 0.263, blue: 0.0),
        onPrimaryContainer: Color(red: 1.0, green: 0.878, blue: 0.510),
        tertiary: Color(red: 0.816, green: 0.816, blue: 0.816),
        onTertiary: Color(red: 0.129, green: 0.129, blue: 0.129),
        background: Color(red: 0.110, green: 0.102, blue: 0.071),
        onBackground: Color(red: 0.902, green: 0.882, blue: 0.835),
        surface: Color(red: 0.110, green: 0.102, blue: 0.071),
        onSurface: Color(red: 0.902, green: 0.882, blue: 0.835),
        secondaryText: Color(red: 0.796, green: 0.769, blue: 0.710),
        error: Color(red: 1.0, green: 0.706, blue: 0.671),
        divider: Color(red: 0.286, green: 0.271, blue: 0.212)
    )
}

private struct CustardPaletteKey: EnvironmentKey {
    static let defaultValue = CustardPalette.light
}

extension EnvironmentValues {
    var custardPalette: CustardPalette {
        get { self[CustardPaletteKey.self] }
        set { self[CustardPaletteKey.self] = newValue }
    }
}

struct CustardThemed: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            .environment(
                \.custardPalette,
                colorScheme == .dark ? .dark : .light
            )
            .tint(colorScheme == .dark ? CustardPalette.dark.primary : CustardPalette.light.primary)
    }
}

extension View {
    func custardThemed() -> some View {
        modifier(CustardThemed())
    }
}
