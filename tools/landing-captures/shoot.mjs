// Screenshots of the real interface (Vite dev server in browser mode) with sample meetings, for the
// landing page (docs/LANDING.md). With the dev server on 8766 (cd app && npx vite --port 8766):
//   node tools/landing-captures/shoot.mjs data/captures-landing [only-names-containing]
import { spawn } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { readFileSync } from "node:fs";
import { seed, slideHtml } from "./seed.mjs";
const [OUT, ONLY] = process.argv.slice(2);
mkdirSync(OUT, { recursive: true });
const prof = mkdtempSync(`${tmpdir()}/murmur-captures-`);
const chrome = spawn("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", ["--headless=new", "--remote-debugging-port=9334", `--user-data-dir=${prof}`, "--hide-scrollbars", "about:blank"], { stdio: "ignore" });
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
let targets;
for (let i = 0; i < 40 && !targets; i++) { await wait(500); targets = await fetch("http://127.0.0.1:9334/json").then((r) => r.json()).catch(() => undefined); }
const ws = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener("open", r));
let id = 0; const pending = new Map();
ws.addEventListener("message", (e) => { const m = JSON.parse(e.data); if (pending.has(m.id)) { pending.get(m.id)(m.result ?? m.error); pending.delete(m.id); } });
const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expr) => (await send("Runtime.evaluate", { expression: expr, awaitPromise: true, returnByValue: true })).result?.value;
const size = (w, h) => send("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 2, mobile: false });
await send("Page.enable"); await send("Runtime.enable");

// What only the desktop app has (Obsidian's vaults, Notion, recording "La teva veu"), faked in the
// browser for the captures that need it: `open(lang, { mock })` leaves the config in localStorage,
// a script before the page's own takes it, and three modules are patched as Vite serves them so
// backend.ts calls the fake and the settings think they're in the app. Without `mock`, nothing changes.
await send("Page.addScriptToEvaluateOnNewDocument", { source: `(()=>{let c;try{c=JSON.parse(localStorage.getItem("mm.mock")||"null")}catch{} if(!c)return;
  const H={}; window.__mmEmit=(e,p)=>(H[e]||[]).forEach(h=>h({event:e,payload:p}));
  window.__mmListen=(e,h)=>{(H[e]||=[]).push(h);return Promise.resolve(()=>{H[e]=(H[e]||[]).filter(x=>x!==h)})};
  window.__mmInvoke=(cmd,args)=>{
    if(cmd==="record_voice")return new Promise(()=>{});
    if(cmd==="notion_find"){const q=((args&&args.query)||"").toLowerCase();return Promise.resolve(c.places.filter(p=>p.title.toLowerCase().includes(q)))}
    if(cmd in c.answers)return Promise.resolve(c.answers[cmd]);
    return Promise.reject(new Error("not in the captures: "+cmd));
  };})()` });
const PATCHES = [
  ["/src/lib/backend.ts", (b) => b.replace(/\binvoke\(/g, "(window.__mmInvoke||invoke)(").replace(/\blisten\(/g, "(window.__mmListen||listen)(")],
  ["/src/components/SettingsDialog.tsx", (b) => b.replace(/backend\.isTauri\(\)/g, "(!!window.__mmInvoke||backend.isTauri())")],
  ["/src/App.tsx", (b) => b.replace("if (backend.isTauri()) backend.obsidianVaults()", "if (!!window.__mmInvoke||backend.isTauri()) backend.obsidianVaults()")],
];
await send("Fetch.enable", { patterns: PATCHES.map(([path]) => ({ urlPattern: `*${path}*`, requestStage: "Response" })) });
ws.addEventListener("message", async (e) => {
  const m = JSON.parse(e.data);
  if (m.method !== "Fetch.requestPaused") return;
  const { requestId, request, responseStatusCode, responseHeaders } = m.params;
  const patch = PATCHES.find(([path]) => new URL(request.url).pathname === path)?.[1];
  const res = await send("Fetch.getResponseBody", { requestId });
  const body = res.base64Encoded ? Buffer.from(res.body, "base64").toString("utf8") : res.body;
  await send("Fetch.fulfillRequest", { requestId, responseCode: responseStatusCode, responseHeaders, body: Buffer.from(patch ? patch(body) : body).toString("base64") });
});
const locale = (lang, ns) => JSON.parse(readFileSync(new URL(`../../app/src/locales/${lang}/${ns}.json`, import.meta.url), "utf8"));
/** The fake Obsidian and Notion of each language. */
const MOCK = {
  ca: { vaults: ["Feina", "Personal"], folder: "Reunions", workspace: "Estudi Nord", places: ["Reunions", "Web nova", "Estudi Nord · Equip", "Notes de la setmana"], intro: "Murmur, sóc la Laia. Jo porto les xarxes." },
  es: { vaults: ["Trabajo", "Personal"], folder: "Reuniones", workspace: "Estudi Nord", places: ["Reuniones", "Web nueva", "Estudi Nord · Equipo", "Notas de la semana"], intro: "Murmur, soy Laia. Yo llevo las redes." },
  en: { vaults: ["Work", "Personal"], folder: "Meetings", workspace: "Estudi Nord", places: ["Meetings", "New website", "Estudi Nord · Team", "Weekly notes"], intro: "Murmur, I'm Laia. I look after social media." },
};
const mockFor = (lang) => {
  const x = MOCK[lang];
  const vaults = x.vaults.map((name, i) => ({ name, path: `/Users/marta/Obsidian/${name}`, open: i === 0 }));
  const places = x.places.map((title, i) => ({ id: `p${i}`, title, url: `https://www.notion.so/p${i}` }));
  return {
    config: { places, answers: { obsidian_vaults: vaults, notion_status: { connected: true, workspace: x.workspace }, notion_export: "https://www.notion.so/murmur", share_read_file: [], write_export: null, voice_status: null, list_microphones: [] } },
    settings: { obsidianVault: vaults[0].path, obsidianFolder: x.folder, obsidianAuto: true, obsidianPeople: true, notionPage: { id: places[0].id, title: places[0].title }, notionAuto: true },
  };
};

// Shared-screen slides, rendered here and stored as data URLs.
const slides = {};
await size(1280, 720);
for (const lang of ["ca", "es", "en"]) {
  slides[lang] = [];
  for (const i of [0, 1]) {
    await send("Page.navigate", { url: "data:text/html;charset=utf-8," + encodeURIComponent(slideHtml(lang, i)) });
    await wait(400);
    await send("Emulation.setDeviceMetricsOverride", { width: 1280, height: 720, deviceScaleFactor: 1, mobile: false });
    const s = await send("Page.captureScreenshot", { format: "jpeg", quality: 80 });
    slides[lang].push("data:image/jpeg;base64," + s.data);
  }
}

const W = 1440, H = 900;
const shot = async (name, clip) => {
  const s = await send("Page.captureScreenshot", { format: "png", ...(clip ? { clip: { ...clip, scale: 1 } } : {}) });
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(s.data, "base64"));
  console.log("·", name);
};
const clickText = (text, sel = "*") => ev(`(()=>{const e=[...document.querySelectorAll(${JSON.stringify(sel)})].find(e=>e.textContent.trim()===${JSON.stringify(text)}&&e.offsetParent!==null); e?.click(); return !!e})()`);

async function open(lang, { theme = "acid", mode = "light", cover = false, notes = true, panel = 340, w = W, h = H, live = null, mock = false, intro = false } = {}) {
  await size(w, h);
  const d = seed(lang, slides[lang]);
  // The sample's line in Spanish shows off "Català + castellà" on the landing; the captures of
  // what's new (with `mock` or `intro`) are all in Catalan.
  if ((mock || intro) && lang === "ca")
    for (const m of d.meetings) for (const u of m.utterances) if (u.text.startsWith("La puc fer yo")) u.text = "La puc fer jo, però necessito que em passeu els preus definitius abans de dimecres.";
  // `live`: seconds into the meeting. Only what was said by then, no summary yet.
  if (live) {
    // Only what was said by then, no summary yet, and the next line half said (the engine is still writing it).
    // The meeting leaves the stored list: it comes back through the app's own "new", as the one recording.
    const m = d.meetings.find((m) => m.title === d.title);
    const words = (m.utterances.find((u) => u.end > live)?.text ?? "").split(" ");
    d.next = words.slice(0, Math.ceil(words.length * 0.6)).join(" ");
    d.live = { ...m, status: "recording", duration: live, cover: null, utterances: m.utterances.filter((u) => u.end <= live),
      notesDoc: { ...m.notesDoc, content: m.notesDoc.content.filter((b) => b.type !== "taskList") },
      screens: m.screens.filter((x) => x.t <= live) };
    d.meetings = d.meetings.filter((x) => x !== m);
    // "Murmur, sóc la Laia": Laia has no name yet, and introduces herself in the last line.
    if (intro) {
      const { 2: _laia, ...names } = m.speakerNames;
      const last = d.live.utterances.at(-1);
      d.live.speakerNames = names;
      d.live.utterances = [...d.live.utterances, { id: "intro", start: last.end + 1, end: last.end + 6, speaker: 2, text: MOCK[lang].intro }];
      d.next = "";
    }
  }
  const fake = mock ? mockFor(lang) : null;
  await send("Page.navigate", { url: "http://localhost:8766/" });
  await wait(1500);
  await ev(`localStorage.clear();
    localStorage.setItem("transcriptor.settings.v1", ${JSON.stringify(JSON.stringify({ uiLanguage: lang, theme, mode, calendarEnabled: true, language: lang === "en" ? "en" : "ca-es", ...fake?.settings }))});
    ${fake ? `localStorage.setItem("mm.mock", ${JSON.stringify(JSON.stringify(fake.config))});` : ""}
    localStorage.setItem("transcriptor.meetings.v1", ${JSON.stringify(JSON.stringify(d.meetings))});
    localStorage.setItem("transcriptor.view.v1", ${JSON.stringify(JSON.stringify({ cover }))});
    localStorage.setItem("transcriptor.notesLayout.v1", ${JSON.stringify(JSON.stringify({ visible: notes, full: false, width: panel }))});`);
  await send("Page.reload"); await wait(2500);
  if (live) await goLive(d);
  else await clickText(d.title);
  await wait(1200);
  return d;
}
// The meeting as if it were recording now, through the app's own reducer (found in React's fiber
// tree). The recording clock (a 500 ms interval) is held still so the time shown stays at `live`.
async function goLive(d) {
  await ev(`(()=>{
    const si = window.setInterval; window.setInterval = (f, ms, ...a) => ms === 500 ? 0 : si(f, ms, ...a);
    const root = document.getElementById("root"); const key = Object.keys(root).find(k => k.startsWith("__reactContainer"));
    let dispatch, stack = [root[key]];
    while (stack.length && !dispatch) { const f = stack.pop(); let h = f.memoizedState;
      while (h && typeof h === "object" && "next" in h) { const s = h.memoizedState; if (s && s.open && "selectedId" in s && h.queue?.dispatch) dispatch = h.queue.dispatch; h = h.next; }
      if (f.sibling) stack.push(f.sibling); if (f.child) stack.push(f.child); }
    window.__mmDispatch = dispatch;
    dispatch({ type: "new", meeting: ${JSON.stringify(d.live)} });
    dispatch({ type: "partial", channel: "system", text: ${JSON.stringify(d.next)} });
  })()`);
}
// Settings › Mòbil is only in development builds: not in the captures.
const hideMobileTab = (lang) => ev(`[...document.querySelectorAll("[role=dialog] button")].find(e=>e.textContent.trim()===${JSON.stringify(locale(lang, "settings").tabs.mobile)})?.style.setProperty("display","none")`);
const want = (n) => !ONLY || n.includes(ONLY);
/** A toast through the app's own sonner (the same module instance, found among the loaded ones). */
const toastNow = (text) => ev(`(async()=>{const u=performance.getEntriesByType("resource").map(e=>e.name).find(n=>/\\/deps\\/sonner\\.js/.test(n)); (await import(u)).toast(${JSON.stringify(text)})})()`);
const openExportMenu = () => ev(`[...document.querySelectorAll('header button')].find(b=>/Export/.test(b.textContent))?.dispatchEvent(new PointerEvent("pointerdown",{bubbles:true,button:0,pointerType:"mouse"}))`);
const openSettings = async (lang, label) => {
  await ev(`document.querySelector('nav button[title]')?.click()`);
  await wait(600);
  await hideMobileTab(lang);
  await clickText(label, "[role=dialog] button");
  await wait(700);
};

/** What's new after 0.13.0 (Obsidian, Notion) and in it (La teva veu, «Murmur, sóc…»). */
async function newThings(lang, p) {
  const app = locale(lang, "app"), settings = locale(lang, "settings"), onboarding = locale(lang, "onboarding");
  if (want(p + "15-exportar-notion-obsidian")) {
    await open(lang, { mock: true });
    await openExportMenu(); await wait(500);
    await shot(p + "15-exportar-notion-obsidian");
  }
  for (const [n, label] of [["16-desat-notion", app.exportNotion], ["17-desat-obsidian", app.exportObsidian]]) {
    if (!want(p + n)) continue;
    await open(lang, { mock: true });
    await openExportMenu(); await wait(500);
    await clickText(label, "[role=menuitem]"); await wait(1500);
    await shot(p + n);
  }
  if (want(p + "18-configuracio-exportar")) {
    await open(lang, { mock: true });
    await openSettings(lang, settings.tabs.export);
    await ev(`[...document.querySelectorAll("[role=dialog] h3, [role=dialog] div, [role=dialog] p")].find(e=>e.children.length===0&&e.textContent.trim()==="Obsidian")?.scrollIntoView({block:"start"})`);
    await wait(400);
    await shot(p + "18-configuracio-exportar");
  }
  if (want(p + "19-notion-tria")) {
    await open(lang, { mock: true });
    await openSettings(lang, settings.tabs.export);
    await ev(`(()=>{const b=[...document.querySelectorAll("[role=dialog] button")].filter(e=>e.textContent.trim()===${JSON.stringify(settings.export.chooseVault)}); const last=b.at(-1); last?.click()})()`);
    await wait(1200);
    await ev(`document.querySelector("[role=dialog] ul li:last-child")?.scrollIntoView({block:"end"})`);
    await wait(400);
    await shot(p + "19-notion-tria");
  }
  if (want(p + "20-la-teva-veu")) {
    await open(lang, { mock: true });
    await openSettings(lang, settings.tabs.transcription);
    await clickText(settings.transcription.voiceRecord, "[role=dialog] button"); await wait(700);
    await ev(`document.querySelector('button[aria-label=${JSON.stringify(onboarding.voice.record)}]')?.click()`); await wait(300);
    const words = onboarding.voice.sentence.split(" ").length;
    for (let i = 1; i <= Math.round(words * 0.55); i++) { await ev(`__mmEmit("voice-progress",{said:${i},total:${words}}); __mmEmit("voice-level",{rms:${0.02 + (i % 3) * 0.015}})`); await wait(260); }
    await wait(400);
    await shot(p + "20-la-teva-veu");
  }
  if (want(p + "21-presenta-te")) {
    const d = await open(lang, { panel: 640, live: 1250, intro: true });
    await ev(`__mmDispatch({ type: "introduce", id: ${JSON.stringify(d.live.id)}, utteranceId: "intro", name: "Laia" })`);
    await toastNow(app.introNamed.replace("{{speaker}}", locale(lang, "format").speaker.replace("{{n}}", "3")).replace("{{name}}", "Laia"));
    await wait(1200);
    await shot(p + "21-presenta-te");
  }
}

for (const lang of ["ca", "es", "en"]) {
  const p = `${lang}-`;
  if (want(p + "01-reunio")) { await open(lang); await shot(p + "01-reunio"); }
  if (want(p + "02-reunio-fosc")) { await open(lang, { mode: "dark" }); await shot(p + "02-reunio-fosc"); }
  if (want(p + "05-resum-sencer")) { await open(lang, { cover: true, notes: false, h: 2000 }); await shot(p + "05-resum-sencer"); }
  if (want(p + "03-resum")) { await open(lang, { cover: true, notes: false }); await shot(p + "03-resum"); }
  if (want(p + "04-resum-fosc")) { await open(lang, { cover: true, notes: false, mode: "dark" }); await shot(p + "04-resum-fosc"); }
  await newThings(lang, p);
  if (lang !== "ca") continue;
  // Social posts: 16:9, the transcript panel wide so the conversation reads at a glance.
  for (const [n, o] of [["13-tuit-a", { panel: 560 }], ["13-tuit-b", { panel: 640 }], ["13-tuit-fosc", { panel: 560, mode: "dark" }], ["14-directe", { panel: 640, live: 1250 }], ["14-directe-fosc", { panel: 640, live: 1250, mode: "dark" }]])
    if (want(p + n)) { await open(lang, { w: 1280, h: 720, ...o }); await shot(p + n); }
  if (want(p + "06-tema-mono")) { await open(lang, { theme: "mono" }); await shot(p + "06-tema-mono"); }
  if (want(p + "07-tema-formigo")) { await open(lang, { theme: "formigo" }); await shot(p + "07-tema-formigo"); }
  if (want(p + "08-tema-formigo-fosc")) { await open(lang, { theme: "formigo", mode: "dark" }); await shot(p + "08-tema-formigo-fosc"); }
  if (want(p + "09-exportar")) {
    await open(lang);
    await ev(`[...document.querySelectorAll('header button')].find(b=>/Exporta/.test(b.textContent))?.dispatchEvent(new PointerEvent("pointerdown",{bubbles:true,button:0,pointerType:"mouse"}))`);
    await wait(500);
    await ev(`(()=>{const el=[...document.querySelectorAll('[role=menuitem]')].find(e=>e.textContent.includes("Word")); el?.focus(); el?.dispatchEvent(new KeyboardEvent("keydown",{key:"ArrowRight",bubbles:true}))})()`);
    await wait(500);
    await shot(p + "09-exportar");
  }
  if (want(p + "10-cerca")) {
    const d = await open(lang);
    await ev(`(()=>{const i=document.querySelector('nav input'); i.focus(); const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set; set.call(i,${JSON.stringify(d.search)}); i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
    await wait(1200);
    await shot(p + "10-cerca");
  }
  for (const [tab, label] of [["general", "General"], ["calendar", "Calendari"], ["transcription", "Transcripció"], ["intelligence", "Intel·ligència"], ["share", "Compartir"], ["appearance", "Aparença"]]) {
    const n = `${p}11-configuracio-${tab}`;
    if (!want(n)) continue;
    await open(lang);
    await ev(`document.querySelector('nav button[title]')?.click()`);
    await wait(600);
    await hideMobileTab(lang);
    await clickText(label, '[role=dialog] button');
    await wait(600);
    await shot(n);
  }
  if (want(p + "12-benvinguda")) {
    await size(W, H);
    await send("Page.navigate", { url: "http://localhost:8766/?window=onboarding" });
    await wait(2500);
    await shot(p + "12-benvinguda");
  }
}
ws.close(); chrome.kill();
await wait(500);
rmSync(prof, { recursive: true, force: true });
