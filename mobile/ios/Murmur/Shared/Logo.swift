import SwiftUI

/// The "m" of the Eco mark (docs/design/icons/logo/murmur-mark.svg), on the 200-unit grid, fitted
/// to its frame the way the icon shows it (the 140-unit box from 30).
struct MarkShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: 60, y: 140)); p.addLine(to: CGPoint(x: 60, y: 100))
        p.addArc(center: CGPoint(x: 78, y: 100), radius: 18, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: 96, y: 140))
        p.move(to: CGPoint(x: 96, y: 100))
        p.addArc(center: CGPoint(x: 114, y: 100), radius: 18, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: 132, y: 140))
        let scale = rect.width / 140
        return p.applying(CGAffineTransform(translationX: -30, y: -30).concatenating(CGAffineTransform(scaleX: scale, y: scale)))
    }
}

/// The Eco mark: the "m" and its echo up and to the right. `echo` (0…1) is how far the echo
/// stands from the "m": 1 is the logo as drawn; the Dynamic Island moves it with the voice. Made
/// of shapes (not a Canvas) so the echo's move animates, also in a Live Activity.
struct LogoMark: View {
    @Environment(\.murmur) private var palette
    var size: CGFloat
    var echo: CGFloat = 1

    var body: some View {
        let s = size / 140
        let round = { (width: CGFloat) in StrokeStyle(lineWidth: width * s, lineCap: .round, lineJoin: .round) }
        ZStack {
            MarkShape().stroke(palette.logoBar2, style: round(17))
                .offset(x: 15 * s * echo, y: -15 * s * echo)
            MarkShape().stroke(palette.logoBackground, style: round(27))
            MarkShape().stroke(palette.logoBar1, style: round(17))
        }
        .frame(width: size, height: size)
    }
}

/// The app icon's tile: the mark on its black square.
struct LogoTile: View {
    @Environment(\.murmur) private var palette
    @Environment(\.colorScheme) private var scheme
    var size: CGFloat
    var echo: CGFloat = 1
    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
            .fill(palette.logoBackground)
            // logoRing (DESIGN.md): none in light, a faint ring in dark so the black tile isn't lost.
            .overlay(RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                .strokeBorder(scheme == .dark ? palette.textPrimary.opacity(0.15) : .clear, lineWidth: 1))
            .overlay(LogoMark(size: size * 0.78, echo: echo))
            .frame(width: size, height: size)
    }
}
