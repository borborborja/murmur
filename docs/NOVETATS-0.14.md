# Murmur 0.14: el post del blog i els canvis a la web

Tot el que ha canviat des de la primera publicació fins a la 0.14.0 (15 de novembre de 2026), per escriure el post del blog i posar al dia la web (murmur.quicodomingo.com). Les dades surten de les novetats de cada versió (`app/src/locales/*/whatsnew.json`) i dels README. Els textos de la web que ja hi ha són a [LANDING.md](LANDING.md).

## Què hi ha de nou

| Novetat | Versió | Qui l'ha demanada | Captura |
|---|---|---|---|
| Windows i Linux | 0.12.0 | @marinaplanas, @marionasoler_ i @davidgnaveros; feta per @xaviviro | (la de sempre, a Windows: falta) |
| Whisper català del BSC: central, valencià i balear | 0.12.0 | @Ricard25J | `ca-03-resum` |
| Traduir la transcripció i triar l'idioma del resum | 0.12.0 | @freecates | (falta: una reunió amb la traducció) |
| La teva empremta de veu | 0.13.0 | | `ca-20-la-teva-veu` |
| Presentacions («Murmur, sóc la Marta») | 0.13.0 | | `ca-21-presenta-te` |
| Exportar a Notion | 0.14.0 | @ultrasonica | `ca-16-desat-notion`, `ca-19-notion-tria` |
| Subtítols (SRT i WebVTT) | 0.14.0 | @ultrasonica | (falta: els subtítols sobre el vídeo) |
| Transcriure vídeos | 0.14.0 | @ultrasonica | (la mateixa) |
| Exportar a Obsidian | 0.14.0 | (pensant en @ganyet) | `ca-17-desat-obsidian`, `ca-18-configuracio-exportar` |
| Configuració en una barra lateral | 0.14.0 | | `ca-18-configuracio-exportar` |
| Ajuntar dos parlants, puntuació de Softcatalà | 0.11.4 | | |
| Estadístiques d'ús anònimes, que es poden desactivar | 0.13.0 | | |
| **De camí:** iPhone, iPad, Android i iPhone Duo | | | (els mockups de `docs/mockups/`, si es volen ensenyar) |

