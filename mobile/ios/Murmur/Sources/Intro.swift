import SwiftUI

/// "Murmur, sóc la Marta": someone introduces themselves to Murmur and their speaker gets the name.
/// The same as the desktop (app/src/lib/intro.ts): the wake word avoids false positives ("sóc la
/// Marta" is said for many reasons), and "Murmur" is in the glossary built in, so the live text
/// spells it right. Only the text is read: no model.
enum Intro {
    /// Ways to say "I am", longest first so "jo sóc" wins over "sóc".
    private static let leads = ["my name is", "em dic", "jo soc", "jo sóc", "me llamo", "mi nombre es", "yo soy", "i am", "i'm", "im", "soc", "sóc", "soy", "this is", "aquí", "aqui"]
        .sorted { $0.count > $1.count }
    /// Articles before a name in Catalan ("la Marta", "en Jordi", "l'Anna", "na Laia").
    private static let articles = ["la", "el", "en", "na", "l'", "n'", "l’", "n’"]
    /// Where a name ends: "Murmur, sóc la Marta i porto el pressupost".
    private static let stop: Set<String> = ["i", "y", "and", "que", "but", "però", "pero", "de", "del", "from", "amb", "con", "with", "per", "para", "for"]
    private static let maxWords = 3

    /// Lowercase, without accents or marks: what two spellings of a name share.
    static func fold(_ s: String) -> String {
        let plain = s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased()
        let kept = plain.map { $0.isLetter || $0.isNumber || $0 == "'" || $0 == " " ? $0 : " " }
        return String(kept).split(separator: " ").joined(separator: " ")
    }

    /// The name in an introduction to Murmur, as heard; nil if the line isn't one.
    static func name(in text: String) -> String? {
        let pattern = try? NSRegularExpression(pattern: #"^\W*m[uú]r\s?m[uú]r\b[\s,.:;!¡¿?-]*(.*)$"#, options: [.caseInsensitive])
        let range = NSRange(text.startIndex..., in: text)
        guard let match = pattern?.firstMatch(in: text, range: range), let tail = Range(match.range(at: 1), in: text) else { return nil }
        var rest = String(text[tail]).trimmingCharacters(in: .whitespaces)
        let lower = rest.lowercased()
        guard let lead = leads.first(where: { lower == $0 || lower.hasPrefix("\($0) ") || lower.hasPrefix("\($0),") }) else { return nil }
        rest = String(rest.dropFirst(lead.count)).trimmingCharacters(in: CharacterSet(charactersIn: " ,"))
        if let article = articles.first(where: { rest.lowercased().hasPrefix($0.hasSuffix("'") || $0.hasSuffix("’") ? $0 : "\($0) ") }) {
            rest = String(rest.dropFirst(article.count)).trimmingCharacters(in: .whitespaces)
        }
        var words: [String] = []
        for raw in rest.split(separator: " ") {
            let word = raw.trimmingCharacters(in: CharacterSet.letters.inverted.subtracting(CharacterSet(charactersIn: "'’-")))
                .trimmingCharacters(in: CharacterSet(charactersIn: "'’-"))
            if word.isEmpty || stop.contains(word.lowercased()) { break }
            words.append(word)
            // A mark after the word ends the name ("Marta, i porto…").
            if words.count == maxWords || (raw.last.map { ".,;:!?".contains($0) } ?? false) { break }
        }
        return words.isEmpty ? nil : words.joined(separator: " ")
    }

    private static func distance(_ a: [Character], _ b: [Character]) -> Int {
        var d = Array(0...b.count)
        for i in 1...max(1, a.count) where !a.isEmpty {
            var prev = d[0]
            d[0] = i
            for j in stride(from: 1, through: b.count, by: 1) {
                let tmp = d[j]
                d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] == b[j - 1] ? 0 : 1))
                prev = tmp
            }
        }
        return d[b.count]
    }

    /// Two first names that are the same once folded, or one letter apart in a name of 4 letters or more.
    private static func close(_ a: String, _ b: String) -> Bool {
        a == b || (min(a.count, b.count) >= 4 && distance(Array(a), Array(b)) <= 1)
    }

    /// The name to give: one already given that matches what was heard, whole or by first name (if
    /// only one does); otherwise what was heard, with capitals.
    static func match(_ heard: String, among names: [String]) -> String {
        let h = fold(heard)
        let first = h.split(separator: " ").first.map(String.init) ?? h
        var unique: [String: String] = [:]
        for name in names.map({ $0.trimmingCharacters(in: .whitespaces) }) where !name.isEmpty { unique[fold(name)] = name }
        if let whole = unique.first(where: { $0.key == h })?.value { return whole }
        let byFirst = unique.filter { close($0.key.split(separator: " ").first.map(String.init) ?? $0.key, first) }
        if byFirst.count == 1, let only = byFirst.first?.value { return only }
        return heard.split(separator: " ").map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
    }
}

/// A short message about what just changed, with an undo. It goes away by itself after a while.
struct Toast: Identifiable {
    let id = UUID()
    var text: String
    var undo: (() -> Void)?
}

/// The toast, above the controls (the desktop's colours).
struct ToastView: View {
    @Environment(\.murmur) private var palette
    var toast: Toast
    var dismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(toast.text).font(.system(size: 15)).foregroundStyle(palette.toastText)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let undo = toast.undo {
                Button(L("intro.undo")) {
                    undo()
                    dismiss()
                }
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(palette.toastSecondary)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 13)
        .background(palette.toastBackground, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(palette.toastBorder, lineWidth: 0.5))
        .shadow(color: .black.opacity(0.15), radius: 14, y: 8)
        .padding(.horizontal, 16)
        .task(id: toast.id) {
            try? await Task.sleep(for: .seconds(5))
            dismiss()
        }
        .accessibilityElement(children: .combine)
    }
}
