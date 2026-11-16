import SwiftUI

/// Where the list's logo is, for the splash to land on it.
struct LogoAnchorKey: PreferenceKey {
    static var defaultValue: Anchor<CGRect>?
    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) { value = value ?? nextValue() }
}

/// The logo's tile with the echo split into ghosts (the landing's hero): `spread` 0 is the logo
/// as drawn; 1, three ghosts of the echo far out up and to the right, fading. Animatable.
private struct SplashTile: View, Animatable {
    @Environment(\.murmur) private var palette
    var size: CGFloat
    var spread: CGFloat
    var animatableData: CGFloat {
        get { spread }
        set { spread = newValue }
    }

    var body: some View {
        let markSize = size * 0.78
        let s = markSize / 140
        let round = { (width: CGFloat) in StrokeStyle(lineWidth: width * s, lineCap: .round, lineJoin: .round) }
        RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
            .fill(palette.logoBackground)
            .overlay {
                ZStack {
                    ForEach(1...3, id: \.self) { k in
                        let d = 15 + spread * CGFloat(k) * 17
                        MarkShape().stroke(palette.logoBar2, style: round(17 - CGFloat(k) * 1.6))
                            .offset(x: d * s, y: -d * s)
                            .opacity(spread * (0.62 - CGFloat(k) * 0.1))
                    }
                    let main = 15 + spread * 6
                    MarkShape().stroke(palette.logoBar2, style: round(17)).offset(x: main * s, y: -main * s)
                    MarkShape().stroke(palette.logoBackground, style: round(27))
                    MarkShape().stroke(palette.logoBar1, style: round(17))
                }
                .frame(width: markSize, height: markSize)
            }
            .frame(width: size, height: size)
    }
}

/// The splash (mockup, screen A): it starts as the launch screen (the same tile, at the centre),
/// the echo splits into ghosts and comes back, and the tile flies to the list's logo while the
/// list shows. The first time, it fades into the welcome instead. With Reduce Motion, a fade.
struct SplashOverlay: View {
    @Environment(\.murmur) private var palette
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The list's logo, where it lands (nil: it fades).
    var target: CGRect?
    var size: CGSize
    var done: () -> Void

    @State private var spread: CGFloat = 0
    @State private var fly = false
    @State private var fade = false
    private static let start: CGFloat = 112

    var body: some View {
        let landing = fly ? target : nil
        let side = landing?.width ?? Self.start
        let centre = landing.map { CGPoint(x: $0.midX, y: $0.midY) } ?? CGPoint(x: size.width / 2, y: size.height / 2)
        ZStack {
            palette.background.opacity(fly || fade ? 0 : 1)
            SplashTile(size: side, spread: spread)
                .position(centre)
                .opacity(fade ? 0 : 1)
        }
        .ignoresSafeArea()
        .allowsHitTesting(!fly && !fade)
        .accessibilityHidden(true)
        .task {
            if reduceMotion {
                try? await Task.sleep(for: .milliseconds(200))
                withAnimation(.easeOut(duration: 0.3)) { fade = true }
                try? await Task.sleep(for: .milliseconds(300))
                return done()
            }
            try? await Task.sleep(for: .milliseconds(150))
            withAnimation(.easeOut(duration: 0.42)) { spread = 1 }
            try? await Task.sleep(for: .milliseconds(420))
            withAnimation(.easeInOut(duration: 0.26)) { spread = 0 }
            try? await Task.sleep(for: .milliseconds(240))
            if target != nil {
                withAnimation(.spring(duration: 0.45, bounce: 0.12)) { fly = true }
                try? await Task.sleep(for: .milliseconds(450))
            } else {
                withAnimation(.easeOut(duration: 0.3)) { fade = true }
                try? await Task.sleep(for: .milliseconds(300))
            }
            done()
        }
    }
}
