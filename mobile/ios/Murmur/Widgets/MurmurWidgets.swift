import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct MurmurWidgets: WidgetBundle {
    var body: some Widget {
        RecordingLiveActivity()
    }
}

/// The recording on the lock screen and in the Dynamic Island (docs/mockups/mobil.html, screen 7).
/// A dark card, like the desktop's recording pill, in both modes. iOS gives a Live Activity about
/// 160 pt of height: the time and the voice share one row, the last line one line.
struct RecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingAttributes.self) { context in
            LockScreenRecording(context: context).murmurTheme(.acid).environment(\.colorScheme, .dark)
                .activityBackgroundTint(MurmurPalette.acidDark.recordingPill)
                .activitySystemActionForegroundColor(MurmurPalette.acidDark.onRecordingPill)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    EchoMark(state: context.state, size: 34).padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    RecordingTimer(state: context.state).font(.mono(17, weight: .medium))
                        .multilineTextAlignment(.trailing).frame(width: 84, alignment: .trailing).padding(.trailing, 6)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        LastLine(state: context.state).lineLimit(1)
                        RecordingButtons(paused: context.state.pausedAt != nil, confirming: context.state.confirmingStop)
                    }
                }
            } compactLeading: {
                // Murmur in the island: its mark, whose echo moves with the voice.
                EchoMark(state: context.state, size: 24)
            } compactTrailing: {
                RecordingTimer(state: context.state).font(.mono(14, weight: .semibold))
                    .foregroundStyle(MurmurPalette.acidDark.recordingLevel)
                    .multilineTextAlignment(.trailing).frame(width: 52, alignment: .trailing)
            } minimal: {
                EchoMark(state: context.state, size: 22)
            }
            .keylineTint(MurmurPalette.acidDark.recordingLevel)
        }
    }
}

/// The Eco mark with its echo following the voice: close to the "m" in silence, out where the
/// logo has it when someone speaks. Its move is animated between updates.
struct EchoMark: View {
    var state: RecordingAttributes.ContentState
    var size: CGFloat
    var body: some View {
        let echo = state.pausedAt != nil ? 0.3 : 0.45 + 0.75 * min(1, state.voice)
        LogoMark(size: size, echo: echo)
            .murmurTheme(.acid).environment(\.colorScheme, .dark)
            .animation(.spring(duration: 0.7, bounce: 0.35), value: echo)
    }
}

/// Counts by itself (the system draws it), stopped while paused.
struct RecordingTimer: View {
    var state: RecordingAttributes.ContentState
    var body: some View {
        if let paused = state.pausedAt {
            Text(clock(paused)).monospacedDigit()
        } else {
            Text(timerInterval: state.startedAt...Date.distantFuture, countsDown: false).monospacedDigit()
        }
    }
}

/// The voice of the last seconds in acid bars, eased between updates.
struct VoiceBars: View {
    var state: RecordingAttributes.ContentState
    var body: some View {
        let levels = state.levels.count >= 5 ? Array(state.levels.suffix(5)) : Array(repeating: 0.3, count: 5)
        HStack(spacing: 4) {
            ForEach(Array(levels.enumerated()), id: \.offset) { _, level in
                Capsule()
                    .fill(MurmurPalette.acidDark.recordingLevel.opacity(state.pausedAt == nil ? 1 : 0.35))
                    .frame(width: 5, height: 6 + 22 * min(1, level))
            }
        }
        .frame(height: 28)
        .animation(.spring(duration: 0.6, bounce: 0.3), value: levels)
    }
}

struct LastLine: View {
    var state: RecordingAttributes.ContentState
    var size: CGFloat = 14
    var body: some View {
        let dark = MurmurPalette.acidDark
        if state.lastLine.isEmpty {
            Text(L("rec.listening")).font(.system(size: size)).foregroundStyle(dark.textSecondary)
        } else {
            (Text(state.speaker.map { "\($0) · " } ?? "").fontWeight(.semibold).foregroundColor(dark.textPrimary)
                + Text("…" + state.lastLine).foregroundColor(dark.textSecondary))
                .font(.system(size: size))
        }
    }
}

struct RecordingButtons: View {
    var paused: Bool
    /// Atura was tapped once: the second tap stops.
    var confirming: Bool
    var height: CGFloat = 36
    var fontSize: CGFloat = 14
    var body: some View {
        let dark = MurmurPalette.acidDark
        HStack(spacing: 8) {
            Button(intent: MarkMomentIntent()) {
                Label(L("rec.mark"), systemImage: "flag").frame(maxWidth: .infinity, minHeight: height)
            }
            .tint(dark.textPrimary.opacity(0.14))
            Button(intent: TogglePauseIntent()) {
                Label(paused ? L("rec.resume") : L("rec.pause"), systemImage: paused ? "record.circle" : "pause.fill").frame(maxWidth: .infinity, minHeight: height)
            }
            .tint(dark.textPrimary.opacity(0.14))
            Button(intent: StopRecordingIntent()) {
                Label(confirming ? L("rec.stopConfirm") : L("rec.stop"), systemImage: "stop.fill")
                    .frame(maxWidth: .infinity, minHeight: height)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
            // The dark red with white (5.6:1), as in the app.
            .tint(MurmurPalette.acidLight.recordingLabel)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .font(.system(size: fontSize, weight: .semibold))
        .labelStyle(.titleAndIcon)
        .foregroundStyle(dark.onRecordingPill)
    }
}

struct LockScreenRecording: View {
    @Environment(\.murmur) private var palette
    var context: ActivityViewContext<RecordingAttributes>

    // Three rows that fill the ~160 pt iOS allows (16 + 44 + 10 + 20 + 10 + 42 + 16 = 158):
    // the mark, the title and the time; the last line; the buttons.
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 12) {
                EchoMark(state: context.state, size: 36)
                    .frame(width: 44, height: 44)
                    .background(palette.logoBackground, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous).strokeBorder(palette.textPrimary.opacity(0.15)))
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.attributes.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(palette.textPrimary).lineLimit(1)
                    HStack(spacing: 6) {
                        VoiceBars(state: context.state).scaleEffect(0.6, anchor: .leading).frame(width: 26, height: 17, alignment: .leading)
                        Text("\(context.state.pausedAt == nil ? L("rec.recording") : L("rec.pause")) · \(context.attributes.language)")
                            .font(.system(size: 13)).foregroundStyle(palette.textSecondary).lineLimit(1)
                    }
                }
                .layoutPriority(0)
                Spacer(minLength: 6)
                // A system timer measures itself as wide as the longest time it could show: it gets
                // a fixed width (up to "9:59:59") instead of fixedSize, which broke the whole card.
                RecordingTimer(state: context.state).font(.mono(30, weight: .medium)).foregroundStyle(palette.textPrimary)
                    .lineLimit(1).multilineTextAlignment(.trailing)
                    .frame(width: 140, alignment: .trailing)
            }
            LastLine(state: context.state, size: 15).lineLimit(1)
            RecordingButtons(paused: context.state.pausedAt != nil, confirming: context.state.confirmingStop, height: 42, fontSize: 15)
        }
        .padding(16)
    }
}

extension Font {
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced).monospacedDigit()
    }
}
