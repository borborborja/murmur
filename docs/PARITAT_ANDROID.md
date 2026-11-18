# Paritat de Murmur a Android

Referència: escriptori 0.16.0, commit `cfeda3ede4528e1d2772b6eda01065611f9ef04b`. Dispositiu de referència: Pixel 8a de 8 GB. Aquesta matriu diferencia implementació, proves repetibles i validació amb dispositius o serveis reals. «Implementat» no vol dir que el comportament estigui acreditat en producció.

La [validació del 18/11/2026](ANDROID_VALIDACIO.md) registra 7 tests Rust, 37 JVM, 7 Node i 7 Python, més **12 proves instrumentades úniques a l'emulador** i **12 al Pixel 8a físic amb Android 17**, totes superades. Les dotze físiques cobreixen cinc camins de motor/inferència, un de MCP sobre TLS, dos del servei de gravació i quatre recorreguts Compose. Aquests darrers han tornat a passar amb la disposició final de l'assistent a l'emulador i al Pixel; les repeticions no afegeixen proves úniques al recompte.

`lintDebug` no presenta errors. Els APK debug i release tenen l'alineació ELF/ZIP de 16 KB verificada. La release reconstruïda `Murmur-0.16.0-android.1-arm64.apk`, de **100.181.650 bytes**, està compilada, signada, instal·lada i oberta al Pixel, amb SHA-256 `1d24577cc3842f3c79f86028cd79901c4ba1937a058ec1a4c3f6f0046d270caf`. La captura física confirma que els botons de l'assistent són íntegres sobre la barra de gestos. La sessió llarga, les mesures globals de recursos i els comptes reals continuen pendents.

## Funcions i comprovacions

| Capacitat de referència | Implementació Android | Evidència o prova necessària |
|---|---|---|
| Directe català, castellà, barreja i anglès | Nucli Rust real amb Conformer int8 o Parakeet; text parcial i final | Conformer real provat a l'emulador i al Pixel; els quatre modes i la precisió requereixen corpus específic |
| Micròfon i àudio del sistema separats, AEC | AudioRecord, captura compatible de reproducció i Pipeline de dues fonts | Prova de dos canals; límits de trucades protegides a sota |
| Gravar amb pantalla apagada | Servei en primer pla, notificació i spool PCM privat abans de la inferència | Prova curta real d'AudioRecord/PCM/Opus continuant sense l'Activity superada a l'emulador i al Pixel; una hora amb pantalla apagada pendent |
| Continuar i pausar reunions | Recuperació de la reunió, identitat de parlants i parts temporals; pausa visible a la transcripció | Continuació/descarregar motor provades al Pixel amb un fitxer de referència; sessió de micròfon real pendent |
| Edició de transcripció | IDs estables i flag `edited`; la revisió compara el text de partida abans d'aplicar resultats | `UiWorkflowTest`, proves de preservació i corpus d'exportació |
| Parlants, canvi de nom, assignació i fusió | Operacions natives i bloqueig manual, noms del calendari i alta d'un parlant | Proves Rust de dades i proves JVM d'edicions; precisió amb veus reals pendent |
| «Murmur, sóc…» | Detecció del prefix, normalització de noms i propagació al parlant | Casos catalans, castellans i anglesos a `MeetingEditsTest` |
| Empremta de veu | Enrolament i reconeixement locals amb TitaNet; eliminació des de configuració | Micròfon i identitat al Pixel pendents; cap empremta al núvol |
| Glossari | Termes del motor i proposta d'afegir correccions manuals | Tests Rust i de reconeixement de noms; interacció de l'usuari |
| Diarització final | Models originals pyannote i ERes2Net per canal | Models reals executats a l'emulador i al Pixel; comparació d'identitats i F1 amb reunions reals pendent |
| Whisper final i retranscripció | whisper.cpp real en procés separat; revisió per spans de les frases sense editar | Whisper Small provat al Pixel per fitxer, spans DTW i detecció d'idioma; BSC/turbo i WER català/dialectes pendents |
| Puntuació Softcatalà | Runtime ONNX complet i model opcional, català o barreja | Model real executat a l'emulador i al Pixel; qualitat sobre corpus llarg pendent |
| Tria de passos i feina ajornada | Cua persistent, passos pendents, cancel·lació i recuperació | Recuperació automàtica d'una feina després d'una arrencada en fred i servei en primer pla comprovats a l'emulador; concurrència i manca de models pendents |
| Resum complet | Prompts i esquemes de títol, extracte, to, resum, decisions, tasques i parts | Validació d'esquemes JVM; qualitat i fidelitat amb models reals pendent |
| Edicions i resum obsolet | Conservació de tasques manuals/completades, noms propagats i avís de resum anterior als canvis | `MeetingEditsTest`; modificacions concurrents durant la generació |
| Traducció amb original intacte | Resultat per ID, instantània d'origen, progrés i recuperació parcial | Prova de línia corregida, absència de traducció i error parcial |
| Model local autònom | llama.cpp separat; Gemma E2B per defecte, E4B opcional; context limitat | JSON amb Gemma E2B generat a l'emulador i al Pixel; resum complet, qualitat i mesures globals de RAM/bateria pendents |
| Claude/OpenAI/OpenRouter/Gemini | Catàlegs, claus protegides, model triat i respostes estructurades | `CloudClientTest` amb servidor controlat; cada compte real pendent |
| Ollama Cloud | API directa, catàleg dinàmic, clau pròpia i validació/reparació de JSON | Proves de protocol; la manca d'esquemes garantits és explícita |
| Notes riques i minuts | Editor TipTap empaquetat sense xarxa, extensions originals, zoom i format | Bundle generat, roundtrip de dades; prova WebView en dispositiu |
| Fotos, captures i moments marcats | CameraX només d'imatge, MediaProjection opcional i marques temporals | Proves sense interferir el micròfon; contingut protegit no capturable |
| Biblioteca, cerca i etiquetes | SQLite/FTS5 compartit, cerca amb salts, filtres i gestió global d'etiquetes | SQLite/catàleg/dades reals provats al Pixel; recorregut visual físic pendent |
| Reproducció, salts i velocitats | MediaPlayer real, estat visible i velocitats 1/1,25/1,5/2 | Àudio exportat i retorn entre pestanyes; diferents codecs |
| Markdown, Word, PDF i adjunts | Exportadors, selector Android, impressió a PDF, àudio i ZIP de captures | Comparació de contingut i obertura en aplicacions reals |
| SRT i WebVTT originals o traduïts | Cues, salts de parlant i límits de durada/línies | `SubtitleTest`; els temps de paraula són aproximacions com a l'original |
| Markdown automàtic i Obsidian | SAF, permisos persistents, propietats i adjunts | Carpeta real, reexportació i permís revocat pendents |
| Notion | OAuth públic amb PKCE, refresh serialitzat, selector i exportació | Proves de components; compte real i renovació del token pendents |
| Enllaços Cloudflare | Mateix visor i envelope xifrat v1, contrasenya, caducitat, adjunts i revocació | `ShareCryptoTest`; Worker real propi pendent |
| Calendari i avisos | CalendarContract, convidats, selector i alarmes | Calendaris reals, zona horària, reinici i estalvi de bateria pendents |
| Barra flotant i accés ràpid | Superposició opcional, notificació i mosaic de configuració ràpida | Adaptació nativa; els detalls de finestra d'escriptori no s'han de donar per equivalents sense comprovar-los |
| Sincronització original | QR/Noise v1, mDNS, resumible, manual i intents automàtics | Proves de protocol i `fake_computer`; escriptori real i xarxa canviant pendents |
| Connector Claude directe | HTTPS local, pin de certificat, clients aprovats/revocables i pont stdio | Pont Node i proves TLS/aprovació/401/403/eines/pujada/revocació al Pixel superats; clients Claude reals pendents |
| Temes, modes i tres llengües | Tokens originals, Space Grotesk, català/castellà/anglès i recursos locals | Claus traduïdes verificades i proves Compose; revisió visual dels sis modes |
| Actualitzacions | Canal Android configurable, SHA-256 i certificat abans de l'instal·lador | Verificador i prova de dos APK amb la mateixa signatura; cap canal públic publicat |
| Privadesa i registres | Keystore, còpies desactivades, logs tècnics limitats, analítica pròpia opcional i conservació d'àudio configurable | Eliminació real de l'Opus provada a l'emulador i al Pixel amb conservació i tots els passos desactivats; inspecció de trànsit/logs i permisos pendent |

