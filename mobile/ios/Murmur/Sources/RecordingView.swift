import SwiftUI

enum MeetingTab: Hashable { case transcript, notes }

/// Recording: the card with the waveform, the ruler and the timer; the transcript or the notes;
/// and, within reach of the thumb, Marca, Pausa and Atura.
struct RecordingView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    var done: (String?) -> Void
    @State private var tab: MeetingTab = .transcript
    @State private var note = ""
    @State private var stopping = false

    var body: some View {
        let live = store.live
        VStack(spacing: 0) {
            HStack {
                CircleButton(systemImage: "chevron.left", label: L("rec.back")) { dismiss() }
                Spacer()
                Text(live?.title ?? "").font(.system(size: 17, weight: .semibold)).lineLimit(1)
                Spacer()
                Color.clear.frame(width: 44, height: 44)
            }
            .padding(.horizontal, 16).frame(height: 56)

            RecordingCard(live: live)
                .padding(.horizontal, 16).padding(.top, 4)

            Segmented(options: [(MeetingTab.transcript, L("rec.transcript")), (.notes, notesLabel(live))], selection: $tab)
                .padding(.horizontal, 16).padding(.top, 14)

            ZStack(alignment: .bottom) {
                if tab == .transcript { transcript(live) } else { notes(live) }
                LinearGradient(colors: [palette.background.opacity(0), palette.background], startPoint: .top, endPoint: .bottom)
                    .frame(height: 60).allowsHitTesting(false)
            }
            controls(live)
        }
        .background(palette.background.ignoresSafeArea())
        .foregroundStyle(palette.textPrimary)
        .toolbar(.hidden, for: .navigationBar)
        // `-autostop <seconds>` (development): stops by itself, to try the end without touching the screen.
        .task {
            let args = ProcessInfo.processInfo.arguments
            guard let i = args.firstIndex(of: "-autostop"), i + 1 < args.count, let after = Double(args[i + 1]) else { return }
            try? await Task.sleep(for: .seconds(after))
            store.mark()
            stopping = true
            done(await store.stop())
        }
        .onChange(of: store.live == nil) { _, ended in
            if ended && !stopping { dismiss() }
        }
    }

    private func notesLabel(_ live: LiveMeeting?) -> String {
        let n = live?.notes.count ?? 0
        return n > 0 ? "\(L("rec.notes")) · \(n)" : L("rec.notes")
    }

    private func transcript(_ live: LiveMeeting?) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    if (live?.lines.isEmpty ?? true) && (live?.partial.isEmpty ?? true) {
                        Text(L("rec.listening")).font(.system(size: 17)).foregroundStyle(palette.textTertiary).padding(.top, 8)
                    }
                    ForEach(live?.lines ?? []) { line in
                        LineView(speaker: Int(line.speaker), start: line.start, text: line.text, marked: live?.isMarked(line) ?? false)
                    }
                    if let partial = live?.partial, !partial.isEmpty {
                        (Text(partial).foregroundColor(palette.textTertiary) + Text(" |").foregroundColor(palette.caret))
                            .font(.system(size: 17)).lineSpacing(4)
                    }
                    Color.clear.frame(height: 20).id("end")
                }
                .padding(.horizontal, 20).padding(.top, 8)
            }
            .onChange(of: live?.lines.count) { _, _ in withAnimation { proxy.scrollTo("end") } }
            .onChange(of: live?.partial) { _, _ in proxy.scrollTo("end") }
        }
    }

    private func notes(_ live: LiveMeeting?) -> some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(live?.notes ?? []) { NoteRow(note: $0) }
                }
                .padding(.horizontal, 12).padding(.top, 10)
            }
            HStack(spacing: 10) {
                TextField(L("notes.placeholder"), text: $note)
                    .submitLabel(.done)
                    .onSubmit { store.addNote(note); note = "" }
                    .font(.system(size: 17))
                    .padding(.horizontal, 16).frame(height: 48)
                    .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .padding(.horizontal, 16).padding(.top, 6)
        }
    }

    private func controls(_ live: LiveMeeting?) -> some View {
        HStack(spacing: 10) {
            Button { store.mark() } label: {
                Image(systemName: "flag").font(.system(size: 19, weight: .medium)).frame(width: 56, height: 56)
                    .background(palette.background, in: Circle())
                    .overlay(Circle().strokeBorder(palette.separator))
            }
            .accessibilityLabel(L("rec.mark"))
            Button { store.togglePause() } label: {
                Label(live?.paused == true ? L("rec.recording") : L("rec.pause"), systemImage: live?.paused == true ? "record.circle" : "pause.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(palette.background, in: Capsule())
                    .overlay(Capsule().strokeBorder(palette.separator))
            }
            Button {
                stopping = true
                Task { done(await store.stop()) }
            } label: {
                Label(L("rec.stop"), systemImage: "stop.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(palette.newMeeting, in: Capsule())
                    .foregroundStyle(palette.onNewMeeting)
            }
            .disabled(stopping)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16).padding(.bottom, 8).padding(.top, 6)
    }
}

/// The card: the waveform with a ruler of minutes and the red now line, the state and the timer.
struct RecordingCard: View {
    @Environment(\.murmur) private var palette
    @EnvironmentObject private var store: Store
    var live: LiveMeeting?

    var body: some View {
        VStack(spacing: 0) {
            Waveform(levels: live?.levels ?? [], seconds: live?.seconds ?? 0)
                .frame(height: 134)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        RecordingDot()
                        Text((live?.paused == true ? L("rec.pause") : L("rec.recording")).uppercased())
                            .font(.mono(11)).tracking(0.9).foregroundStyle(palette.textSecondary)
                    }
                    Text(longClock(live?.seconds ?? 0)).font(.mono(40, weight: .medium)).tracking(-0.8)
                }
                Spacer()
                Text(languageName(store.language)).font(.system(size: 13)).foregroundStyle(palette.textSecondary)
            }
            .padding(.horizontal, 18).padding(.vertical, 14)
            .background(palette.background, in: UnevenRoundedRectangle(topLeadingRadius: 26, topTrailingRadius: 26, style: .continuous))
        }
        .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
    }
}

