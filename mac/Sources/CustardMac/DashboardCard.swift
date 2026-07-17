import SwiftUI

struct DashboardCard<Trailing: View>: View {
    @Environment(\.custardPalette) private var palette

    let iconName: String
    let title: String
    let subtitle: String
    let trailing: Trailing

    init(
        iconName: String,
        title: String,
        subtitle: String,
        @ViewBuilder trailing: () -> Trailing = { EmptyView() }
    ) {
        self.iconName = iconName
        self.title = title
        self.subtitle = subtitle
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: iconName)
                .font(.title2)
                .foregroundStyle(palette.onPrimaryContainer)
                .frame(width: 40, height: 40)
                .background(palette.primaryContainer)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(palette.onSurface)
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(palette.secondaryText)
                    .lineLimit(2)
            }

            Spacer(minLength: 8)

            trailing
        }
        .padding(16)
        .background(palette.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(palette.divider, lineWidth: 1)
        )
    }
}

struct DashboardCardButton: View {
    @Environment(\.custardPalette) private var palette

    let iconName: String
    let title: String
    let subtitle: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            DashboardCard(iconName: iconName, title: title, subtitle: subtitle) {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(palette.secondaryText)
            }
        }
        .buttonStyle(.plain)
    }
}

struct StartButtonCard: View {
    @Environment(\.custardPalette) private var palette

    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "play.circle.fill")
                    .font(.title)
                Text("启动")
                    .font(.title3.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)
            .foregroundStyle(palette.onPrimary)
            .background(palette.primary)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}
