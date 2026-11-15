import Foundation

/// One line of the transcript, as the engine sends it (engine.rs Event::Final).
struct Line: Identifiable, Decodable {
    let id: UInt32
    let start: Double
    let end: Double
    let speaker: Int
    let text: String
}

/// What `mm_live_push`, `mm_live_finish` and `mm_bench_file` return.
struct Batch {
    var finals: [Line] = []
    var partial: String?
    var audioSeconds: Double = 0
    var computeSeconds: Double = 0
}

/// murmur-core on the phone (ffi/src/lib.rs). Not thread-safe: use it from one serial queue.
final class Engine: @unchecked Sendable {
    private let probe: OpaquePointer
    let loadSeconds: Double

    init(modelsDir: String, int8: Bool, threads: Int32) throws {
        guard let p = mm_probe_new(modelsDir, int8, threads) else {
            throw ProbeError(Engine.take(mm_load_error())?["error"] as? String ?? "?")
        }
        probe = p
        loadSeconds = mm_probe_load_seconds(p)
    }

    func liveStart(partials: Bool) throws {
        if let e = Engine.take(mm_live_start(probe, partials))?["error"] as? String { throw ProbeError(e) }
    }

    func livePush(_ samples: [Float]) -> Batch {
        samples.withUnsafeBufferPointer { Engine.batch(mm_live_push(probe, $0.baseAddress, $0.count)) }
    }

    func liveFinish() -> Batch {
        Engine.batch(mm_live_finish(probe))
    }

    func bench(wav: String, partials: Bool) throws -> Batch {
        let ptr = mm_bench_file(probe, wav, partials)
        let json = Engine.take(ptr)
        if let e = json?["error"] as? String { throw ProbeError(e) }
        return Engine.batch(json: json)
    }

    private static func take(_ ptr: UnsafeMutablePointer<CChar>?) -> [String: Any]? {
        guard let ptr else { return nil }
        defer { mm_string_free(ptr) }
        let data = Data(String(cString: ptr).utf8)
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private static func batch(_ ptr: UnsafeMutablePointer<CChar>?) -> Batch {
        batch(json: take(ptr))
    }

    private static func batch(json: [String: Any]?) -> Batch {
        var b = Batch()
        guard let json else { return b }
        b.audioSeconds = json["audio_s"] as? Double ?? 0
        b.computeSeconds = json["compute_s"] as? Double ?? 0
        for event in json["events"] as? [[String: Any]] ?? [] {
            switch event["type"] as? String {
            case "final":
                if let data = try? JSONSerialization.data(withJSONObject: event),
                   let line = try? JSONDecoder().decode(Line.self, from: data) {
                    b.finals.append(line)
                }
            case "partial":
                b.partial = event["text"] as? String
            default:
                break
            }
        }
        return b
    }
}

struct ProbeError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
