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

## Enviar-ho a l'ordinador

- **Primera versió:** el mòbil desa l'àudio i la transcripció del directe; en acabar, es comparteixen al Mac (AirDrop o menú Compartir). El Mac ja obre àudios («Transcriu des d'un fitxer») i té l'extensió de Compartir, i hi fa la passada final.
- **Més endavant:** sincronització per la xarxa local (Bonjour) o una carpeta d'iCloud Drive.
- Si el Mac rep l'àudio, la transcripció del mòbil només serveix per llegir-la en directe: el Mac en fa una de millor. Això simplifica el format que s'ha d'enviar.

## Primer pas: una prova d'una tarda

Amb les apps de demostració de sherpa-onnx (Android i iOS), carregar-hi el nostre Conformer int8 i mesurar al telèfon:

1. La velocitat (RTF) per frase.
2. La qualitat en català, comparada amb el Mac (la mateixa gravació).
3. La bateria i la temperatura en mitja hora de gravació.

Si surt bé, el pas següent és provar si Tauri mobile compila el motor.
