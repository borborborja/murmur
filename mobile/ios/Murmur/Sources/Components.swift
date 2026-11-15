import SwiftUI

/// A speaker's label, with the desktop's colours (speaker 1 violet, 2 coral, 3 teal, 4 magenta).
struct SpeakerChip: View {
    @Environment(\.murmur) private var palette
    var speaker: Int
    var name: String?

    var body: some View {
        let (tint, text) = colors
        Text(name ?? L("rec.speaker", "\(speaker + 1)"))
            .font(.system(size: 12, weight: .semibold))
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(tint, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            .foregroundStyle(text)
    }

    private var colors: (Color, Color) {
        switch speaker % 4 {
        case 0: return (palette.speaker1Tint, palette.speaker1Text)
        case 1: return (palette.speaker2Tint, palette.speaker2Text)
        case 2: return (palette.speaker3Tint, palette.speaker3Text)
        default: return (palette.speaker4Tint, palette.speaker4Text)
        }
    }
}

/// "⚑ Important": a marked moment, in the accent with dark text (never accent text on light).
struct FlagChip: View {
    @Environment(\.murmur) private var palette
    var label: String
    var body: some View {
        Label(label, systemImage: "flag")
            .font(.system(size: 12, weight: .semibold))
            .labelStyle(.titleAndIcon)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(palette.accent, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            .foregroundStyle(palette.onAccent)
    }
}

/// The two or three level bars of the recording pill: they move with the voice.
struct LevelBars: View {
    @Environment(\.murmur) private var palette
    var level: Float
    var count = 2
    var height: CGFloat = 16

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<count, id: \.self) { i in
                Capsule()
                    .fill(palette.recordingLevel)
                    .frame(width: 4, height: barHeight(i))
            }
        }
        .frame(height: height)
        .animation(.easeOut(duration: 0.15), value: level)
    }

    private func barHeight(_ i: Int) -> CGFloat {
        let v = CGFloat(min(1, max(0, (log10(Double(max(level, 0.0001))) + 3) / 2.5)))
        let shape: [CGFloat] = [0.65, 1, 0.8]
        return max(5, height * v * shape[i % 3])
    }
}

/// The red recording dot with its halo.
struct RecordingDot: View {
    @Environment(\.murmur) private var palette
    var body: some View {
        Circle().fill(palette.recordingDot).frame(width: 8, height: 8)
            .background(Circle().fill(palette.recordingDot.opacity(0.22)).frame(width: 14, height: 14))
    }
}

/// A round 44 pt button on the subtle fill (back, more, search).
struct CircleButton: View {
    @Environment(\.murmur) private var palette
    var systemImage: String
    var label: String
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage).font(.system(size: 17, weight: .medium))
                .frame(width: 44, height: 44)
                .background(palette.fillSubtle, in: Circle())
                .foregroundStyle(palette.textPrimary)
        }
        .accessibilityLabel(label)
    }
}

/// The segmented control of the desktop (Transcripció / Notes).
struct Segmented<T: Hashable>: View {
    @Environment(\.murmur) private var palette
    @Environment(\.colorScheme) private var scheme
    var options: [(T, String)]
    @Binding var selection: T
    @Namespace private var thumb

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.0) { value, label in
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { selection = value }
                } label: {
                    Text(label).font(.system(size: 14, weight: .medium))
                        .frame(maxWidth: .infinity).padding(.vertical, 7)
                        .foregroundStyle(selection == value ? palette.textPrimary : palette.textSecondary)
                        .background {
                            if selection == value {
                                RoundedRectangle(cornerRadius: 9, style: .continuous)
                                    .fill(scheme == .dark ? Color(white: 0.17) : palette.segmentedSelected)
                                    .shadow(color: .black.opacity(0.08), radius: 1.5, y: 1)
                                    .matchedGeometryEffect(id: "thumb", in: thumb)
                            }
                        }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(palette.segmentedTrack, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// "Canvia el nom": an alert with the meeting's title in a field.
struct RenameAlert: ViewModifier {
    @Binding var isPresented: Bool
    @Binding var title: String
    var save: (String) -> Void

    func body(content: Content) -> some View {
        content.alert(L("meeting.renameTitle"), isPresented: $isPresented) {
            TextField(L("meeting.renameTitle"), text: $title)
                .submitLabel(.done)
            Button(L("meeting.cancel"), role: .cancel) {}
            Button(L("meeting.save")) { save(title) }
        }
    }
}

extension View {
    func renameAlert(isPresented: Binding<Bool>, title: Binding<String>, save: @escaping (String) -> Void) -> some View {
        modifier(RenameAlert(isPresented: isPresented, title: title, save: save))
    }
}

extension Font {
    /// Monospaced, tabular figures: times never jump.
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced).monospacedDigit()
    }
}

extension View {
    /// Liquid Glass where there is (iOS 26), a material otherwise.
    @ViewBuilder func glassCapsule() -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: Capsule())
        } else {
            self.background(.ultraThinMaterial, in: Capsule())
        }
    }
}
