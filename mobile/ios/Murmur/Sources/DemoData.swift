#if DEBUG
import Foundation

/// The sample meetings of the App Store's screenshots (`-demo`, development builds only): the
/// landing's invented people (Marta, Jordi, Laia), never real ones. They're added once; the
/// revised ones are marked as back from the computer, with its cover.
///
/// In the app's language: Catalan, Spanish or English. The phone transcribes Catalan and Spanish
/// only, so in English the meetings are in Spanish and the rest (titles, notes, the computer's
/// summary, which can be written in English) is in English, as it would really be.
enum DemoData {
    /// The texts of one language.
    private struct Texts {
        var meetingLanguage: String
        var you: String
        var launch: (title: String, tags: [String], lines: [String], heading: String, note: String, tasksDone: String, tasksOpen: String, mark: String,
                     excerpt: String, tone: String, summary: [String], decisions: [String], tasks: [(String, String, String)], parts: [(String, [String])])
        var budget: (title: String, tag: String, lines: [String])
        var weekly: (title: String, lines: [String])
        var interview: (title: String, line: String, excerpt: String, summary: String)
    }

    private static let catalan = Texts(
        meetingLanguage: "ca-es", you: "Tu",
        launch: (
            "Llançament de la web nova", ["Web", "Estudi Nord"],
            ["Bon dia a tothom. Avui la idea és tancar el calendari del llançament i repassar què falta de la web.",
             "Perfecte. Jo he portat les captures de l'última versió, les comparteixo ara.",
             "Els textos en anglès els tinc gairebé llestos. Me'ls podria mirar algú abans de divendres?",
             "Sí, els reviso jo. I la pàgina de preus, qui la porta?",
             "La puc fer jo, però necessito que em passeu els preus definitius abans de dimecres.",
             "Els preus els tanquem dilluns amb direcció, us els envio aquell mateix dia.",
             "Doncs si tot va bé, publiquem el dimarts 18 al matí.",
             "I deixem la versió antiga desada per si de cas, com l'últim cop."],
            "Llançament · dimarts 18", "Portada acabada. Falten preus i textos en anglès.",
            "Captures de l'última versió", "Preus definitius abans de dimecres", "Moment important",
            "L'equip tanca el calendari: la web nova es publica el dimarts 18 al matí, amb els preus i els textos en anglès acabats abans.",
            "Tothom proposa solucions i es reparteixen la feina.",
            ["La portada ja està acabada.", "Falten els preus definitius i els textos en anglès.", "Es publica el dimarts 18 al matí, amb la versió antiga desada."],
            ["Es publica el dimarts 18 al matí.", "Els preus es tanquen dilluns amb direcció."],
            [("Revisar els textos en anglès", "divendres", "Tu"), ("Pàgina de preus", "dimecres", "Laia"), ("Enviar els preus definitius", "dilluns", "Marta")],
            [("El calendari", ["Data de publicació", "Què falta de la web"]), ("Preus i textos", ["Qui porta cada cosa"])]),
        budget: ("Pressupost 2027", "Direcció",
                 ["Tanquem la partida de màrqueting a la baixa i ho compensem amb la de formació.",
                  "D'acord, però deixem marge per a la fira de la primavera."]),
        weekly: ("Seguiment setmanal", ["Els preus els tanquem dilluns amb direcció.", "Jo tinc la demo del client nou a punt per dijous."]),
        interview: ("Entrevista amb la Núria", "M'interessa molt la part de recerca d'usuaris.",
                    "Una bona candidata per a recerca d'usuaris.", "Experiència en entrevistes"))

    /// The Spanish meetings: also the transcript of the English screenshots.
    private static let spanishLines = (
        launch: ["Buenos días a todos. Hoy la idea es cerrar el calendario del lanzamiento y repasar qué falta de la web.",
                 "Perfecto. Yo he traído las capturas de la última versión, las comparto ahora.",
                 "Los textos en inglés los tengo casi listos. ¿Me los podría mirar alguien antes del viernes?",
                 "Sí, los reviso yo. ¿Y la página de precios, quién la lleva?",
                 "La puedo hacer yo, pero necesito que me paséis los precios definitivos antes del miércoles.",
                 "Los precios los cerramos el lunes con dirección, os los envío ese mismo día.",
                 "Pues si todo va bien, publicamos el martes 18 por la mañana.",
                 "Y dejamos la versión antigua guardada por si acaso, como la última vez."],
        budget: ["Cerramos la partida de marketing a la baja y lo compensamos con la de formación.",
                 "De acuerdo, pero dejemos margen para la feria de primavera."],
        weekly: ["Los precios los cerramos el lunes con dirección.", "Yo tengo la demo del cliente nuevo lista para el jueves."],
        interview: "Me interesa mucho la parte de investigación de usuarios.")

    private static let spanish = Texts(
        meetingLanguage: "es", you: "Tú",
        launch: (
            "Lanzamiento de la web nueva", ["Web", "Estudio Norte"], spanishLines.launch,
            "Lanzamiento · martes 18", "Portada terminada. Faltan precios y textos en inglés.",
            "Capturas de la última versión", "Precios definitivos antes del miércoles", "Momento importante",
            "El equipo cierra el calendario: la web nueva se publica el martes 18 por la mañana, con los precios y los textos en inglés terminados antes.",
            "Todo el mundo propone soluciones y se reparten el trabajo.",
            ["La portada ya está terminada.", "Faltan los precios definitivos y los textos en inglés.", "Se publica el martes 18 por la mañana, con la versión antigua guardada."],
            ["Se publica el martes 18 por la mañana.", "Los precios se cierran el lunes con dirección."],
            [("Revisar los textos en inglés", "viernes", "Tú"), ("Página de precios", "miércoles", "Laia"), ("Enviar los precios definitivos", "lunes", "Marta")],
            [("El calendario", ["Fecha de publicación", "Qué falta de la web"]), ("Precios y textos", ["Quién lleva cada cosa"])]),
        budget: ("Presupuesto 2027", "Dirección", spanishLines.budget),
        weekly: ("Seguimiento semanal", spanishLines.weekly),
        interview: ("Entrevista con Núria", spanishLines.interview, "Una buena candidata para investigación de usuarios.", "Experiencia en entrevistas"))

