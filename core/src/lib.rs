//! Murmur's engine, shared by the computer app (`app/src-tauri`) and, later, the mobile apps:
//! live transcription (sherpa-onnx), speakers, "La teva veu", the glossary, punctuation, the
//! Opus recording and the meetings database. Nothing here knows about windows or the UI; texts
//! that reach people go through `tr!` (i18n.rs).

pub mod aec;
pub mod db;
pub mod diarize;
pub mod engine;
pub mod glossary;
pub mod i18n;
pub mod models;
pub mod pipeline;
pub mod playback;
pub mod punctuate;
pub mod recorder;
pub mod storage;
pub mod voice;
