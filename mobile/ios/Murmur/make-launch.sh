#!/usr/bin/env bash
# The launch screen (Info.plist UILaunchScreen): the logo's tile at 112 pt, drawn exactly as the
# app's splash draws it (Shared/Logo.swift LogoTile: the corner at 26 %, the mark at 78 %), so the
# app takes over without a jump; and the background, the acid theme's (Theme.swift), in light and
# dark. Rendered with Chrome, as make-icon.sh.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
assets="$here/Resources/Assets.xcassets"
tmp="$(mktemp -d)"
# 112 pt tile; the mark is the 140-unit box from 30, fitted to 87.36 pt and centred.
cat > "$tmp/launch.svg" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" width="336" height="336" viewBox="0 0 112 112">
  <rect width="112" height="112" rx="29.12" fill="#0B0B0B"/>
  <g transform="translate(12.32 12.32) scale(0.624) translate(-30 -30)" fill="none" stroke-linecap="round" stroke-linejoin="round">
    <path d="M60 140 V100 a18 18 0 0 1 36 0 V140 M96 100 a18 18 0 0 1 36 0 V140" transform="translate(15 -15)" stroke="#D4FF3A" stroke-width="17"/>
    <path d="M60 140 V100 a18 18 0 0 1 36 0 V140 M96 100 a18 18 0 0 1 36 0 V140" stroke="#0B0B0B" stroke-width="27"/>
    <path d="M60 140 V100 a18 18 0 0 1 36 0 V140 M96 100 a18 18 0 0 1 36 0 V140" stroke="#F5F5F5" stroke-width="17"/>
  </g>
</svg>
SVG
mkdir -p "$assets/LaunchMark.imageset" "$assets/LaunchBackground.colorset"
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --disable-gpu --hide-scrollbars \
  --default-background-color=00000000 --window-size=336,336 --screenshot="$assets/LaunchMark.imageset/LaunchMark@3x.png" "file://$tmp/launch.svg" 2>/dev/null
cat > "$assets/LaunchMark.imageset/Contents.json" <<'JSON'
{
  "images" : [
    { "filename" : "LaunchMark@3x.png", "idiom" : "universal", "scale" : "3x" }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
JSON
# The acid theme's background (Theme.swift: acidLight, acidDark).
cat > "$assets/LaunchBackground.colorset/Contents.json" <<'JSON'
{
  "colors" : [
    { "idiom" : "universal", "color" : { "color-space" : "srgb", "components" : { "red" : "1.000", "green" : "1.000", "blue" : "1.000", "alpha" : "1.000" } } },
    { "idiom" : "universal", "appearances" : [ { "appearance" : "luminosity", "value" : "dark" } ],
      "color" : { "color-space" : "srgb", "components" : { "red" : "0.055", "green" : "0.055", "blue" : "0.055", "alpha" : "1.000" } } }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
JSON
rm -rf "$tmp"
sips -g pixelWidth -g pixelHeight -g hasAlpha "$assets/LaunchMark.imageset/LaunchMark@3x.png" | tail -3
