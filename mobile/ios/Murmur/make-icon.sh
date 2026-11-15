#!/usr/bin/env bash
# Renders Resources/icon.svg into the app icon (1024 × 1024 PNG, no transparency) with Chrome.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/Resources/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$out"
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --disable-gpu --hide-scrollbars \
  --default-background-color=0B0B0BFF --window-size=1024,1024 --screenshot="$out/AppIcon.png" "file://$here/Resources/icon.svg" 2>/dev/null
cat > "$out/Contents.json" <<'JSON'
{
  "images" : [
    { "filename" : "AppIcon.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
JSON
printf '{\n  "info" : { "author" : "xcode", "version" : 1 }\n}\n' > "$here/Resources/Assets.xcassets/Contents.json"
sips -g pixelWidth -g pixelHeight -g hasAlpha "$out/AppIcon.png" | tail -3
