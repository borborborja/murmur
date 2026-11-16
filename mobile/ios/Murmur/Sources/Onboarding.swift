import AVFoundation
import SwiftUI

/// The first time (mockup, screens B to F): what Murmur is, what it does in four pages with a
/// small demo each (as the desktop's onboarding), how you record and the microphone's permission,
/// and two things that can wait (your voice, the computer). Then the list.
struct OnboardingView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    var finish: () -> Void

    private enum Step: Hashable { case welcome, features, setup, microphone, voice, computer }
    @State private var step: Step = .welcome
    @State private var page = 0
    @State private var micDenied = false
    @State private var readingVoice = false
    @State private var pairing = false
    @AppStorage("language") private var language = "ca-es"
    @AppStorage("moreInRoom") private var moreInRoom = true

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            switch step {
            case .welcome:
                OnboardingPage(art: { LogoTile(size: 112) },
                               title: L("onboarding.welcomeTitle"), text: L("onboarding.welcomeTagline"),
                               note: (L("onboarding.welcomePrivate"), "lock"),
                               primary: (L("onboarding.start"), { go(.features) }))
            case .features:
                features
            case .setup:
                OnboardingPage(art: { setupArt }, kicker: L("onboarding.setupKicker"), title: L("onboarding.setupTitle"),
                               note: (L("onboarding.consent"), "person"),
                               primary: (L("onboarding.next"), { go(.microphone) }))
            case .microphone:
                OnboardingPage(art: { micArt }, kicker: L("onboarding.micKicker"), title: L("onboarding.micTitle"),
                               text: micDenied ? L("onboarding.micDenied") : L("onboarding.micText"),
                               primary: (micDenied ? L("onboarding.next") : L("onboarding.micAllow"), askMicrophone))
            case .voice:
                OnboardingPage(art: { voiceArt }, kicker: L("onboarding.voiceKicker"), title: L("onboarding.voiceTitle"),
                               note: (L("onboarding.voiceText"), "lock"),
                               primary: (L("onboarding.voiceGo"), { readingVoice = true }),
                               topAction: (L("onboarding.notNow"), { go(.computer) }))
            case .computer:
                OnboardingPage(art: { computerArt }, kicker: L("onboarding.computerKicker"), title: L("onboarding.computerTitle"),
                               text: L("onboarding.computerText"), note: (L("onboarding.computerDownload"), "laptopcomputer"),
                               primary: (L("onboarding.computerGo"), { pairing = true }),
                               topAction: (L("onboarding.notNow"), finish))
            }
        }
        .foregroundStyle(palette.textPrimary)
        .fullScreenCover(isPresented: $readingVoice) {
            VoiceEnrollView(saved: { go(.computer) })
        }
        .fullScreenCover(isPresented: $pairing, onDismiss: { if store.computer != nil { finish() } }) {
            PairView()
        }
    }

    private func go(_ next: Step) {
        withAnimation(.easeInOut(duration: 0.3)) { step = next }
    }

    private func askMicrophone() {
        if micDenied { return go(.voice) }
        Task {
            let allowed = Microphone.simulated != nil ? true : await AVAudioApplication.requestRecordPermission()
            if allowed { go(.voice) } else { withAnimation { micDenied = true } }
        }
    }

    // MARK: What it does: four pages, swiped or with Continua.

    private var features: some View {
        let pages: [(kicker: String, title: String, text: String)] = [
            (L("onboarding.f1Kicker"), L("onboarding.f1Title"), L("onboarding.f1Text")),
            (L("onboarding.f2Kicker"), L("onboarding.f2Title"), L("onboarding.f2Text")),
            (L("onboarding.f3Kicker"), L("onboarding.f3Title"), L("onboarding.f3Text")),
            (L("onboarding.f4Kicker"), L("onboarding.f4Title"), L("onboarding.f4Text")),
        ]
        return TabView(selection: $page) {
            ForEach(pages.indices, id: \.self) { i in
                OnboardingPage(art: { featureArt(i) }, kicker: pages[i].kicker, title: pages[i].title, text: pages[i].text,
                               dots: (i, pages.count),
                               primary: (L("onboarding.next"), { i < pages.count - 1 ? withAnimation { page = i + 1 } : go(.setup) }),
                               topAction: (L("onboarding.skip"), { go(.setup) }))
                    .tag(i)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
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

/// One page of the onboarding: its art in the middle, then the kicker, title, text and a note,
/// the dots, the button; "Omet" or "Ara no" at the top.
private struct OnboardingPage<Art: View>: View {
    @Environment(\.murmur) private var palette
    @ViewBuilder var art: () -> Art
    var kicker: String? = nil
    var title: String
    var text: String? = nil
    var note: (String, String)? = nil
    var dots: (Int, Int)? = nil
    var primary: (String, () -> Void)
    var topAction: (String, () -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                if let topAction {
                    Button(topAction.0, action: topAction.1).font(.system(size: 16)).foregroundStyle(palette.textSecondary)
                        .frame(minHeight: 44)
                }
            }
            .frame(height: 44)
            Spacer(minLength: 12)
            art().frame(maxWidth: .infinity)
            Spacer(minLength: 12)
            if let kicker { Text(kicker).font(.system(size: 13, weight: .semibold)).foregroundStyle(palette.textSecondary) }
            Text(title).font(.system(size: 30, weight: .bold)).tracking(-0.6).padding(.top, 6).padding(.bottom, 10)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let text {
                Text(text).font(.system(size: 17)).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 8)
            }
            if let note {
                Label(note.0, systemImage: note.1).font(.system(size: 13)).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Group {
                if let dots {
                    HStack(spacing: 7) {
                        ForEach(0..<dots.1, id: \.self) { i in
                            Capsule().fill(i == dots.0 ? palette.textPrimary : palette.separator).frame(width: i == dots.0 ? 20 : 7, height: 7)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityHidden(true)
                } else {
                    Color.clear.frame(height: 7)
                }
            }
            .padding(.vertical, 18)
            Button(action: primary.1) {
                Text(primary.0).font(.system(size: 17, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(palette.newMeeting, in: Capsule())
                    .foregroundStyle(palette.onNewMeeting)
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
