import Foundation

/// A meeting as text, to share from the phone (the system's share sheet: Mail, Notes, Messages…):
/// the summary, the notes and the transcript, in Markdown as the computer exports them. Only what
/// the person chooses to send leaves the phone, and only when they send it.
extension MeetingDetail {
    var markdown: String {
        var out = ["# \(title)", "\(createdAt.formatted(date: .long, time: .shortened)) · \(max(1, Int(duration / 60))) min"]
        if !tags.isEmpty { out.append(tags.joined(separator: " · ")) }

        if let cover {
            out.append("## \(L("meeting.summary"))")
            if let excerpt = cover.excerpt { out.append(excerpt) }
            if !cover.summary.isEmpty { out.append(cover.summary.map { "- \($0)" }.joined(separator: "\n")) }
            if !cover.decisions.isEmpty {
                out.append("### \(L("cover.decisions"))")
                out.append(cover.decisions.map { "- \($0.text)\($0.t.map { " (\(clock($0)))" } ?? "")" }.joined(separator: "\n"))
            }
            if !cover.tasks.isEmpty {
                out.append("### \(L("cover.tasks"))")
                out.append(cover.tasks.map { "- [\($0.done ? "x" : " ")] \($0.text)\($0.owner.map { " · \($0)" } ?? "")" }.joined(separator: "\n"))
            }
        }

        let notes = self.notes.filter { !$0.isEmpty && $0.kind != .photo }
        if !notes.isEmpty {
            out.append("## \(L("rec.notes"))")
            out.append(notes.map { block in
                let at = block.t.map { "[\(clock($0))] " } ?? ""
                switch block.kind {
                case .heading: return "### \(block.text)"
                case .bullet: return "- \(at)\(block.text)"
                case .task: return "- [\(block.checked ? "x" : " ")] \(at)\(block.text)"
                case .mark: return "⚑ \(at)\(block.text)"
                default: return "\(at)\(block.text)"
                }
            }.joined(separator: "\n\n"))
        }

        if !utterances.isEmpty {
            out.append("## \(L("rec.transcript"))")
            out.append(utterances.map { u in
                let name = speakerNames[u.speaker] ?? L("rec.speaker", "\(u.speaker + 1)")
                return "**\(name)** (\(clock(u.start))): \(u.text)"
            }.joined(separator: "\n\n"))
        }
        return out.joined(separator: "\n\n")
    }
}
