# Validació local Android

Estat comprovat el **18 de novembre de 2026**. Referència funcional: Murmur 0.16.0, commit `cfeda3ede4528e1d2772b6eda01065611f9ef04b`. Es distingeixen les proves en **emulador ARM64 amb API 34** de les executades al **Pixel 8a físic amb Android 17**.

## Resultats verificats

| Comprovació | Resultat |
|---|---:|
| Tests Rust del host | 7 superats, 0 fallades |
| Tests JVM d'Android | 37 superats, 0 fallades, 0 omesos |
| Tests Node del pont MCP | 7 superats |
| Tests Python de preparació i signatura | 7 superats |
| Suite instrumentada Android, emulador | 10 superats |
| Proves addicionals de `RecordingService`, emulador | 2 superades |
| Motor i inferència locals, Pixel 8a físic | 5 superats, 0 omesos |
| MCP sobre TLS, Pixel 8a físic | 1 superat, 0 omesos |
| `RecordingService`, Pixel 8a físic | 2 superades |
| Recorreguts Compose, Pixel 8a físic | 4 superats amb la disposició final de l'assistent |
| Recuperació de cua en arrencada en fred, emulador | Comprovada, feina completada i servei en primer pla |
| `lintDebug` | Superat, 0 errors fatals o errors |
| APK debug | Compilat, instal·lat i obert a l'emulador |
| Alineació de l'APK debug | ELF ARM64 i entrades ZIP de les biblioteques verificades a 16 KB |
| APK release `0.16.0-android.1` | Compilat i signatura APK v2 verificada |
| Alineació de l'APK release | Les 12 biblioteques natives ARM64 i les seves entrades ZIP verificades a 16 KB |
| Instal·lació de la release al Pixel 8a | Darrera reconstrucció instal·lada i oberta en fred; peu de l'assistent completament visible |

L'artefacte signat és `dist/android/Murmur-0.16.0-android.1-arm64.apk`, de **100.181.650 bytes**. SHA-256: `1d24577cc3842f3c79f86028cd79901c4ba1937a058ec1a4c3f6f0046d270caf`. El certificat és `CN=Murmur Android, O=Independent Android port`; la seva empremta SHA-256 és `f5cedc410b8f06537295c00e39efa34e076c6e367f947753ac9c7894af2ea27f`. És una signatura pròpia d'aquesta adaptació. No s'ha publicat cap canal públic.

La suite instrumentada de deu proves executa SQLite i el catàleg Rust, Conformer amb text real, descàrrega de memòria i continuació, diarització i puntuació amb models reals, Whisper sobre tot el fitxer i per frases, detecció d'idioma, generació local de JSON amb GGUF, aparellament MCP sobre TLS i quatre fluxos Compose. Els models requerits estaven instal·lats en aquesta execució; les proves d'inferència no s'hi han comptat com a èxits per omissió.

Els quatre fluxos Compose s'han repetit a l'emulador amb la darrera disposició de l'assistent i han passat en **11,068 s**. La captura i la jerarquia de la finestra també confirmen que els dos botons del peu queden íntegres sobre la barra de gestos. Són els mateixos quatre tests, ja inclosos en la suite general: el recompte continua sent de dotze proves úniques a l'emulador.

Les dues proves addicionals de gravació han fet servir **AudioRecord, el servei en primer pla, el spool PCM i la finalització de l'àudio Opus reals**. Han comprovat que la captura continuava després de deixar l'Activity i que la reunió acabava desada amb durada i àudio; també que, amb la conservació d'àudio desactivada, s'eliminava l'Opus encara que tots els passos de processament estiguessin desactivats. Han passat en **57,168 s**. Són **12 proves úniques a l'emulador** en total: deu de la suite general i dues del servei. Aquesta execució substitueix la prova única anterior del servei, que no s'hi torna a sumar. Les sessions curtes d'emulador no acrediten una hora de bateria, captura acústica de qualitat ni funcionament físic amb pantalla bloquejada.

## Proves físiques tancades

Al Pixel 8a s'han superat, sense omissions, cinc proves de motor i inferència en **150,019 s en conjunt**: SQLite/catàleg Rust, Conformer amb transcripció i descàrrega/continuació, diarització amb puntuació, resposta JSON local de Gemma E2B i Whisper Small sobre fitxer, spans DTW i detecció d'idioma. Els models s'han transferit per USB i se n'ha comprovat cada SHA-256. Això acredita execució real d'aquests camins al dispositiu; no el corpus BSC/turbo ni la qualitat d'un resum complet.

També s'ha superat una prova física de MCP en **6,042 s**, amb TLS, mecanisme d'aprovació, respostes d'autenticació/origen 401/403, eines, pujada d'un fitxer i revocació. No hi ha hagut encara una connexió d'un client Claude real.

