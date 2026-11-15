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

/// The recording on the lock screen and in the Dynamic Island (docs/mockups/mobil.html, screen 7):
/// the time, the level, the last thing said, and Marca, Pausa, Atura. A dark card, like the
/// desktop's recording pill, in both modes.
struct RecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingAttributes.self) { context in
            LockScreenRecording(context: context).murmurTheme(.acid).environment(\.colorScheme, .dark)
                .activityBackgroundTint(MurmurPalette.acidDark.recordingPill)
                .activitySystemActionForegroundColor(MurmurPalette.acidDark.onRecordingPill)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    LogoTile(size: 34).murmurTheme(.acid).environment(\.colorScheme, .dark).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    RecordingTimer(state: context.state).font(.mono(17, weight: .medium)).padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 10) {
                        LastLine(state: context.state).lineLimit(2)
                        RecordingButtons(paused: context.state.pausedAt != nil, confirming: context.state.confirmingStop)
                    }
                }
            } compactLeading: {
                Circle().fill(MurmurPalette.acidDark.recordingDot).frame(width: 9, height: 9)
            } compactTrailing: {
                RecordingTimer(state: context.state).font(.mono(14, weight: .semibold)).frame(maxWidth: 52)
            } minimal: {
                Circle().fill(MurmurPalette.acidDark.recordingDot).frame(width: 9, height: 9)
            }
        }
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

struct LastLine: View {
    @Environment(\.murmur) private var palette
    var state: RecordingAttributes.ContentState
    var body: some View {
        if state.lastLine.isEmpty {
            Text(L("rec.listening")).font(.system(size: 15)).foregroundStyle(MurmurPalette.acidDark.textSecondary)
        } else {
            (Text(state.speaker.map { "\($0) · " } ?? "").fontWeight(.semibold).foregroundColor(MurmurPalette.acidDark.textPrimary)
                + Text("…" + state.lastLine).foregroundColor(MurmurPalette.acidDark.textSecondary))
                .font(.system(size: 15))
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
                Label(L("rec.mark"), systemImage: "flag").frame(maxWidth: .infinity, minHeight: 40)
            }
            .tint(dark.textPrimary.opacity(0.14))
            Button(intent: TogglePauseIntent()) {
                Label(paused ? L("rec.resume") : L("rec.pause"), systemImage: paused ? "record.circle" : "pause.fill").frame(maxWidth: .infinity, minHeight: 40)
            }
            .tint(dark.textPrimary.opacity(0.14))
            Button(intent: StopRecordingIntent()) {
                Label(confirming ? L("rec.stopConfirm") : L("rec.stop"), systemImage: "stop.fill")
                    .frame(maxWidth: .infinity, minHeight: 40)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
            .tint(confirming ? dark.recordingDot : dark.recordingDot.opacity(0.75))
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .font(.system(size: 15, weight: .semibold))
        .foregroundStyle(dark.onRecordingPill)
    }
}

struct LockScreenRecording: View {
    @Environment(\.murmur) private var palette
    var context: ActivityViewContext<RecordingAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                LogoTile(size: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(context.attributes.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(palette.textPrimary)
                    Text("\(context.state.pausedAt == nil ? L("rec.recording") : L("rec.pause")) · \(context.attributes.language)")
                        .font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                }
                Spacer()
            }
            HStack(alignment: .center) {
                RecordingTimer(state: context.state).font(.mono(40, weight: .medium)).foregroundStyle(palette.textPrimary)
                Spacer()
                // Still bars in the acid, as in the desktop's pill (a Live Activity can't follow the voice).
                HStack(spacing: 4) {
                    ForEach([12, 22, 16, 26, 10] as [CGFloat], id: \.self) { h in
                        Capsule().fill(palette.recordingLevel.opacity(context.state.pausedAt == nil ? 1 : 0.35)).frame(width: 5, height: h)
                    }
                }
            }
            LastLine(state: context.state).lineLimit(2)
            RecordingButtons(paused: context.state.pausedAt != nil, confirming: context.state.confirmingStop)
        }
        .padding(16)
    }
}

extension Font {
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced).monospacedDigit()
    }
}
