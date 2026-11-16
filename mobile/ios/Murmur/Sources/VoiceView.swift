import AVFoundation
import SwiftUI

/// Configuració › La teva veu (mockup, screen 17): saved or not, and what it is (biometric data,
/// only on this phone).
struct VoiceSettingsView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @State private var recording = false
    @State private var confirmDelete = false

    var body: some View {
        let saved = store.voiceSavedAt
        List {
            Section {
                VStack(spacing: 10) {
                    Image(systemName: "waveform").font(.system(size: 30, weight: .medium))
                        .frame(width: 84, height: 84).background(palette.fillSubtle, in: Circle())
                        .accessibilityHidden(true)
                    if let saved {
                        Text(L("voice.saved", saved.formatted(date: .long, time: .omitted))).font(.system(size: 22, weight: .bold))
                            .multilineTextAlignment(.center)
                    }
                    Text(saved == nil ? L("voice.none") : L("voice.savedText")).font(.system(size: 15)).foregroundStyle(palette.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 8)
                .listRowBackground(Color.clear)
            } footer: {
                Label(L("voice.bio"), systemImage: "lock").font(.system(size: 13))
            }
            Section {
                Button(saved == nil ? L("voice.read") : L("voice.again")) { recording = true }
                if saved != nil {
                    Button(L("voice.delete"), role: .destructive) { confirmDelete = true }
                }
            }
            .listRowBackground(palette.fillSubtle)
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .foregroundStyle(palette.textPrimary)
        .navigationTitle(L("voice.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .fullScreenCover(isPresented: $recording) { VoiceEnrollView() }
        .confirmationDialog(L("voice.deleteTitle"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("voice.deleteConfirm"), role: .destructive) { store.deleteVoice() }
        } message: {
            Text(L("voice.deleteMessage"))
        }
    }
}

/// The microphone's samples while reading the sentence, behind a lock (they come from the
/// microphone's thread).
private final class Samples: @unchecked Sendable {
    private let lock = NSLock()
    private var all: [Float] = []
    private var levels: [Float] = []
    func add(_ s: [Float]) {
        let rms = s.isEmpty ? 0 : (s.reduce(0) { $0 + $1 * $1 } / Float(s.count)).squareRoot()
        lock.lock()
        all += s
        levels.append(rms)
        if levels.count > 15 { levels.removeFirst(levels.count - 15) }
        lock.unlock()
    }
    var snapshot: [Float] { lock.lock(); defer { lock.unlock() }; return all }
    var count: Int { lock.lock(); defer { lock.unlock() }; return all.count }
    var recent: [Float] { lock.lock(); defer { lock.unlock() }; return levels }
}

/// Reading the sentence (mockup, screen 17 left): the words go dark as they're heard, the voice's
/// level and the time below. It stops a moment after the last word, or at 15 s, or with Atura.
struct VoiceEnrollView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @Environment(\.dismiss) private var dismiss
    /// Called once it's saved (the onboarding goes on).
    var saved: () -> Void = {}

    private enum Phase: Equatable { case ready, listening, saving, failed(String) }
    @State private var phase: Phase = .ready
    @State private var said = 0
    @State private var microphone: Microphone?
    private let buffer = Samples()
    private static let limit: Double = 15
    private let sentence = L("voice.sentence")
    private var words: [String] { sentence.split(separator: " ").map(String.init) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Button(L("voice.cancel")) {
                    microphone?.stop()
                    dismiss()
                }
                .font(.system(size: 17))
                Spacer()
                Text(L("voice.title")).font(.system(size: 17, weight: .semibold))
                Spacer()
                Color.clear.frame(width: 60, height: 44)
            }
            .padding(.horizontal, 16).frame(height: 56)

            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    if phase == .listening { RecordingDot() }
                    Text(phase == .listening ? L("voice.listening") : L("voice.ready"))
                        .font(.system(size: 13, weight: .medium)).foregroundStyle(palette.textSecondary)
                }
                // The sentence: what's been read in the text colour, the rest paler.
                (Text(words.prefix(said).joined(separator: " ") + (said > 0 ? " " : "")).foregroundColor(palette.textPrimary)
                    + Text(words.dropFirst(said).joined(separator: " ")).foregroundColor(said == 0 && phase != .listening ? palette.textPrimary : palette.textTertiary))
                    .font(.system(size: 26, weight: .semibold)).lineSpacing(4)
                    .padding(.top, 26)
                    .accessibilityLabel(sentence)

                TimelineView(.periodic(from: .now, by: 0.1)) { _ in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .center, spacing: 4) {
                            let levels = buffer.recent
                            ForEach(0..<15, id: \.self) { i in
                                let level = i < levels.count ? CGFloat(min(1, levels[i] * 12)) : 0
                                Capsule().fill(palette.textPrimary).frame(width: 5, height: 6 + level * 50)
                            }
                        }
                        .frame(height: 60, alignment: .center)
                        .padding(.top, 30)
                        let seconds = Double(buffer.count) / 16_000
                        ProgressView(value: min(seconds, Self.limit), total: Self.limit).tint(palette.textPrimary)
                        HStack {
                            Text(clock(seconds)); Spacer(); Text(clock(Self.limit))
                        }
                        .font(.tabular(13)).foregroundStyle(palette.textSecondary)
                    }
                    .opacity(phase == .listening ? 1 : 0.35)
                }
                .accessibilityHidden(true)

                if case .failed(let message) = phase {
                    Label(message, systemImage: "exclamationmark.triangle").font(.system(size: 15)).padding(.top, 18)
                }
                Spacer()
                Label(L("voice.bio"), systemImage: "lock").font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                    .padding(.bottom, 14)
                Button(action: primary) {
                    Text(phase == .listening ? L("voice.stop") : phase == .saving ? L("voice.saving") : L("voice.read"))
                        .font(.system(size: 17, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(palette.newMeeting, in: Capsule())
                        .foregroundStyle(palette.onNewMeeting)
                }
                .buttonStyle(.plain)
                .disabled(phase == .saving)
            }
            .padding(.horizontal, 24).padding(.bottom, 16)
        }
        .background(palette.background.ignoresSafeArea())
        .foregroundStyle(palette.textPrimary)
        .onDisappear { microphone?.stop() }
    }

    private func primary() {
        switch phase {
        case .listening: finish()
        case .saving: break
        default: listen()
        }
    }

    private func listen() {
        Task {
            guard await AVAudioApplication.requestRecordPermission() else {
                phase = .failed(L("mic.denied"))
                return
            }
            said = 0
            let mic = Microphone()
            let buffer = buffer
            do {
                try mic.start { buffer.add($0) }
            } catch {
                phase = .failed(error.localizedDescription)
                return
            }
            microphone = mic
            phase = .listening
            var finishAt: Int?
            // Every half second: how far it's been read; a moment after the last word, done.
            while phase == .listening {
                try? await Task.sleep(for: .milliseconds(500))
                let samples = buffer.snapshot
                let now = await store.voiceProgress(sentence, samples)
                said = max(said, now)
                if said >= words.count, finishAt == nil { finishAt = samples.count + 16_000 * 6 / 10 }
                if Double(samples.count) / 16_000 >= Self.limit || finishAt.map({ samples.count >= $0 }) ?? false { break }
            }
            if phase == .listening { finish() }
        }
    }

    private func finish() {
        microphone?.stop()
        microphone = nil
        phase = .saving
        let samples = buffer.snapshot
        Task {
            do {
                try await store.enrollVoice(samples)
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                saved()
                dismiss()
            } catch {
                phase = .failed(error.localizedDescription)
            }
        }
    }
}
