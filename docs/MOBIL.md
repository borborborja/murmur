# Murmur per a mòbil (exploració)

Idea: una versió «light» per a iOS i Android que només faci la transcripció en directe, i que després enviï la reunió a l'ordinador per a la passada final (parlants, Whisper i resum). Aquest document recull què sabem i què cal provar abans de decidir res. Encara no hi ha codi.

## El model: sí

- El Conformer ca-es ja existeix en **int8, 142 MB** (huggingface.co/quicodomingo/murmur-models, `conformer-ca-es-sherpa/*.int8.onnx`), al costat del fp32 (499 MB). En un mòbil hi cap de sobres, de disc i de memòria.
- **sherpa-onnx**, el motor que fem servir, té suport oficial per a Android (Kotlin/Java) i iOS (Swift), amb el mateix transductor offline i el mateix Silero VAD.
- Velocitat esperada: a l'M4, l'int8 fa RTF 0,039. Un mòbil bo (A17, Snapdragon 8 Gen 2) deu ser de 3 a 6 vegades més lent: RTF 0,1–0,25. N'hi ha prou per al text definitiu de cada frase; el text provisional (ara cada 0,6 s) és el que més gasta, i al mòbil s'hauria de fer menys sovint. **És una estimació: cal mesurar-ho.**
- Els parlants en directe (models de ~40 MB) també hi caben. Whisper i Gemma no: es queden a l'ordinador.

## El que de debò limita

1. **Només el micròfon.** Ni iOS ni Android deixen gravar l'àudio d'una trucada d'una altra app (Zoom, Meet, Teams). El mòbil és per a **reunions presencials**, amb el telèfon sobre la taula.
2. **Segon pla i pantalla apagada.** iOS: mode d'àudio en segon pla (amb l'indicador taronja). Android: un servei en primer pla amb notificació. Totes dues necessiten configuració nativa.
3. **Bateria i temperatura** en una hora d'inferència contínua. Amb menys text provisional hauria d'anar bé; cal mesurar-ho.

## Com fer l'app

- **Tauri 2 mobile** (recomanat per provar primer): compila per a iOS i Android, i reaprofitaria la interfície de React i el motor en Rust (`pipeline.rs`, `engine.rs`). Riscos: que sherpa-onnx compili per a mòbil des de Rust (`sherpa-onnx-sys`), que la captura d'àudio de `cpal` funcioni a iOS (CoreAudio) i Android (AAudio), i el segon pla, que demana codi natiu.
- **Pla B:** apps natives a partir dels exemples oficials de sherpa-onnx en Swift i Kotlin.

## Proposta funcional

El mòbil és un **company de l'ordinador**, no una còpia de l'app: grava reunions presencials amb el text en directe, i l'ordinador hi fa la passada final (Whisper, parlants, resum), que torna al mòbil.

### Gravar
- Botó gran per gravar i el recordatori de consentiment en començar, com a l'ordinador.
- Text en directe amb els colors dels parlants. «Murmur, sóc la Marta» hi funciona igual. «La teva veu» es grava al mateix mòbil: l'empremta és biomètrica i no viatja entre aparells.
- Notes ràpides amb el minut, i un botó per marcar un moment com a important.
- **Fotos amb el minut:** la pissarra, una diapositiva projectada, un paper. Són l'equivalent de les captures de l'ordinador (`capture.rs`): van a la reunió com una captura més, amb el seu minut, i a l'ordinador surten sota la frase d'aquell moment i a la portada.
  - No toquen el micròfon. La càmera es fa servir dins de l'app i només per a fotos: a iOS, una `AVCaptureSession` sense entrada d'àudio no canvia la sessió d'àudio; a Android, CameraX (`ImageCapture`) no fa servir el micròfon, i la gravació continua al servei en primer pla. El que no es pot fer és vídeo, que sí que demana el micròfon.
  - El so de l'obturador queda gravat; és un soroll curt que no afecta la transcripció.
  - També es poden afegir després fotos fetes amb la càmera del sistema: es col·loquen al minut segons l'hora de la foto (EXIF), si són de durant la reunió.
