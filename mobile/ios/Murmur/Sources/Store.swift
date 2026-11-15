import AVFoundation
import SwiftUI

/// The phone's texts: app/src/locales/<lang>/mobile.json, made into Localizable.xcstrings by
/// tools/mobile-strings.mjs ({{x}} becomes %@).
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = Bundle.main.localizedString(forKey: key, value: nil, table: nil)
    return args.isEmpty ? format : String(format: format, arguments: args)
}

extension Line: Identifiable {}
extension MeetingRow: Identifiable {}

/// A note taken while recording, at its second; `mark` is "Marca" (an important moment).
struct NoteItem: Identifiable {
    let id = UUID()
    var t: Double
    var text: String
    var mark = false
}

/// The recording in progress, as the screen shows it.
struct LiveMeeting {
    var id: String
    var title: String
    var seconds: Double = 0
    var lines: [Line] = []
    var partial = ""
    /// The level of each piece of audio (~100 ms), for the waveform.
    var levels: [Float] = []
    var notes: [NoteItem] = []
    var paused = false

    /// The last thing said, for the list.
    var lastLine: String { partial.isEmpty ? (lines.last?.text ?? "") : partial }
    func isMarked(_ line: Line) -> Bool {
        notes.contains { $0.mark && $0.t >= line.start - 1 && $0.t <= line.end + 2 }
    }
}

/// A lock-protected switch the microphone's thread reads (paused or not).
final class Gate: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isOn: Bool {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set { lock.lock(); value = newValue; lock.unlock() }
    }
}

/// The app's state: the meetings on this phone and the recording, over murmur-mobile (Rust).
@MainActor
final class Store: ObservableObject {
    @Published var meetings: [MeetingRow] = []
    @Published var live: LiveMeeting?
    @Published var error: String?

    let engine: Murmur?
    private let queue = DispatchQueue(label: "murmur.engine", qos: .userInitiated)
    private let microphone = Microphone()
    private let paused = Gate()

    init() {
        let models = (Bundle.main.resourcePath ?? "") + "/models"
        let data = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Murmur").path
        do {
            engine = try Murmur(modelsDir: models, dataDir: data)
        } catch {
            engine = nil
            self.error = error.localizedDescription
        }
        reload()
        // The models load in the background, so the first recording starts at once.
        let engine = engine
        queue.async { try? engine?.prepare() }
    }

    func reload() {
        meetings = (try? engine?.meetings()) ?? []
    }

    var language: String { UserDefaults.standard.string(forKey: "language") ?? "ca-es" }
    var partialEvery: Double { UserDefaults.standard.object(forKey: "partialEvery") as? Double ?? 1.5 }

