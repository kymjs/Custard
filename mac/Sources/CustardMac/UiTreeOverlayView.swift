import SwiftUI

struct UiTreeOverlayView: View {
    let nodes: [UiTreeNode]
    let deviceSize: CGSize
    var contentAlignment: Alignment = .center

    var body: some View {
        Canvas { context, viewSize in
            let layout = fittedImageLayout(viewSize: viewSize, imageSize: deviceSize)

            for node in nodes {
                let deviceRect = node.bounds
                guard deviceRect.width > 0, deviceRect.height > 0 else { continue }

                let x = layout.origin.x + (deviceRect.minX / deviceSize.width) * layout.size.width
                let y = layout.origin.y + (deviceRect.minY / deviceSize.height) * layout.size.height
                let w = (deviceRect.width / deviceSize.width) * layout.size.width
                let h = (deviceRect.height / deviceSize.height) * layout.size.height
                let rect = CGRect(x: x, y: y, width: w, height: h)

                let strokeColor: Color = node.editable ? .orange : (node.clickable ? .green : .cyan)
                context.stroke(Path(rect), with: .color(strokeColor.opacity(0.85)), lineWidth: 1)

                if let label = node.label, w > 24, h > 14 {
                    let display = truncate(label, maxLength: 24)
                    let text = Text(display)
                        .font(.system(size: 9))
                        .foregroundColor(strokeColor)
                    context.draw(text, at: CGPoint(x: rect.minX + 2, y: rect.minY + 2), anchor: .topLeading)
                }
            }
        }
        .allowsHitTesting(false)
    }

    private func fittedImageLayout(viewSize: CGSize, imageSize: CGSize) -> (origin: CGPoint, size: CGSize) {
        guard imageSize.width > 0, imageSize.height > 0 else {
            return (CGPoint(x: 0, y: 0), viewSize)
        }

        let imageAspect = imageSize.width / imageSize.height
        let viewAspect = viewSize.width / viewSize.height

        if imageAspect > viewAspect {
            let displayedWidth = viewSize.width
            let displayedHeight = viewSize.width / imageAspect
            let offsetY = contentAlignment == .leading ? 0 : (viewSize.height - displayedHeight) / 2
            return (CGPoint(x: 0, y: offsetY), CGSize(width: displayedWidth, height: displayedHeight))
        } else {
            let displayedHeight = viewSize.height
            let displayedWidth = viewSize.height * imageAspect
            let offsetX = contentAlignment == .leading ? 0 : (viewSize.width - displayedWidth) / 2
            return (CGPoint(x: offsetX, y: 0), CGSize(width: displayedWidth, height: displayedHeight))
        }
    }

    private func truncate(_ text: String, maxLength: Int) -> String {
        if text.count <= maxLength { return text }
        return String(text.prefix(maxLength - 1)) + "…"
    }
}
