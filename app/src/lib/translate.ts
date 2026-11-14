import { useSyncExternalStore, type Dispatch } from "react";
import { toast } from "sonner";

import * as backend from "./backend";
import { cloudModelName, coverEngine } from "./cloud";
import { speakerName } from "./format";
import { t } from "./i18n";
import type { Settings } from "./settings";
import type { Action } from "./store";
import type { LlmTask, Meeting, Translation } from "./types";

/**
 * "Tradueix la transcripció a…": the language model the user has for the summaries (Gemma here,
 * or Claude or ChatGPT with their key) translates the transcript line by line. The original
 * stays; the translation goes beside it (Meeting.translation) and the transcript can show either.
 *
 * Gemma reads the whole transcript once and translates it in groups of lines (one task each), so
 * it loads and reads once, as for the summary. Claude and ChatGPT get one group at a time, so the
 * meeting isn't sent again with every group, and the lines show up as they come.
 */

export type TranslationLanguage = Translation["lang"];

/** Lines per group: short enough to keep every line, long enough to keep the context. */
const GROUP = 25;

/** The target language as the model is told. */
const TARGET: Record<TranslationLanguage, string> = { ca: "Catalan", es: "Spanish", en: "English" };

const SYSTEM =
  "You translate meeting transcripts. Each line of the document is one numbered line of what someone said. " +
  "Translate faithfully and naturally: keep the meaning, tone and register, names of people, companies and products, figures and dates. " +
  "Never summarise, merge, split or leave out lines, and never add anything.";

const SCHEMA = JSON.stringify({
  type: "object",
  properties: { lines: { type: "array", items: { type: "object", properties: { n: { type: "integer" }, text: { type: "string" } }, required: ["n", "text"] } } },
  required: ["lines"],
});

/** Meetings being translated now: lines done of all. */
type Progress = { lang: TranslationLanguage; done: number; total: number };
let progress: Record<string, Progress> = {};
const listeners = new Set<() => void>();
const setProgress = (id: string, p: Progress | null) => {
  const { [id]: _old, ...rest } = progress;
  progress = p ? { ...rest, [id]: p } : rest;
  listeners.forEach((l) => l());
};

/** The translation in progress for each meeting. */
export function useTranslating(): Record<string, Progress> {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => progress,
  );
}

/** `n. Name: text`, one line per utterance, numbered from 1. */
function document(m: Meeting, from = 0, to = m.utterances.length): string {
  return m.utterances
    .slice(from, to)
    .map((u, i) => `${from + i + 1}. ${speakerName(m, u.speaker)}: ${u.text.replace(/\s+/g, " ")}`)
    .join("\n");
}

function task(m: Meeting, from: number, to: number, lang: TranslationLanguage): LlmTask {
  const chars = m.utterances.slice(from, to).reduce((n, u) => n + u.text.length, 0);
  return {
    id: `lines-${from + 1}-${to}`,
    prompt:
      `Translate lines ${from + 1} to ${to} into ${TARGET[lang]}. ` +
      "One entry per line, with its number, and only what was said (without the speaker's name). " +
      "A line already in that language stays as it is.",
    // About 3 characters per token, with room for longer translations and the JSON around them.
    maxTokens: Math.ceil((chars / 3) * 1.6) + 40 * (to - from) + 100,
    schema: SCHEMA,
  };
}

/** The model's answer for a group: utterance id → text. */
function parse(text: string | null, m: Meeting, from: number, to: number): Record<string, string> {
  const out: Record<string, string> = {};
  try {
    const lines = (JSON.parse(text ?? "") as { lines?: { n?: number; text?: string }[] }).lines ?? [];
    for (const line of lines) {
      const i = Number(line.n) - 1;
      const value = line.text?.trim();
      if (i >= from && i < to && value) out[m.utterances[i].id] = value;
    }
  } catch {
    // A group that came back broken stays untranslated: its lines show the original.
  }
  return out;
}

export async function translateMeeting(m: Meeting, lang: TranslationLanguage, settings: Settings, dispatch: Dispatch<Action>) {
  if (progress[m.id] || !m.utterances.length) return;
  const engine = coverEngine(settings);
  const total = m.utterances.length;
  const groups: [number, number][] = [];
  for (let i = 0; i < total; i += GROUP) groups.push([i, Math.min(total, i + GROUP)]);
  const sources = Object.fromEntries(m.utterances.map((u) => [u.id, u.text]));
  let texts: Record<string, string> = {};
  const save = (done: boolean) =>
    dispatch({ type: "translation", id: m.id, translation: { lang, texts, sources, done, writtenBy: engine ? cloudModelName(engine) : undefined } });
  setProgress(m.id, { lang, done: 0, total });
  save(false);
  const job = `translate:${m.id}`;
  // Gemma: its progress, group by group, comes as llm-progress events.
  const unlisten = engine
    ? null
    : backend.onLlmProgress((e) => {
        if (e.job !== job) return;
        const at = groups.findIndex(([from, to]) => e.step === `lines-${from + 1}-${to}`);
        if (at >= 0) setProgress(m.id, { lang, done: groups[at][0], total });
      });
  try {
    if (engine) {
      for (const [from, to] of groups) {
        const [result] = await backend.runLlm({ job, system: SYSTEM, document: document(m, from, to), tasks: [task(m, from, to, lang)], keepLoaded: false, engine });
        if (result.error) throw new Error(result.error);
        texts = { ...texts, ...parse(result.text, m, from, to) };
        save(false);
        setProgress(m.id, { lang, done: to, total });
      }
    } else {
      const results = await backend.runLlm({
        job,
        system: SYSTEM,
        document: document(m),
        tasks: groups.map(([from, to]) => task(m, from, to, lang)),
        keepLoaded: false,
        engine: null,
      });
      results.forEach((r, i) => (texts = { ...texts, ...parse(r.text, m, groups[i][0], groups[i][1]) }));
    }
    save(true);
    const missing = total - Object.keys(texts).length;
    if (missing > 0) toast(t("transcript:translationMissing", { count: missing }));
  } catch (e) {
    toast.error(t("transcript:translationFailed", { error: String(e) }));
    // What came in stays (the rest shows the original); with nothing in, no translation.
    if (Object.keys(texts).length) save(true);
    else dispatch({ type: "translation", id: m.id, translation: null });
  } finally {
    (await unlisten)?.();
    setProgress(m.id, null);
  }
}
