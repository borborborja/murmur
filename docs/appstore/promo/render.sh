#!/bin/sh
# Les captures amb titular de l'App Store: promo.html, amb Chrome sense finestra, a docs/appstore/<idioma>/botiga/.
# Primer cal tenir les captures de l'app a docs/appstore/<idioma>/ (vegeu docs/APPSTORE.md).
set -e
cd "$(dirname "$0")"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for lang in ${@:-ca es en}; do
  mkdir -p "../$lang/botiga"
  for n in 0 1 2 3 4; do
    "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
      --allow-file-access-from-files --window-size=1206,2622 --virtual-time-budget=3000 \
      --screenshot="$PWD/../$lang/botiga/$((n + 1)).png" "file://$PWD/promo.html?lang=$lang&n=$n" 2>/dev/null
  done
  echo "$lang"
done
