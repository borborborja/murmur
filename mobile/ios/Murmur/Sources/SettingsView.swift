import SwiftUI

/// The iOS grouped list, with the desktop's sections that make sense on the phone.
struct SettingsView: View {
    @Environment(\.murmur) private var palette
    @AppStorage("language") private var language = "ca-es"
    @AppStorage("partialEvery") private var partialEvery = 1.5

    var body: some View {
        List {
            Section(L("settings.computer")) {
                Label(L("settings.computerSoon"), systemImage: "laptopcomputer")
                    .font(.system(size: 15)).foregroundStyle(palette.textSecondary)
            }
            .listRowBackground(palette.fillSubtle)

            Section(L("settings.transcription")) {
                Picker(L("settings.language"), selection: $language) {
                    Text(L("language.caEs")).tag("ca-es")
                    Text(L("language.ca")).tag("ca")
                    Text(L("language.es")).tag("es")
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
    }
}