- Amb la pantalla bloquejada: Live Activity a l'iPhone (Dynamic Island, temps i botó d'aturar) i notificació fixa a Android. Per començar d'un toc: widget, drecera de Siri, botó d'acció.
- El calendari del telèfon per posar títol a la reunió i avisar quan comença.

### Després
- Al mòbil queden l'àudio, la transcripció del directe i les fotos, que ja es poden llegir i buscar.
- La reunió passa a l'ordinador, que hi fa la passada final i en retorna el resultat. Mentrestant, la reunió diu «Pendent de l'ordinador».
- L'àudio i les fotos no s'esborren del mòbil fins que l'ordinador confirma que els té.

### Emparellar amb l'ordinador (des de la primera versió)
- L'ordinador ensenya un codi QR (Configuració, i un pas opcional de l'onboarding del mòbil). El QR porta l'adreça i una clau; a partir d'aquí, els dos aparells es reconeixen i tot el que s'envien va xifrat amb aquesta clau.
- Es troben per la xarxa local (Bonjour / mDNS), sense cap servidor de Murmur. Funciona entre plataformes: iPhone amb Windows, Android amb Mac.
- Es sincronitza en totes dues direccions: les reunions noves del mòbil van a l'ordinador, i els resultats (text revisat, parlants, resum) tornen al mòbil. Si l'ordinador no hi és, les reunions esperen al mòbil.
- Es pot desemparellar des de tots dos costats.
- El menú Compartir (AirDrop, etc.) queda com a alternativa per a qui no vulgui emparellar.
- Permisos: «Xarxa local» a iOS; a l'ordinador, el tallafoc pot demanar permís la primera vegada.
- Si l'ordinador rep l'àudio, la transcripció del mòbil només serveix per llegir-la en directe: l'ordinador en fa una de millor.

### Fora del mòbil
- L'àudio de les trucades (el sistema no ho deixa), Whisper, Gemma i el connector per a Claude.
- Més endavant, opcional: resum des del mòbil amb la clau pròpia de Claude o ChatGPT, desada al clauer del telèfon i enviant només text.

### Fases
| Fase | Què hi ha |
|---|---|
| 0 | La prova d'una tarda (a sota): velocitat, qualitat i bateria |
| 1 | Gravar, text en directe, parlants, notes, fotos amb el minut, pantalla bloquejada, emparellar i sincronitzar |
| 2 | Llegir al mòbil les reunions gravades a l'ordinador, fotos de la galeria per hora |
| 3 | Resum al núvol amb la clau pròpia, widgets, rellotge |

### Decidit
- **El mòbil funciona sol.** Sense ordinador, la reunió es queda amb la transcripció ràpida (Conformer, parlants en directe, notes i fotos). Emparellat, l'ordinador hi afegeix la passada final i el resum.
- **Primer iOS** (iPhone i iPad), que és el que tenim per provar. Android després.
- **Apps natives amb el motor compartit**, no Tauri. Tauri pinta la interfície en una pàgina web, i el que fa que una app se senti d'iOS (Liquid Glass, barres que el sistema col·loca, les size classes, `ArrangementView` i les zones reservades de l'iPhone Duo, la Live Activity, la càmera) només existeix en SwiftUI i UIKit. Per això:
  - L'ordinador continua amb Tauri.
  - El motor en Rust (transcripció, parlants, presentacions, la teva veu, glossari, base de dades) se separa en un mòdul comú que fan servir l'ordinador i el mòbil.
  - iOS: app en SwiftUI a `mobile/ios/`, que crida el motor amb UniFFI. Android, més endavant, en Jetpack Compose amb el mateix motor.
  - Els textos surten de `app/src/locales/` i es converteixen als formats d'iOS i Android, sense duplicar traduccions.