També han passat les dues proves físiques del servei de gravació: continuïtat de la captura després de deixar l'Activity i eliminació de l'Opus quan la conservació i tots els passos estan desactivats. Els quatre recorreguts Compose han tornat a passar amb la disposició final en **6,9 s**: descàrrega inicial del model, correcció amb ID estable, guia i cancel·lació de l'empremta de veu, i aprovació visible del connector. El total físic verificat continua sent de **dotze proves úniques**; les repeticions no s'hi tornen a sumar.

A l'emulador també s'ha comprovat separadament una arrencada en fred amb una feina persistent en espera: s'ha recuperat automàticament, ha acabat amb estat `done` i `ProcessingService` estava en primer pla. Aquesta comprovació no s'afegeix al recompte de proves instrumentades.

La release signada també s'ha instal·lat i obert en fred al Pixel, separadament de les proves instrumentades. La captura i la jerarquia de finestra físiques confirmen que els dos botons del peu de l'assistent són íntegres sobre la barra de gestos.

Els temps anteriors són durades de les suites de prova, no mesures de latència o de factor de temps real. No s'afirma cap pic global de memòria: les mostres del procés principal no inclouen per si soles els processos `:llm` i `:whisper`.

Les proves dels proveïdors remots utilitzen respostes controlades. Les del pont comproven el transport i l'autenticació; no són una sessió real de Claude Desktop o Claude Code amb el telèfon de l'usuari. Els set tests Python comproven la lògica de preparació de la signatura; l'APK real s'ha acreditat separadament amb `apksigner`, `zipalign` i el verificador de biblioteques i recursos.

## Evidència local

Els resultats generats són locals i estan ignorats pel repositori:

- `mobile/android/rust-tests.log`.
- `mobile/android/app/build/test-results/testDebugUnitTest/TEST-*.xml`.
- `mobile/android/instrumentation-final.log`, amb `OK (10 tests)`.
- `mobile/android/privacy-emulator-tests.log`, amb `OK (2 tests)`; substitueix l'execució anterior de `recording-service-test.log`.
- `mobile/android/pixel-native-tests.log`, amb `OK (5 tests)`.
- `mobile/android/pixel-mcp-test.log`, amb `OK (1 test)`.
- `mobile/android/pixel-ui-recording-tests.log`, amb les dues proves del servei superades.
- `mobile/android/pixel-final-layout-tests.log`, amb `OK (4 tests)` amb la disposició final de l'assistent.
- `mobile/android/app/build/pixel-release-onboarding-final.png` i `pixel-release-ui.xml`, amb els botons íntegres sobre la barra de gestos.
- `mobile/android/final-ui-emulator-tests.log`, amb `OK (4 tests)` amb la darrera disposició de l'assistent.
- `mobile/android/app/build/final-onboarding-emulator.png` i `.xml`, amb els botons del peu dins l'àrea útil.
- `mobile/android/queue-recovery-smoke.log`, amb recuperació en arrencada en fred superada.
- `mobile/android/pixel-model-transfer.log`, amb cada SHA-256 verificat.
- `mobile/android/pixel-cleanup.log`, amb les aplicacions debug i de proves desinstal·lades i la configuració de pantalla restaurada.
- `mobile/android/final-debug-check.log` i `mobile/android/app/build/reports/lint-results-debug.*`.
- La sortida de `mobile/android/scripts/verify-apk.py` sobre l'APK debug.
- `mobile/android/release-build.log`.
- `dist/android/Murmur-0.16.0-android.1-arm64.verification.json`, `.signature.txt` i `.apk.sha256`.

Les ordres per repetir-les són a [ANDROID.md](ANDROID.md). Un resultat local no acredita l'execució del workflow remot de GitHub Actions.

Al Pixel només queda instal·lada la release `dev.murmur.android`, oberta al primer assistent. Les aplicacions debug i de proves s'han desinstal·lat, s'han alliberat els seus **3,4 GB de models de prova** i s'ha restaurat la configuració original de pantalla. No s'hi han importat dades personals ni configurat comptes. L'usuari ha de descarregar el model base i, si vol núvol, seleccionar el proveïdor i introduir-hi la seva clau.

## Pendent de validar

- Pixel 8a físic: una hora amb pantalla apagada, bateria, temperatura, RAM, retard, Bluetooth/USB, trucades entrants i recuperació després d'interrupcions.
- Qualitat de transcripció, parlants, resum i traducció amb corpus representatiu; català central, valencià, balear, castellà i barreja. Una prova curta amb text no mesura WER ni F1.
- Comptes reals de Claude, OpenAI, OpenRouter, Ollama Cloud i Gemini; OAuth de Notion; Worker Cloudflare propi; escriptori Murmur i clients Claude reals.
- Actualització instal·lada entre dos APK de release amb la mateixa signatura i canal HTTPS real.

La matriu de [PARITAT_ANDROID.md](PARITAT_ANDROID.md) descriu el codi, les adaptacions del sistema i l'abast restant. No s'afirma que totes les funcions estiguin verificades al 100% ni que les proves d'emulador eliminin els límits de captura d'àudio, MediaProjection, permisos o segon pla d'Android.
