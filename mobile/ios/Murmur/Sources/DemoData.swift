#if DEBUG
import Foundation

/// The sample meetings of the App Store's screenshots (`-demo`, development builds only): the
/// landing's invented people (Marta, Jordi, Laia), never real ones. They're added once; the
/// revised ones are marked as back from the computer, with its cover.
enum DemoData {
    @MainActor static func load(into store: Store) {
        guard let engine = store.engine, !(store.meetings.contains { $0.id.hasPrefix("demo-") }) else { return }
        // At believable hours, before the screenshots' 9:41: today at 9:00 and 8:30, yesterday at 16:00 and 11:00.
        let calendar = Calendar.current
        func at(_ daysAgo: Int, _ hour: Int, _ minute: Int = 0) -> Double {
            let day = calendar.date(byAdding: .day, value: -daysAgo, to: Date())!
            return calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day)!.timeIntervalSince1970 * 1000
        }
        let now = Date().timeIntervalSince1970 * 1000
        func lines(_ id: String, _ items: [(Double, Int, String)]) -> [[String: Any]] {
            items.enumerated().map { i, l in ["id": "\(id)-\(i)", "start": l.0, "end": l.0 + 8, "speaker": l.1, "text": l.2] }
        }
        func p(_ text: String, _ t: Double) -> [String: Any] { ["type": "paragraph", "attrs": ["t": t], "content": [["type": "text", "text": text]]] }

        let launch: [String: Any] = [
            "id": "demo-launch", "title": "Llançament de la web nova", "createdAt": at(0, 9), "duration": 34 * 60,
            "status": "done", "language": "ca-es", "tags": ["Web", "Estudi Nord"],
            "speakerNames": ["0": "Tu", "1": "Marta", "2": "Laia"],
            "utterances": lines("demo-launch", [
                (4, 0, "Bon dia a tothom. Avui la idea és tancar el calendari del llançament i repassar què falta de la web."),
                (16, 1, "Perfecte. Jo he portat les captures de l'última versió, les comparteixo ara."),
                (41, 2, "Els textos en anglès els tinc gairebé llestos. Me'ls podria mirar algú abans de divendres?"),
                (58, 0, "Sí, els reviso jo. I la pàgina de preus, qui la porta?"),
                (66, 2, "La puc fer jo, però necessito que em passeu els preus definitius abans de dimecres."),
                (79, 1, "Els preus els tanquem dilluns amb direcció, us els envio aquell mateix dia."),
                (95, 0, "Doncs si tot va bé, publiquem el dimarts 18 al matí."),
                (104, 1, "I deixem la versió antiga desada per si de cas, com l'últim cop."),
            ]),
            "notesDoc": ["type": "doc", "content": [
                ["type": "heading", "attrs": ["level": 2, "t": 100], "content": [["type": "text", "text": "Llançament · dimarts 18"]]],
                p("Portada acabada. Falten preus i textos en anglès.", 440),
                ["type": "taskList", "content": [
                    ["type": "taskItem", "attrs": ["checked": true, "t": 760], "content": [p("Captures de l'última versió", 760)]],
                    ["type": "taskItem", "attrs": ["checked": false, "t": 772], "content": [p("Preus definitius abans de dimecres", 772)]],
                ]],
                ["type": "paragraph", "attrs": ["t": 722, "mark": true], "content": [["type": "text", "text": "Moment important"]]],
            ]],
            "marks": [["t": 722]],
            "cover": [
                "generatedAt": now / 1000,
                "excerpt": "L'equip tanca el calendari: la web nova es publica el dimarts 18 al matí, amb els preus i els textos en anglès acabats abans.",
                "tone": ["label": "constructive", "reason": "Tothom proposa solucions i es reparteixen la feina."],
                "summary": ["La portada ja està acabada.", "Falten els preus definitius i els textos en anglès.", "Es publica el dimarts 18 al matí, amb la versió antiga desada."],
                "decisions": [["text": "Es publica el dimarts 18 al matí.", "t": 95], ["text": "Els preus es tanquen dilluns amb direcció.", "t": 79]],
                "tasks": [
                    ["text": "Revisar els textos en anglès", "owner": "Tu", "when": "divendres", "t": 58, "done": true],
                    ["text": "Pàgina de preus", "owner": "Laia", "when": "dimecres", "t": 66],
                    ["text": "Enviar els preus definitius", "owner": "Marta", "when": "dilluns", "t": 79],
                ],
                "parts": [["t": 0, "title": "El calendari", "points": ["Data de publicació", "Què falta de la web"]], ["t": 60, "title": "Preus i textos", "points": ["Qui porta cada cosa"]]],
            ],
        ]
        let budget: [String: Any] = [
            "id": "demo-budget", "title": "Pressupost 2027", "createdAt": at(1, 11), "duration": 55 * 60,
            "status": "done", "language": "ca", "tags": ["Direcció"], "speakerNames": ["0": "Tu", "1": "Jordi"],
            "utterances": lines("demo-budget", [
                (12, 1, "Tanquem la partida de màrqueting a la baixa i ho compensem amb la de formació."),
                (30, 0, "D'acord, però deixem marge per a la fira de la primavera."),
            ]),
        ]
        let weekly: [String: Any] = [
            "id": "demo-weekly", "title": "Seguiment setmanal", "createdAt": at(0, 8, 30), "duration": 25 * 60,
            "status": "done", "language": "ca-es", "tags": ["Estudi Nord"], "speakerNames": ["0": "Tu", "1": "Marta", "2": "Jordi"],
            "utterances": lines("demo-weekly", [
                (8, 1, "Els preus els tanquem dilluns amb direcció."),
                (20, 2, "Jo tinc la demo del client nou a punt per dijous."),
            ]),
        ]
        let interview: [String: Any] = [
            "id": "demo-interview", "title": "Entrevista amb la Núria", "createdAt": at(1, 16), "duration": 45 * 60,
            "status": "done", "language": "ca", "speakerNames": ["0": "Tu", "1": "Núria"],
            "utterances": lines("demo-interview", [(10, 1, "M'interessa molt la part de recerca d'usuaris.")]),
            "cover": ["generatedAt": now / 1000, "excerpt": "Una bona candidata per a recerca d'usuaris.", "summary": ["Experiència en entrevistes"]],
        ]
        for meeting in [launch, budget, weekly, interview] {
            try? engine.putMeeting(json: NotesDoc.json(meeting))
        }
        // The revised ones are back from the computer (sync.json, as the engine keeps it).
        let sync: [String: Any] = ["since": 0, "sent": ["demo-launch", "demo-budget", "demo-interview"], "revised": ["demo-launch", "demo-budget", "demo-interview"], "edited": []]
        try? JSONSerialization.data(withJSONObject: sync).write(to: store.dataDir.appendingPathComponent("sync.json"))
        store.reload()
    }
}
#endif
