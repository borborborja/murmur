package dev.murmur.android.export

import android.content.ContextWrapper
import dev.murmur.android.intelligence.Catalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class RichExportTest {
    private fun exporter(): ExportManager = ExportManager(ContextWrapper(null)) { Catalog(JSONObject("""{
      "app":{"viewCover":"Resum"},"export":{"notes":"Notes","transcript":"Transcripció","screens":"Captures"},
      "summary":"Resum","tone":"To","talk":"Qui parla","decisions":"Decisions","tasks":"Tasques","parts":"Parts",
      "format":{"minutes":"{{m}} min","hoursMinutes":"{{h}} h {{m}} min"},
      "transcript":{"pause":"Pausa de {{from}} a {{to}} · {{duration}}"}
    }""")) }

    private fun meeting(): JSONObject = JSONObject("""{
      "id":"m1","title":"Pressupost <privat> & prova","createdAt":0,"duration":30,"language":"ca",
      "speakerNames":{"0":"Àngels"},"tags":["Projecte A"],
      "parts":[{"at":10,"startedAt":3610000}],
      "utterances":[{"id":"u1","speaker":0,"start":0,"end":10,"text":"Primer fragment."},{"id":"u2","speaker":0,"start":10,"end":30,"text":"Segon fragment."}],
      "notesDoc":{"type":"doc","content":[
        {"type":"heading","attrs":{"level":1,"t":1},"content":[{"type":"text","text":"Capçalera"}]},
        {"type":"paragraph","attrs":{"t":2,"indent":1},"content":[{"type":"text","text":"Àudio <A> & B","marks":[{"type":"bold"},{"type":"italic"}]},{"type":"hardBreak"},{"type":"text","text":"segona línia","marks":[{"type":"underline"},{"type":"strike"}]}]},
        {"type":"taskList","content":[{"type":"taskItem","attrs":{"t":3,"checked":true},"content":[{"type":"paragraph","content":[{"type":"text","text":"Enviar pressupost"}]}]}]},
        {"type":"blockquote","content":[{"type":"paragraph","attrs":{"t":4},"content":[{"type":"text","text":"Cita literal"}]}]}
      ]},
      "screens":[{"t":15,"path":"attachments/m1-2.jpg"},{"t":5,"path":"attachments/m1-1.jpg"}]
    }""")

    @Test fun markdownPreservesRichNotesFirstPauseAndMeetingSnapshot() {
        val meeting = meeting()
        val before = meeting.toString()
        val markdown = exporter().markdown(meeting)
        assertTrue(markdown.contains("### Capçalera"))
        assertTrue(markdown.contains("[0:02] ***Àudio <A> & B***"))
        assertTrue(markdown.contains("- [x] [0:03] Enviar pressupost"))
        assertTrue(markdown.contains("> [0:04] Cita literal"))
        assertTrue(markdown.contains("Pausa de "))
        assertTrue(markdown.contains("1 h 00 min"))
        assertEquals(1, Regex("Pausa de ").findAll(markdown).count())
        assertTrue(markdown.indexOf("Pausa de ") < markdown.indexOf("Segon fragment."))
        assertEquals(before, meeting.toString())
    }

    @Test fun docxIsValidOoxmlWithFormattingUnicodeAndRealLineBreaks() {
        val archive = mutableMapOf<String, ByteArray>()
        ZipInputStream(exporter().docx(meeting()).inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; archive[entry.name] = zip.readBytes() }
        }
        assertTrue(archive.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels")))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        archive.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { factory.newDocumentBuilder().parse(it.inputStream()) }
        val document = archive.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(document.contains("Àudio &lt;A&gt; &amp; B"))
        assertTrue(document.contains("<w:b/>") && document.contains("<w:i/>") && document.contains("<w:u ") && document.contains("<w:strike/>"))
        assertTrue(document.contains("<w:br/>"))
        assertTrue(document.contains("Enviar pressupost"))
    }

    @Test fun obsidianIncludesOrderedScreensAttachmentsPeopleAndProperties() {
        val note = exporter().obsidian(meeting(), people = true)
        assertTrue(note.startsWith("---\ndate:"))
        assertTrue(note.contains("participants:\n  - \"[[Àngels]]\""))
        assertTrue(note.contains("- \"Projecte-A\""))
        assertTrue(note.contains("**[0:05]**\n![[attachments/m1-1.jpg]]"))
        assertTrue(note.indexOf("m1-1.jpg") < note.indexOf("m1-2.jpg"))
        assertFalse(note.contains("# Pressupost"))
    }
}
