//! Murmur's engine for the phone apps (docs/MOBIL.md), through UniFFI: the computer's live
//! transcription (`murmur-core`) with the Conformer in int8, and the same meetings database, so a
//! meeting recorded on the phone has the computer's format and goes to it as it is.
//!
//! Swift (and later Kotlin) see one object, `Murmur`: the list of meetings, and a recording that
//! starts, takes the microphone's 16 kHz samples and stops into a saved meeting.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use murmur_core::db::Db;
use murmur_core::engine::{Event, LiveModel, LoadOptions, Models, SAMPLE_RATE};
use murmur_core::pipeline::{Pipeline, Source};
use murmur_core::recorder::Recorder;
use serde_json::{Value, json};

uniffi::setup_scaffolding!();

/// The speaker threshold and the most speakers, as the computer's defaults (Settings).
const THRESHOLD: f32 = 0.7;
const MAX_SPEAKERS: usize = 6;
/// On the phone the Conformer runs in int8 on the two fast cores (docs/MOBIL.md: 4 threads are slower).
const LOAD: LoadOptions = LoadOptions { int8: true, threads: 2 };

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MurmurError {
    #[error("{0}")]
    Failed(String),
}

impl From<anyhow::Error> for MurmurError {
    fn from(e: anyhow::Error) -> Self {
        Self::Failed(format!("{e:#}"))
    }
}

type Result<T> = std::result::Result<T, MurmurError>;

/// One finished line of the transcript.
#[derive(uniffi::Record)]
pub struct Line {
    pub id: String,
    pub start: f64,
    pub end: f64,
    pub speaker: i64,
    pub text: String,
}

/// What a piece of audio brought: finished lines, the provisional text (`Some("")` clears it),
/// the seconds recorded so far and the level of this piece (rms).
#[derive(uniffi::Record)]
pub struct LiveUpdate {
    pub finals: Vec<Line>,
    pub partial: Option<String>,
    pub seconds: f64,
    pub level: f32,
}

/// A meeting in the list, without its transcript.
#[derive(uniffi::Record)]
pub struct MeetingRow {
    pub id: String,
    pub title: String,
    /// Milliseconds since 1970, as the computer stores it.
    pub created_at: f64,
    pub duration: f64,
    pub status: String,
    pub tags: Vec<String>,
    /// The last thing said, to recognise it in the list.
    pub last_line: Option<String>,
    /// The computer has revised it (Whisper, speakers, summary).
    pub revised: bool,
}

/// The loaded models, shared by every recording. sherpa-onnx's objects aren't marked Send/Sync,
/// but they're only used by one recording at a time, behind `Murmur.live`'s lock.
struct SharedModels(&'static Models);
unsafe impl Send for SharedModels {}
unsafe impl Sync for SharedModels {}

/// The recording in progress. Moved between threads only behind the lock.
struct Live {
    pipeline: Pipeline<'static>,
    meeting: Value,
    samples: u64,
    lines: Vec<Value>,
}
unsafe impl Send for Live {}

#[derive(uniffi::Object)]
pub struct Murmur {
    models_dir: PathBuf,
    data_dir: PathBuf,
    models: Mutex<Option<SharedModels>>,
    db: Mutex<Db>,
    live: Mutex<Option<Live>>,
}

fn now_ms() -> f64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as f64).unwrap_or(0.0)
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

#[uniffi::export]
impl Murmur {
    /// `models_dir`: the app bundle's models; `data_dir`: where meetings and recordings go.
    #[uniffi::constructor]
    pub fn new(models_dir: String, data_dir: String) -> Result<std::sync::Arc<Self>> {
        let data_dir = PathBuf::from(data_dir);
        let db = Db::open(&data_dir.join("murmur.db"))?;
        Ok(std::sync::Arc::new(Self { models_dir: PathBuf::from(models_dir), data_dir, models: Mutex::new(None), db: Mutex::new(db), live: Mutex::new(None) }))
    }

