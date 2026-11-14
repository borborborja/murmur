"""Final pass by Catalan variant: the live Conformer against Whisper models, run exactly as the
app runs them (murmur-whisper: utterances spliced into 28 s chunks, words placed by DTW).

    uv run python -m bench.dialects manifest.json --whisper app/whisper/target/release/murmur-whisper \\
        --model turbo=models/whisper-ggml/ggml-large-v3-turbo-q5_0.bin \\
        --model valencia=models/whisper-ggml/ggml-3cat-valencian-q5_0.bin

The manifest is a list of clips: {"set": "valencia-cv", "lang": "ca", "audio": "<file>",
"ref": "<text>"}. A short clip is one utterance; a longer one (over 25 s) is cut into utterances
with Silero VAD, as the live pipeline does, and its hypothesis is their texts joined. Output: WER per
set and engine (normalized, without punctuation: the BSC models don't write it) and the time each
Whisper model took, in results/dialects/report.json.
"""

import argparse
import json
import re
import subprocess
import tempfile
import time
from collections import defaultdict
from pathlib import Path

import jiwer
import numpy as np
from num2words import num2words

from bench.metrics import normalize

RATE = 16_000
GAP_S = 1.0


def load(path: str) -> np.ndarray:
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
        check=True,
        capture_output=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.int16)


LONG_S = 25.0


def utterances(clip: np.ndarray) -> list[np.ndarray]:
    if len(clip) / RATE <= LONG_S:
        return [clip]
    from bench.audio import vad_segments

    segments = vad_segments(clip.astype(np.float32) / 32768, Path("models/silero_vad.onnx"))
    return [clip[int(s.start * RATE) : int(s.end * RATE)] for s in segments] or [clip]


def whisper(binary: str, model: str, lang: str, clips: list[list[np.ndarray]]) -> tuple[list[list], float]:
    """All clips of one language as one channel, each utterance a span."""
    gap = np.zeros(int(GAP_S * RATE), dtype=np.int16)
    spans, parts, t = [], [], 0.0
    for utts in clips:
        for c in utts:
            spans.append({"start": t, "end": t + len(c) / RATE})
            parts += [c, gap]
            t += len(c) / RATE + GAP_S
    with tempfile.NamedTemporaryFile(suffix=".pcm") as pcm:
        pcm.write(np.concatenate(parts).tobytes())
        pcm.flush()
        job = json.dumps({"channels": [{"pcm": pcm.name, "spans": spans}]})
        start = time.monotonic()
        out = subprocess.run([binary, model, lang], input=job, capture_output=True, text=True, check=True)
        elapsed = time.monotonic() - start
    by_start = {}
    for line in out.stdout.splitlines():
        msg = json.loads(line)
        for item in msg.get("texts", []):
            by_start[round(item["start"], 2)] = item["text"]
    # Left out (None) = Whisper heard nothing or went wrong there: the app keeps the live text.
    flat = iter(by_start.get(round(s["start"], 2)) for s in spans)
    return [[next(flat) for _ in utts] for utts in clips], elapsed


def spell_numbers(text: str, lang: str) -> str:
    """Common Voice spells numbers out and Whisper writes digits: "23" is "vint-i-tres"."""
    return re.sub(r"\d+", lambda m: f" {num2words(int(m.group()), lang=lang)} ", text)


def wer(refs: list[str], hyps: list[str], lang: str) -> float:
    pairs = [(normalize(spell_numbers(r, lang)), normalize(spell_numbers(h or "", lang))) for r, h in zip(refs, hyps)]
    pairs = [(r, h) for r, h in pairs if r]
    return jiwer.wer([r for r, _ in pairs], [h for _, h in pairs])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest")
    ap.add_argument("--whisper", required=True)
    ap.add_argument("--model", action="append", default=[], help="name=path to a ggml model")
    ap.add_argument("--out", default="results/dialects")
    ap.add_argument("--sets", help="only these sets, comma-separated")
    ap.add_argument("--rescore", action="store_true", help="only score the texts already in --out")
    args = ap.parse_args()
    if args.rescore:
        return rescore(Path(args.out))

    clips = json.loads(Path(args.manifest).read_text())
    sets: dict[str, list[dict]] = defaultdict(list)
    for c in clips:
        if not args.sets or c["set"] in args.sets.split(","):
            sets[c["set"]].append(c)
    audio = {c["audio"]: load(c["audio"]) for c in clips}

    from bench.engines import ConformerCaEsRnnt

    live = ConformerCaEsRnnt()
    report: dict = {}
    for name, items in sets.items():
        lang = items[0]["lang"]
        refs = [c["ref"] for c in items]
        samples = [utterances(audio[c["audio"]]) for c in items]
        minutes = sum(len(u) for utts in samples for u in utts) / RATE / 60
        row = {"lang": lang, "clips": len(items), "minutes": round(minutes, 1), "wer": {}, "seconds": {}}
        if lang in ("ca", "es"):
            hyps = [[live.model.recognize(u.astype(np.float32) / 32768, sample_rate=RATE) for u in utts] for utts in samples]
            live_texts = [" ".join(h) for h in hyps]
            row["wer"]["live"] = round(wer(refs, live_texts, lang), 4)
            write(Path(args.out) / f"{name}.live.txt", refs, live_texts)
        else:
            hyps = [[None] * len(utts) for utts in samples]
        for spec in args.model:
            model_name, path = spec.split("=", 1)
            per_utt, elapsed = whisper(args.whisper, path, lang, samples)
            # As in the app: utterances Whisper left out keep the live text.
            texts = [" ".join(t if t is not None else (h or "") for t, h in zip(ts, hs)) for ts, hs in zip(per_utt, hyps)]
            row["wer"][model_name] = round(wer(refs, texts, lang), 4)
            row["seconds"][model_name] = round(elapsed, 1)
            write(Path(args.out) / f"{name}.{model_name}.txt", refs, texts)
        report[name] = row
        print(name, json.dumps(row, ensure_ascii=False), flush=True)
    path = Path(args.out, "report.json")
    old = json.loads(path.read_text()) if path.exists() else {}
    for name, row in report.items():
        if name in old:
            row["wer"] = {**old[name]["wer"], **row["wer"]}
            row["seconds"] = {**old[name]["seconds"], **row["seconds"]}
    path.write_text(json.dumps({**old, **report}, indent=2, ensure_ascii=False) + "\n")


def write(path: Path, refs: list[str], texts: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(f"REF {r}\nHYP {t or ''}\n" for r, t in zip(refs, texts)))


def rescore(out: Path) -> None:
    """WER of every <set>.<engine>.txt in `out`, by set."""
    table: dict[str, dict[str, float]] = defaultdict(dict)
    for f in sorted(out.glob("*.txt")):
        name, engine = f.stem.rsplit(".", 1)
        blocks = [b.split("\n") for b in f.read_text().split("\n\n") if b.strip()]
        refs = [b[0][4:] for b in blocks]
        hyps = [b[1][4:] if len(b) > 1 else "" for b in blocks]
        lang = "es" if name.startswith("es") else "en" if name.startswith("en") else "ca"
        table[name][engine] = round(wer(refs, hyps, lang), 4)
    for name, row in table.items():
        print(name, json.dumps(row))


if __name__ == "__main__":
    main()
