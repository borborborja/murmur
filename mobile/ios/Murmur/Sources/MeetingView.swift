import SwiftUI

/// A finished meeting. Made on the phone: the quick transcript and the notes, and what the
/// computer will add when it finds it (the title, the summary, Whisper).
struct MeetingView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @Environment(\.dismiss) private var dismiss
    var id: String
    @State private var tab: MeetingTab = .transcript
    @State private var confirmDelete = false
    @State private var renaming = false
    @State private var newTitle = ""
    /// The speaker being renamed (the label's menu).
    @State private var naming: Speaker?

    var body: some View {
        let m = store.meeting(id)
        VStack(spacing: 0) {
            HStack {
                CircleButton(systemImage: "chevron.left", label: L("rec.back")) { dismiss() }
                Spacer()
                Menu {
                    Button(L("meeting.rename"), systemImage: "pencil") { startRename(m?.title) }
                    Button(L("meeting.delete"), systemImage: "trash", role: .destructive) { confirmDelete = true }
                } label: {
                    Image(systemName: "ellipsis").font(.system(size: 17, weight: .medium)).frame(width: 44, height: 44)
                        .background(palette.fillSubtle, in: Circle())
                }
            }
            .padding(.horizontal, 16).frame(height: 56)

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let m {
                        HStack(spacing: 6) {
                            Image(systemName: "iphone")
                            Text("\(m.createdAt.formatted(date: .abbreviated, time: .shortened)) · \(max(1, Int(m.duration / 60))) min")
                        }
                        .font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                        // Tapping the title renames it.
                        Button { startRename(m.title) } label: {
                            Text(m.title).font(.system(size: 28, weight: .bold)).tracking(-0.5)
                                .multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint(L("meeting.rename"))
                        .padding(.top, 6)

                        if !m.revised {
                            HStack(alignment: .top, spacing: 10) {
                                Image(systemName: "laptopcomputer")
                                Text(L("meeting.pending"))
                            }
                            .font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                            .padding(.horizontal, 14).padding(.vertical, 11)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .padding(.top, 14)
                        }

                        Segmented(options: [(MeetingTab.transcript, L("rec.transcript")), (.notes, m.notes.isEmpty ? L("rec.notes") : "\(L("rec.notes")) · \(m.notes.count)")], selection: $tab)
                            .padding(.top, 14)

                        VStack(alignment: .leading, spacing: 14) {
                            if tab == .transcript {
                                if m.utterances.isEmpty { Text(L("meeting.empty")).foregroundStyle(palette.textTertiary) }
                                let actions = SpeakerActions(
                                    speakers: m.speakers,
                                    rename: { naming = $0 },
                                    reassign: { store.reassignLine($0, to: $1, in: id) },
                                    merge: { store.mergeSpeakers($0, into: $1, in: id) })
                                ForEach(m.utterances) { u in
                                    LineView(id: u.id, speaker: u.speaker, name: m.speakerNames[u.speaker], start: u.start, text: u.text,
                                             marked: m.isMarked(u), actions: actions)
                                }
                            } else {
                                ForEach(m.notes) { NoteRow(note: $0) }
                            }
                        }
                        .padding(.top, 16)
                    }
                }
                .padding(.horizontal, 20).padding(.bottom, 120)
            }
        }
        .background(palette.background.ignoresSafeArea())
        .foregroundStyle(palette.textPrimary)
        .toolbar(.hidden, for: .navigationBar)
        .speakerNameAlert($naming) { store.nameSpeaker($0.id, $1, in: id) }
        .confirmationDialog(L("meeting.deleteConfirm"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(L("meeting.delete"), role: .destructive) {
                store.delete(id)
                dismiss()
            }
        }
        .renameAlert(isPresented: $renaming, title: $newTitle) { store.rename(id, to: $0) }
    }

    private func startRename(_ title: String?) {
        newTitle = title ?? ""
        renaming = true
    }
}
