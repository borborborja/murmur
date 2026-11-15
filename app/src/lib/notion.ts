import * as backend from "./backend";
import { toMarkdown } from "./export";
import type { Meeting } from "./types";

/**
 * Notion (Settings › Exportar, menu Exporta; notion.rs): the meeting as a page in the user's own
 * Notion, under the page they chose. Only the text goes: the summary and notes, then the
 * transcript (in subpages if it's long). Never the audio or the screenshots.
 */
export async function saveToNotion(m: Meeting, page: { id: string } | null): Promise<string> {
  const summary = m.cover ? toMarkdown(m, "summary", { title: false }) : "";
  const transcript = toMarkdown(m, m.cover ? "transcript" : "all", { title: false });
  return backend.notionExport(m.title, summary, transcript, page?.id ?? null);
}