/// What has been heard, a bar per half second up to the red line (now), dotted after it, over a
/// ruler with the time every 5 seconds: the ruler and the bars share one scale.
struct Waveform: View {
    @Environment(\.murmur) private var palette
    var levels: [Float]
    var seconds: Double

    /// Pieces of audio come every ~100 ms: five make a bar.
    private static let perBar = 5
    private static let barSeconds = 0.5
    private static let step: CGFloat = 4
    private static let rulerHeight: CGFloat = 30

    var body: some View {
        Canvas { ctx, size in
            let waveHeight = size.height - Self.rulerHeight
            let mid = waveHeight / 2 + 6
            let now = (size.width * 0.62).rounded()
            // The bars, newest at the red line.
            let bars = stride(from: 0, to: levels.count, by: Self.perBar).map { levels[$0..<min(levels.count, $0 + Self.perBar)].max() ?? 0 }
            for (k, level) in bars.reversed().enumerated() {
                let x = now - Self.step * CGFloat(k + 1)
                if x < 10 { break }
                let v = CGFloat(min(1, (Double(level) / 0.22).squareRoot()))
                let h = max(2, v * 56)
                ctx.fill(Path(roundedRect: CGRect(x: x, y: mid - h / 2, width: 2, height: h), cornerRadius: 1), with: .color(palette.textTertiary.opacity(0.8)))
            }
            for x in stride(from: now + Self.step, to: size.width - 10, by: Self.step) {
                ctx.fill(Path(CGRect(x: x, y: mid - 1, width: 2, height: 2)), with: .color(palette.separator))
            }
            // The ruler: a line, and the time every 5 s where it falls.
            ctx.fill(Path(CGRect(x: 0, y: waveHeight, width: size.width, height: 1)), with: .color(palette.separator))
            let pxPerSecond = Self.step / Self.barSeconds
            let first = (seconds - Double(now) / pxPerSecond) / 5
            for mark in stride(from: max(0, first.rounded(.up) * 5), through: seconds + Double(size.width - now) / pxPerSecond, by: 5) {
                let x = now + CGFloat(mark - seconds) * pxPerSecond
                guard x > 16, x < size.width - 16 else { continue }
                let current = abs(mark - seconds) < 2.5
                ctx.draw(Text(clock(mark)).font(.mono(11, weight: current ? .medium : .regular)).foregroundColor(current ? palette.textPrimary : palette.textTertiary),
                         at: CGPoint(x: x, y: waveHeight + Self.rulerHeight / 2))
            }
            ctx.fill(Path(roundedRect: CGRect(x: now, y: 12, width: 2, height: waveHeight - 12), cornerRadius: 1), with: .color(palette.recordingDot))
        }
    }
}

/// One line of the transcript: the speaker, the minute, the text; a marked one stands out.
struct LineView: View {
    @Environment(\.murmur) private var palette
    var speaker: Int
    var name: String? = nil
    var start: Double
    var text: String
    var marked: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                SpeakerChip(speaker: speaker, name: name)
                Text(clock(start)).font(.mono(12)).foregroundStyle(palette.timestamp)
                if marked { FlagChip(label: L("rec.important")) }
            }
            Text(text).font(.system(size: 17)).lineSpacing(4).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, marked ? 12 : 0).padding(.vertical, marked ? 9 : 0)
        .background(marked ? palette.fillSubtle : .clear, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, marked ? -12 : 0)
    }
}

struct NoteRow: View {
    @Environment(\.murmur) private var palette
    var note: NoteItem
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(clock(note.t)).font(.mono(12)).foregroundStyle(palette.timestamp).frame(width: 44, alignment: .trailing)
            if note.mark { FlagChip(label: note.text) } else { Text(note.text).font(.system(size: 17)) }
            Spacer(minLength: 0)
        }
    }
}
