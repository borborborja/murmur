import SwiftUI

/// A meeting's tags: the ones it has, the others already used, and a new one (mockup, screen 5:
/// "+ Etiqueta"). The same tags as the computer.
struct TagsSheet: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @Environment(\.dismiss) private var dismiss
    var id: String
    @State var tags: [String]
    @State private var draft = ""
    @FocusState private var focused: Bool

    /// Every tag on the phone, those of this meeting first.
    private var all: [String] {
        var seen = Set(tags.map { $0.lowercased() })
        var out = tags
        for tag in store.meetings.flatMap(\.tags).sorted(by: { $0.localizedCompare($1) == .orderedAscending }) where seen.insert(tag.lowercased()).inserted {
            out.append(tag)
        }
        return out
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField(L("tags.new"), text: $draft).focused($focused).submitLabel(.done)
                            .onSubmit(add)
                        if !draft.trimmingCharacters(in: .whitespaces).isEmpty {
                            Button(L("tags.create", draft.trimmingCharacters(in: .whitespaces)), action: add).font(.system(size: 15, weight: .semibold))
                        }
                    }
                } footer: {
                    Text(L("tags.hint"))
                }
                .listRowBackground(palette.fillSubtle)
                if !all.isEmpty {
                    Section {
                        ForEach(all, id: \.self) { tag in
                            let on = tags.contains(tag)
                            Button { toggle(tag) } label: {
                                HStack {
                                    Text(tag)
                                    Spacer()
                                    if on { Image(systemName: "checkmark").font(.system(size: 15, weight: .semibold)) }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(on ? .isSelected : [])
                        }
                    }
                    .listRowBackground(palette.fillSubtle)
                }
            }
            .scrollContentBackground(.hidden)
            .background(palette.background)
            .navigationTitle(L("tags.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tags.done")) { dismiss() }.fontWeight(.semibold)
                }
            }
        }
        .foregroundStyle(palette.textPrimary)
        .tint(palette.textPrimary)
        .onChange(of: tags) { _, tags in store.setTags(tags, in: id) }
    }

    private func add() {
        let tag = draft.trimmingCharacters(in: .whitespaces)
        draft = ""
        guard !tag.isEmpty else { return }
        // An existing tag in another case is the same tag.
        let existing = all.first { $0.lowercased() == tag.lowercased() } ?? tag
        if !tags.contains(existing) { tags.append(existing) }
    }

    private func toggle(_ tag: String) {
        if let i = tags.firstIndex(of: tag) { tags.remove(at: i) } else { tags.append(tag) }
    }
}

/// A meeting's tags as small chips, and "+ Etiqueta" after them; they wrap onto more lines.
struct FlowTags: View {
    @Environment(\.murmur) private var palette
    var tags: [String]

    var body: some View {
        Flow(spacing: 6) {
            ForEach(tags, id: \.self) { tag in
                Text(tag).font(.system(size: 12, weight: .medium)).foregroundStyle(palette.textSecondary)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
            }
            Text("+ \(L("tags.add"))").font(.system(size: 12, weight: .medium)).foregroundStyle(palette.textTertiary)
                .padding(.vertical, 3)
        }
        .frame(minHeight: 44, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(tags.isEmpty ? L("tags.title") : "\(L("tags.title")): \(tags.joined(separator: ", "))")
    }
}

/// Lays its children out in rows, wrapping when a row is full.
struct Flow: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, width: proposal.width ?? .infinity)
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(0, rows.count - 1)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for i in row.items {
                let size = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y + (row.height - size.height) / 2), proposal: .unspecified)
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [(items: [Int], width: CGFloat, height: CGFloat)] {
        var rows: [(items: [Int], width: CGFloat, height: CGFloat)] = []
        var current: (items: [Int], width: CGFloat, height: CGFloat) = ([], 0, 0)
        for i in subviews.indices {
            let size = subviews[i].sizeThatFits(.unspecified)
            let needed = current.items.isEmpty ? size.width : current.width + spacing + size.width
            if needed > width, !current.items.isEmpty {
                rows.append(current)
                current = ([i], size.width, size.height)
            } else {
                current = (current.items + [i], needed, max(current.height, size.height))
            }
        }
        if !current.items.isEmpty { rows.append(current) }
        return rows
    }
}
