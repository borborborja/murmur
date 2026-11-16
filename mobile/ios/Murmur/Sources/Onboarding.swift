import AVFoundation
import SwiftUI

/// The first time (mockup, screens B to F): what Murmur is, what it does in four pages with a
/// small demo each (as the desktop's onboarding), how you record and the microphone's permission,
/// and two things that can wait (your voice, the computer). Then the list.
struct OnboardingView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    var finish: () -> Void

    /// The screens in order: the welcome, the four of what it does, how you record, the
    /// microphone, your voice, the computer. One page stays; only its content changes.
    @State private var index = 0
    /// Which way the content goes (forward: in from the right).
    @State private var forward = true
    @State private var micDenied = false
    @State private var readingVoice = false
    @State private var pairing = false
    @AppStorage("language") private var language = "ca-es"
    @AppStorage("moreInRoom") private var moreInRoom = true
    private static let features = 1...4
    private static let last = 8

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            OnboardingPage(spec: spec(index), id: index, forward: forward)
        }
        .foregroundStyle(palette.textPrimary)
        // The four pages of what it does also go by swiping.
        .simultaneousGesture(DragGesture(minimumDistance: 30).onEnded { drag in
            guard Self.features.contains(index), abs(drag.translation.width) > abs(drag.translation.height) else { return }
            if drag.translation.width < -50 { go(index + 1) } else if drag.translation.width > 50, index > Self.features.lowerBound { go(index - 1) }
        })
        .fullScreenCover(isPresented: $readingVoice) {
            VoiceEnrollView(saved: { go(index + 1) })
        }
        .fullScreenCover(isPresented: $pairing, onDismiss: { if store.computer != nil { finish() } }) {
            PairView()
        }
    }

    private func go(_ next: Int) {
        guard next != index else { return }
        if next > Self.last { return finish() }
        forward = next > index
        withAnimation(.spring(duration: 0.45, bounce: 0.15)) { index = next }
    }

    private func askMicrophone() {
        if micDenied { return go(index + 1) }
        Task {
            let allowed = Microphone.simulated != nil ? true : await AVAudioApplication.requestRecordPermission()
            if allowed { go(index + 1) } else { withAnimation { micDenied = true } }
        }
    }

    /// What screen `i` shows.
    private func spec(_ i: Int) -> OnboardingPage.Spec {
        let next = (L("onboarding.next"), { go(i + 1) })
        switch i {
        case 0:
            return .init(art: AnyView(LogoTile(size: 112)), title: L("onboarding.welcomeTitle"), text: L("onboarding.welcomeTagline"),
                         note: (L("onboarding.welcomePrivate"), "lock"), primary: (L("onboarding.start"), { go(1) }))
        case Self.features:
            let n = i - Self.features.lowerBound + 1
            return .init(art: AnyView(featureArt(n - 1)), kicker: L("onboarding.f\(n)Kicker"), title: L("onboarding.f\(n)Title"),
                         text: L("onboarding.f\(n)Text"), dots: (n - 1, Self.features.count), primary: next,
                         top: (L("onboarding.skip"), { go(Self.features.upperBound + 1) }))
        case 5:
            return .init(art: AnyView(setupArt), kicker: L("onboarding.setupKicker"), title: L("onboarding.setupTitle"),
                         note: (L("onboarding.consent"), "person"), primary: next)
        case 6:
            return .init(art: AnyView(micArt), kicker: L("onboarding.micKicker"), title: L("onboarding.micTitle"),
                         text: micDenied ? L("onboarding.micDenied") : L("onboarding.micText"),
                         primary: (micDenied ? L("onboarding.next") : L("onboarding.micAllow"), askMicrophone))
        case 7:
            return .init(art: AnyView(voiceArt), kicker: L("onboarding.voiceKicker"), title: L("onboarding.voiceTitle"),
                         note: (L("onboarding.voiceText"), "lock"), primary: (L("onboarding.voiceGo"), { readingVoice = true }),
                         top: (L("onboarding.notNow"), { go(i + 1) }))
        default:
            return .init(art: AnyView(computerArt), kicker: L("onboarding.computerKicker"), title: L("onboarding.computerTitle"),
                         text: L("onboarding.computerText"), note: (L("onboarding.computerDownload"), "laptopcomputer"),
                         primary: (L("onboarding.computerGo"), { pairing = true }), top: (L("onboarding.notNow"), finish))
        }
    }

    @ViewBuilder private func featureArt(_ i: Int) -> some View {
        switch i {
        case 0: liveArt
        case 1: speakersArt
        case 2: lockArt
        default: syncArt
        }
    }

    private func demoCard<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12, content: content)
            .padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
    }

    private var liveArt: some View {
        demoCard {
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) { SpeakerChip(speaker: 1, name: L("onboarding.demoMarta")); Text("4:12").font(.tabular(12)).foregroundStyle(palette.timestamp) }
                Text(L("onboarding.demoLine1")).font(.system(size: 16))
            }
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) { SpeakerChip(speaker: 0, name: L("rec.you")); Text("4:20").font(.tabular(12)).foregroundStyle(palette.timestamp) }
                (Text(L("onboarding.demoLine2") + " ") + Text(L("onboarding.demoPartial")).foregroundColor(palette.textTertiary) + Text(" |").foregroundColor(palette.caret))
                    .font(.system(size: 16))
            }
        }
        .accessibilityHidden(true)
    }

    private var speakersArt: some View {
        demoCard {
            HStack(spacing: 6) {
                SpeakerChip(speaker: 0, name: L("rec.you"))
                SpeakerChip(speaker: 1, name: L("onboarding.demoMarta"))
                SpeakerChip(speaker: 2, name: L("onboarding.demoJordi"))
            }
            HStack(spacing: 2) {
                palette.speaker1.frame(maxWidth: .infinity)
                palette.speaker2.frame(maxWidth: .infinity).layoutPriority(-1)
                palette.speaker3.frame(width: 60)
            }
            .frame(height: 8).clipShape(Capsule())
            noteRow("1:40") { Text(L("onboarding.demoNote1")).font(.system(size: 16, weight: .semibold)) }
            noteRow("3:05") {
                HStack(spacing: 8) { Image(systemName: "square").foregroundStyle(palette.textTertiary); Text(L("onboarding.demoNote2")).font(.system(size: 16)) }
            }
            noteRow("4:20") { FlagChip(label: L("rec.markNote")) }
        }
        .accessibilityHidden(true)
    }

    private func noteRow<Content: View>(_ time: String, @ViewBuilder _ content: () -> Content) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(time).font(.tabular(12)).foregroundStyle(palette.timestamp).frame(width: 34, alignment: .trailing)
            content()
        }
    }

    /// The lock screen's card, in its own dark colours (as the Live Activity).
    private var lockArt: some View {
        let dark = MurmurPalette.acidDark
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 9) {
                LogoTile(size: 28)
                VStack(alignment: .leading, spacing: 0) {
                    Text(L("onboarding.demoMeeting")).font(.system(size: 14, weight: .semibold))
                    Text("\(L("rec.recording")) · \(L("language.caEs"))").font(.system(size: 12)).foregroundStyle(dark.textSecondary)
                }
            }
            HStack {
                Text("12:34").font(.tabular(38))
                Spacer()
                HStack(spacing: 4) {
                    ForEach([12, 22, 16, 26, 10], id: \.self) { h in Capsule().fill(dark.recordingLevel).frame(width: 5, height: CGFloat(h)) }
                }
            }
            (Text(L("onboarding.demoMarta")).fontWeight(.semibold) + Text(" · …" + L("onboarding.demoLine1")).foregroundColor(dark.textSecondary))
                .font(.system(size: 14)).lineLimit(1)
            HStack(spacing: 8) {
                lockButton(L("rec.mark"), "flag", dark.textPrimary.opacity(0.14))
                lockButton(L("rec.pause"), "pause.fill", dark.textPrimary.opacity(0.14))
                lockButton(L("rec.stop"), "stop.fill", MurmurPalette.acidLight.recordingLabel)
            }
        }
        .foregroundStyle(dark.textPrimary)
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(dark.recordingPill, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
        .environment(\.colorScheme, .dark)
        .accessibilityHidden(true)
    }

    private func lockButton(_ title: String, _ icon: String, _ fill: Color) -> some View {
        Label(title, systemImage: icon).font(.system(size: 13, weight: .semibold)).lineLimit(1)
            .frame(maxWidth: .infinity, minHeight: 34).background(fill, in: Capsule())
    }

    private var syncArt: some View {
        VStack(spacing: 14) {
            HStack(spacing: 10) {
                device("iphone", L("onboarding.demoPhone"), L("onboarding.demoPhoneText"))
                VStack(spacing: 6) {
                    Image(systemName: "lock").font(.system(size: 12))
                    Rectangle().fill(palette.textTertiary).frame(width: 40, height: 2)
                        .mask(HStack(spacing: 4) { ForEach(0..<5, id: \.self) { _ in Rectangle().frame(width: 4) } })
                    Text(L("onboarding.demoEncrypted")).font(.system(size: 11))
                }
                .foregroundStyle(palette.textTertiary)
                device("laptopcomputer", L("onboarding.demoComputer"), L("onboarding.demoComputerText"))
            }
            Label(L("meeting.revised"), systemImage: "checkmark.seal").font(.system(size: 14)).foregroundStyle(palette.textSecondary)
                .padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .accessibilityHidden(true)
    }

    private func device(_ icon: String, _ name: String, _ text: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 30))
            Text(name).font(.system(size: 14, weight: .semibold))
            Text(text).font(.system(size: 12)).foregroundStyle(palette.textSecondary).multilineTextAlignment(.center)
        }
        .padding(.vertical, 16).padding(.horizontal, 8).frame(maxWidth: .infinity)
        .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    // MARK: How you record, the microphone, your voice, the computer.

    private var setupArt: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("onboarding.setupLanguage")).font(.system(size: 13)).foregroundStyle(palette.textSecondary).padding(.leading, 16)
            VStack(spacing: 0) {
                ForEach([("ca-es", L("language.caEs")), ("ca", L("language.ca")), ("es", L("language.es"))], id: \.0) { value, name in
                    Button { language = value } label: {
                        HStack {
                            Text(name).font(.system(size: 17))
                            Spacer()
                            if language == value { Image(systemName: "checkmark").font(.system(size: 15, weight: .semibold)) }
                        }
                        .padding(.horizontal, 16).frame(minHeight: 50).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(language == value ? .isSelected : [])
                    if value != "es" { Divider().padding(.leading, 16) }
                }
            }
            .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            Text(L("onboarding.setupRoom")).font(.system(size: 13)).foregroundStyle(palette.textSecondary).padding(.leading, 16).padding(.top, 10)
            Segmented(options: [(false, L("start.onlyMe")), (true, L("start.moreInRoom"))], selection: $moreInRoom)
        }
    }

    private var micArt: some View {
        Image(systemName: "mic").font(.system(size: 44, weight: .medium))
            .frame(width: 132, height: 132).background(palette.fillSubtle, in: Circle())
            .accessibilityHidden(true)
    }

    private var voiceArt: some View {
        let words = L("voice.sentence").split(separator: " ")
        return VStack(alignment: .leading, spacing: 22) {
            (Text(words.prefix(7).joined(separator: " ") + " ") + Text(words.dropFirst(7).joined(separator: " ")).foregroundColor(palette.textTertiary))
                .font(.system(size: 24, weight: .semibold)).lineSpacing(4)
            HStack(spacing: 4) {
                ForEach(Array([10, 24, 38, 30, 46, 22, 14, 34, 44, 28, 18].enumerated()), id: \.offset) { _, h in
                    Capsule().fill(palette.textPrimary).frame(width: 5, height: CGFloat(h))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityHidden(true)
    }

    private var computerArt: some View {
        ZStack {
            Viewfinder().stroke(palette.textPrimary, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                .frame(width: 180, height: 180)
            Image(systemName: "laptopcomputer").font(.system(size: 54, weight: .light))
        }
        .accessibilityHidden(true)
    }
}

/// The onboarding's page. It stays from screen to screen: the art and the words change (they come
/// in from the side they're going to and fade), the dots and the button stay where they are, and
/// the dot slides to the next one.
private struct OnboardingPage: View {
    struct Spec {
        var art: AnyView
        var kicker: String? = nil
        var title: String
        var text: String? = nil
        var note: (String, String)? = nil
        var dots: (Int, Int)? = nil
        var primary: (String, () -> Void)
        var top: (String, () -> Void)? = nil
    }

    @Environment(\.murmur) private var palette
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var spec: Spec
    var id: Int
    var forward: Bool

    private var change: AnyTransition {
        if reduceMotion { return .opacity }
        let shift: CGFloat = 36
        return .asymmetric(insertion: .offset(x: forward ? shift : -shift).combined(with: .opacity),
                           removal: .offset(x: forward ? -shift : shift).combined(with: .opacity))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                if let top = spec.top {
                    Button(top.0, action: top.1).font(.system(size: 16)).foregroundStyle(palette.textSecondary).frame(minHeight: 44)
                        .transition(.opacity)
                }
            }
            .frame(height: 44)
            ZStack {
                spec.art.id(id).transition(change)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            ZStack(alignment: .topLeading) {
                VStack(alignment: .leading, spacing: 0) {
                    if let kicker = spec.kicker { Text(kicker).font(.system(size: 13, weight: .semibold)).foregroundStyle(palette.textSecondary) }
                    Text(spec.title).font(.system(size: 30, weight: .bold)).tracking(-0.6).padding(.top, 6).padding(.bottom, 10)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                    if let text = spec.text {
                        Text(text).font(.system(size: 17)).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                            .padding(.bottom, 8)
                    }
                    if let note = spec.note {
                        Label(note.0, systemImage: note.1).font(.system(size: 13)).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .id(id).transition(change)
            }
            // The dots: the current one, long, slides to the next.
            HStack(spacing: 7) {
                if let dots = spec.dots {
                    ForEach(0..<dots.1, id: \.self) { i in
                        Capsule().fill(i == dots.0 ? palette.textPrimary : palette.separator).frame(width: i == dots.0 ? 20 : 7, height: 7)
                    }
                }
            }
            .frame(maxWidth: .infinity, minHeight: 7)
            .padding(.vertical, 18)
            .accessibilityHidden(true)
            Button(action: spec.primary.1) {
                Text(spec.primary.0).font(.system(size: 17, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(palette.newMeeting, in: Capsule())
                    .foregroundStyle(palette.onNewMeeting)
                    .contentTransition(.opacity)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 28).padding(.bottom, 16)
    }
}

/// Four corners, as a viewfinder (the QR to scan).
private struct Viewfinder: Shape {
    func path(in rect: CGRect) -> Path {
        let l = rect.width * 0.22
        var p = Path()
        for (corner, dx, dy) in [(CGPoint(x: rect.minX, y: rect.minY), 1.0, 1.0), (CGPoint(x: rect.maxX, y: rect.minY), -1.0, 1.0),
                                 (CGPoint(x: rect.minX, y: rect.maxY), 1.0, -1.0), (CGPoint(x: rect.maxX, y: rect.maxY), -1.0, -1.0)] {
            p.move(to: CGPoint(x: corner.x, y: corner.y + dy * l))
            p.addLine(to: corner)
            p.addLine(to: CGPoint(x: corner.x + dx * l, y: corner.y))
        }
        return p
    }
}