    /// Starts recording. False if it couldn't (no microphone).
    func start() async -> Bool {
        guard let engine, live == nil else { return live != nil }
        let allowed = Microphone.simulated != nil ? true : await AVAudioApplication.requestRecordPermission()
        guard allowed else {
            error = L("mic.denied")
            return false
        }
        let time = Date().formatted(date: .omitted, time: .shortened)
        let title = L("rec.defaultTitle", time)
        let language = language, every = Float(partialEvery)
        do {
            let id = try await onQueue { try engine.start(title: title, language: language, partialEvery: every) }
            live = LiveMeeting(id: id, title: title)
            paused.isOn = false
            let gate = paused
            try microphone.start { [weak self] samples in
                guard !gate.isOn else { return }
                self?.queue.async {
                    guard let update = try? engine.push(samples: samples) else { return }
                    Task { @MainActor in self?.apply(update) }
                }
            }
            UIApplication.shared.isIdleTimerDisabled = true
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    func togglePause() {
        guard live != nil else { return }
        live?.paused.toggle()
        paused.isOn = live?.paused ?? false
    }

    func mark() {
        guard let t = live?.seconds else { return }
        live?.notes.append(NoteItem(t: t, text: L("rec.markNote"), mark: true))
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    func addNote(_ text: String) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let t = live?.seconds else { return }
        live?.notes.append(NoteItem(t: t, text: text))
    }

    /// Stops and saves the meeting; returns its id.
    func stop() async -> String? {
        guard let engine, let current = live else { return nil }
        microphone.stop()
        UIApplication.shared.isIdleTimerDisabled = false
        let extra = Self.extra(notes: current.notes)
        do {
            let id = try await onQueue { try engine.stop(extra: extra) }
            live = nil
            reload()
            return id
        } catch {
            self.error = error.localizedDescription
            live = nil
            return nil
        }
    }

    func delete(_ id: String) {
        try? engine?.deleteMeeting(id: id)
        reload()
    }

    func meeting(_ id: String) -> MeetingDetail? {
        guard let json = try? engine?.meetingJson(id: id), let data = json.data(using: .utf8) else { return nil }
        return MeetingDetail(json: data)
    }

    private func apply(_ u: LiveUpdate) {
        guard live != nil else { return }
        live?.lines.append(contentsOf: u.finals)
        if !u.finals.isEmpty { live?.partial = "" }
        if let p = u.partial { live?.partial = p }
        live?.seconds = u.seconds
        live?.levels.append(u.level)
        if (live?.levels.count ?? 0) > 1200 { live?.levels.removeFirst(200) }
    }

    private func onQueue<T>(_ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { c in queue.async { c.resume(with: Result { try work() }) } }
    }

    /// The notes as the computer stores them (a TipTap document, each paragraph with its second),
    /// and the marked moments.
    private static func extra(notes: [NoteItem]) -> String {
        let paragraphs: [[String: Any]] = notes.sorted { $0.t < $1.t }.map {
            ["type": "paragraph", "attrs": ["t": $0.t], "content": [["type": "text", "text": $0.text]]]
        }
        var extra: [String: Any] = ["marks": notes.filter(\.mark).map { ["t": $0.t] }]
        if !paragraphs.isEmpty { extra["notesDoc"] = ["type": "doc", "content": paragraphs] }
        let data = (try? JSONSerialization.data(withJSONObject: extra)) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }
}

/// A finished meeting, read from the computer's JSON format.
struct MeetingDetail {
    struct Utterance: Identifiable { let id: String; let start: Double; let end: Double; let speaker: Int; let text: String }
    var title = ""
    var createdAt = Date()
    var duration: Double = 0
    var utterances: [Utterance] = []
    var notes: [NoteItem] = []
    var speakerNames: [Int: String] = [:]
    var revised = false

    init?(json: Data) {
        guard let m = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else { return nil }
        title = m["title"] as? String ?? ""
        createdAt = Date(timeIntervalSince1970: (m["createdAt"] as? Double ?? 0) / 1000)
        duration = m["duration"] as? Double ?? 0
        revised = !(m["cover"] is NSNull) && m["cover"] != nil
        for (k, v) in m["speakerNames"] as? [String: Any] ?? [:] {
            if let i = Int(k), let name = v as? String, !name.isEmpty { speakerNames[i] = name }
        }
        utterances = (m["utterances"] as? [[String: Any]] ?? []).map {
            Utterance(id: $0["id"] as? String ?? UUID().uuidString, start: $0["start"] as? Double ?? 0, end: $0["end"] as? Double ?? 0, speaker: $0["speaker"] as? Int ?? 0, text: $0["text"] as? String ?? "")
        }
        let marks = Set((m["marks"] as? [[String: Any]] ?? []).compactMap { $0["t"] as? Double })
        let doc = (m["notesDoc"] as? [String: Any])?["content"] as? [[String: Any]] ?? []
        notes = doc.map { block in
            let t = (block["attrs"] as? [String: Any])?["t"] as? Double ?? 0
            let text = (block["content"] as? [[String: Any]] ?? []).compactMap { $0["text"] as? String }.joined()
            return NoteItem(t: t, text: text, mark: marks.contains(t))
        }
    }

    func isMarked(_ u: Utterance) -> Bool { notes.contains { $0.mark && $0.t >= u.start - 1 && $0.t <= u.end + 2 } }
}

func clock(_ seconds: Double) -> String {
    let s = Int(seconds)
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
}

/// "00:12:34", as the recording card shows it.
func longClock(_ seconds: Double) -> String {
    let s = Int(seconds)
    return String(format: "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}
