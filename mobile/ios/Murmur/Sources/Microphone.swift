import AVFoundation

/// The microphone as 16 kHz mono floats, in ~100 ms pieces, as the engine wants them. It keeps
/// going with the screen locked (UIBackgroundModes: audio).
final class Microphone: @unchecked Sendable {
    /// `-simulate <file.wav>` (development, as TRANSCRIPTOR_SIMULATE on the computer): a 16 kHz
    /// mono WAV instead of the microphone, at its real speed.
    private var timer: DispatchSourceTimer?
    static var simulated: String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-simulate"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!

    func start(onSamples: @escaping ([Float]) -> Void) throws {
        if let path = Self.simulated {
            let data = try Data(contentsOf: URL(fileURLWithPath: path))
            guard let at = data.range(of: Data("data".utf8))?.upperBound else { return }
            let pcm = data[(at + 4)...]
            var samples = [Float](repeating: 0, count: pcm.count / 2)
            pcm.withUnsafeBytes { raw in
                for i in 0..<samples.count { samples[i] = Float(Int16(littleEndian: raw.loadUnaligned(fromByteOffset: i * 2, as: Int16.self))) / 32768 }
            }
            var offset = 0
            let t = DispatchSource.makeTimerSource(queue: .global(qos: .userInitiated))
            t.schedule(deadline: .now(), repeating: .milliseconds(100))
            t.setEventHandler {
                guard offset < samples.count else { return }
                let end = min(samples.count, offset + 1600)
                onSamples(Array(samples[offset..<end]))
                offset = end
            }
            t.resume()
            timer = t
            return
        }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .default)
        try session.setActive(true)
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        converter = AVAudioConverter(from: format, to: target)
        input.installTap(onBus: 0, bufferSize: AVAudioFrameCount(format.sampleRate / 10), format: format) { [weak self] buffer, _ in
            guard let self, let converter = self.converter else { return }
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * 16_000 / format.sampleRate) + 32
            guard let out = AVAudioPCMBuffer(pcmFormat: self.target, frameCapacity: capacity) else { return }
            var given = false
            var error: NSError?
            converter.convert(to: out, error: &error) { _, status in
                if given {
                    status.pointee = .noDataNow
                    return nil
                }
                given = true
                status.pointee = .haveData
                return buffer
            }
            guard error == nil, let data = out.floatChannelData else { return }
            onSamples(Array(UnsafeBufferPointer(start: data[0], count: Int(out.frameLength))))
        }
        engine.prepare()
        try engine.start()
    }

    func stop() {
        if let t = timer {
            t.cancel()
            timer = nil
            return
        }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false)
    }
}
