import ActivityKit
import AVFoundation
import SwiftUI

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
    var notes: [NoteItem] = []
    var paused = false
    /// Names given while recording, by speaker number.
    var speakerNames: [Int: String] = [:]
    /// Speakers merged into another (from → into): also the lines still to come.
    var aliases: [Int: Int] = [:]
    /// Lines given to another speaker by hand, by line id.
    var lineSpeakers: [String: Int] = [:]

    /// The last thing said, for the list.
    var lastLine: String { partial.isEmpty ? (lines.last?.text ?? "") : partial }
    func isMarked(_ line: Line) -> Bool {
        notes.contains { $0.mark && $0.t >= line.start - 1 && $0.t <= line.end + 2 }
    }

    /// Who said a line, after what was changed by hand.
    func speaker(of line: Line) -> Int {
        if let s = lineSpeakers[line.id] { return s }
        var s = Int(line.speaker)
        var seen: Set<Int> = []
        while let next = aliases[s], !seen.contains(next) { seen.insert(s); s = next }
        return s
    }

    /// The speakers there are, by number, with their names.
    var speakers: [Speaker] {
        Set(lines.map(speaker(of:))).sorted().map { Speaker(id: $0, name: speakerNames[$0]) }
    }
}

/// A speaker in a meeting: their number, and the name given, if any.
struct Speaker: Identifiable, Hashable {
    let id: Int
    var name: String?
    var label: String { name ?? L("rec.speaker", "\(id + 1)") }
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

/// The recording's own clock and sound level, kept on the microphone's side. The waveform, the
/// timer and the level bars follow them every frame, without waiting for the engine, which can be
/// busy for half a second redoing the provisional text.
final class Meter: @unchecked Sendable {
    struct Sample { let t: Double; let level: Float }
    private let lock = NSLock()
    private var samples: [Sample] = []
    /// Seconds recorded before the current run (pauses stop the clock).
    private var recorded = 0.0
    private var runStart: CFTimeInterval?
    private var last: Float = 0

    func reset() {
        lock.lock(); defer { lock.unlock() }
        samples = []; recorded = 0; last = 0
        runStart = CACurrentMediaTime()
    }

    func pause() {
        lock.lock(); defer { lock.unlock() }
        if let start = runStart { recorded += CACurrentMediaTime() - start; runStart = nil }
        last = 0
    }

    func resume() {
        lock.lock(); defer { lock.unlock() }
        if runStart == nil { runStart = CACurrentMediaTime() }
    }

    private var now: Double { recorded + (runStart.map { CACurrentMediaTime() - $0 } ?? 0) }

    /// Seconds recorded, by the wall clock, without the pauses.
    var seconds: Double { lock.lock(); defer { lock.unlock() }; return now }
    var level: Float { lock.lock(); defer { lock.unlock() }; return last }

    /// A piece of audio from the microphone (its rms).
    func add(_ audio: [Float]) {
        guard !audio.isEmpty else { return }
        let rms = (audio.reduce(0) { $0 + $1 * $1 } / Float(audio.count)).squareRoot()
        lock.lock(); defer { lock.unlock() }
        samples.append(Sample(t: now, level: rms))
        last = rms
        if samples.count > 1500 { samples.removeFirst(300) }
    }

    func snapshot() -> (seconds: Double, samples: [Sample]) {
        lock.lock(); defer { lock.unlock() }
        return (now, samples)
    }
}

/// The app's state: the meetings on this phone and the recording, over murmur-mobile (Rust).
@MainActor
final class Store: ObservableObject {
    @Published var meetings: [MeetingRow] = []
    @Published var live: LiveMeeting?
    @Published var error: String?
    /// The ＋ was just pressed: the acid light fills up while the microphone opens.
    @Published var surging = false
    /// The microphone in use, as iOS names it, shown on the recording card.
    @Published var inputName: String?
    /// The lock screen's Atura was tapped once: a second tap within 4 s stops.
    private var stopArmed = false
    private var routeObserver: NSObjectProtocol?

    let engine: Murmur?
    private let queue = DispatchQueue(label: "murmur.engine", qos: .userInitiated)
    private let microphone = Microphone()
    private let paused = Gate()
    let meter = Meter()

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
        // The Live Activity's buttons (lock screen, Dynamic Island) land here.
        RecordingControls.handler = { [weak self] command in
            guard let self else { return }
            switch command {
            case .mark: self.mark()
            case .togglePause: self.togglePause()
            case .stop: self.armOrStop()
            }
        }
    }

    /// The lock screen's Atura asks twice, as the desktop's indicator: the first tap turns it into
    /// "Toca per aturar" for 4 s.
    private func armOrStop() {
        if stopArmed {
            stopArmed = false
            Task { _ = await stop() }
            return
        }
        stopArmed = true
        updateActivity()
        Task {
            try? await Task.sleep(for: .seconds(4))
            guard stopArmed else { return }
            stopArmed = false
            updateActivity()
        }
    }

    // MARK: The Live Activity

    private var activity: Activity<RecordingAttributes>?

    private var activityState: RecordingAttributes.ContentState {
        let seconds = meter.seconds
        let last = live?.lines.last
        return RecordingAttributes.ContentState(
            startedAt: Date().addingTimeInterval(-seconds),
            pausedAt: live?.paused == true ? seconds : nil,
            lastLine: String((last?.text ?? "").suffix(140)),
            speaker: last.flatMap { line in live.map { Speaker(id: $0.speaker(of: line), name: $0.speakerNames[$0.speaker(of: line)]).label } },
            confirmingStop: stopArmed,
            levels: recentLevels())
    }

