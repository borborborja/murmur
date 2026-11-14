/**
 * The aurora (docs/design/DESIGN.md › Post-procés): when a finished meeting's text or cover comes in,
 * a soft light in the speakers' colours goes down what's on screen. Above it the new content is
 * there; inside it everything is blurred, tinted and pushed down a little; below it the old
 * content remains until the light gets there.
 *
 * It doesn't touch how the content is drawn: right before the change, a copy of what's there now
 * goes on top of it, opaque; React draws the new content underneath; the light then trims the copy
 * away from the top. Markup: a `data-reveal-host` (positioned) around a `data-reveal="<key>"
 * data-meeting="<id>"`, inside a scrolling `data-reveal-viewport`.
 */

/** Positions of the light, top to bottom: purple, pink, orange, yellow, acid (speaker colours, then the accent). */
const STOPS = ["--mm-speaker-1", "--mm-speaker-4", "--mm-speaker-2", "--mm-reveal-warm", "--mm-accent"] as const;
const DURATION_MS = 1800;
/** The light's height, as a share of the visible area. */
const HEIGHT = 0.46;
/** How far a block is pushed down when the light is on it (px), and stretched. */
const PUSH_PX = 7;
const PUSH_SCALE = 0.015;

const running = new WeakSet<HTMLElement>();

export type RevealKey = "transcript" | "cover";

/** What gets pushed: each line of the transcript, each part of the cover. */
const BLOCKS: Record<RevealKey, string> = {
  transcript: "[data-utterance-id]",
  cover: ":scope > *",
};

/**
 * `change` updates the content (a dispatch). If that content is on screen, it's revealed with the
 * aurora; otherwise (another meeting open, reduced motion, already revealing) it just changes.
 */
export function reveal(key: RevealKey, meetingId: string, change: () => void) {
  const el = document.querySelector<HTMLElement>(`[data-reveal="${key}"][data-meeting="${CSS.escape(meetingId)}"]`);
  const host = el?.closest<HTMLElement>("[data-reveal-host]");
  const viewport = el?.closest<HTMLElement>("[data-reveal-viewport]");
  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (!el || !host || !viewport || running.has(el) || reduced || !el.offsetHeight) {
    change();
    return;
  }
  running.add(el);
  const copy = el.cloneNode(true) as HTMLElement;
  copy.removeAttribute("data-reveal");
  copy.setAttribute("aria-hidden", "true");
  copy.inert = true;
  Object.assign(copy.style, {
    position: "absolute",
    top: `${el.offsetTop}px`,
    left: `${el.offsetLeft}px`,
    width: `${el.offsetWidth}px`,
    background: "var(--mm-background)",
    pointerEvents: "none",
    zIndex: "4",
  });
  host.appendChild(copy);
  change();
  // Once React has drawn the new content.
  requestAnimationFrame(() => requestAnimationFrame(() => void play(key, el, copy, host, viewport).finally(() => running.delete(el))));
}

async function play(key: RevealKey, el: HTMLElement, copy: HTMLElement, host: HTMLElement, viewport: HTMLElement) {
  const light = document.createElement("div");
  light.className = "mm-reveal";
  light.setAttribute("aria-hidden", "true");
  host.appendChild(light);
  const style = getComputedStyle(document.documentElement);
  const stops = STOPS.map((name) => rgb(style.getPropertyValue(name).trim()));
  const blocks = [...el.querySelectorAll<HTMLElement>(BLOCKS[key]), ...copy.querySelectorAll<HTMLElement>(BLOCKS[key])];
  // The new content may be longer: the copy covers all of it until the light gets there.
  copy.style.minHeight = `${el.offsetHeight}px`;
  const ease = (p: number) => 1 - Math.pow(1 - p, 2.2);
  const start = performance.now();

  await new Promise<void>((done) => {
    const frame = (now: number) => {
      const p = Math.min(1, (now - start) / DURATION_MS);
      const view = viewport.getBoundingClientRect();
      const hostTop = host.getBoundingClientRect().top;
      const height = view.height * HEIGHT;
      // The light's centre, in the host's coordinates: from just above the visible area to just below it.
      const centre = view.top - hostTop - height * 0.25 + ease(p) * (view.height + height * 0.1);
      light.style.height = `${height}px`;
      light.style.transform = `translateY(${centre - height / 2}px)`;
      light.style.opacity = String(Math.min(1, p * 5) * (p > 0.85 ? (1 - p) / 0.15 : 1));
      const [a, b, c] = colours(stops, p);
      light.style.setProperty("--mm-reveal-1", a);
      light.style.setProperty("--mm-reveal-2", b);
      light.style.setProperty("--mm-reveal-3", c);
      copy.style.clipPath = `inset(${Math.max(0, centre - copy.offsetTop)}px 0 0 0)`;
      for (const block of blocks) {
        const r = block.getBoundingClientRect();
        const k = Math.exp(-(((r.top + r.height / 2 - hostTop - centre) / (height * 0.32)) ** 2));
        block.style.transform = k > 0.01 ? `translateY(${(k * PUSH_PX).toFixed(2)}px) scaleY(${(1 + k * PUSH_SCALE).toFixed(4)})` : "";
      }
      if (p < 1) requestAnimationFrame(frame);
      else done();
    };
    requestAnimationFrame(frame);
  });
  for (const block of blocks) block.style.transform = "";
  copy.remove();
  light.remove();
}

/** "#6A55F5" or "rgb(…)" to [r, g, b]. */
function rgb(value: string): [number, number, number] {
  if (value.startsWith("#")) {
    const hex = value.slice(1, 7);
    return [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number];
  }
  const [r, g, b] = value.match(/[\d.]+/g)?.map(Number) ?? [0, 0, 0];
  return [r, g, b];
}

/** Three colours for the light at position `p` (0–1): the stop it's at, blended into the next, and their neighbours. */
function colours(stops: [number, number, number][], p: number): [string, string, string] {
  const at = (q: number) => {
    const f = Math.min(1, Math.max(0, q)) * (stops.length - 1);
    const i = Math.min(stops.length - 2, Math.floor(f));
    const t = f - i;
    return `rgb(${stops[i].map((v, k) => Math.round(v + (stops[i + 1][k] - v) * t)).join(",")})`;
  };
  return [at(p - 0.12), at(p), at(p + 0.12)];
}
