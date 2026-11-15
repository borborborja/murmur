import SwiftUI

/// A test bench, not the app: the numbers we need and the text, nothing else.
struct ContentView: View {
    @StateObject private var probe = Probe()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("Fils", selection: $probe.threads) {
                        ForEach([1, 2, 4] as [Int32], id: \.self) { Text("\($0)").tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .disabled(probe.state != .idle)
                    Toggle("Text provisional", isOn: $probe.partials)
                        .disabled(probe.state != .idle)
                    Button {
                        Task { await probe.benchmark() }
                    } label: {
                        Label("Prova amb la reunió de 3 minuts", systemImage: "doc.text.magnifyingglass")
                    }
                    .disabled(probe.state != .idle)
                    Button {
                        Task { probe.state == .live ? await probe.stopLive() : await probe.startLive() }
                    } label: {
                        Label(probe.state == .live ? "Atura" : "Grava en directe", systemImage: probe.state == .live ? "stop.circle.fill" : "mic.circle.fill")
                    }
                    .tint(probe.state == .live ? .red : .accentColor)
                    .disabled(probe.state == .loading || probe.state == .benchmarking)
                } footer: {
                    Text("El Conformer en int8, com el de l'ordinador. Per a la prova de bateria, grava mitja hora amb la pantalla bloquejada.")
                }

                Section("Mesures") {
                    stat("Estat", status)
                    if let s = probe.loadSeconds { stat("Càrrega del model", String(format: "%.1f s", s)) }
                    stat("Àudio", String(format: "%.1f s", probe.audioSeconds))
                    stat("Càlcul", String(format: "%.1f s", probe.computeSeconds))
                    stat("Factor de temps real", probe.audioSeconds > 0 ? String(format: "%.3f (%.0f× més ràpid)", probe.rtf, 1 / max(probe.rtf, 0.0001)) : "–")
                    stat("Temperatura", thermal())
                    if let e = probe.error { Text(e).foregroundStyle(.red).font(.footnote) }
                    if !probe.report.isEmpty {
                        ShareLink(item: probe.report) { Label("Envia els resultats", systemImage: "square.and.arrow.up") }
                    }
                }

                if probe.log.count > 1 {
                    Section("Registre cada 30 s") {
                        ForEach(Array(probe.log.dropFirst().enumerated()), id: \.offset) { _, row in
                            Text(row).font(.caption.monospaced())
                        }
                    }
                }

                Section("Transcripció") {
                    if !probe.partial.isEmpty {
                        Text(probe.partial).foregroundStyle(.secondary).italic()
                    }
                    ForEach(probe.lines.reversed()) { line in
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(clock(line.start)) · Parlant \(line.speaker + 1)").font(.caption).foregroundStyle(.secondary)
                            Text(line.text)
                        }
                    }
                }
            }
            .navigationTitle("Murmur Probe")
            // `-autobench` (simctl launch, or Xcode's scheme): runs the test meeting at launch and
            // prints the report, to check a build without touching the screen.
            .task {
                let args = ProcessInfo.processInfo.arguments
                guard args.contains("-autobench") else { return }
                if let i = args.firstIndex(of: "-threads"), i + 1 < args.count, let n = Int32(args[i + 1]) { probe.threads = n }
                probe.partials = !args.contains("-nopartials")
                await probe.benchmark()
                print(probe.error ?? probe.report)
            }
        }
    }

    private var status: String {
        switch probe.state {
        case .idle: return "A punt"
        case .loading: return "Carregant el model…"
        case .benchmarking: return "Transcrivint la reunió…"
        case .live: return "Escoltant"
        }
    }

    private func stat(_ label: String, _ value: String) -> some View {
        LabeledContent(label) { Text(value).monospacedDigit() }
    }
}

@main
struct MurmurProbeApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