## Adaptacions inevitables

1. Android estàndard no concedeix captura universal de telèfon, Meet, Zoom o Teams. Només es capturen reproduccions que ho permetin; un conflicte del micròfon pot produir silenci i s'ha de mostrar.
2. El sistema demana consentiment per MediaProjection i pot acabar-lo en bloquejar la pantalla. Les finestres segures no es poden copiar.
3. La detecció automàtica fiable d'una reunió d'una altra app es substitueix per calendari i inici manual. No s'utilitzen permisos d'accessibilitat ni lectura de notificacions per simular-la.
4. Les carpetes i els vaults s'escullen explícitament. Els calendaris són els que hi ha sincronitzats al dispositiu. L'exactitud del recordatori depèn del permís i de la gestió d'energia.
5. El connector Claude usa un pont local a l'ordinador; no un connector públic del núvol. La sincronització conserva l'abast mòbil→escriptori→resultats, sense inventar un mirall bidireccional de tota la biblioteca.
6. La instal·lació d'actualitzacions passa per Android i manté la signatura. Els models grans són opcionals; la qualitat d'un perfil lleuger es mesura separadament de la del model original.

## Criteri per donar la paritat per validada

- Compilar l'APK amb tots els motors reals, verificar l'alineació de 16 KB i executar els tests Rust, JVM i del pont.
- Executar proves instrumentades sense ometre els models que es volen acreditar. Registrar explícitament els tests omesos.
- Verificar una reunió física d'una hora al Pixel 8a, sense pèrdua de dades, amb pantalla apagada i recuperació de processos. Mesurar retard, RAM, temperatura i bateria.
- Contrastar català central, valencià, balear, castellà i barreja amb transcripcions i parlants de referència. Mesurar el perfil compacte abans d'atribuir-li qualitat equivalent.
- Obrir les exportacions i comprovar els enllaços amb les aplicacions i serveis reals. Fer OAuth, sincronització i consultes Claude amb comptes i xarxa de l'usuari.
- Comprovar que les correccions manuals, les tasques i els noms sobreviuen a totes les passades i als resultats de l'ordinador. Documentar qualsevol diferència observada abans d'anomenar-la resolta.

Les ordres de compilació i verificació són a [ANDROID.md](ANDROID.md). El workflow d'Actions és una configuració de CI, no una afirmació que ja s'hagi executat remotament.
