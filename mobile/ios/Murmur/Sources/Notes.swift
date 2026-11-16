import AVFoundation
import SwiftUI
import UIKit

/// One block of the notes (mockup, screen 4): text with a format, a marked moment or a photo, each
/// with the meeting second it was written at, as the computer's editor stamps them.
struct NoteBlock: Identifiable, Equatable {
    enum Kind: String { case paragraph, heading, bullet, task, mark, photo }
    var id = UUID()
    var kind: Kind = .paragraph
    var text = ""
    var t: Double?
    var checked = false
    /// A photo's file, relative to the data folder ("screens/<meeting>/<ms>.jpg").
    var photo: String?

    var isText: Bool { kind != .mark && kind != .photo }
    var isEmpty: Bool { isText && text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

extension Array where Element == NoteBlock {
    /// What counts as a note (for "Notes · 3").
    var noteCount: Int { filter { !$0.isEmpty }.count }

    /// A marked moment or a photo goes before the empty line at the end, where typing goes on.
    mutating func insertBeforeTrailing(_ block: NoteBlock) {
        if let last = last, last.isEmpty, last.kind == .paragraph { insert(block, at: Swift.max(0, endIndex - 1)) } else { append(block) }
    }

    /// There's always an empty line at the end to write on.
    mutating func ensureTrailing() {
        if !(last.map { $0.isEmpty && $0.kind == .paragraph } ?? false) { append(NoteBlock()) }
    }
}

/// The notes as the computer stores them: a TipTap document (headings, paragraphs, bullet and task
/// lists, each block with `attrs.t`), the marked moments (`marks`) and the photos as its
/// screenshots (`screens`).
enum NotesDoc {
    static func encode(_ blocks: [NoteBlock]) -> [String: Any]? {
        var content: [[String: Any]] = []
        let text = { (s: String) -> [[String: Any]] in s.isEmpty ? [] : [["type": "text", "text": s]] }
        let attrs = { (b: NoteBlock) -> [String: Any] in b.t.map { ["t": $0] } ?? [:] }
        for block in blocks where !block.isEmpty && block.kind != .photo {
            switch block.kind {
            case .heading:
                content.append(["type": "heading", "attrs": attrs(block).merging(["level": 2]) { a, _ in a }, "content": text(block.text)])
            case .bullet, .task:
                let list = block.kind == .bullet ? "bulletList" : "taskList"
                var itemAttrs = attrs(block)
                if block.kind == .task { itemAttrs["checked"] = block.checked }
                let item: [String: Any] = ["type": block.kind == .bullet ? "listItem" : "taskItem", "attrs": itemAttrs,
                                           "content": [["type": "paragraph", "content": text(block.text)]]]
                // Consecutive items of the same kind are one list.
                if var previous = content.last, previous["type"] as? String == list, var items = previous["content"] as? [[String: Any]] {
                    items.append(item)
                    previous["content"] = items
                    content[content.count - 1] = previous
                } else {
                    content.append(["type": list, "content": [item]])
                }
            case .mark:
                // The mark goes in the paragraph itself (`attrs.mark`): the database keeps the
                // document, and has nowhere for a separate list of marks. The computer shows the text.
                content.append(["type": "paragraph", "attrs": attrs(block).merging(["mark": true]) { a, _ in a }, "content": text(block.text)])
            default:
                content.append(["type": "paragraph", "attrs": attrs(block), "content": text(block.text)])
            }
        }
        return content.isEmpty ? nil : ["type": "doc", "content": content]
    }

    static func marks(_ blocks: [NoteBlock]) -> [[String: Any]] {
        blocks.filter { $0.kind == .mark }.compactMap { $0.t.map { ["t": $0] } }
    }

    static func screens(_ blocks: [NoteBlock]) -> [[String: Any]] {
        blocks.filter { $0.kind == .photo }.compactMap { b in b.photo.map { ["t": b.t ?? 0, "path": $0] } }
    }

    /// The other way: a meeting's document, marks and photos (each photo after the last block
    /// written before it).
    static func decode(doc: [String: Any]?, marks: Set<Double>, screens: [[String: Any]]) -> [NoteBlock] {
        func flat(_ node: [String: Any]) -> String {
            if let s = node["text"] as? String { return s }
            return (node["content"] as? [[String: Any]] ?? []).map(flat).joined()
        }
        func t(_ node: [String: Any]) -> Double? { (node["attrs"] as? [String: Any])?["t"] as? Double }
        var blocks: [NoteBlock] = []
        for node in doc?["content"] as? [[String: Any]] ?? [] {
            switch node["type"] as? String {
            case "heading":
                blocks.append(NoteBlock(kind: .heading, text: flat(node), t: t(node)))
            case "bulletList", "orderedList", "taskList":
                for item in node["content"] as? [[String: Any]] ?? [] {
                    let task = item["type"] as? String == "taskItem"
                    let checked = (item["attrs"] as? [String: Any])?["checked"] as? Bool ?? false
                    blocks.append(NoteBlock(kind: task ? .task : .bullet, text: flat(item), t: t(item), checked: checked))
                }
            default:
                let text = flat(node)
                let marked = (node["attrs"] as? [String: Any])?["mark"] as? Bool ?? false
                if let at = t(node), marked || marks.contains(at) {
                    blocks.append(NoteBlock(kind: .mark, text: text, t: at))
                } else if !text.isEmpty {
                    blocks.append(NoteBlock(kind: .paragraph, text: text, t: t(node)))
                }
            }
        }
        // Marks saved without a paragraph (older recordings).
        for at in marks where !blocks.contains(where: { $0.kind == .mark && $0.t == at }) {
            blocks.append(NoteBlock(kind: .mark, text: L("rec.markNote"), t: at))
        }
        for screen in screens {
            guard let path = screen["path"] as? String else { continue }
            let at = screen["t"] as? Double ?? 0
            let photo = NoteBlock(kind: .photo, t: at, photo: path)
            let after = blocks.lastIndex { ($0.t ?? 0) <= at }
            blocks.insert(photo, at: after.map { $0 + 1 } ?? 0)
        }
        return blocks
    }

    static func json(_ object: Any) -> String {
        (try? JSONSerialization.data(withJSONObject: object)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    }
}

/// The notes editor: one line per block, the minute in the margin. Return makes a new block (a new
/// item, in a list; an empty item leaves the list). The format bar is `NotesFormatBar`.
struct NotesEditor: View {
    @Environment(\.murmur) private var palette
    @Binding var blocks: [NoteBlock]
    var focus: FocusState<UUID?>.Binding
    /// The meeting second now, to stamp new blocks; nil for a finished meeting.
    var now: () -> Double?
    var dataDir: URL

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 10) {
            ForEach(blocks) { block in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(block.t.map(clock) ?? "").font(.tabular(12)).foregroundStyle(palette.timestamp)
                        .frame(width: 44, alignment: .trailing)
                    content(block)
                }
            }
        }
        .onChange(of: focus.wrappedValue) { old, _ in
            // A line left empty goes away (not the last one, where typing goes on).
            guard let old, let i = blocks.firstIndex(where: { $0.id == old }), blocks[i].isEmpty, i < blocks.count - 1 else { return }
            blocks.remove(at: i)
        }
    }

