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

    var body: some View {
        List {
            Section(L("settings.computer")) {
                Label(L("settings.computerSoon"), systemImage: "laptopcomputer")
                    .font(.system(size: 15)).foregroundStyle(palette.textSecondary)
            }
            .listRowBackground(palette.fillSubtle)

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

            Section(L("settings.transcription")) {
                Picker(L("settings.language"), selection: $language) {
                    Text(L("language.caEs")).tag("ca-es")
                    Text(L("language.ca")).tag("ca")
                    Text(L("language.es")).tag("es")
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
        .onAppear {
            // Asking iOS for the microphones touches the audio session: not while recording.
            guard store.live == nil, Microphone.simulated == nil else { return }
            let bluetooth: Set<AVAudioSession.Port> = [.bluetoothHFP, .bluetoothLE, .bluetoothA2DP]
            inputs = Microphone.available().map { ($0.uid, $0.portName, bluetooth.contains($0.portType)) }
        }
    }
}
