import Foundation
import UIKit

/// The afternoon test (docs/MOBIL.md): loads the engine, runs the test meeting through it, or
/// listens to the microphone while it logs speed, battery and temperature every 30 s.
@MainActor
final class Probe: ObservableObject {
    enum State: Equatable { case idle, loading, benchmarking, live }

    @Published var state: State = .idle
    @Published var threads: Int32 = 2
    /// The provisional text while someone speaks (about ten times the cost of the final text).
    @Published var partials = true
    @Published var loadSeconds: Double?
    @Published var lines: [Line] = []
    @Published var partial = ""
    @Published var audioSeconds: Double = 0
    @Published var computeSeconds: Double = 0
    @Published var error: String?
    /// One row every 30 s while live: the battery and heat over half an hour.
    @Published var log: [String] = []
    /// What the last run measured, to send back (the share sheet).
    @Published var report = ""

    private var engine: Engine?
    private var engineThreads: Int32 = 0
    private let queue = DispatchQueue(label: "murmur.engine", qos: .userInitiated)
    private let microphone = Microphone()
    private var timer: Timer?
    private var startedAt = Date()

    var rtf: Double { audioSeconds > 0 ? computeSeconds / audioSeconds : 0 }

    private var modelsDir: String { Bundle.main.resourcePath! + "/models" }

    /// The engine with the chosen threads, loaded once (off the main thread).
    private func loadedEngine() async throws -> Engine {
        if let engine, engineThreads == threads { return engine }
        state = .loading
        let dir = modelsDir, n = threads
        let engine = try await withCheckedThrowingContinuation { (c: CheckedContinuation<Engine, Error>) in
            queue.async { c.resume(with: Result { try Engine(modelsDir: dir, int8: true, threads: n) }) }
        }
        self.engine = engine
        engineThreads = n
        loadSeconds = engine.loadSeconds
        return engine
    }

    func benchmark() async {
        reset()
        do {
            let engine = try await loadedEngine()
            state = .benchmarking
            let wav = modelsDir + "/reunio-3min.wav"
            let partials = self.partials
            let batch = try await withCheckedThrowingContinuation { (c: CheckedContinuation<Batch, Error>) in
                queue.async { c.resume(with: Result { try engine.bench(wav: wav, partials: partials) }) }
            }
            lines = batch.finals
            audioSeconds = batch.audioSeconds
            computeSeconds = batch.computeSeconds
            report = header(kind: "Fitxer: reunio-3min.wav") + transcript()
        } catch {
            self.error = error.localizedDescription
        }
        state = .idle
    }

    func startLive() async {
        reset()
        do {
            let engine = try await loadedEngine()
            try engine.liveStart(partials: partials)
            UIDevice.current.isBatteryMonitoringEnabled = true
            UIApplication.shared.isIdleTimerDisabled = true
            startedAt = Date()
            log = ["minut,audio_s,compute_s,rtf,bateria,temperatura"]
            try microphone.start { [weak self] samples in
                guard let self else { return }
                self.queue.async {
                    let batch = engine.livePush(samples)
                    Task { @MainActor in self.apply(batch) }
                }
            }
            state = .live
            logRow()
            timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.logRow() }
            }
        } catch {
            self.error = error.localizedDescription
            state = .idle
        }
    }

    func stopLive() async {
        microphone.stop()
        timer?.invalidate()
        timer = nil
        UIApplication.shared.isIdleTimerDisabled = false
        guard let engine else { return }
        let batch = await withCheckedContinuation { (c: CheckedContinuation<Batch, Never>) in
            queue.async { c.resume(returning: engine.liveFinish()) }
        }
        apply(batch)
        partial = ""
        logRow()
        report = header(kind: "Directe (micròfon)") + log.joined(separator: "\n") + "\n\n" + transcript()
        state = .idle
    }

    private func apply(_ batch: Batch) {
        lines.append(contentsOf: batch.finals)
        if !batch.finals.isEmpty { partial = "" }
        if let p = batch.partial { partial = p }
        audioSeconds = batch.audioSeconds
        computeSeconds = batch.computeSeconds
    }

    private func logRow() {
        let minutes = Date().timeIntervalSince(startedAt) / 60
        let battery = UIDevice.current.batteryLevel
        log.append(String(format: "%.1f,%.0f,%.1f,%.3f,%.0f%%,%@", minutes, audioSeconds, computeSeconds, rtf, battery * 100, thermal()))
    }

    private func reset() {
        error = nil
        lines = []
        partial = ""
        audioSeconds = 0
        computeSeconds = 0
        report = ""
    }

    private func header(kind: String) -> String {
        """
        Murmur Probe · \(kind)
        Telèfon: \(machine()) · iOS \(UIDevice.current.systemVersion)
        Model: Conformer ca-es int8 · \(threads) fils · text provisional: \(partials ? "sí" : "no") · càrrega \(String(format: "%.1f", loadSeconds ?? 0)) s
        Àudio: \(String(format: "%.1f", audioSeconds)) s · càlcul: \(String(format: "%.1f", computeSeconds)) s · RTF \(String(format: "%.3f", rtf))


        """
    }

    private func transcript() -> String {
        lines.map { "[\(clock($0.start))] Parlant \($0.speaker + 1): \($0.text)" }.joined(separator: "\n")
    }
}

func clock(_ seconds: Double) -> String {
    let s = Int(seconds)
    return String(format: "%d:%02d", s / 60, s % 60)
}

func thermal() -> String {
    switch ProcessInfo.processInfo.thermalState {
    case .nominal: return "normal"
    case .fair: return "tebi"
    case .serious: return "calent"
    case .critical: return "crític"
    @unknown default: return "?"
    }
}

/// "iPhone17,1": which phone it is.
func machine() -> String {
    var info = utsname()
    uname(&info)
    return withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix(while: { $0 != 0 }), as: UTF8.self) }
}