    /// Loads the models now (they load on the first recording otherwise): about half a second.
    pub fn prepare(&self) -> Result<()> {
        self.models().map(|_| ())
    }

    /// The meetings, newest first.
    pub fn meetings(&self) -> Result<Vec<MeetingRow>> {
        let db = lock(&self.db);
        let list = db.list()?;
        Ok(list
            .into_iter()
            .map(|s| {
                let full = db.get(&s.id).ok().flatten();
                let last_line = full
                    .as_ref()
                    .and_then(|m| m["utterances"].as_array().and_then(|u| u.last()).and_then(|u| u["text"].as_str()).map(str::to_owned));
                let revised = full.as_ref().is_some_and(|m| m.get("cover").is_some_and(|c| !c.is_null()));
                MeetingRow { id: s.id, title: s.title, created_at: s.created_at, duration: s.duration, status: s.status, tags: s.tags, last_line, revised }
            })
            .collect())
    }

    /// A meeting as the computer stores it (JSON), or None.
    pub fn meeting_json(&self, id: String) -> Result<Option<String>> {
        Ok(lock(&self.db).get(&id)?.map(|m| m.to_string()))
    }

    pub fn delete_meeting(&self, id: String) -> Result<()> {
        if let Some(m) = lock(&self.db).get(&id)? {
            if let Some(path) = m["audioPath"].as_str() {
                let _ = std::fs::remove_file(path);
            }
        }
        lock(&self.db).delete(&id).map_err(Into::into)
    }

    /// A new title for a meeting (empty: left as it was).
    pub fn rename_meeting(&self, id: String, title: String) -> Result<()> {
        let title = title.trim();
        if title.is_empty() {
            return Ok(());
        }
        self.edit(&id, |m| m["title"] = json!(title))
    }

    /// A speaker's name ("Parlant 2" is "Laia"); empty takes it away. As the computer stores it:
    /// `speakerNames` by number.
    pub fn set_speaker_name(&self, id: String, speaker: i64, name: String) -> Result<()> {
        let name = name.trim().to_owned();
        self.edit(&id, |m| {
            if !m["speakerNames"].is_object() {
                m["speakerNames"] = json!({});
            }
            if name.is_empty() {
                m["speakerNames"].as_object_mut().map(|o| o.remove(&speaker.to_string()));
            } else {
                m["speakerNames"][speaker.to_string()] = json!(name);
            }
        })
    }

    /// One line goes to another speaker, set by hand (`speakerLocked`: the computer's final pass
    /// leaves it alone).
    pub fn set_line_speaker(&self, id: String, line: String, speaker: i64) -> Result<()> {
        self.edit(&id, |m| {
            for u in m["utterances"].as_array_mut().into_iter().flatten() {
                if u["id"] == json!(line) {
                    u["speaker"] = json!(speaker);
                    u["speakerLocked"] = json!(true);
                }
            }
        })
    }

    /// Every line of `from` is `into`'s: two speakers that were the same person.
    pub fn merge_speakers(&self, id: String, from: i64, into: i64) -> Result<()> {
        self.edit(&id, |m| {
            for u in m["utterances"].as_array_mut().into_iter().flatten() {
                if u["speaker"] == json!(from) {
                    u["speaker"] = json!(into);
                }
            }
            m["speakerNames"].as_object_mut().map(|o| o.remove(&from.to_string()));
        })
    }

