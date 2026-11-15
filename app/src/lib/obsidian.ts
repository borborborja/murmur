import { openUrl } from "@tauri-apps/plugin-opener";

import * as backend from "./backend";
import { dayStamp, safeName, toMarkdown } from "./export";
import { clock } from "./format";
import { t } from "./i18n";
import type { Meeting } from "./types";

/**
 * Obsidian (Settings › Exportar, menu Exporta): a meeting as a note in a vault. Obsidian is a
 * folder of Markdown files, so it's the Markdown export plus what makes it at home there:
 * properties at the top (date, duration, participants, tags) that Bases and Dataview can filter,
 * tasks as `- [ ]`, the screenshots copied into the vault and embedded, and the people as
 * `[[links]]` if wanted. Nothing leaves the computer.
 */

export type ObsidianOptions = {
  /** The vault's folder. */
  vault: string;
  /** A folder inside it ("Reunions"); empty: the vault's root. */
  folder: string;
  /** Speakers as `[[Marta]]`: a page per person, with all their meetings. */
  people: boolean;
};

const yaml = (s: string) => JSON.stringify(s);

/** The names the user gave (or the meeting learnt), not "Parlant 2". */
const participants = (m: Meeting) => [...new Set(Object.values(m.speakerNames ?? {}).map((n) => n?.trim()).filter((n): n is string => !!n))];

/** The note's text. `screens`: the file names the screenshots got in the vault, in order. */
export function obsidianNote(m: Meeting, people: boolean, screens: { t: number; file: string }[]): string {
  const link = (name: string) => (people ? `[[${name}]]` : name);
  const d = new Date(m.createdAt);
  const local = `${dayStamp(m.createdAt)}T${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  const names = participants(m);
  const tags = (m.tags ?? []).map((tag) => tag.trim().replace(/\s+/g, "-")).filter(Boolean);
  const props = [
    "---",
    `date: ${local}`,
    `duration: ${Math.max(1, Math.round(m.duration / 60))}`,
    ...(names.length ? ["participants:", ...names.map((n) => `  - ${yaml(link(n))}`)] : []),
    ...(tags.length ? ["tags:", ...tags.map((tag) => `  - ${yaml(tag)}`)] : []),
    ...(m.language ? [`language: ${m.language}`] : []),
    `source: ${yaml(m.device?.name ?? "Murmur")}`,
    "---",
    "",
  ];
  let body = toMarkdown(m, "all", { person: link, title: false });
  if (screens.length) {
    body = `${body.trimEnd()}\n\n## ${t("export:screens")}\n\n${screens.map((s) => `**[${clock(s.t)}]**\n![[${s.file}]]`).join("\n\n")}\n`;
  }
  return props.join("\n") + body;
}

/**
 * Writes the meeting into the vault: "<folder>/2026-11-15 Title.md", and its screenshots in
 * "<folder>/attachments/". Saving again replaces them. Returns the note's path.
 */
export async function saveToObsidian(m: Meeting, opts: ObsidianOptions): Promise<string> {
  const dir = [opts.vault.replace(/[\\/]+$/, ""), opts.folder.trim().replace(/^[\\/]+|[\\/]+$/g, "")].filter(Boolean).join("/");
  const base = `${dayStamp(m.createdAt)} ${safeName(m.title)}`;
  const screens: { t: number; file: string }[] = [];
  for (const s of [...(m.screens ?? [])].sort((a, b) => a.t - b.t)) {
    const ext = s.path.split(".").pop() ?? "jpg";
    const file = `${base} ${clock(s.t).replace(/:/g, ".")}.${ext}`;
    try {
      const bytes = await backend.shareReadFile(m.id, "screen", s.path);
      await backend.writeExport(`${dir}/attachments/${file}`, new Uint8Array(bytes));
      screens.push({ t: s.t, file });
    } catch {
      /* a screenshot that's gone: the note goes without it */
    }
  }
  const path = `${dir}/${base}.md`;
  await backend.writeExport(path, new TextEncoder().encode(obsidianNote(m, opts.people, screens)));
  return path;
}

/** Opens the note in Obsidian. */
export const openInObsidian = (path: string) => openUrl(`obsidian://open?path=${encodeURIComponent(path)}`);
