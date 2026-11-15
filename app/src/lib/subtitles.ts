import { save } from "@tauri-apps/plugin-dialog";

import * as backend from "./backend";
import { safeName } from "./export";
import { speakerName } from "./format";
import type { Meeting, Utterance } from "./types";

/**
 * Subtitles (menu Exporta › Subtítols): the transcript as SRT or WebVTT, for a video or for
 * anything that plays them. A line of the transcript can last half a minute, and a subtitle
 * shouldn't: each one is cut into cues of at most two lines of 42 characters and 6 seconds,
 * preferably after a sentence or a comma, with its time shared out by length (there are no
 * word timings). A cue never crosses from one speaker to the next.
 */

export type SubtitleFormat = "srt" | "vtt";
export type SubtitleOptions = {
  /** The speaker's name at the start of each of their lines ("Marta: …", or `<v Marta>` in WebVTT). */
  speakers: boolean;
  /** The translation's text instead of the original, where there is one. */
  translated: boolean;
};

const LINE = 42;
const MAX_CHARS = LINE * 2;
const MAX_SECONDS = 6;
const MIN_SECONDS = 1;

type Cue = { start: number; end: number; text: string; speaker: string | null };

/**
 * The text cut into pieces that fit a cue, as even as they can be (no short orphan at the end):
 * a piece closes after a sentence past half its share, after a comma past most of it, or at its
 * share, and never goes over `max`.
 */
export function splitText(text: string, max = MAX_CHARS): string[] {
  const words = text.trim().split(/\s+/).filter(Boolean);
  const pieces: string[] = [];
  let left = words.join(" ").length;
  const share = () => left / Math.ceil(left / max);
  let target = share();
  let current: string[] = [];
  for (let i = 0; i < words.length; i++) {
    current.push(words[i]);
    const next = words[i + 1];
    if (!next) break;
    const piece = current.join(" ");
    const close =
      piece.length + 1 + next.length > max ||
      (/[.!?…]$/.test(words[i]) && piece.length >= target * 0.5) ||
      (/[,;:]$/.test(words[i]) && piece.length >= target * 0.8) ||
      piece.length >= target;
    if (close) {
      pieces.push(piece);
      left -= piece.length + 1;
      target = share();
      current = [];
    }
  }
  if (current.length) pieces.push(current.join(" "));
  return pieces;
}

/** One or two lines, as even as the words allow. */
export function wrap(text: string): string {
  if (text.length <= LINE) return text;
  const words = text.split(" ");
  let best = text;
  let score = Infinity;
  for (let i = 1; i < words.length; i++) {
    const a = words.slice(0, i).join(" ");
    const b = words.slice(i).join(" ");
    const s = Math.max(a.length, b.length);
    if (s < score) (score = s), (best = `${a}\n${b}`);
  }
  return best;
}

/** The cues of one line of the transcript, its time shared out by length. */
function cuesOf(u: Utterance, text: string, speaker: string | null): Cue[] {
  // The name goes in the first cue's lines: room for it.
  const pieces = splitText(text, MAX_CHARS - (speaker ? speaker.length + 2 : 0));
  const total = pieces.reduce((n, p) => n + p.length, 0) || 1;
  const span = Math.max(u.end - u.start, MIN_SECONDS * pieces.length * 0.5);
  let at = u.start;
  return pieces.map((piece, i) => {
    const length = (span * piece.length) / total;
    const cue = { start: at, end: at + length, text: piece, speaker: i === 0 ? speaker : null };
    at += length;
    return cue;
  });
}

export function subtitleCues(m: Meeting, opts: SubtitleOptions): Cue[] {
  const translation = opts.translated ? m.translation : null;
  const lines = [...m.utterances].filter((u) => u.text.trim()).sort((a, b) => a.start - b.start);
  const several = new Set(lines.map((u) => u.speaker)).size > 1;
  const cues: Cue[] = [];
  let previous: number | null = null;
  for (const u of lines) {
    const text = (translation?.texts[u.id] ?? u.text).trim();
    // The name only when the speaker changes, and only if there's more than one.
    const speaker = opts.speakers && several && u.speaker !== previous ? speakerName(m, u.speaker) : null;
    previous = u.speaker;
    cues.push(...cuesOf(u, text, speaker));
  }
  // No cue longer than 6 s or shorter than 1 s, and none running into the next.
  return cues.map((c, i) => {
    const next = cues[i + 1]?.start ?? Infinity;
    const end = Math.min(Math.max(c.end, c.start + MIN_SECONDS), c.start + MAX_SECONDS, next);
    return { ...c, end: Math.max(end, c.start + 0.1) };
  });
}

/** "00:01:02,345" (SRT) or "00:01:02.345" (WebVTT). */
export function stamp(seconds: number, format: SubtitleFormat): string {
  const ms = Math.max(0, Math.round(seconds * 1000));
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor(ms / 60_000) % 60;
  const s = Math.floor(ms / 1000) % 60;
  const pad = (n: number, w = 2) => String(n).padStart(w, "0");
  return `${pad(h)}:${pad(m)}:${pad(s)}${format === "srt" ? "," : "."}${pad(ms % 1000, 3)}`;
}

export function toSubtitles(m: Meeting, format: SubtitleFormat, opts: SubtitleOptions): string {
  const cues = subtitleCues(m, opts);
  const blocks = cues.map((c, i) => {
    const time = `${stamp(c.start, format)} --> ${stamp(c.end, format)}`;
    if (format === "vtt") return `${time}\n${c.speaker ? `<v ${c.speaker.replace(/[<>]/g, "")}>` : ""}${wrap(c.text)}`;
    // The name counts in the line's length.
    return `${i + 1}\n${time}\n${wrap(c.speaker ? `${c.speaker}: ${c.text}` : c.text)}`;
  });
  return (format === "vtt" ? "WEBVTT\n\n" : "") + blocks.join("\n\n") + "\n";
}

/**
 * Where a meeting's video (or audio) came from, for this computer only: the save panel then
 * offers the subtitles next to it, with its name, so players find them on their own.
 */
const SOURCES_KEY = "murmur.sources.v1";
const sources = (): Record<string, string> => {
  try {
    return JSON.parse(localStorage.getItem(SOURCES_KEY) ?? "{}");
  } catch {
    return {};
  }
};
export function rememberSource(meetingId: string, path: string) {
  try {
    localStorage.setItem(SOURCES_KEY, JSON.stringify({ ...sources(), [meetingId]: path }));
  } catch {
    /* only a convenience */
  }
}

/** Asks where to save, then writes the file. Returns false if the user cancelled. */
export async function exportSubtitles(m: Meeting, format: SubtitleFormat, opts: SubtitleOptions): Promise<boolean> {
  const bytes = new TextEncoder().encode(toSubtitles(m, format, opts));
  const lang = opts.translated && m.translation ? `.${m.translation.lang}` : "";
  const source = sources()[m.id];
  const fileName = source ? `${source.replace(/\.[^./\\]+$/, "")}${lang}.${format}` : `${safeName(m.title)}${lang}.${format}`;
  if (!backend.isTauri()) {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([bytes]));
    a.download = fileName.split(/[\\/]/).pop()!;
    a.click();
    URL.revokeObjectURL(a.href);
    return true;
  }
  const path = await save({ defaultPath: fileName, filters: [{ name: format === "srt" ? "SubRip" : "WebVTT", extensions: [format] }] });
  if (!path) return false;
  await backend.writeExport(path, bytes);
  return true;
}
