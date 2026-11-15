import { trackEvent } from "@aptabase/tauri";

import { isTauri } from "./backend";

/**
 * Anonymous usage statistics (Aptabase, lib.rs APTABASE_KEY): which features are used and how
 * long meetings are, to know what to improve. Never what's in a meeting (titles, text, names,
 * notes, audio), never an identifier: Aptabase keeps a session id in memory that expires, and
 * adds the app version, the operating system and its language. Off with Settings › General ›
 * "Envia estadístiques d'ús anònimes"; without a key in lib.rs nothing is sent at all.
 */

let allowed = true;

/** Follows the setting (App.tsx). */
export function setAnalyticsAllowed(on: boolean) {
  allowed = on;
}

type Props = Record<string, string | number | boolean>;

export function track(name: string, props?: Props) {
  if (!allowed || !isTauri()) return;
  // Aptabase takes strings and numbers: true/false go as "yes"/"no".
  const values = props && Object.fromEntries(Object.entries(props).map(([k, v]) => [k, typeof v === "boolean" ? (v ? "yes" : "no") : v]));
  trackEvent(name, values).catch(() => {});
}

/** A length in seconds as a range, never the exact value: "<5", "5-15", "15-30", "30-60", "60-120", ">120" (minutes). */
export function minutesBucket(seconds: number): string {
  const m = seconds / 60;
  if (m < 5) return "<5";
  if (m < 15) return "5-15";
  if (m < 30) return "15-30";
  if (m < 60) return "30-60";
  if (m < 120) return "60-120";
  return ">120";
}

/** A count as a range: "0", "1-5", "6-20", "21-50", ">50". */
export function countBucket(n: number): string {
  if (n <= 0) return "0";
  if (n <= 5) return "1-5";
  if (n <= 20) return "6-20";
  if (n <= 50) return "21-50";
  return ">50";
}
