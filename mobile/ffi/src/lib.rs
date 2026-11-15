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

    /// Starts recording a meeting: `title` as the phone shows it until the computer names it,
    /// `language` ("ca", "es", "ca-es", "en"), and how often the provisional text is redone (0: never).
    /// Returns the meeting's id.
    pub fn start(&self, title: String, language: String, partial_every: f32) -> Result<String> {
        let models = self.models()?;
        let created = now_ms();
        let id = format!("phone-{}", created as u64);
        let audio = self.data_dir.join("recordings").join(format!("{id}.ogg"));
        let recorder = Recorder::create(&audio, 1)?;
        let mut pipeline = Pipeline::new(models, true, false, MAX_SPEAKERS, THRESHOLD, Some(recorder), vec![])?;
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
        for e in events {
            if let Event::Final { id, start, end, speaker, text, .. } = e {
                lines.push(json!({ "id": format!("u{id}"), "start": start, "end": end, "speaker": speaker, "text": text }));
            }
        }
        meeting["utterances"] = Value::Array(lines);
        meeting["duration"] = json!(samples as f64 / SAMPLE_RATE as f64);
        meeting["status"] = json!("done");
        if let Ok(Value::Object(add)) = serde_json::from_str::<Value>(&extra) {
            for (k, v) in add {
                meeting[k] = v;
            }
        }
        lock(&self.db).put(&meeting)?;
        Ok(meeting["id"].as_str().unwrap_or_default().to_owned())
    }

    /// Whether a recording is in progress (after the app was killed, it isn't).
    pub fn is_recording(&self) -> bool {
        lock(&self.live).is_some()
    }
}

impl Murmur {
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

fn update(live: &mut Live, events: Vec<Event>, level: f32) -> LiveUpdate {
    let mut finals = vec![];
    let mut partial = None;
    for e in events {
        match e {
            Event::Final { id, start, end, speaker, text, .. } => {
                let line = Line { id: format!("u{id}"), start: start as f64, end: end as f64, speaker: speaker as i64, text };
                live.lines.push(json!({ "id": line.id, "start": line.start, "end": line.end, "speaker": line.speaker, "text": line.text }));
                finals.push(line);
            }
            Event::Partial { text, .. } => partial = Some(text),
        }
    }
    LiveUpdate { finals, partial, seconds: live.samples as f64 / SAMPLE_RATE as f64, level }
}
