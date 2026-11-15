import AVFoundation

/// The microphone as 16 kHz mono floats, in ~100 ms pieces, as the engine wants them. It keeps
/// going with the screen locked (UIBackgroundModes: audio), and when the microphone changes in the
/// middle (headphones plugged in or out) it carries on with the new one.
final class Microphone: @unchecked Sendable {
    /// `-simulate <file.wav>` (development, as TRANSCRIPTOR_SIMULATE on the computer): a 16 kHz
    /// mono WAV instead of the microphone, at its real speed.
    private var timer: DispatchSourceTimer?
    static var simulated: String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-simulate"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    /// The microphone chosen in Configuració (its uid); empty: automatic, as iOS picks it (a wired
    /// or USB one if there is one, otherwise the phone's).
    static var chosen: String { UserDefaults.standard.string(forKey: "micInput") ?? "" }
    /// Whether the chosen one is Bluetooth: those only join when chosen, since they hear only the
    /// person wearing them, not the room.
    static var chosenIsBluetooth: Bool { UserDefaults.standard.bool(forKey: "micInputBluetooth") }

    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
    private var onSamples: (([Float]) -> Void)?
    private var configurationObserver: NSObjectProtocol?

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
        self.onSamples = onSamples
        try Self.configureSession()
        try startEngine()
        // A new microphone (or the old one gone) stops the engine: start it again with the new format.
        configurationObserver = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            try? self?.restartEngine()
        }
    }

    func stop() {
        if let t = timer {
            t.cancel()
            timer = nil
            return
        }
        if let observer = configurationObserver { NotificationCenter.default.removeObserver(observer) }
        configurationObserver = nil
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        onSamples = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// The microphone in use, as iOS names it ("Micròfon de l'iPhone", "AirPods de…").
    static var currentName: String? {
        AVAudioSession.sharedInstance().currentRoute.inputs.first?.portName
    }

    /// The microphones this phone has now, for Configuració. Bluetooth ones only show up while the
    /// session allows them, so it's set up briefly to ask.
    static func available() -> [AVAudioSessionPortDescription] {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .mixWithOthers])
        try? session.setActive(true)
        let inputs = session.availableInputs ?? []
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        return inputs
    }

    private static func configureSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .default, options: chosenIsBluetooth ? [.allowBluetooth] : [])
        try session.setActive(true)
        let port = chosen.isEmpty ? nil : session.availableInputs?.first { $0.uid == chosen }
        // Not there any more (unplugged): back to automatic.
        try? session.setPreferredInput(port)
    }

    private func startEngine() throws {
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return }
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
            self.onSamples?(Array(UnsafeBufferPointer(start: data[0], count: Int(out.frameLength))))
        }
        engine.prepare()
        try engine.start()
    }

    private func restartEngine() throws {
        guard onSamples != nil else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try startEngine()
    }
}
