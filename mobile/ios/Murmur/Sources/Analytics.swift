import Aptabase
import Foundation

/// Anonymous usage statistics (Aptabase, as the desktop's app/src/lib/analytics.ts, with the same
/// app: Aptabase tells the systems apart): which features are used and how long meetings are, to
/// know what to improve. Never what's in a meeting (titles, text, names, notes, photos, audio),
/// never an identifier: Aptabase adds the app version, the system and its language. Off with
/// Configuració › Privadesa (and the onboarding): then nothing is sent at all.
enum Analytics {
    private static let key = "A-EU-9961693988"
    /// The switch (on unless turned off).
    static var allowed: Bool { UserDefaults.standard.object(forKey: "analytics") as? Bool ?? true }
    private static var started = false

    /// At launch, and when it's turned on.
    static func start() {
        guard allowed, !started else { return }
        started = true
        Aptabase.shared.initialize(appKey: key)
    }

    static func track(_ name: String, _ props: [String: Any] = [:]) {
        guard allowed else { return }
        start()
        // Aptabase takes strings and numbers: true/false go as "yes"/"no".
        var values: [String: Any] = [:]
        for (k, v) in props {
            switch v {
            case let b as Bool: values[k] = b ? "yes" : "no"
            case let s as String: values[k] = s
            case let n as Int: values[k] = n
            case let d as Double: values[k] = d
            default: continue
            }
        }
        if values.isEmpty {
            Aptabase.shared.trackEvent(name)
        } else {
            Aptabase.shared.trackEvent(name, with: values)
        }
    }

    /// A length as a range, never the exact value: "<5", "5-15", "15-30", "30-60", "60-120", ">120" (minutes).
    static func minutesBucket(_ seconds: Double) -> String {
        let m = seconds / 60
        if m < 5 { return "<5" }
        if m < 15 { return "5-15" }
        if m < 30 { return "15-30" }
        if m < 60 { return "30-60" }
        if m < 120 { return "60-120" }
        return ">120"
    }

    /// A count as a range: "0", "1-5", "6-20", "21-50", ">50".
    static func countBucket(_ n: Int) -> String {
        if n <= 0 { return "0" }
        if n <= 5 { return "1-5" }
        if n <= 20 { return "6-20" }
        if n <= 50 { return "21-50" }
        return ">50"
    }
}