    /// Starts recording a meeting: `title` as the phone shows it until the computer names it,
    /// `language` ("ca", "es", "ca-es", "en"), whether there are more people in the room (or
    /// it's only the user: one speaker), and how often the provisional text is redone (0: never).
    /// Returns the meeting's id.
    pub fn start(&self, title: String, language: String, more_in_room: bool, partial_every: f32) -> Result<String> {
        let models = self.models()?;
        let created = now_ms();
        let id = format!("phone-{}", created as u64);
        let audio = self.data_dir.join("recordings").join(format!("{id}.ogg"));
        let recorder = Recorder::create(&audio, 1)?;
        let mut pipeline = Pipeline::new(models, true, false, if more_in_room { MAX_SPEAKERS } else { 1 }, THRESHOLD, Some(recorder), vec![])?;
        pipeline.set_partials(partial_every > 0.0);
        if partial_every > 0.0 {
            pipeline.set_partial_every(partial_every);
        }
        let meeting = json!({
            "id": id, "title": title, "createdAt": created, "duration": 0.0, "status": "recording",
            "utterances": [], "speakerNames": {}, "notes": [], "language": language,
            "sources": { "mic": true, "system": false }, "audioPath": audio.to_string_lossy(),
        });
        *lock(&self.live) = Some(Live { pipeline, meeting, samples: 0, lines: vec![] });
        Ok(id)
    }

    /// 16 kHz mono samples from the microphone.
    pub fn push(&self, samples: Vec<f32>) -> Result<LiveUpdate> {
        let mut guard = lock(&self.live);
        let live = guard.as_mut().ok_or_else(|| MurmurError::Failed("not recording".into()))?;
        let level = if samples.is_empty() { 0.0 } else { (samples.iter().map(|s| s * s).sum::<f32>() / samples.len() as f32).sqrt() };
        live.samples += samples.len() as u64;
        let events = live.pipeline.push(Source::Mic, &samples);
        Ok(update(live, events, level))
    }

    /// Stops and saves the meeting. `extra` is a JSON object the app adds to it: its notes
    /// (`notesDoc`), its photos (`screens`) and the moments marked as important (`marks`).
    pub fn stop(&self, extra: String) -> Result<String> {
        let live = lock(&self.live).take().ok_or_else(|| MurmurError::Failed("not recording".into()))?;
        let Live { pipeline, mut meeting, samples, mut lines } = live;
        let (events, _) = pipeline.finish()?;
        let prefix = meeting["id"].as_str().unwrap_or_default().to_owned();
        for e in events {
            if let Event::Final { id, start, end, speaker, text, .. } = e {
                lines.push(json!({ "id": line_id(&prefix, id), "start": start, "end": end, "speaker": speaker, "text": text }));
            }
        }
        meeting["duration"] = json!(samples as f64 / SAMPLE_RATE as f64);
        meeting["status"] = json!("done");
        if let Ok(Value::Object(mut add)) = serde_json::from_str::<Value>(&extra) {
            // What was changed while recording: speakers merged (`speakerAliases`, from → into,
            // also for the last lines, which only arrive now) and lines given to someone else
            // (`lineSpeakers`, by line id, set by hand).
            let aliases = add.remove("speakerAliases").unwrap_or_default();
            let by_line = add.remove("lineSpeakers").unwrap_or_default();
            for u in &mut lines {
                let id = u["id"].as_str().unwrap_or_default().to_owned();
                if let Some(s) = by_line.get(&id).and_then(Value::as_i64) {
                    u["speaker"] = json!(s);
                    u["speakerLocked"] = json!(true);
                } else if let Some(s) = u["speaker"].as_i64().and_then(|s| aliases.get(s.to_string()).and_then(Value::as_i64)) {
                    u["speaker"] = json!(s);
                }
            }
            for (k, v) in add {
                meeting[k] = v;
            }
        }
        meeting["utterances"] = Value::Array(lines);
        lock(&self.db).put(&meeting)?;
        Ok(meeting["id"].as_str().unwrap_or_default().to_owned())
    }

    /// Whether a recording is in progress (after the app was killed, it isn't).
    pub fn is_recording(&self) -> bool {
        lock(&self.live).is_some()
    }
}

