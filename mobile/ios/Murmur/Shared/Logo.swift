import SwiftUI

/// The Eco mark: the "m" and its echo up and to the right (docs/design/icons/logo/murmur-mark.svg),
/// drawn on the 200-unit grid.
struct LogoMark: View {
    @Environment(\.murmur) private var palette
    var size: CGFloat

    private func m() -> Path {
        var p = Path()
        p.move(to: CGPoint(x: 60, y: 140)); p.addLine(to: CGPoint(x: 60, y: 100))
        p.addArc(center: CGPoint(x: 78, y: 100), radius: 18, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: 96, y: 140))
        p.move(to: CGPoint(x: 96, y: 100))
        p.addArc(center: CGPoint(x: 114, y: 100), radius: 18, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: 132, y: 140))
        return p
    }

    var body: some View {
        Canvas { ctx, size in
            let scale = size.width / 140
            ctx.translateBy(x: -30 * scale, y: -30 * scale)
            ctx.scaleBy(x: scale, y: scale)
            let round = StrokeStyle(lineWidth: 17, lineCap: .round, lineJoin: .round)
            ctx.stroke(m().applying(CGAffineTransform(translationX: 15, y: -15)), with: .color(palette.logoBar2), style: round)
            ctx.stroke(m(), with: .color(palette.logoBackground), style: StrokeStyle(lineWidth: 27, lineCap: .round, lineJoin: .round))
            ctx.stroke(m(), with: .color(palette.logoBar1), style: round)
        }
        .frame(width: size, height: size)
    }
}

/// The app icon's tile: the mark on its black square.
struct LogoTile: View {
    @Environment(\.murmur) private var palette
    @Environment(\.colorScheme) private var scheme
    var size: CGFloat
    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
            .fill(palette.logoBackground)
            .overlay(RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)// logoRing (DESIGN.md): none in light, a faint ring in dark so the black tile isn't lost.
            .strokeBorder(scheme == .dark ? palette.textPrimary.opacity(0.15) : .clear, lineWidth: 1))
            .overlay(LogoMark(size: size * 0.78))
            .frame(width: size, height: size)
    }
}