    /// The voice of the last 2.5 s in five half seconds (0…1), for the bars and the mark's echo.
    private func recentLevels() -> [Double] {
        let (now, samples) = meter.snapshot()
        return (0..<5).reversed().map { k in
            let from = now - Double(k + 1) * 0.5, to = now - Double(k) * 0.5
            let level = samples.filter { $0.t >= from && $0.t < to }.map(\.level).max() ?? 0
            return min(1, (Double(level) / 0.22).squareRoot())
        }
    }

    /// Updates the Live Activity about once a second, for the voice; the time it counts itself.
    private var activityTimer: Timer?

    private func startActivity(title: String) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = RecordingAttributes(title: title, language: languageName(language))
        activity = try? Activity.request(attributes: attributes, content: .init(state: activityState, staleDate: nil))
        activityTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.live?.paused == false else { return }
                self.updateActivity()
            }
        }
    }

    private func updateActivity() {
        guard let activity else { return }
        let state = activityState
        Task { await activity.update(.init(state: state, staleDate: nil)) }
    }

    private func endActivity() {
        activityTimer?.invalidate()
        activityTimer = nil
        guard let activity else { return }
        self.activity = nil
        let state = activityState
        Task { await activity.end(.init(state: state, staleDate: nil), dismissalPolicy: .immediate) }
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
        let room = UserDefaults.standard.object(forKey: "moreInRoom") as? Bool ?? true
        do {
            let id = try await onQueue { try engine.start(title: title, language: language, moreInRoom: room, partialEvery: every) }
            live = LiveMeeting(id: id, title: title)
            paused.isOn = false
            meter.reset()
            let gate = paused, meter = meter
            try microphone.start { [weak self] samples in
                guard !gate.isOn else { return }
                meter.add(samples)
                self?.queue.async {
                    guard let update = try? engine.push(samples: samples) else { return }
                    Task { @MainActor in self?.apply(update) }
                }
            }
            UIApplication.shared.isIdleTimerDisabled = true
            inputName = Microphone.currentName
            routeObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.inputName = Microphone.currentName }
            }
            startActivity(title: title)
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
        if paused.isOn { meter.pause() } else { meter.resume() }
        updateActivity()
    }

    // MARK: Speakers (the label's menu, as on the desktop)

    /// While recording: they're kept here and go with the meeting when it stops.
    func nameLiveSpeaker(_ speaker: Int, _ name: String) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        live?.speakerNames[speaker] = name.isEmpty ? nil : name
        updateActivity()
    }

    func reassignLiveLine(_ line: String, to speaker: Int) {
        live?.lineSpeakers[line] = speaker
        updateActivity()
    }

    func mergeLiveSpeakers(_ from: Int, into: Int) {
        guard from != into else { return }
        live?.aliases[from] = into
        // Lines given by hand to the one that goes follow it.
        for (line, s) in live?.lineSpeakers ?? [:] where s == from { live?.lineSpeakers[line] = into }
        if live?.speakerNames[into] == nil { live?.speakerNames[into] = live?.speakerNames[from] }
        live?.speakerNames[from] = nil
        updateActivity()
    }

    /// A finished meeting: saved at once.
    func nameSpeaker(_ speaker: Int, _ name: String, in meeting: String) {
        try? engine?.setSpeakerName(id: meeting, speaker: Int64(speaker), name: name)
        reload()
    }

    func reassignLine(_ line: String, to speaker: Int, in meeting: String) {
        try? engine?.setLineSpeaker(id: meeting, line: line, speaker: Int64(speaker))
        reload()
    }

    func mergeSpeakers(_ from: Int, into: Int, in meeting: String) {
        guard from != into else { return }
        try? engine?.mergeSpeakers(id: meeting, from: Int64(from), into: Int64(into))
        reload()
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
        if let observer = routeObserver { NotificationCenter.default.removeObserver(observer) }
        routeObserver = nil
        stopArmed = false
        endActivity()
        let extra = Self.extra(current)
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

    func rename(_ id: String, to title: String) {
        try? engine?.renameMeeting(id: id, title: title)
        reload()
    }

    func meeting(_ id: String) -> MeetingDetail? {
        guard let json = try? engine?.meetingJson(id: id), let data = json.data(using: .utf8) else { return nil }
        return MeetingDetail(json: data)
    }

    private func apply(_ u: LiveUpdate) {
        guard live != nil else { return }
        live?.lines.append(contentsOf: u.finals)
        if !u.finals.isEmpty {
            live?.partial = ""
            // The lock screen gets each finished sentence (not the provisional text: too many updates).
            updateActivity()
        }
        if let p = u.partial { live?.partial = p }
        live?.seconds = u.seconds
    }

    private func onQueue<T>(_ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { c in queue.async { c.resume(with: Result { try work() }) } }
    }

    /// The notes as the computer stores them (a TipTap document, each paragraph with its second),
    /// the marked moments, and what was changed about the speakers while recording (the engine
    /// applies the aliases and the lines' speakers, also to the last lines).
    private static func extra(_ live: LiveMeeting) -> String {
        let paragraphs: [[String: Any]] = live.notes.sorted { $0.t < $1.t }.map {
            ["type": "paragraph", "attrs": ["t": $0.t], "content": [["type": "text", "text": $0.text]]]
        }
        var extra: [String: Any] = [
            "marks": live.notes.filter(\.mark).map { ["t": $0.t] },
            "speakerNames": Dictionary(uniqueKeysWithValues: live.speakerNames.map { ("\($0.key)", $0.value) }),
            "speakerAliases": Dictionary(uniqueKeysWithValues: live.aliases.map { ("\($0.key)", $0.value) }),
            "lineSpeakers": live.lineSpeakers,
        ]
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

    var speakers: [Speaker] {
        Set(utterances.map(\.speaker)).sorted().map { Speaker(id: $0, name: speakerNames[$0]) }
    }
}
