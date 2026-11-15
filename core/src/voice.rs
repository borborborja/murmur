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

/// A word as compared: lowercase, without accents or marks ("Sóc," → "soc").
fn fold(word: &str) -> String {
    word.chars()
        .filter_map(|c| {
            let c = match c {
                'à' | 'á' | 'À' | 'Á' => 'a',
                'è' | 'é' | 'È' | 'É' => 'e',
                'í' | 'ï' | 'Í' | 'Ï' => 'i',
                'ò' | 'ó' | 'Ò' | 'Ó' => 'o',
                'ú' | 'ü' | 'Ú' | 'Ü' => 'u',
                'ç' | 'Ç' => 'c',
                'ñ' | 'Ñ' => 'n',
                c => c,
            };
            c.is_alphanumeric().then(|| c.to_ascii_lowercase())
        })
        .collect()
}

fn close(a: &str, b: &str) -> bool {
    if a == b {
        return true;
    }
    // A long word heard with one letter off, or cut short ("reconèixer" as "reconeix").
    let (short, long) = if a.len() <= b.len() { (a, b) } else { (b, a) };
    if short.len() >= 4 && long.starts_with(short) {
        return true;
    }
    if short.len() < 4 {
        return false;
    }
    let (a, b): (Vec<char>, Vec<char>) = (a.chars().collect(), b.chars().collect());
    let mut d: Vec<usize> = (0..=b.len()).collect();
    for i in 1..=a.len() {
        let mut prev = d[0];
        d[0] = i;
        for j in 1..=b.len() {
            let tmp = d[j];
            d[j] = (d[j] + 1).min(d[j - 1] + 1).min(prev + usize::from(a[i - 1] != b[j - 1]));
            prev = tmp;
        }
    }
    d[b.len()] <= 1
}

/// How many words of `sentence` have been read, going by what the live engine heard: each word
/// heard moves on to the next few words of the sentence that it matches (a word skipped or
/// misheard doesn't hold it back). The karaoke of "La teva veu".
pub fn progress(sentence: &[&str], heard: &str) -> usize {
    let words: Vec<String> = sentence.iter().map(|w| fold(w)).collect();
    let mut at = 0;
    for word in heard.split_whitespace().map(fold).filter(|w| !w.is_empty()) {
        if let Some(j) = (at..(at + 3).min(words.len())).find(|&j| close(&words[j], &word)) {
            at = j + 1;
        }
    }
    at
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_karaoke_follows_what_was_heard() {
        let s: Vec<&str> = "Bon dia. Sóc la veu que Murmur ha d'aprendre a reconèixer a les reunions".split(' ').collect();
        assert_eq!(progress(&s, ""), 0);
        assert_eq!(progress(&s, "bon dia soc la"), 4);
        // Misheard and skipped words don't stop it; the next ones that match move it on.
        assert_eq!(progress(&s, "bon dia so la veu que mur mur ha"), 8);
        assert_eq!(progress(&s, "bon dia soc la veu que murmur ha daprendre a reconeix"), 11);
        // Words that aren't in the sentence don't move it.
        assert_eq!(progress(&s, "hola què tal"), 0);
    }

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
