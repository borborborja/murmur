import SwiftUI

/// The cover the computer's language model writes after the final pass (app/src/lib/types.ts
/// `Cover`), as it comes back to the phone.
struct Cover {
    struct Decision: Identifiable { let id = UUID(); var text: String; var t: Double? }
    struct Task: Identifiable { let id = UUID(); var text: String; var owner: String?; var when: String?; var t: Double?; var done: Bool }
    struct Part: Identifiable { let id = UUID(); var t: Double; var title: String; var points: [String] }

    var excerpt: String?
    /// A tone id ("constructive"…); older covers saved a label in some language.
    var tone: String?
    var toneReason: String?
    var summary: [String] = []
    var decisions: [Decision] = []
    var tasks: [Task] = []
    var parts: [Part] = []

    init?(_ c: [String: Any]) {
        excerpt = (c["excerpt"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        if let tone = c["tone"] as? [String: Any] {
            self.tone = tone["label"] as? String
            toneReason = tone["reason"] as? String
        }
        summary = c["summary"] as? [String] ?? []
        decisions = (c["decisions"] as? [[String: Any]] ?? []).compactMap { d in
            (d["text"] as? String).map { Decision(text: $0, t: d["t"] as? Double) }
        }
        tasks = (c["tasks"] as? [[String: Any]] ?? []).compactMap { d in
            (d["text"] as? String).map { Task(text: $0, owner: d["owner"] as? String, when: d["when"] as? String, t: d["t"] as? Double, done: d["done"] as? Bool ?? false) }
        }
        parts = (c["parts"] as? [[String: Any]] ?? []).compactMap { d in
            (d["title"] as? String).map { Part(t: d["t"] as? Double ?? 0, title: $0, points: d["points"] as? [String] ?? []) }
        }
        if excerpt == nil, summary.isEmpty, decisions.isEmpty, tasks.isEmpty, parts.isEmpty { return nil }
    }

    /// The tone in the interface's language (the desktop's six, by id).
    var toneLabel: String? {
        guard let tone else { return nil }
        let id = tone.lowercased()
        return ["constructive", "relaxed", "neutral", "formal", "tense", "conflictive"].contains(id) ? L("cover.tones.\(id)") : tone
    }
}

/// The meeting's cover (mockup, screen 6): the lede, the tone and who spoke, the summary, the
/// decisions and the tasks with their minute, and the parts.
struct CoverView: View {
    @Environment(\.murmur) private var palette
    @Environment(\.colorScheme) private var scheme
    var cover: Cover
    var talk: [(speaker: Int, seconds: Double)]
    var speakerNames: [Int: String]
    var toggleTask: (Int, Bool) -> Void = { _, _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let excerpt = cover.excerpt {
                Text(excerpt).font(.system(size: 17)).lineSpacing(4).fixedSize(horizontal: false, vertical: true)
            }
            HStack(alignment: .top, spacing: 10) {
                if let tone = cover.toneLabel {
                    tile(L("cover.tone")) {
                        HStack(spacing: 7) {
                            // The acid only on dark (DESIGN.md: never a fill that reads as text on light).
                            Circle().fill(scheme == .dark ? palette.accent : palette.textPrimary).frame(width: 8, height: 8)
                            Text(tone).font(.system(size: 15, weight: .semibold))
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityHint(cover.toneReason ?? "")
                }
                if !talk.isEmpty {
                    tile(L("cover.talk")) { TalkBar(talk: talk) }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(L("cover.talk"))
                        .accessibilityValue(talkDescription)
                }
            }
            .padding(.top, 16)

            if !cover.summary.isEmpty {
                section(L("cover.summary"))
                ForEach(cover.summary, id: \.self) { point in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text("•").foregroundStyle(palette.textTertiary)
                        Text(point).fixedSize(horizontal: false, vertical: true)
                    }
                    .font(.system(size: 16)).padding(.bottom, 8)
                }
            }

            section(L("cover.decisions"))
            if cover.decisions.isEmpty {
                Text(L("cover.noDecisions")).font(.system(size: 15)).foregroundStyle(palette.textSecondary)
            }
            ForEach(cover.decisions) { d in item(t: d.t) { Text(d.text) } }

            section(L("cover.tasks"))
            if cover.tasks.isEmpty {
                Text(L("cover.noTasks")).font(.system(size: 15)).foregroundStyle(palette.textSecondary)
            }
            // Tapping a task ticks it (or unticks it).
            ForEach(Array(cover.tasks.enumerated()), id: \.element.id) { i, task in
                Button { toggleTask(i, !task.done) } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Image(systemName: task.done ? "checkmark.square.fill" : "square")
                            .foregroundStyle(task.done ? palette.toggleOn : palette.textTertiary)
                            .accessibilityHidden(true)
                        (Text(task.text) + Text(task.owner.map { " · \($0)" } ?? "").foregroundColor(palette.textSecondary))
                            .strikethrough(task.done, color: palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .font(.system(size: 16)).padding(.bottom, 10)
                .accessibilityAddTraits(task.done ? .isSelected : [])
            }

            if !cover.parts.isEmpty {
                section(L("cover.parts"))
                ForEach(cover.parts) { part in
                    VStack(alignment: .leading, spacing: 6) {
                        item(t: part.t) { Text(part.title).font(.system(size: 16, weight: .semibold)) }
                        ForEach(part.points, id: \.self) { point in
                            Text("· \(point)").font(.system(size: 15)).foregroundStyle(palette.textSecondary)
                                .padding(.leading, 46).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(.bottom, 10)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var talkDescription: String {
        let total = max(1, talk.reduce(0) { $0 + $1.seconds })
        return talk.map { "\(speakerNames[$0.speaker] ?? L("rec.speaker", "\($0.speaker + 1)")) \(Int(($0.seconds / total * 100).rounded()))%" }.joined(separator: ", ")
    }

    private func section(_ title: String) -> some View {
        Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(palette.textSecondary)
            .padding(.top, 22).padding(.bottom, 8)
            .accessibilityAddTraits(.isHeader)
    }

    private func tile<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 12, weight: .semibold)).foregroundStyle(palette.textSecondary)
            content()
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func item<Content: View>(t: Double?, @ViewBuilder _ content: () -> Content) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(t.map(clock) ?? "").font(.tabular(12)).foregroundStyle(palette.timestamp).frame(width: 34, alignment: .leading)
            content().font(.system(size: 16)).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, 10)
    }
}

/// Who spoke, as one bar split by speaker in the speakers' colours.
private struct TalkBar: View {
    @Environment(\.murmur) private var palette
    var talk: [(speaker: Int, seconds: Double)]

    var body: some View {
        GeometryReader { geo in
            let total = max(1, talk.reduce(0) { $0 + $1.seconds })
            let gaps = CGFloat(max(0, talk.count - 1)) * 2
            HStack(spacing: 2) {
                ForEach(talk, id: \.speaker) { entry in
                    color(entry.speaker).frame(width: max(2, (geo.size.width - gaps) * entry.seconds / total))
                }
            }
        }
        .frame(height: 8)
        .clipShape(Capsule())
        .padding(.top, 5)
    }

    private func color(_ speaker: Int) -> Color {
        switch speaker % 4 {
        case 0: palette.speaker1
        case 1: palette.speaker2
        case 2: palette.speaker3
        default: palette.speaker4
        }
    }
}
