/**
 * "Murmur, sóc la Marta": someone introduces themselves to Murmur and their speaker gets the name.
 * The wake word avoids false positives ("sóc la Marta" is said for many reasons). "Murmur" is in
 * the glossary built in (glossary.rs), so the live text spells it right ("mur mur" → "Murmur").
 * The name heard is matched to the calendar guests, the names already given and the glossary
 * ("xabi" → "Xavi Vinaixa"); otherwise it's written as heard, with capitals.
 */

/** Ways to say "I am", longest first so "jo sóc" wins over "sóc". */
const LEADS = [
  "my name is",
  "em dic",
  "jo soc",
  "jo sóc",
  "me llamo",
  "mi nombre es",
  "yo soy",
  "i am",
  "i'm",
  "im",
  "soc",
  "sóc",
  "soy",
  "this is",
  "aquí",
  "aqui",
].sort((a, b) => b.length - a.length);

/** Articles before a name in Catalan ("la Marta", "en Jordi", "l'Anna", "na Laia"). */
const ARTICLES = ["la", "el", "en", "na", "l'", "n'", "l’", "n’"];

/** Where a name ends: "Murmur, sóc la Marta i porto el pressupost". */
const STOP = new Set(["i", "y", "and", "que", "but", "però", "pero", "de", "del", "from", "amb", "con", "with", "per", "para", "for"]);

const MAX_WORDS = 3;

/** Lowercase, without accents or marks: what two spellings of a name share. */
export const fold = (s: string) =>
  s
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9' ]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();

/** The name in an introduction to Murmur, as heard; null if the line isn't one. */
export function introducedName(text: string): string | null {
  const m = text.match(/^\W*m[uú]r\s?m[uú]r\b[\s,.:;!¡¿?-]*(.*)$/i);
  if (!m) return null;
  let rest = m[1].trim();
  const lower = rest.toLowerCase();
  const lead = LEADS.find((l) => lower === l || lower.startsWith(`${l} `) || lower.startsWith(`${l},`));
  if (!lead) return null;
  rest = rest.slice(lead.length).replace(/^[\s,]+/, "");
  const article = ARTICLES.find((a) => rest.toLowerCase().startsWith(a.endsWith("'") || a.endsWith("’") ? a : `${a} `));
  if (article) rest = rest.slice(article.length).trimStart();
  const words: string[] = [];
  for (const raw of rest.split(/\s+/)) {
    const word = raw.replace(/^[^\p{L}]+|[^\p{L}'’-]+$/gu, "");
    if (!word || STOP.has(word.toLowerCase())) break;
    words.push(word);
    // A mark after the word ends the name ("Marta, i porto…").
    if (words.length === MAX_WORDS || /[.,;:!?]$/.test(raw)) break;
  }
  return words.length ? words.join(" ") : null;
}

function distance(a: string, b: string): number {
  const d = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let prev = d[0];
    d[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const tmp = d[j];
      d[j] = Math.min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = tmp;
    }
  }
  return d[b.length];
}

/** Two first names that are the same once folded, or one letter apart in a name of 4 letters or more. */
const close = (a: string, b: string) => a === b || (Math.min(a.length, b.length) >= 4 && distance(a, b) <= 1);

/**
 * The name to give: a candidate (guest, name already given, glossary word) that matches what was
 * heard, whole or by first name, if only one does; otherwise what was heard, with capitals.
 */
export function matchName(heard: string, candidates: string[]): string {
  const h = fold(heard);
  const first = h.split(" ")[0];
  const unique = [...new Map(candidates.filter((c) => c.trim()).map((c) => [fold(c), c.trim()])).values()];
  const whole = unique.find((c) => fold(c) === h);
  if (whole) return whole;
  const byFirst = unique.filter((c) => close(fold(c).split(" ")[0], first));
  if (byFirst.length === 1) return byFirst[0];
  return heard
    .split(" ")
    .map((w) => w.charAt(0).toLocaleUpperCase() + w.slice(1))
    .join(" ");
}
