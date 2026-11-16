import SwiftUI

/// Searching every meeting on the phone: titles, what was said and the notes (the engine's
/// full-text search, as on the computer). Each result opens its meeting where it was found.
struct SearchView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @Environment(\.dismiss) private var dismiss
    var open: (Route) -> Void
    @State private var query = ""
    @State private var hits: [SearchHit] = []
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(palette.textSecondary)
                    TextField(L("search.placeholder"), text: $query)
                        .focused($focused)
                        .submitLabel(.search)
                        .autocorrectionDisabled()
                    if !query.isEmpty {
                        Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(palette.textTertiary) }
                            .buttonStyle(.plain)
                    }
                }
                .font(.system(size: 17))
                .padding(.horizontal, 14).frame(height: 44)
                .background(palette.fillSubtle, in: Capsule())
                CircleButton(systemImage: "xmark", label: L("search.close")) { dismiss() }
            }
            .padding(.horizontal, 16).padding(.vertical, 6)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if query.trimmingCharacters(in: .whitespaces).isEmpty {
                        Text(L("search.hint")).font(.system(size: 15)).foregroundStyle(palette.textSecondary).padding(.top, 20)
                    } else if hits.isEmpty {
                        Text(L("search.empty", query)).font(.system(size: 15)).foregroundStyle(palette.textSecondary).padding(.top, 20)
                    }
                    // One flat list with ids unique across it: a lazy stack drops rows whose ids repeat.
                    ForEach(rows) { item in
                        switch item {
                        case .header(let id, let title, let createdAt):
                            Button { open(.meeting(id)) } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(title).font(.system(size: 17, weight: .semibold)).lineLimit(1)
                                    Text(Date(timeIntervalSince1970: createdAt / 1000).formatted(date: .abbreviated, time: .shortened))
                                        .font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .padding(.top, 20).padding(.bottom, 6)
                        case .hit(_, let hit):
                            Button { openHit(hit) } label: { row(hit) }.buttonStyle(.plain)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20).padding(.bottom, 40)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(palette.background.ignoresSafeArea())
        .foregroundStyle(palette.textPrimary)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            focused = true
            // `-search <words>` (development): searches at once, to see it without typing.
            let args = ProcessInfo.processInfo.arguments
            if query.isEmpty, let i = args.firstIndex(of: "-search"), i + 1 < args.count { query = args[i + 1] }
        }
        .task(id: query) {
            // A moment after the last key, so each letter doesn't search.
            try? await Task.sleep(for: .milliseconds(180))
            guard !Task.isCancelled else { return }
            hits = (try? store.engine?.search(query: query)) ?? []
        }
    }

    private enum Row: Identifiable {
        case header(String, String, Double)
        case hit(String, SearchHit)
        var id: String {
            switch self {
            case .header(let id, _, _): "meeting-\(id)"
            case .hit(let id, _): id
            }
        }
    }

    /// A meeting's heading, then what was found in it.
    private var rows: [Row] {
        groups.flatMap { group in
            [Row.header(group.id, group.title, group.createdAt)]
                + group.hits.enumerated().map { Row.hit("\(group.id)#\($0.offset)", $0.element) }
        }
    }

    /// The results by meeting, in the order the best one came.
    private var groups: [(id: String, title: String, createdAt: Double, hits: [SearchHit])] {
        var order: [String] = []
        var byMeeting: [String: [SearchHit]] = [:]
        for hit in hits {
            if byMeeting[hit.meetingId] == nil { order.append(hit.meetingId) }
            byMeeting[hit.meetingId, default: []].append(hit)
        }
        return order.compactMap { id in
            guard let list = byMeeting[id], let first = list.first else { return nil }
            // The title is already the heading: only the lines and the notes below it.
            return (id, first.title, first.createdAt, list.filter { $0.kind != "title" })
        }
    }

    private func row(_ hit: SearchHit) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: hit.kind == "note" ? "note.text" : "text.quote").font(.system(size: 12)).foregroundStyle(palette.textTertiary)
            Text(clock(hit.t)).font(.tabular(12)).foregroundStyle(palette.timestamp).frame(width: 40, alignment: .leading)
            Text(highlighted(hit.snippet)).font(.system(size: 15)).foregroundStyle(palette.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading).multilineTextAlignment(.leading)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }

    /// The matched words (between \u{2} and \u{3}) in the text's own colour and bold.
    private func highlighted(_ snippet: String) -> AttributedString {
        var out = AttributedString()
        var bold = false
        var current = ""
        func flush() {
            var part = AttributedString(current)
            if bold {
                part.font = .system(size: 15, weight: .semibold)
                part.foregroundColor = palette.textPrimary
            }
            out += part
            current = ""
        }
        for ch in snippet {
            if ch == "\u{2}" || ch == "\u{3}" {
                flush()
                bold = ch == "\u{2}"
            } else {
                current.append(ch)
            }
        }
        flush()
        return out
    }

    private func openHit(_ hit: SearchHit) {
        let tab: MeetingTab = hit.kind == "note" ? .notes : hit.kind == "utterance" ? .transcript : .summary
        open(.meetingAt(hit.meetingId, tab))
    }
}