    @ViewBuilder private func content(_ block: NoteBlock) -> some View {
        switch block.kind {
        case .mark:
            FlagChip(label: block.text)
        case .photo:
            if let path = block.photo { PhotoThumb(url: dataDir.appendingPathComponent(path), t: block.t ?? 0) }
        default:
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if block.kind == .bullet {
                    Text("•").font(.system(size: 17, weight: .bold))
                } else if block.kind == .task {
                    Button { toggleChecked(block.id) } label: {
                        Image(systemName: block.checked ? "checkmark.square.fill" : "square")
                            .font(.system(size: 19)).foregroundStyle(block.checked ? palette.toggleOn : palette.textTertiary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("notes.task"))
                    .accessibilityValue(block.checked ? "✓" : "")
                }
                TextField(block.id == blocks.last?.id ? L("notes.placeholder") : "",
                          text: Binding(get: { block.text }, set: { edit(block.id, $0) }), axis: .vertical)
                    .focused(focus, equals: block.id)
                    .font(block.kind == .heading ? .system(size: 20, weight: .bold) : .system(size: 17))
                    .strikethrough(block.kind == .task && block.checked, color: palette.textTertiary)
                    .foregroundStyle(block.kind == .task && block.checked ? palette.textSecondary : palette.textPrimary)
            }
        }
    }

    private func toggleChecked(_ id: UUID) {
        guard let i = blocks.firstIndex(where: { $0.id == id }) else { return }
        blocks[i].checked.toggle()
    }

    private func stamp(_ i: Int) {
        if blocks[i].t == nil, !blocks[i].isEmpty, let now = now() { blocks[i].t = now.rounded(.down) }
    }

