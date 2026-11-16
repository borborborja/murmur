import AVFoundation
import SwiftUI

/// The iOS grouped list, with the desktop's sections that make sense on the phone.
struct SettingsView: View {
    @Environment(\.murmur) private var palette
    @AppStorage("language") private var language = "ca-es"
    @AppStorage("partialEvery") private var partialEvery = 1.5
    @AppStorage("moreInRoom") private var moreInRoom = true
    @AppStorage("micInput") private var micInput = ""
    @AppStorage("micInputBluetooth") private var micInputBluetooth = false
    @EnvironmentObject private var store: Store
    /// The microphones there are now (uid, name, Bluetooth or not).
    @State private var inputs: [(uid: String, name: String, bluetooth: Bool)] = []
    @AppStorage("autoSync") private var autoSync = true
    @AppStorage("keepAudio") private var keepAudio = true
    @AppStorage("theme") private var theme = MurmurTheme.acid.rawValue
    @AppStorage("appearance") private var appearance = "system"
    @State private var pairing = false
    @State private var confirmUnpair = false

    /// The computer: connect one, or the one connected, how it's syncing, and what to do with it.
    @ViewBuilder private var computerSection: some View {
        if let computer = store.computer {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: "laptopcomputer").font(.system(size: 17)).frame(width: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(computer.name).font(.system(size: 17))
                        syncLine.font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                    }
                    Spacer(minLength: 0)
                    if store.syncing { ProgressView() }
                }
                .padding(.vertical, 2)
                Button(L("sync.now"), systemImage: "arrow.triangle.2.circlepath") { Task { await store.sync() } }
                    .disabled(store.syncing || store.live != nil)
                Toggle(isOn: $autoSync) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("sync.auto"))
                        Text(L("sync.autoHint")).font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                    }
                }
                .tint(palette.toggleOn)
                Button(L("sync.unpair"), role: .destructive) { confirmUnpair = true }
            } header: {
                Text(L("settings.computer"))
            } footer: {
                if let problem = store.syncProblem {
                    Label(problem, systemImage: "exclamationmark.triangle").font(.system(size: 13))
                }
            }
            .listRowBackground(palette.fillSubtle)
        } else {
            Section {
                Button { pairing = true } label: {
                    Label(L("settings.pairComputer"), systemImage: "qrcode.viewfinder")
                }
            } header: {
                Text(L("settings.computer"))
            } footer: {
                Text(L("settings.computerHint")).font(.system(size: 13))
            }
            .listRowBackground(palette.fillSubtle)
        }
    }

    /// "Sincronitzat fa 5 min · Per enviar: 2", or what's happening now.
    private var syncLine: Text {
        if store.syncing { return Text(L("sync.syncing")) }
        var parts: [String] = []
        if let last = store.lastSync {
            parts.append(L("sync.synced", last.formatted(.relative(presentation: .named))))
        } else {
            parts.append(L("sync.never"))
        }
        if store.unsent > 0 { parts.append(L("sync.toSend", "\(store.unsent)")) }
        return Text(parts.joined(separator: " · "))
    }

    var body: some View {
        List {
            computerSection

            Section {
                Picker(L("settings.microphone"), selection: $micInput) {
                    Text(L("settings.micAuto")).tag("")
                    ForEach(inputs, id: \.uid) { input in
                        Text(input.name).tag(input.uid)
                    }
                    // The chosen one, even if it isn't connected now.
                    if !micInput.isEmpty && !inputs.contains(where: { $0.uid == micInput }) {
                        Text(micInput).tag(micInput)
                    }
                }
                .disabled(store.live != nil)
                .onChange(of: micInput) { _, uid in
                    micInputBluetooth = inputs.first { $0.uid == uid }?.bluetooth ?? false
                }
            } header: {
                Text(L("settings.audio"))
            } footer: {
                Text(L("settings.micHint")).font(.system(size: 13))
            }
            .listRowBackground(palette.fillSubtle)

            Section {
                Toggle(L("settings.keepAudio"), isOn: $keepAudio).tint(palette.toggleOn)
            } footer: {
                Text(L("settings.keepAudioHint")).font(.system(size: 13))
            }
            .listRowBackground(palette.fillSubtle)

            Section(L("settings.transcription")) {
                Picker(L("settings.language"), selection: $language) {
                    Text(L("language.caEs")).tag("ca-es")
                    Text(L("language.ca")).tag("ca")
                    Text(L("language.es")).tag("es")
                }
                NavigationLink {
                    VoiceSettingsView()
                } label: {
                    LabeledContent(L("voice.title"), value: store.voiceSavedAt.map { L("voice.saved", $0.formatted(date: .abbreviated, time: .omitted)) } ?? L("voice.notSaved"))
                }
                Picker(L("start.roomTitle"), selection: $moreInRoom) {
                    Text(L("start.onlyMe")).tag(false)
                    Text(L("start.moreInRoom")).tag(true)
                }
                Picker(selection: $partialEvery) {
                    Text(L("settings.partialsOff")).tag(0.0)
                    ForEach([1.0, 1.5, 3.0], id: \.self) { s in
                        Text(L("settings.partialsEvery", s.formatted(.number.precision(.fractionLength(0...1))))).tag(s)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("settings.partials"))
                        Text(L("settings.partialsHint")).font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                    }
                }
            }
            .listRowBackground(palette.fillSubtle)

            Section(L("settings.appearance")) {
                Picker(L("settings.theme"), selection: $theme) {
                    ForEach(MurmurTheme.allCases) { Text(L("themes.\($0.rawValue)")).tag($0.rawValue) }
                }
                Picker(L("settings.mode"), selection: $appearance) {
                    Text(L("settings.modeSystem")).tag("system")
                    Text(L("settings.modeLight")).tag("light")
                    Text(L("settings.modeDark")).tag("dark")
                }
            }
            .listRowBackground(palette.fillSubtle)

            Section {
            } footer: {
                Label(L("settings.privacy"), systemImage: "lock").font(.system(size: 13))
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .safeAreaInset(edge: .top) {
            Text(L("settings.title")).font(.system(size: 34, weight: .bold)).tracking(-0.7)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20).padding(.top, 8)
                .background(palette.background)
        }
        .contentMargins(.bottom, 110, for: .scrollContent)
        .fullScreenCover(isPresented: $pairing) { PairView() }
        .confirmationDialog(L("sync.unpairConfirm"), isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button(L("sync.unpair"), role: .destructive) { store.unpair() }
            Button(L("meeting.cancel"), role: .cancel) {}
        }
        .onAppear {
            // Asking iOS for the microphones touches the audio session: not while recording.
            guard store.live == nil, Microphone.simulated == nil else { return }
            let bluetooth: Set<AVAudioSession.Port> = [.bluetoothHFP, .bluetoothLE, .bluetoothA2DP]
            inputs = Microphone.available().map { ($0.uid, $0.portName, bluetooth.contains($0.portType)) }
        }
    }
}