    private static let english = Texts(
        meetingLanguage: "es", you: "You",
        launch: (
            "New website launch", ["Website", "North Studio"], spanishLines.launch,
            "Launch · Tuesday 18th", "Home page done. Pricing and English copy still to do.",
            "Screenshots of the latest version", "Final pricing before Wednesday", "Important moment",
            "The team settles the timeline: the new website goes live on Tuesday the 18th in the morning, with pricing and the English copy finished first.",
            "Everyone brings solutions and the work gets shared out.",
            ["The home page is finished.", "Final pricing and the English copy are still missing.", "It goes live on Tuesday the 18th in the morning, with the old version kept."],
            ["It goes live on Tuesday the 18th in the morning.", "Pricing is settled on Monday with management."],
            [("Review the English copy", "Friday", "You"), ("Pricing page", "Wednesday", "Laia"), ("Send the final pricing", "Monday", "Marta")],
            [("The timeline", ["Launch date", "What's left on the website"]), ("Pricing and copy", ["Who does what"])]),
        budget: ("2027 budget", "Management", spanishLines.budget),
        weekly: ("Weekly check-in", spanishLines.weekly),
        interview: ("Interview with Núria", spanishLines.interview, "A strong candidate for user research.", "Interviewing experience"))

    private static var texts: Texts {
        switch Locale.preferredLanguages.first?.prefix(2) {
        case "es": spanish
        case "en": english
        default: catalan
        }
    }

    @MainActor static func load(into store: Store) {
        guard let engine = store.engine, !(store.meetings.contains { $0.id.hasPrefix("demo-") }) else { return }
        let x = texts
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

        let l = x.launch
        let times: [(Double, Int)] = [(4, 0), (16, 1), (41, 2), (58, 0), (66, 2), (79, 1), (95, 0), (104, 1)]
        let taskTimes: [Double] = [58, 66, 79]
        let launch: [String: Any] = [
            "id": "demo-launch", "title": l.title, "createdAt": at(0, 9), "duration": 34 * 60,
            "status": "done", "language": x.meetingLanguage, "tags": l.tags,
            "speakerNames": ["0": x.you, "1": "Marta", "2": "Laia"],
            "utterances": lines("demo-launch", zip(times, l.lines).map { ($0.0, $0.1, $1) }),
            "notesDoc": ["type": "doc", "content": [
                ["type": "heading", "attrs": ["level": 2, "t": 100], "content": [["type": "text", "text": l.heading]]],
                p(l.note, 440),
                ["type": "taskList", "content": [
                    ["type": "taskItem", "attrs": ["checked": true, "t": 760], "content": [p(l.tasksDone, 760)]],
                    ["type": "taskItem", "attrs": ["checked": false, "t": 772], "content": [p(l.tasksOpen, 772)]],
                ]],
                ["type": "paragraph", "attrs": ["t": 722, "mark": true], "content": [["type": "text", "text": l.mark]]],
            ]],
            "marks": [["t": 722]],
            "cover": [
                "generatedAt": now / 1000,
                "excerpt": l.excerpt,
                "tone": ["label": "constructive", "reason": l.tone],
                "summary": l.summary,
                "decisions": [["text": l.decisions[0], "t": 95], ["text": l.decisions[1], "t": 79]],
                "tasks": l.tasks.enumerated().map { i, task -> [String: Any] in
                    var item: [String: Any] = ["text": task.0, "owner": task.2, "when": task.1, "t": taskTimes[i]]
                    if i == 0 { item["done"] = true }
                    return item
                },
                "parts": [["t": 0, "title": l.parts[0].0, "points": l.parts[0].1], ["t": 60, "title": l.parts[1].0, "points": l.parts[1].1]],
            ],
        ]
        let budget: [String: Any] = [
            "id": "demo-budget", "title": x.budget.title, "createdAt": at(1, 11), "duration": 55 * 60,
            "status": "done", "language": x.meetingLanguage, "tags": [x.budget.tag], "speakerNames": ["0": x.you, "1": "Jordi"],
            "utterances": lines("demo-budget", [(12, 1, x.budget.lines[0]), (30, 0, x.budget.lines[1])]),
        ]
        let weekly: [String: Any] = [
            "id": "demo-weekly", "title": x.weekly.title, "createdAt": at(0, 8, 30), "duration": 25 * 60,
            "status": "done", "language": x.meetingLanguage, "tags": [l.tags[1]], "speakerNames": ["0": x.you, "1": "Marta", "2": "Jordi"],
            "utterances": lines("demo-weekly", [(8, 1, x.weekly.lines[0]), (20, 2, x.weekly.lines[1])]),
        ]
        let interview: [String: Any] = [
            "id": "demo-interview", "title": x.interview.title, "createdAt": at(1, 16), "duration": 45 * 60,
            "status": "done", "language": x.meetingLanguage, "speakerNames": ["0": x.you, "1": "Núria"],
            "utterances": lines("demo-interview", [(10, 1, x.interview.line)]),
            "cover": ["generatedAt": now / 1000, "excerpt": x.interview.excerpt, "summary": [x.interview.summary]],
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
