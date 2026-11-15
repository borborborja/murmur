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
                    RecordingTimer(state: context.state).font(.mono(17, weight: .medium)).padding(.trailing, 6)
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
                    .foregroundStyle(MurmurPalette.acidDark.recordingLevel).frame(maxWidth: 52)
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
    var body: some View {
        let dark = MurmurPalette.acidDark
        if state.lastLine.isEmpty {
            Text(L("rec.listening")).font(.system(size: 14)).foregroundStyle(dark.textSecondary)
        } else {
            (Text(state.speaker.map { "\($0) · " } ?? "").fontWeight(.semibold).foregroundColor(dark.textPrimary)
                + Text("…" + state.lastLine).foregroundColor(dark.textSecondary))
                .font(.system(size: 14))
        }
    }
}

struct RecordingButtons: View {
    var paused: Bool
    /// Atura was tapped once: the second tap stops.
    var confirming: Bool
    var body: some View {
        let dark = MurmurPalette.acidDark
        HStack(spacing: 8) {
            Button(intent: MarkMomentIntent()) {
                Label(L("rec.mark"), systemImage: "flag").frame(maxWidth: .infinity, minHeight: 34)
            }
            .tint(dark.textPrimary.opacity(0.14))
            Button(intent: TogglePauseIntent()) {
                Label(paused ? L("rec.resume") : L("rec.pause"), systemImage: paused ? "record.circle" : "pause.fill").frame(maxWidth: .infinity, minHeight: 34)
            }
            .tint(dark.textPrimary.opacity(0.14))
            Button(intent: StopRecordingIntent()) {
                Label(confirming ? L("rec.stopConfirm") : L("rec.stop"), systemImage: "stop.fill")
                    .frame(maxWidth: .infinity, minHeight: 34)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
            // The dark red with white (5.6:1), as in the app.
            .tint(MurmurPalette.acidLight.recordingLabel)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .font(.system(size: 14, weight: .semibold))
        .labelStyle(.titleAndIcon)
        .foregroundStyle(dark.onRecordingPill)
    }
}

struct LockScreenRecording: View {
    @Environment(\.murmur) private var palette
    var context: ActivityViewContext<RecordingAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .center, spacing: 10) {
                EchoMark(state: context.state, size: 30)
                    .frame(width: 34, height: 34)
                    .background(palette.logoBackground, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                VStack(alignment: .leading, spacing: 0) {
                    Text(context.attributes.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(palette.textPrimary).lineLimit(1)
                    Text("\(context.state.pausedAt == nil ? L("rec.recording") : L("rec.pause")) · \(context.attributes.language)")
                        .font(.system(size: 12)).foregroundStyle(palette.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 6)
                VoiceBars(state: context.state)
                RecordingTimer(state: context.state).font(.mono(26, weight: .medium)).foregroundStyle(palette.textPrimary)
                    .frame(minWidth: 70, alignment: .trailing)
            }
            LastLine(state: context.state).lineLimit(1)
            RecordingButtons(paused: context.state.pausedAt != nil, confirming: context.state.confirmingStop)
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
    }
}

extension Font {
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced).monospacedDigit()
    }
}
