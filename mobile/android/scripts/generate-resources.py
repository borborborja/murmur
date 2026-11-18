#!/usr/bin/env python3
"""Keep Android's bundled translations and palettes in sync with the desktop sources."""
import json
import shutil
from pathlib import Path
from xml.sax.saxutils import escape

android = Path(__file__).resolve().parent.parent
repo = android.parent.parent
assets = android / "app/src/main/assets"
res = android / "app/src/main/res"
locales = {lang: json.loads((android / "i18n" / f"{lang}.json").read_text(encoding="utf-8")) for lang in ("ca", "es", "en")}
keys = set(locales["ca"])
for lang, values in locales.items():
    if set(values) != keys:
        raise SystemExit(f"Android translation keys differ for {lang}: {keys ^ set(values)}")
    target = assets / "locales" / lang
    target.mkdir(parents=True, exist_ok=True)
    for source in (repo / "app/src/locales" / lang).glob("*.json"):
        shutil.copyfile(source, target / source.name)
    (target / "android.json").write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    intelligence = json.loads((target / "cover.json").read_text(encoding="utf-8"))
    intelligence["errors"] = json.loads((android / "i18n/errors" / f"{lang}.json").read_text(encoding="utf-8"))
    for namespace in ("app", "export", "share"):
        intelligence[namespace] = json.loads((target / f"{namespace}.json").read_text(encoding="utf-8"))
    intelligence_target = assets / "intelligence"
    intelligence_target.mkdir(parents=True, exist_ok=True)
    (intelligence_target / f"{lang}.json").write_text(json.dumps(intelligence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    destination = res / ("values" if lang == "ca" else f"values-{lang}")
    destination.mkdir(parents=True, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for key, value in values.items():
        value = escape(value).replace("'", "\\'").replace('"', '\\"')
        lines.append(f'    <string name="{key}" formatted="false">{value}</string>')
    lines.append('</resources>')
    (destination / "strings.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")
shutil.copyfile(repo / "docs/design/tokens/murmur-tokens.json", assets / "murmur-tokens.json")
share = assets / "share"
share.mkdir(parents=True, exist_ok=True)
for name in ("worker.js", "viewer.html", "viewer.css", "viewer-script.js"):
    shutil.copyfile(repo / "share/src" / name, share / name)
licenses = assets / "licenses/generated"
licenses.mkdir(parents=True, exist_ok=True)
shutil.copyfile(repo / "LICENSE", licenses / "Murmur.txt")
print(f"Android resources: {len(keys)} strings x 3 languages; original locales and palette copied")
