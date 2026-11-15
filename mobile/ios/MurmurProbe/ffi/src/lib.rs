//! The afternoon test (docs/MOBIL.md): the computer's live engine (`murmur-core`) on the phone,
//! behind a few C functions the Swift app calls. One `Probe` per app, used from one queue.
//!
//! Every function that returns text returns a JSON string the caller frees with
//! `mm_string_free`. Times are in seconds.

use std::ffi::{CStr, CString, c_char};
use std::path::Path;
use std::time::Instant;

use murmur_core::engine::{Event, LiveModel, LoadOptions, Models, SAMPLE_RATE};
use murmur_core::pipeline::{Pipeline, Source};
use serde_json::{Value, json};

/// Speaker threshold, as the computer's default (Settings.threshold).
const THRESHOLD: f32 = 0.7;
const MAX_SPEAKERS: usize = 6;

pub struct Probe {
    models: &'static Models,
    live: Option<Live>,
    /// How long loading the models took.
    load_s: f64,
}

struct Live {
    pipeline: Pipeline<'static>,
    /// Audio pushed and time spent processing it: their ratio is the real-time factor.
    audio_s: f64,
    compute_s: f64,
}

fn text(value: Value) -> *mut c_char {
    CString::new(value.to_string()).unwrap_or_default().into_raw()
}

fn error(e: impl std::fmt::Display) -> *mut c_char {
    text(json!({ "error": format!("{e:#}") }))
}

fn events(list: Vec<Event>) -> Vec<Value> {
    list.into_iter().filter_map(|e| serde_json::to_value(e).ok()).collect()
}

/// Loads the live models from `models_dir` (the Conformer in int8 if `int8`, on `threads`
/// threads). Null if they can't be loaded; the reason goes to `mm_load_error`.
///
/// # Safety
/// `models_dir` is a valid C string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mm_probe_new(models_dir: *const c_char, int8: bool, threads: i32) -> *mut Probe {
    let dir = unsafe { CStr::from_ptr(models_dir) }.to_string_lossy().into_owned();
    let started = Instant::now();
    match Models::load_with(Path::new(&dir), LiveModel::CatalanSpanish, LoadOptions { int8, threads }) {
        Ok(models) => Box::into_raw(Box::new(Probe { models: Box::leak(Box::new(models)), live: None, load_s: started.elapsed().as_secs_f64() })),
        Err(e) => {
            set_load_error(format!("{e:#}"));
            std::ptr::null_mut()
        }
    }
}

static LOAD_ERROR: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

fn set_load_error(e: String) {
    if let Ok(mut slot) = LOAD_ERROR.lock() {
        *slot = e;
    }
}

/// Why the last `mm_probe_new` failed.
#[unsafe(no_mangle)]
pub extern "C" fn mm_load_error() -> *mut c_char {
    text(json!({ "error": LOAD_ERROR.lock().map(|e| e.clone()).unwrap_or_default() }))
}

/// Seconds it took to load the models.
///
/// # Safety
/// `probe` comes from `mm_probe_new`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mm_probe_load_seconds(probe: *const Probe) -> f64 {
    unsafe { &*probe }.load_s
}

/// Starts a live session on the microphone (one channel, everyone in the room). `partials`: the
/// provisional text while someone speaks, which costs about ten times the final text.
///
/// # Safety
/// `probe` comes from `mm_probe_new`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mm_live_start(probe: *mut Probe, partials: bool) -> *mut c_char {
    let probe = unsafe { &mut *probe };
    match Pipeline::new(probe.models, true, false, MAX_SPEAKERS, THRESHOLD, None, vec![]) {
        Ok(mut pipeline) => {
            pipeline.set_partials(partials);
            probe.live = Some(Live { pipeline, audio_s: 0.0, compute_s: 0.0 });
            text(json!({ "ok": true }))
        }
        Err(e) => error(e),
    }
}

/// 16 kHz mono samples from the microphone. Returns `{events, audio_s, compute_s}`.
///
/// # Safety
/// `probe` comes from `mm_probe_new`; `samples` holds `count` floats.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mm_live_push(probe: *mut Probe, samples: *const f32, count: usize) -> *mut c_char {
    let probe = unsafe { &mut *probe };
    let Some(live) = probe.live.as_mut() else { return error("no live session") };
    let samples = unsafe { std::slice::from_raw_parts(samples, count) };
    let started = Instant::now();
    let list = live.pipeline.push(Source::Mic, samples);
    live.compute_s += started.elapsed().as_secs_f64();
    live.audio_s += count as f64 / SAMPLE_RATE as f64;
    text(json!({ "events": events(list), "audio_s": live.audio_s, "compute_s": live.compute_s }))
}

/// Ends the live session: the last sentence. Returns `{events, audio_s, compute_s}`.
///
/// # Safety
/// `probe` comes from `mm_probe_new`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mm_live_finish(probe: *mut Probe) -> *mut c_char {
    let probe = unsafe { &mut *probe };
    let Some(live) = probe.live.take() else { return error("no live session") };
    let started = Instant::now();
    match live.pipeline.finish() {
        Ok((list, _)) => text(json!({ "events": events(list), "audio_s": live.audio_s, "compute_s": live.compute_s + started.elapsed().as_secs_f64() })),
        Err(e) => error(e),
    }
}

/// A 16 kHz mono 16-bit WAV through the same pipeline, as fast as the phone goes, in 100 ms
/// pieces as the microphone would bring them. Returns `{events, audio_s, compute_s}`.
///
/// # Safety
/// `probe` comes from `mm_probe_new`; `wav_path` is a valid C string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mm_bench_file(probe: *mut Probe, wav_path: *const c_char, partials: bool) -> *mut c_char {
    let probe = unsafe { &mut *probe };
    let path = unsafe { CStr::from_ptr(wav_path) }.to_string_lossy().into_owned();
    let samples = match read_wav(Path::new(&path)) {
        Ok(s) => s,
        Err(e) => return error(e),
    };
    let mut pipeline = match Pipeline::new(probe.models, true, false, MAX_SPEAKERS, THRESHOLD, None, vec![]) {
        Ok(p) => p,
        Err(e) => return error(e),
    };
    pipeline.set_partials(partials);
    let started = Instant::now();
    let mut list = vec![];
    for chunk in samples.chunks(SAMPLE_RATE / 10) {
        list.extend(pipeline.push(Source::Mic, chunk));
    }
    match pipeline.finish() {
        Ok((last, _)) => list.extend(last),
        Err(e) => return error(e),
    }
    let finals: Vec<Event> = list.into_iter().filter(|e| matches!(e, Event::Final { .. })).collect();
    text(json!({ "events": events(finals), "audio_s": samples.len() as f64 / SAMPLE_RATE as f64, "compute_s": started.elapsed().as_secs_f64() }))
}

/// Frees a string returned by any of these functions.
///
/// # Safety
/// `s` comes from one of these functions and is freed once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mm_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(unsafe { CString::from_raw(s) });
    }
}

/// The samples of a 16 kHz mono 16-bit WAV (the repo's test recordings).
fn read_wav(path: &Path) -> anyhow::Result<Vec<f32>> {
    let bytes = std::fs::read(path)?;
    let data = bytes.windows(4).position(|w| w == b"data").ok_or_else(|| anyhow::anyhow!("not a WAV"))? + 8;
    Ok(bytes[data..].chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]]) as f32 / 32768.0).collect())
}