    /// Typing in a block; Return (a new line in the text) splits it.
    private func edit(_ id: UUID, _ text: String) {
        guard let i = blocks.firstIndex(where: { $0.id == id }) else { return }
        guard let newline = text.firstIndex(of: "\n") else {
            blocks[i].text = text
            stamp(i)
            blocks.ensureTrailing()
            return
        }
        let before = String(text[..<newline])
        let after = String(text[text.index(after: newline)...]).replacingOccurrences(of: "\n", with: " ")
        let kind = blocks[i].kind
        // Return on an empty item leaves the list.
        if before.trimmingCharacters(in: .whitespaces).isEmpty, after.isEmpty, kind == .bullet || kind == .task {
            blocks[i].kind = .paragraph
            return
        }
        blocks[i].text = before
        stamp(i)
        let next = NoteBlock(kind: kind == .heading ? .paragraph : kind, text: after)
        blocks.insert(next, at: i + 1)
        stamp(i + 1)
        blocks.ensureTrailing()
        focus.wrappedValue = next.id
    }
}

/// Above the keyboard, while writing a note: heading, list, checkbox, a photo, and (recording) the
/// time; Fet puts the keyboard away.
struct NotesFormatBar: View {
    @Environment(\.murmur) private var palette
    @Binding var blocks: [NoteBlock]
    var focus: FocusState<UUID?>.Binding
    var photo: (() -> Void)?
    var seconds: (() -> Double)?

    var body: some View {
        HStack(spacing: 4) {
            format(.heading, "textformat.size", L("notes.heading"))
            format(.bullet, "list.bullet", L("notes.list"))
            format(.task, "checklist", L("notes.task"))
            if let photo {
                Button(action: photo) { icon("camera") }.accessibilityLabel(L("notes.photo"))
            }
            Spacer(minLength: 0)
            if let seconds {
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    HStack(spacing: 7) {
                        RecordingDot()
                        Text(clock(seconds())).font(.tabular(14, weight: .semibold))
                    }
                    .padding(.horizontal, 12).frame(height: 34)
                    .background(palette.recordingPill, in: Capsule())
                    .foregroundStyle(palette.onRecordingPill)
                }
            }
            Button { focus.wrappedValue = nil } label: {
                Text(L("notes.done")).font(.system(size: 16, weight: .semibold)).padding(.horizontal, 12).frame(height: 44)
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(palette.textPrimary)
        .padding(.horizontal, 10).frame(height: 52)
        .background(.bar)
    }

    private func icon(_ name: String) -> some View {
        Image(systemName: name).font(.system(size: 18, weight: .medium)).frame(width: 44, height: 44)
    }

    private func format(_ kind: NoteBlock.Kind, _ systemImage: String, _ label: String) -> some View {
        let i = blocks.firstIndex { $0.id == focus.wrappedValue }
        let on = i.map { blocks[$0].kind == kind } ?? false
        return Button {
            guard let i else { return }
            blocks[i].kind = on ? .paragraph : kind
        } label: {
            icon(systemImage).background(on ? palette.fillSubtle : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .accessibilityLabel(label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// A photo in the notes, with its minute on it.
struct PhotoThumb: View {
    var url: URL
    var t: Double
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Rectangle().fill(.quaternary)
            }
            Text(L("notes.photoAt", clock(t))).font(.system(size: 12, weight: .semibold)).foregroundStyle(.white)
                .padding(.horizontal, 7).padding(.vertical, 2)
                .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                .padding(8)
        }
        .frame(width: 220, height: 150)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("notes.photoAt", clock(t)))
        .task(id: url) {
            let url = url
            image = await Task.detached { UIImage(contentsOfFile: url.path)?.preparingThumbnail(of: CGSize(width: 660, height: 450)) }.value
        }
    }
}

/// The camera, for a photo (the photo library where there's no camera, as in the simulator).
struct CameraPicker: UIViewControllerRepresentable {
    var taken: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.taken(image) }
            parent.dismiss()
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

extension UIImage {
    /// A JPEG no bigger than `side` on its long side: enough to read a whiteboard, small to send.
    func jpeg(side: CGFloat = 2048, quality: CGFloat = 0.82) -> Data? {
        let scale = min(1, side / max(size.width, size.height))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in draw(in: CGRect(origin: .zero, size: target)) }
        return resized.jpegData(compressionQuality: quality)
    }
}
