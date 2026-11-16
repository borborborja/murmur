import SwiftUI

/// A finished meeting. Made on the phone: the quick transcript and the notes, and what the
/// computer will add when it finds it (the title, the summary, Whisper).
struct MeetingView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @Environment(\.dismiss) private var dismiss
    var id: String
    @State private var confirmDelete = false
    @State private var renaming = false
    @State private var newTitle = ""
    /// The speaker being renamed (the label's menu).
    @State private var naming: Speaker?
    /// The notes, as they're edited here (saved as they change).
    @State private var notes: [NoteBlock] = []
    @State private var notesLoaded = false
    @FocusState private var editing: UUID?
    /// The tab chosen by hand; until then, the summary if there is one.
    @State private var chosen: MeetingTab?

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

                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: m.revised ? "checkmark.seal" : "laptopcomputer")
                            // No computer yet; waiting to find it; on the computer, waiting for its
                            // final pass; or back, revised.
                            Text(m.revised ? L("meeting.revised") : store.computer == nil ? L("meeting.pendingUnpaired") : m.sent ? L("meeting.sent") : L("meeting.pending"))
                        }
                        .font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .padding(.top, 14)

                        let tab = currentTab(m)
                        Segmented(options: tabs(m), selection: Binding(get: { tab }, set: { chosen = $0 }))
                            .padding(.top, 14)

                        VStack(alignment: .leading, spacing: 14) {
                            if tab == .summary, let cover = m.cover {
                                CoverView(cover: cover, talk: m.talk, speakerNames: m.speakerNames)
                            } else if tab == .transcript {
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
                                NotesEditor(blocks: $notes, focus: $editing, now: { nil }, dataDir: store.dataDir)
                                    .padding(.leading, -16)
                            }
                        }
                        .padding(.top, 16)
                    }
                }
                .padding(.horizontal, 20).padding(.bottom, 120)
            }
            .scrollDismissesKeyboard(.interactively)
            if editing != nil {
                NotesFormatBar(blocks: $notes, focus: $editing, photo: nil, seconds: nil)
            }
        }
        .onAppear {
            guard !notesLoaded, let m = store.meeting(id) else { return }
            notes = m.notes
            notes.ensureTrailing()
            notesLoaded = true
        }
        .onChange(of: notes) { _, blocks in
            if notesLoaded { store.saveNotes(blocks, in: id) }
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

    /// Resum (once the computer has written it), Transcripció and Notes.
    private func tabs(_ m: MeetingDetail) -> [(MeetingTab, String)] {
        let count = notes.noteCount
        let notesTab = (MeetingTab.notes, count == 0 ? L("rec.notes") : "\(L("rec.notes")) · \(count)")
        let summary = m.cover == nil ? [] : [(MeetingTab.summary, L("meeting.summary"))]
        return summary + [(.transcript, L("rec.transcript")), notesTab]
    }

    private func currentTab(_ m: MeetingDetail) -> MeetingTab {
        if let chosen, chosen != .summary || m.cover != nil { return chosen }
        return m.cover != nil ? .summary : .transcript
    }

    private func startRename(_ title: String?) {
        newTitle = title ?? ""
        renaming = true
    }
}