- **Disposició per size classes**, no per dispositiu: amplada compacta (iPhone, Duo tancat, iPad estret) amb una columna; amplada regular (Duo obert, iPad) amb la llista i la reunió alhora; Duo mig plegat amb una banda per costat del plec (la frase a dalt, els botons a baix).
- **Cal Xcode** per a qualsevol app d'iOS. Les proves es fan directament a l'iPhone, sense simuladors.
- Mockups: [`docs/mockups/mobil.html`](mockups/mobil.html), a mida real (402 × 874 pt), clar i fosc, amb els tokens de l'ordinador: inici buit, inici amb reunions mentre se'n grava una, gravant, notes (amb les fotos al minut i el format sobre el teclat), acabada només al mòbil (sense resum ni títol: els fa l'ordinador), tornada de l'ordinador, pantalla bloquejada i Dynamic Island (una Live Activity gran, amb el temps, l'última frase, Marca, Pausa i Atura), connectar amb l'ordinador (el QR) i configuració. Marcar un moment important: un botó mentre graves, a la Live Activity i a la notificació d'Android; la frase surt destacada amb «Important» i a les notes, i a l'ordinador hi arriba com una nota amb el minut. Android (Material 3: inici i gravant, i la notificació fixa del servei en primer pla) i iPhone Duo. Del perfil del simulador (Xcode 27.2): pantalla interior de 2007 × 2853 px (uns 669 × 951 pt) i exterior de 1398 × 2034 px (uns 466 × 678 pt), mitja interior girada. Obert en vertical, el plec és horitzontal; girat, és vertical i queden dues pàgines. Al mockup: obert en vertical (una reunió, res que es toqui sobre el plec), en horitzontal (la llista i la reunió una per banda) i mig plegat damunt la taula (a dalt el temps i la frase en gran; a baix els controls o el teclat, que ocupa just la meitat de baix). La zona del plec fa 40 pt i només és activa mig plegat (`reservedRegions(kind: .division)`, `onHingeChange`, `ArrangementView`, segons l'exemple iPhone-Duo-by-Examples). El simulador del Duo necessita el runtime d'iOS 27.1. Barra flotant de vidre (Reunions, gravar al mig, Configuració) on el botó del mig es torna la píndola «Gravant»; targeta de gravació amb la forma d'ona i el regle de minuts; els parlants amb els xips de l'ordinador i el text provisional en gris dins la frase.

## Ordre de feina

1. Sense Xcode: separar el motor en un mòdul comú i comprovar que l'ordinador continua igual (`cargo test`).
2. Sense Xcode: la part de l'ordinador de l'emparellament (Configuració › Mòbil, codi QR, Bonjour i el protocol xifrat).
3. Amb Xcode: la prova d'una tarda (a sota).
4. Amb Xcode: la primera app SwiftUI, només gravar i text en directe, a l'iPhone per TestFlight.
5. La resta de pantalles del mockup i la sincronització.

## Primer pas amb Xcode: una prova d'una tarda

No amb les demos de sherpa-onnx sinó amb el nostre motor: `mobile/ios/MurmurProbe/` és una app de proves en SwiftUI que fa servir `murmur-core` tal com és, compilat per a iOS (`ffi/`, un crate amb unes quantes funcions en C; sherpa-onnx hi va com a xcframework compartit, que el seu `build.rs` ja descarrega). El Conformer hi va en int8 (`LoadOptions` a `engine.rs`: 137 MB en lloc de 481).

```bash
cd mobile/ios/MurmurProbe
./fetch-models.sh        # els models i la reunió de 3 minuts, de models/ i data/ (no es pugen)
xcodegen && open MurmurProbe.xcodeproj
```

A Xcode, tria el teu equip a *Signing & Capabilities* i l'iPhone com a destinació. L'app té dues proves:

1. **La reunió de 3 minuts**, tan de pressa com pot: la velocitat (RTF) i el text, que es pot enviar per comparar-lo amb el de l'ordinador.
2. **Grava en directe:** el micròfon, amb un registre cada 30 s de la velocitat, la bateria i la temperatura. Segueix amb la pantalla bloquejada. Per a la prova de bateria, mitja hora.

Es pot triar 1, 2 o 4 fils i si hi ha text provisional. Amb `-autobench` (i `-threads N`, `-nopartials`), en obrir-se passa la reunió de prova sola i n'escriu el resultat a la consola.

**Al simulador** (l'M4 del Mac, no el telèfon), la reunió de 3 minuts:

| Fils | Text provisional | Càlcul | RTF |
|---|---|---|---|
| 2 | no | 13,4 s | 0,074 |
| 4 | no | 11,2 s | 0,062 |
| 4 | sí | 82,3 s | 0,457 |

El text provisional (tornar a transcriure els últims segons cada 0,6 s mentre algú parla) multiplica el cost per set. A l'iPhone, que és més lent, probablement caldrà fer-lo més de tant en tant, o més curt, o sense. És el primer que cal mesurar.

**A l'iPhone 14 Pro** (A16, 2 nuclis ràpids i 4 d'eficiència, iOS 27), la mateixa reunió:

| Fils | Text provisional | Càlcul | RTF |
|---|---|---|---|
| 2 | no | 9,2 s | 0,051 |
| 4 | no | 9,3 s | 0,052 |
| 2 | sí | 83,3 s | 0,463 |
| 4 | sí | molt més lent: no acaba | |

- **La velocitat sobra sense text provisional:** 20 vegades el temps real, més de pressa que l'int8 a l'M4. Amb 4 fils no guanya res, i amb el text provisional hi perd molt: la feina va als nuclis d'eficiència. A l'iPhone, 2 fils.
- **El text provisional hi cap, però just:** 0,46 vol dir que el mòbil passa la meitat del temps transcrivint mentre graves. Caldrà veure-ho a la prova de bateria i, segurament, fer-lo més de tant en tant (cada 1,2 s en lloc de 0,6) o més curt.
- **El text és el mateix que al simulador**, paraula per paraula, i els parlants també.
- **L'int8 contra el model sencer** (`cargo run --release --example int8_compare -- ../../data/reunio-3min.wav`, sense text provisional): el 5% de les paraules canvien (27 de 553), sobretot marques i paraules difícils, en totes dues direccions («bookomers» / «bugomers», «lync» / «link»), i els parlants són idèntics. Emparellat, l'ordinador ho refà igualment amb Whisper.

**Pendent:** la bateria i la temperatura en mitja hora en directe.

## L'app (`mobile/ios/Murmur`)

En SwiftUI, amb el motor de `core/` a través de `mobile/ffi` (`murmur-mobile`, UniFFI 0.32). El crate té el seu propi espai de treball: al mòbil sherpa-onnx va com a biblioteca compartida (un xcframework a iOS), i aquesta opció no pot arribar a l'app de l'ordinador, que l'enllaça estàticament.

- **El pont** (`mobile/ffi/src/lib.rs`): un objecte `Murmur` amb la llista de reunions (`meetings`, `meetingJson`, `deleteMeeting`) i la gravació (`start`, `push` amb les mostres de 16 kHz del micròfon, `stop`). Carrega el Conformer en int8 amb 2 fils i fa servir la mateixa base de dades i el mateix format de reunió que l'ordinador: una reunió del mòbil passarà a l'ordinador tal com és. L'àudio es desa en Opus a `recordings/`. `stop` hi afegeix el que porta l'app: les notes (`notesDoc`, com les de l'ordinador, amb el segon de cada paràgraf) i els moments marcats (`marks`).
- **El text provisional**, cada 1,5 s per defecte (Configuració › Text mentre parles: mai, 1, 1,5 o 3 s; `Pipeline::set_partial_every`).
- **Colors:** `docs/design/tokens/Theme.swift`, copiat a `Sources/`. **Textos:** `app/src/locales/*/mobile.json` (docs/TRADUCCIONS.md).

```bash
cd mobile/ios/Murmur
./fetch-models.sh        # els models, de models/ (no es pugen)
./build-rust.sh          # el motor per a l'iPhone i el simulador, i el xcframework a Frameworks/
./bindings.sh            # el codi Swift d'UniFFI (Generated/) i els textos; després de canviar lib.rs o mobile.json
xcodegen && open Murmur.xcodeproj
```

Per provar-la al simulador sense parlar: `xcrun simctl launch <simulador> com.quicodomingo.murmur.ios -simulate ../data/reunio-3min.wav -autostart -autostop 60` llegeix el WAV com si fos el micròfon, comença a gravar en obrir-se i s'atura sola (marcant el moment) al cap de 60 s.

Encara no hi ha: la Live Activity, les fotos, l'editor de notes amb format, l'emparellament, l'iPad, el Duo ni Android.

