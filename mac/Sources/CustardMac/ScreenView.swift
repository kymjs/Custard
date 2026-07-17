import SwiftUI

struct ScreenView: View {
    @Environment(\.custardPalette) private var palette
    @ObservedObject var connection: ConnectionManager
    @ObservedObject var frameStore: ScreenFrameStore
    let deviceInfo: DeviceInfo
    var contentAlignment: Alignment = .center

    @State private var isDragging = false
    @State private var keyCaptureFocusTrigger = 0

    var body: some View {
        ZStack {
            GeometryReader { geometry in
                if let image = frameStore.image {
                    Image(decorative: image, scale: 1.0)
                        .interpolation(.none)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: contentAlignment)
                        .contentShape(Rectangle())
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { value in
                                    let point = mapToDevice(
                                        location: value.location,
                                        viewSize: geometry.size,
                                        imageSize: CGSize(
                                            width: image.width,
                                            height: image.height
                                        )
                                    )
                                    if !isDragging {
                                        isDragging = true
                                        keyCaptureFocusTrigger += 1
                                        connection.sendTouch(
                                            action: Protocol.actionDown,
                                            x: point.x,
                                            y: point.y
                                        )
                                    } else {
                                        connection.sendTouch(
                                            action: Protocol.actionMove,
                                            x: point.x,
                                            y: point.y
                                        )
                                    }
                                }
                                .onEnded { value in
                                    let point = mapToDevice(
                                        location: value.location,
                                        viewSize: geometry.size,
                                        imageSize: CGSize(
                                            width: image.width,
                                            height: image.height
                                        )
                                    )
                                    connection.sendTouch(
                                        action: Protocol.actionUp,
                                        x: point.x,
                                        y: point.y
                                    )
                                    isDragging = false
                                }
                        )
                } else {
                    VStack(spacing: 12) {
                        ProgressView()
                            .tint(palette.primary)
                        Text("等待屏幕画面...")
                            .foregroundStyle(palette.secondaryText)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(palette.background)
                }

                if connection.isScreenCaptureBlocked {
                    UiTreeOverlayView(
                        nodes: connection.uiTreeOverlayNodes,
                        deviceSize: CGSize(width: deviceInfo.width, height: deviceInfo.height),
                        contentAlignment: contentAlignment
                    )

                    VStack {
                        HStack(spacing: 8) {
                            Image(systemName: "eye.slash.fill")
                            Text("当前应用禁止录屏")
                                .font(.subheadline.weight(.semibold))
                            if !connection.uiTreeOverlayNodes.isEmpty {
                                Text("· 已显示 UI 树布局")
                                    .font(.caption)
                                    .foregroundStyle(.white.opacity(0.85))
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(.black.opacity(0.72))
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .padding(12)
                        Spacer()
                    }
                }
            }

            KeyCaptureView(
                onKeyDown: { event in
                    handleKey(event)
                    return true
                },
                onInsertText: { text in
                    connection.sendText(text)
                },
                focusTrigger: keyCaptureFocusTrigger
            )
            .frame(width: 0, height: 0)
        }
    }

    private func handleKey(_ event: NSEvent) {
        if let keyCode = KeyMapper.androidKeyCode(for: event) {
            connection.sendKey(action: Protocol.actionDown, keyCode: keyCode)
            connection.sendKey(action: Protocol.actionUp, keyCode: keyCode)
            return
        }
        if let text = KeyMapper.typedText(for: event) {
            connection.sendText(text)
        }
    }

    private func mapToDevice(
        location: CGPoint,
        viewSize: CGSize,
        imageSize: CGSize
    ) -> (x: Float, y: Float) {
        let imageAspect = imageSize.width / imageSize.height
        let viewAspect = viewSize.width / viewSize.height

        var displayedWidth: CGFloat
        var displayedHeight: CGFloat
        var offsetX: CGFloat = 0
        var offsetY: CGFloat = 0

        if imageAspect > viewAspect {
            displayedWidth = viewSize.width
            displayedHeight = viewSize.width / imageAspect
            offsetY = (viewSize.height - displayedHeight) / 2
        } else {
            displayedHeight = viewSize.height
            displayedWidth = viewSize.height * imageAspect
            offsetX = (viewSize.width - displayedWidth) / 2
        }

        let relativeX = (location.x - offsetX) / displayedWidth
        let relativeY = (location.y - offsetY) / displayedHeight

        let clampedX = max(0, min(1, relativeX))
        let clampedY = max(0, min(1, relativeY))

        return (
            x: Float(clampedX) * Float(deviceInfo.width),
            y: Float(clampedY) * Float(deviceInfo.height)
        )
    }
}
