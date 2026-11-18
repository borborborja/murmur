# Murmur per a Android

Aquesta adaptació parteix de Murmur 0.16.0, commit `cfeda3ede4528e1d2772b6eda01065611f9ef04b` de [quicodomingo/murmur](https://github.com/quicodomingo/murmur/tree/cfeda3ede4528e1d2772b6eda01065611f9ef04b). Manté el nucli Rust, SQLite, el format de les reunions i les llicències originals; afegeix una interfície nativa en Kotlin i Jetpack Compose.

El dispositiu de referència és el Pixel 8a, amb 8 GB de RAM. El codi inclou processament local autònom i proveïdors opcionals al núvol. Que una funció estigui implementada o compili no acredita encara la seva qualitat, temperatura, bateria o fiabilitat en un Pixel físic. L'estat de cada àmbit és a [PARITAT_ANDROID.md](PARITAT_ANDROID.md).

La [validació local registrada](ANDROID_VALIDACIO.md) inclou 7 tests Rust, 37 JVM, 7 del pont Node, 7 Python, **12 proves instrumentades úniques a l'emulador** i **12 al Pixel 8a físic**. Les proves de gravació comproven la captura fora de l'Activity i l'eliminació de l'àudio sense conservació, fins i tot amb tots els passos desactivats. A l'emulador també s'ha recuperat automàticament la cua en una arrencada en fred, amb el servei de processament en primer pla.

`lintDebug` i l'alineació de 16 KB dels APK debug i release han passat. L'APK `0.16.0-android.1` té la signatura v2 verificada i està instal·lat i obert al Pixel. La disposició final de l'assistent té els botons íntegres sobre la barra de gestos; els quatre recorreguts Compose han tornat a passar tant a l'emulador com al Pixel. La sessió llarga, la bateria i els comptes reals continuen pendents.

## Instal·lació i primera obertura

L'app és per a Android 10 o posterior, ARM64. L'APK es pot instal·lar directament; no requereix root, cap servidor propi ni Murmur d'escriptori. La variant de desenvolupament té l'identificador `dev.murmur.android.debug`; la variant de release, `dev.murmur.android`. Android les considera aplicacions diferents i no comparteixen dades.

El fork desa la versió completa a `android/full-parity`. Cada canvi a la branca genera un APK ARM64 de proves. Obre una execució superada de l'Action i descarrega l'artefacte `murmur-android-arm64-debug`. El ZIP inclou l'APK instal·lable, el SHA-256 i els informes de verificació. Els artefactes es conserven 30 dies. [Action APK Android](https://github.com/borborborja/murmur/actions/workflows/android.yml)

Els APK de la branca del fork comparteixen una clau de proves exclusiva i es poden actualitzar entre si. La release `dev.murmur.android` que es va instal·lar al Pixel té la seva pròpia signatura i es compila localment amb `mobile/android/scripts/build-release.sh`.

En obrir-la, l'assistent ofereix descarregar el model de transcripció. Les descàrregues són explícites, es poden cancel·lar i reprendre, i se'n verifica la mida i el SHA-256. Els models no van dins l'APK ni al repositori. Després de descarregar-los, la gravació i les etapes locals funcionen sense connexió.

La gravació demana el permís de micròfon i recorda el consentiment de tothom qui hi participa. Una notificació mostra que l'app grava i permet marcar un moment o aturar-la. Les notes i els canvis es desen al telèfon. «Desa l'àudio» controla la conservació de l'enregistrament; el processament pot necessitar àudio temporal fins que acabi o es cancel·li. Si falta l'àudio, no es pot tornar a transcriure una reunió antiga.

## Models i intel·ligència

| Ús | Perfil inicial | Descàrrega aproximada |
|---|---|---:|
| Directe català/castellà | Conformer ca-es int8, Silero i models de parlants | 228 MB en conjunt |
| Directe anglès | Parakeet TDT-CTC 110M | 458 MB |
| Revisió catalana | BSC Whisper 3cat-balearic Q5_0 | 1,08 GB |
| Revisió castellà/anglès | Whisper large-v3-turbo Q5_0 | 574 MB |
| Revisió opcional lleugera | Whisper Small multilingüe Q5_1 | 190 MB |
| Puntuació catalana | Softcatalà int8 i tokenizer | 128 MB |
| Resum i traducció locals | Gemma 4 E2B Q4_0 de ggml-org | 2,84 GB |
| Resum local opcional original | Gemma 4 E4B | 5,15 GB |

Els bytes exactes, els URL i les sumes són al catàleg d'Android de `mobile/ffi/src/android.rs`, i al catàleg compartit de `core/src/models.rs`. Un fitxer de model ocupa menys disc que la memòria total necessària per executar-lo: també calen context i buffers. Els models es descarreguen de memòria entre etapes. El resum local limita el context i divideix les reunions llargues; la CPU és el camí de base, sense assumir acceleració específica del Tensor G3.

El Whisper català original es conserva per no atribuir a un model petit la mateixa qualitat sense mesurar-la. La revisió Whisper es fa en reunions d'una sola llengua; en català/castellà barrejat es conserva la transcripció del model mixt. La detecció d'idioma dels fitxers utilitza Silero i Whisper. Si el model no està instal·lat o l'idioma no és prou clar, l'app demana l'idioma.

A **Configuració > Intel·ligència** es pot triar Local, Claude, OpenAI, OpenRouter, Ollama Cloud o Gemini, conservar la clau de cada servei i seleccionar-ne el model. Només s'envien transcripció i notes necessàries per a la petició, mai l'àudio. Les claus queden xifrades amb una clau protegida per Android Keystore. No hi ha canvi automàtic del mode local al núvol.

Ollama Cloud es consulta directament per HTTPS amb la clau pròpia; no cal instal·lar Ollama. La llista es llegeix de `https://ollama.com/api/tags`, i les peticions es fan a `/api/chat`. Com que el servei no garanteix esquemes JSON, es valida la resposta i es permet un intent de correcció abans de donar l'etapa per fallida. Els errors conserven els resultats anteriors. Vegeu la [documentació d'Ollama Cloud](https://docs.ollama.com/cloud) i la [limitació d'esquemes](https://docs.ollama.com/capabilities/structured-outputs).

## Integracions i límits del sistema

- **Àudio d'altres apps:** Android només deixa capturar reproduccions amb una política compatible. Les trucades de telèfon i les videotrucades protegides no es poden capturar de manera general. Una altra app pot silenciar el micròfon de Murmur; l'app ho ha de mostrar. Es pot importar un enregistrament disponible o fer la captura amb Murmur d'escriptori. [Captura d'àudio](https://developer.android.com/media/platform/av-capture).
- **Captures de pantalla:** l'usuari autoritza MediaProjection per a la sessió. Només es desa una imatge quan es demana; les superfícies protegides queden excloses. El bloqueig de pantalla o la revocació poden acabar la projecció. [MediaProjection](https://developer.android.com/media/grow/media-projection).
- **Barra flotant:** és opcional i necessita el permís de superposició. La notificació i l'app continuen oferint controls si no es concedeix.
- **Calendari:** l'app consulta els comptes presents al proveïdor Android, amb permís opcional. Els avisos exactes dos minuts abans necessiten l'autorització d'alarmes exactes; altrament poden arribar més tard. No es presenta l'activitat genèrica del micròfon d'una altra app com una detecció fiable de reunió.
- **Carpetes i Obsidian:** l'usuari tria una carpeta amb el selector Android. No es busquen caixes fortes dins les dades privades d'altres aplicacions. Els permisos es poden revocar des del sistema.
- **Notion:** OAuth amb PKCE, navegador del sistema i callback local; exportació de text manual o automàtica a la pàgina triada.
- **Compartir:** conserva el format xifrat v1 del visor Cloudflare original, la selecció de contingut, la contrasenya, la caducitat i la revocació. Publicar requereix configurar el compte propi o un Worker existent; compilar l'app no publica res.
- **Sincronització:** conserva el protocol Noise v1 de l'original: reunions Android cap a l'ordinador i resultats revisats de tornada, amb transferències reprenibles. No és una sincronització general de tota la biblioteca en tots dos sentits. Els intents automàtics en segon pla estan subjectes a les restriccions d'Android.
- **Claude:** el connector directe al telèfon és opcional, només de xarxa local, amb TLS i aparellament aprovat a la pantalla del telèfon. El [pont stdio](../tools/android-mcp/README.md) serveix per a Claude Desktop i Claude Code sense instal·lar Murmur d'escriptori. Els connectors remots d'Anthropic no poden accedir a una IP privada del telèfon. Les pujades d'àudio per transcriure circulen de l'ordinador al telèfon, no pel núvol d'Anthropic.

Els registres tècnics són locals i limitats a dues peces d'1 MB. No hi van títols, notes, transcripcions, noms ni claus. El manifest desactiva les còpies automàtiques de dades de l'app. L'analítica és opcional, desactivada d'entrada, i només s'ofereix si qui compila configura una clau pròpia amb `MURMUR_APTABASE_KEY`; aquesta adaptació no reutilitza la clau de l'autor original.

## Compilar

Cal Git, Node.js 22, Python 3.9 o posterior, Rust 1.100.0, CMake, Ninja, JDK 21 i l'SDK Android amb:

- plataforma i build tools 36;
- NDK `29.0.14206865`;
- CMake `3.22.1`, o `CMAKE` apuntant a un executable compatible.

La compilació nativa admet macOS i Linux, o WSL. A Linux també calen libclang, pkg-config, OpenSSL i Opus de desenvolupament per a les eines i proves del host. No cal instal·lar els models per compilar.

Des de l'arrel del repositori:

```sh
export JAVA_HOME=/ruta/al/jdk-21
export ANDROID_HOME=/ruta/al/sdk-android
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
npm ci --prefix app
python3 mobile/android/scripts/generate-resources.py
node mobile/android/scripts/build-notes.mjs
scripts/build-native.sh
mobile/android/scripts/build-inference.sh
cd mobile/android
./gradlew :app:assembleDebug
```

L'APK és a `mobile/android/app/build/outputs/apk/debug/app-debug.apk`, i es pot instal·lar amb `adb install -r`. La variant debug utilitza la signatura de desenvolupament local. Per a la release instal·lable, el script `mobile/android/scripts/build-release.sh` prepara la signatura pròpia i l'artefacte; les claus i les contrasenyes no formen part del codi font. Una actualització ha de conservar l'identificador d'aplicació i el certificat de signatura.

Les versions fixades són Gradle 8.11.1, AGP 8.10.1, Kotlin 2.0.21, UniFFI 0.32 i sherpa-onnx 1.13.8. Els commits de llama.cpp i whisper.cpp són al script de compilació d'inferència. La suma de sherpa i la distribució Gradle també es verifiquen. No s'han de pujar memòries cau, fitxers `.so`, models, APK o dades personals al repositori.

El canal d'actualització Android és independent del canal d'escriptori. Es configura una URL HTTPS a **Configuració > Adreça del canal d'actualitzacions** amb un manifest com aquest:

```json
{"versionCode":2,"versionName":"0.16.0-android.2","url":"https://example.org/murmur.apk","sha256":"<64 caràcters hexadecimals>"}
```

L'app comprova la suma, el nom del paquet, l'increment de versió i el certificat de l'APK abans de passar-lo a l'instal·lador Android. No s'ha publicat cap canal públic amb aquesta implementació.

## Proves i criteri de lliurament

```sh
export SHERPA_ONNX_LIB_DIR="$(scripts/sherpa-host.sh)"
export LD_LIBRARY_PATH="$SHERPA_ONNX_LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export DYLD_LIBRARY_PATH="$SHERPA_ONNX_LIB_DIR${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
cargo test --locked --features punctuation --manifest-path mobile/ffi/Cargo.toml
cd mobile/android
./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
cd ../..
python3 mobile/android/scripts/verify-apk.py mobile/android/app/build/outputs/apk/debug/app-debug.apk
node --test tools/android-mcp/test/*.test.mjs
npm run check-locales --prefix app
```

Les proves JVM cobreixen contractes dels proveïdors amb respostes controlades, esquemes, xifrat compatible, subtítols i preservació d'edicions. Les proves d'interfície comproven fluxos de l'assistent, correcció de text i aprovació del connector. Les proves natives d'inferència executen models reals si estan instal·lats i indiquen explícitament l'omissió quan falten; un test omès no prova el model.

Les proves Rust del host utilitzen explícitament el directori retornat per `scripts/sherpa-host.sh`, separat de les biblioteques Android. Això evita que la memòria cau de sherpa-onnx 1.13.8 seleccioni els `.so` d'Android durant una execució al Mac o Linux.

El verificador d'APK comprova que les biblioteques són ARM64, els segments ELF i les entrades ZIP tenen alineació de 16 KB, i els recursos per treballar sense xarxa estan empaquetats. Això acredita l'artefacte, no el rendiment del telèfon. El workflow `Android` compila i executa les comprovacions del host; només un resultat real d'Actions acredita el CI remot.

Abans d'anomenar-la validada per al Pixel 8a cal completar una sessió física d'una hora amb pantalla apagada, retorn a l'app, Bluetooth o USB, trucada entrant, permisos revocats, poc espai i recuperació després de matar el procés. També cal mesurar RAM màxima, retard, temperatura, bateria i qualitat catalana dels resums/traduccions. Notion, Cloudflare, els comptes d'IA i Claude real necessiten comprovació amb les configuracions de l'usuari; els tests locals no la substitueixen.
