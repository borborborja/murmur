//! "La teva veu" (onboarding, Configuració › Àudio): a voiceprint of the user, so live clustering
//! knows them as speaker 0, "Tu", even with more people on the same microphone
//! (`LiveSession::known_speakers`, the same as "Continua").
//!
//! Only the print is kept (TitaNet, the live model, normalised), never the recording: a JSON
//! file in the app's data folder that never leaves the computer and can be deleted. It's
//! biometric data, and the interface says so. A print made with another embedding model is
//! ignored (it couldn't be compared).

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};
use sherpa_onnx::{SileroVadModelConfig, SpeakerEmbeddingExtractor, SpeakerEmbeddingExtractorConfig, VadModelConfig, VoiceActivityDetector};

use crate::engine::{SAMPLE_RATE, embedding_model};
use crate::i18n::tr;

/// Less speech than this makes a weak print: read the sentence again.
pub const MIN_SPEECH_S: f32 = 4.0;
const VAD_WINDOW: usize = 512;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Voice {
    pub embedding: Vec<f32>,
    /// Seconds since the epoch (ms in the UI's Date).
    pub created_at: f64,
    /// The embedding model it was made with (`embedding_model`).
    pub model: String,
}

fn path(data: &Path) -> PathBuf {
    data.join("voice.json")
}

/// The saved voice, if there's one made with the current model.
pub fn load(data: &Path) -> Option<Voice> {
    let voice: Voice = serde_json::from_str(&std::fs::read_to_string(path(data)).ok()?).ok()?;
    (voice.model == embedding_model() && !voice.embedding.is_empty()).then_some(voice)
}

pub fn delete(data: &Path) -> Result<()> {
    match std::fs::remove_file(path(data)) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e.into()),
        _ => Ok(()),
    }
}

/// The speech in `samples` (16 kHz mono), without the silences around and between it.
fn speech(models: &Path, samples: &[f32]) -> Result<Vec<f32>> {
    let config = VadModelConfig {
        silero_vad: SileroVadModelConfig {
            model: Some(models.join("silero_vad.onnx").to_string_lossy().into_owned()),
            threshold: 0.5,
            min_silence_duration: 0.3,
            min_speech_duration: 0.25,
            window_size: VAD_WINDOW as i32,
            max_speech_duration: 30.0,
        },
        sample_rate: SAMPLE_RATE as i32,
        num_threads: 1,
        ..Default::default()
    };
    let vad = VoiceActivityDetector::create(&config, 60.0).ok_or_else(|| anyhow!(tr!("vad_load_failed")))?;
    let mut out = vec![];
    let mut drain = |vad: &VoiceActivityDetector| {
        while let Some(seg) = vad.front() {
            out.extend_from_slice(seg.samples());
            vad.pop();
        }
    };
    for window in samples.chunks(VAD_WINDOW) {
        if window.len() == VAD_WINDOW {
            vad.accept_waveform(window);
            drain(&vad);
        }
    }
    vad.flush();
    drain(&vad);
    Ok(out)
}

/// Makes and saves the print of the user reading the sentence. Err (translated) if there's too
/// little speech in it, so they read it again.
pub fn enroll(models: &Path, data: &Path, samples: &[f32]) -> Result<Voice> {
    let speech = speech(models, samples)?;
    let seconds = speech.len() as f32 / SAMPLE_RATE as f32;
    if seconds < MIN_SPEECH_S {
        return Err(anyhow!(tr!("voice_too_short")));
    }
    let extractor = SpeakerEmbeddingExtractor::create(&SpeakerEmbeddingExtractorConfig {
        model: Some(models.join(embedding_model()).to_string_lossy().into_owned()),
        num_threads: 2,
        ..Default::default()
    })
    .context(tr!("speaker_model_load_failed"))?;
    let stream = extractor.create_stream().ok_or_else(|| anyhow!(tr!("speaker_model_load_failed")))?;
    stream.accept_waveform(SAMPLE_RATE as i32, &speech);
    stream.input_finished();
    let mut embedding = extractor.compute(&stream).ok_or_else(|| anyhow!(tr!("voice_too_short")))?;
    let norm = embedding.iter().map(|x| x * x).sum::<f32>().sqrt() + 1e-9;
    embedding.iter_mut().for_each(|x| *x /= norm);
    let created_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0);
    let voice = Voice { embedding, created_at, model: embedding_model() };
    std::fs::create_dir_all(data)?;
    std::fs::write(path(data), serde_json::to_string(&voice)?)?;
    Ok(voice)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_voice_from_another_model_is_ignored_and_delete_is_quiet() {
        let dir = std::env::temp_dir().join(format!("murmur-voice-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        assert!(load(&dir).is_none());
        let other = Voice { embedding: vec![1.0], created_at: 0.0, model: "other.onnx".into() };
        std::fs::write(path(&dir), serde_json::to_string(&other).unwrap()).unwrap();
        assert!(load(&dir).is_none());
        let mine = Voice { model: embedding_model(), ..other };
        std::fs::write(path(&dir), serde_json::to_string(&mine).unwrap()).unwrap();
        assert!(load(&dir).is_some());
        delete(&dir).unwrap();
        delete(&dir).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }
}
