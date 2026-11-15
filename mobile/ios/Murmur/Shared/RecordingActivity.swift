import ActivityKit
import AppIntents
import Foundation

/// The recording as a Live Activity (the lock screen and the Dynamic Island). Compiled into the app
/// and into the widget extension, which draws it.
struct RecordingAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// When the recording would have started had it never been paused: the timer counts from it.
        var startedAt: Date
        /// Seconds recorded when paused; nil while recording.
        var pausedAt: Double?
        /// The last thing said, and by whom ("Parlant 2", or the name given).
        var lastLine: String
        var speaker: String?
        /// Atura was tapped once: the button says "Toca per aturar" until a second tap (or 4 s).
        var confirmingStop = false

        var seconds: Double { pausedAt ?? Date().timeIntervalSince(startedAt) }
    }

    var title: String
    var language: String
}

/// What the Live Activity's buttons ask the app to do. Their intents run in the app's process, so
/// they reach the recording through `handler`, which the app sets.
enum RecordingCommand: Sendable { case mark, togglePause, stop }

@MainActor
enum RecordingControls {
    static var handler: ((RecordingCommand) -> Void)?
}

struct MarkMomentIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "rec.mark"
    init() {}
    func perform() async throws -> some IntentResult {
        await MainActor.run { RecordingControls.handler?(.mark) }
        return .result()
    }
}

struct TogglePauseIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "rec.pause"
    init() {}
    func perform() async throws -> some IntentResult {
        await MainActor.run { RecordingControls.handler?(.togglePause) }
        return .result()
    }
}

struct StopRecordingIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "rec.stop"
    init() {}
    func perform() async throws -> some IntentResult {
        await MainActor.run { RecordingControls.handler?(.stop) }
        return .result()
    }
}