impl Murmur {
    /// Reads a meeting, changes it and saves it back (nothing if it isn't there).
    fn edit(&self, id: &str, change: impl FnOnce(&mut Value)) -> Result<()> {
        let mut db = lock(&self.db);
        let Some(mut meeting) = db.get(id)? else { return Ok(()) };
        change(&mut meeting);
        db.put(&meeting).map_err(Into::into)
    }

    fn models(&self) -> Result<&'static Models> {
        let mut slot = lock(&self.models);
        if let Some(SharedModels(m)) = slot.as_ref() {
            return Ok(m);
        }
        let loaded: &'static Models = Box::leak(Box::new(Models::load_with(Path::new(&self.models_dir), LiveModel::CatalanSpanish, LOAD)?));
        *slot = Some(SharedModels(loaded));
        Ok(loaded)
    }
}

/// A line's id. The database keys lines by id across every meeting (the desktop uses UUIDs), so
/// the meeting's id goes in front: "u3" alone would overwrite line 3 of another meeting.
fn line_id(meeting: &str, n: u32) -> String {
    format!("{meeting}-{n}")
}

fn update(live: &mut Live, events: Vec<Event>, level: f32) -> LiveUpdate {
    let prefix = live.meeting["id"].as_str().unwrap_or_default().to_owned();
    let mut finals = vec![];
    let mut partial = None;
    for e in events {
        match e {
            Event::Final { id, start, end, speaker, text, .. } => {
                let line = Line { id: line_id(&prefix, id), start: start as f64, end: end as f64, speaker: speaker as i64, text };
                live.lines.push(json!({ "id": line.id, "start": line.start, "end": line.end, "speaker": line.speaker, "text": line.text }));
                finals.push(line);
            }
            Event::Partial { text, .. } => partial = Some(text),
        }
    }
    LiveUpdate { finals, partial, seconds: live.samples as f64 / SAMPLE_RATE as f64, level }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(id: &str, speaker: i64) -> Value {
        json!({ "id": id, "start": 0.0, "end": 1.0, "speaker": speaker, "text": "hola" })
    }

    fn saved(m: &Murmur) -> Value {
        serde_json::from_str(&m.meeting_json("phone-1".into()).unwrap().unwrap()).unwrap()
    }

    #[test]
    fn speakers_are_named_moved_and_merged() {
        let dir = std::env::temp_dir().join(format!("murmur-mobile-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let m = Murmur::new("/nowhere".into(), dir.to_string_lossy().into_owned()).unwrap();
        let meeting = json!({ "id": "phone-1", "title": "T", "createdAt": 0.0, "duration": 3.0, "status": "done",
            "utterances": [line("phone-1-0", 0), line("phone-1-1", 1), line("phone-1-2", 2)] });
        lock(&m.db).put(&meeting).unwrap();

        m.set_speaker_name("phone-1".into(), 1, " Laia ".into()).unwrap();
        m.set_speaker_name("phone-1".into(), 2, "Jordi".into()).unwrap();
        m.set_line_speaker("phone-1".into(), "phone-1-0".into(), 1).unwrap();
        m.merge_speakers("phone-1".into(), 2, 1).unwrap();
        m.rename_meeting("phone-1".into(), "Llançament".into()).unwrap();

        let s = saved(&m);
        assert_eq!(s["title"], "Llançament");
        assert_eq!(s["speakerNames"]["1"], "Laia");
        assert!(s["speakerNames"].get("2").is_none());
        let speakers: Vec<i64> = s["utterances"].as_array().unwrap().iter().map(|u| u["speaker"].as_i64().unwrap()).collect();
        assert_eq!(speakers, vec![1, 1, 1]);
        let locked: Vec<bool> = s["utterances"].as_array().unwrap().iter().map(|u| u["speakerLocked"] == json!(true)).collect();
        assert_eq!(locked, vec![true, false, false]);

        // An empty name takes it away.
        m.set_speaker_name("phone-1".into(), 1, "".into()).unwrap();
        assert!(saved(&m)["speakerNames"].get("1").is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