Les captures són a `data/captures-landing/` (no es pugen al repositori), a 2880×1800, en català, castellà i anglès (`ca-`, `es-`, `en-`). Com es tornen a fer: [LANDING.md › Captures](LANDING.md#captures).

## El post del blog

Esborrany en català. Els noms d'usuari s'han de comprovar abans de publicar-lo.

---

### Murmur 0.14: Windows, Linux, Notion, Obsidian, subtítols i vídeos

Fa pocs dies vaig publicar Murmur, una app per transcriure reunions en català sense que l'àudio surti de l'ordinador. Va néixer de la feina de cada dia a Mortensen, on cap eina de transcripció em convencia amb reunions que passen del català al castellà a mitja frase. La resposta ha estat molt més gran del que m'esperava, i moltes de les coses que m'heu demanat ja hi són.

**Windows i Linux.** Era la petició més repetida (gràcies, @marinaplanas, @marionasoler_ i @davidgnaveros). L'ha fet possible @xaviviro, amb una contribució que ho porta tot: les versions per a PC, les proves automàtiques i la publicació. Murmur funciona a Windows 10 i 11 i a Linux de 64 bits, i s'actualitza sol com al Mac.

**Valencià i balear.** @Ricard25J preguntava pel valencià i el balear. En acabar una reunió en català, Murmur la torna a escoltar amb el Whisper català del Barcelona Supercomputing Center, que a les nostres proves ha estat el millor amb el català central, el valencià i el balear.

**Traduir.** Per a @freecates: el menú «···» tradueix la transcripció al català, al castellà o a l'anglès, i al costat de «Torna a generar» pots triar en quina llengua vols el resum. L'original no es toca mai.

**Notion, subtítols i vídeos.** Tres peticions de @ultrasonica:
- **Notion.** Hi connectes Murmur des del navegador, sense crear cap clau, i tries a quina pàgina van les reunions. Es desen des d'Exporta o soles en acabar. A Notion només hi va el text: ni l'àudio ni les captures.
- **Subtítols.** Exporta › Subtítols en SRT o WebVTT, en frases curtes de dues línies, amb el nom de qui parla si vols i en la traducció si n'has fet.
- **Vídeos.** Deixa anar un mp4, mov, mkv o webm sobre Murmur i en transcriu el so. Els subtítols es desen al costat del vídeo, amb el mateix nom, i el reproductor els troba sol.

**Obsidian.** Si fas servir Obsidian (@ganyet, això és per a tu), Murmur hi desa cada reunió com una nota: propietats per filtrar, les tasques com a caselles, les captures i, si vols, les persones com a [[enllaços]]. Tot es queda a l'ordinador.

**La teva veu.** Llegeixes una frase i Murmur et marca com a «Tu» des de la primera paraula, a totes les reunions. Murmur no desa la gravació, només una empremta de la veu, que és una dada biomètrica: per això es queda a l'ordinador i la pots esborrar quan vulguis.

**Presentacions.** Quan sou uns quants a la sala amb un sol micròfon, digues «Murmur, sóc la Marta» i aquell parlant passa a dir-se Marta. Funciona en català, castellà i anglès.

**I més coses petites:** ajuntar dos parlants quan Murmur n'ha separat un en dos, la puntuació de Softcatalà per a les reunions que barregen català i castellà, i una configuració més endreçada, en una barra lateral.

**El que ve: el mòbil.** Murmur per a iPhone, iPad, Android i iPhone Duo. Podrà gravar sense l'ordinador i, quan s'hi connecti, l'ordinador refarà la transcripció i els parlants amb els models grans.

Com sempre, és gratuïta i de codi obert. Es descarrega a murmur.quicodomingo.com. Per gravar una reunió cal el consentiment de tothom qui hi participa, i Murmur t'ho recorda en començar.

---

## Canvis a la web

Els textos de [LANDING.md](LANDING.md) són de la 0.11.2, quan Murmur només era per a Mac. Cal canviar el següent:

### Dades bàsiques

| | Ara diu | Ha de dir |
|---|---|---|
| Plataforma | Mac amb Apple Silicon | Mac amb Apple Silicon (macOS 14.2 o posterior), Windows 10 i 11 i Linux de 64 bits (processador amb AVX2) |
| Descàrrega | Només el `.dmg` | El `.dmg`, l'instal·lador de Windows (`Murmur_<versió>_x64-setup.exe`) i el `.deb` i el `.rpm` de Linux, tots a la mateixa pàgina de la *release* |

### Capçalera

Ja no és només el Mac: «al teu Mac» passa a «al teu ordinador», i el botó detecta el sistema.

| | Català | Castellà | Anglès |
|---|---|---|---|
| Titular | Les teves reunions, transcrites al teu ordinador. | Tus reuniones, transcritas en tu ordenador. | Your meetings, transcribed on your computer. |
| Botó | Descarrega per a Mac / per a Windows / per a Linux | Descargar para Mac / para Windows / para Linux | Download for Mac / for Windows / for Linux |
| Sota el botó | Gratuïta · Mac, Windows i Linux | Gratis · Mac, Windows y Linux | Free · Mac, Windows and Linux |

### Funcionalitats: blocs nous

| Bloc | Català | Castellà | Anglès | Captura |
|---|---|---|---|---|
| Notion i Obsidian | **On ja treballes.** Desa cada reunió a Notion, sense crear cap clau, o com una nota a la teva caixa forta d'Obsidian, amb les tasques i les captures. A mà o sola en acabar. | **Donde ya trabajas.** Guarda cada reunión en Notion, sin crear ninguna clave, o como una nota en tu bóveda de Obsidian, con las tareas y las capturas. A mano o sola al terminar. | **Where you already work.** Save each meeting to Notion, with no key to create, or as a note in your Obsidian vault, with tasks and screenshots. By hand or on its own when it ends. | `16-desat-notion`, `17-desat-obsidian` |
| Vídeos i subtítols | **Subtitula un vídeo.** Deixa anar un vídeo i Murmur en transcriu el so. Exporta els subtítols en SRT o WebVTT, al costat del vídeo i amb el seu nom. | **Subtitula un vídeo.** Suelta un vídeo y Murmur transcribe su sonido. Exporta los subtítulos en SRT o WebVTT, junto al vídeo y con su nombre. | **Subtitle a video.** Drop a video and Murmur transcribes its sound. Export subtitles as SRT or WebVTT, next to the video and with its name. | Falta |
| Traducció | **En una altra llengua.** Tradueix la transcripció al català, al castellà o a l'anglès, i tria en quina llengua vols el resum. L'original no es toca. | **En otra lengua.** Traduce la transcripción al catalán, al castellano o al inglés, y elige en qué lengua quieres el resumen. El original no se toca. | **In another language.** Translate the transcript into Catalan, Spanish or English, and choose the summary's language. The original stays as it was. | Falta |

### Funcionalitats: blocs que canvien

| Bloc | Què s'hi afegeix (català) | Castellà | Anglès | Captura |
|---|---|---|---|---|
| Parlants | Llegeix una frase i Murmur et reconeixerà a totes les reunions. I a la sala, n'hi ha prou amb dir «Murmur, sóc la Marta». | Lee una frase y Murmur te reconocerá en todas las reuniones. Y en la sala, basta con decir «Murmur, soy Marta». | Read one sentence and Murmur will know you in every meeting. In the room, just say "Murmur, I'm Marta". | `20-la-teva-veu`, `21-presenta-te` |
| Compartir i exportar | Afegir-hi subtítols, Notion i Obsidian a la llista. | Añadir subtítulos, Notion y Obsidian. | Add subtitles, Notion and Obsidian. | `15-exportar-notion-obsidian` |
| Per al català | Ja diu que el Whisper del BSC entén el valencià i el balear. Es pot dir més clar: «Funciona amb el català central, el valencià i el balear». | «Funciona con el catalán central, el valenciano y el balear». | "Works with Central, Valencian and Balearic Catalan." | |

### Privadesa

Afegir-hi una frase sobre Notion, que és l'única connexió nova: *Si connectes Notion, només hi va el text de les reunions que hi desis, directament al teu espai* (*Si conectas Notion, solo le llega el texto de las reuniones que guardes allí, directamente a tu espacio* / *If you connect Notion, only the text of the meetings you save there goes to it, straight to your own workspace*). I «Tot es queda en aquest Mac» passa a «Tot es queda al teu ordinador».

### Preguntes freqüents

| Pregunta | Resposta |
|---|---|
| Funciona a Windows o a Linux? | Sí, des de la 0.12, gràcies a @xaviviro: Windows 10 o 11 i Linux de 64 bits, amb un processador amb AVX2 (Intel des del 2013, AMD des del 2015). Als Macs amb Intel, no. |
| Entén el valencià i el balear? | Sí. En acabar, el Whisper català del BSC torna a escoltar la reunió, i és el millor de les nostres proves amb totes tres variants. |
| Pot transcriure un vídeo? | Sí: mp4, mov, m4v, mkv i webm. Murmur n'agafa només el so, i en pots exportar els subtítols. |
| Què s'envia a Notion? | Només el text de la reunió (resum, notes i transcripció), quan l'exportes o en acabar si ho tens activat. Mai l'àudio ni les captures. |
| Hi haurà app per al mòbil? | Sí, per a iPhone, iPad i Android. Podrà gravar sense l'ordinador i, quan s'hi connecti, l'ordinador refarà la transcripció. |

## Captures que falten

- **Subtítols sobre el vídeo:** `reunio.webm` transcrit, amb el `.srt` carregat a VLC o IINA. És la imatge del bloc de vídeos.
- **La traducció:** una reunió amb la transcripció traduïda i el segmentat Original / traducció.
- **Windows:** la finestra de Murmur a Windows, si es vol ensenyar a la capçalera o a la pregunta.
- **Dins Obsidian i Notion:** la nota a Obsidian i la pàgina a Notion, fetes amb l'app de veritat i dades d'exemple (les de Murmur són simulades).

## Abans de publicar

- Comprovar els noms d'usuari: @marinaplanas, @marionasoler_, @davidgnaveros, @xaviviro, @Ricard25J, @freecates, @ultrasonica, @ganyet.
- El mòbil: el post diu com està pensat que funcioni. Si encara no es vol concretar, n'hi ha prou amb «està de camí».
