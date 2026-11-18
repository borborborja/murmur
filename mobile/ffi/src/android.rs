//! Android's JSON facade keeps the full desktop meeting contract across UniFFI, including
//! fields newer than a generated Kotlin record. The audio hot path uses typed Vec<f32> calls.

use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, anyhow};
use murmur_core::{
    diarize,
    engine::{Channel, LiveModel, SAMPLE_RATE},
    models::{self, ModelFile, OptionalModel},
    pipeline::Pipeline,
    playback,
    recorder::Recorder,
};
use serde_json::{Value, json};

use crate::{Live, MAX_SPEAKERS, Murmur, MurmurError, Result, THRESHOLD, lock, now_ms};

/// Rebuild a finalized Opus recording from the capture journal after process death. For a
/// continuation, keep the previous recording up to its resume offset, then append the PCM spool.
fn recover_capture(root: &Path, meeting: &Value, offset: f64) -> anyhow::Result<Option<f64>> {
    use std::io::{BufReader, Read};
    let id = meeting["id"].as_str().ok_or_else(|| anyhow!("meeting id missing"))?;
    anyhow::ensure!(!id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'), "invalid meeting id");
    let microphone = meeting["sources"]["mic"].as_bool().unwrap_or(true);
    let system = meeting["sources"]["system"].as_bool().unwrap_or(false);
    let sources = if microphone && system { vec!["mic", "system"] } else if system { vec!["system"] } else { vec!["mic"] };
    let paths = sources.iter().map(|source| root.join("capture").join(format!("{id}-{source}.pcm"))).collect::<Vec<_>>();
    let frames = paths.iter().filter_map(|path| std::fs::metadata(path).ok().map(|m| m.len() as usize / 2)).max().unwrap_or(0);
    if frames == 0 { return Ok(None); }
    let audio = root.join("recordings").join(format!("{id}.ogg"));
    let offset_frames = (offset * SAMPLE_RATE as f64).round() as usize;
    let prefix = if offset_frames > 0 {
        // Discard only the uncommitted trailing Ogg page; keep complete pages unchanged.
        let bytes = std::fs::read(&audio)?;
        let mut end = 0;
        while end + 27 <= bytes.len() && &bytes[end..end + 4] == b"OggS" {
            let segments = bytes[end + 26] as usize;
            if end + 27 + segments > bytes.len() { break; }
            let size = 27 + segments + bytes[end + 27..end + 27 + segments].iter().map(|n| *n as usize).sum::<usize>();
            if end + size > bytes.len() { break; }
            end += size;
        }
        let trimmed = audio.with_extension("recovery-prefix.ogg");
        std::fs::write(&trimmed, &bytes[..end])?;
        let decoded = playback::decode_channels(&trimmed);
        let _ = std::fs::remove_file(trimmed);
        let decoded = decoded?;
        anyhow::ensure!(decoded.len() == sources.len() && decoded.iter().all(|c| c.len() >= offset_frames), "recording prefix is incomplete");
        decoded
    } else { vec![vec![]; sources.len()] };
    let staged = audio.with_extension("recovered.ogg");
    let mut recorder = Recorder::create(&staged, sources.len())?;
    for start in (0..offset_frames).step_by(3200) {
        let mut block = Vec::with_capacity(3200 * sources.len());
        for frame in start..(start + 3200).min(offset_frames) { for channel in &prefix { block.push(channel[frame]); } }
        recorder.push(&block)?;
    }
    let mut inputs = paths.iter().map(|path| std::fs::File::open(path).ok().map(BufReader::new)).collect::<Vec<_>>();
    for start in (0..frames).step_by(3200) {
        let mut block = Vec::with_capacity(3200 * sources.len());
        for _frame in start..(start + 3200).min(frames) {
            for input in &mut inputs {
                let mut bytes = [0u8; 2];
                let value = match input.as_mut() {
                    Some(reader) => if reader.read_exact(&mut bytes).is_ok() { i16::from_le_bytes(bytes) as f32 / 32768.0 } else { 0.0 },
                    None => 0.0
                };
                block.push(value);
            }
        }
        recorder.push(&block)?;
    }
    recorder.finish()?;
    std::fs::rename(&staged, &audio)?;
    for path in paths { let _ = std::fs::remove_file(path); }
    Ok(Some((offset_frames + frames) as f64 / SAMPLE_RATE as f64))
}

#[cfg(test)]
mod capture_recovery_tests {
    use super::*;
    #[test]
    fn pcm_journal_recovers_and_keeps_a_continuation_prefix() {
        let root = std::env::temp_dir().join(format!("murmur-recover-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("capture")).unwrap();
        let pcm = (0..SAMPLE_RATE).flat_map(|i| ((i as f32 * 0.07).sin() * 8000.0).round().to_le_bytes()).collect::<Vec<_>>();
        // Use actual PCM16, rather than floating point bytes.
        let pcm = pcm.chunks_exact(4).flat_map(|bytes| (f32::from_le_bytes(bytes.try_into().unwrap()) as i16).to_le_bytes()).collect::<Vec<_>>();
        std::fs::write(root.join("capture/test-mic.pcm"), &pcm).unwrap();
        let meeting = json!({ "id": "test", "sources": { "mic": true, "system": false } });
        assert_eq!(recover_capture(&root, &meeting, 0.0).unwrap(), Some(1.0));
        let audio = root.join("recordings/test.ogg");
        assert!(playback::decode(&audio).unwrap().len() >= SAMPLE_RATE);
        assert!(!root.join("capture/test-mic.pcm").exists());
        std::fs::write(root.join("capture/test-mic.pcm"), pcm).unwrap();
        assert_eq!(recover_capture(&root, &meeting, 0.5).unwrap(), Some(1.5));
        assert!(playback::decode(&audio).unwrap().len() >= SAMPLE_RATE * 3 / 2);
        std::fs::remove_dir_all(root).unwrap();
    }
}

const MOBILE_TRANSCRIPTION: OptionalModel = OptionalModel {
    id: "transcription",
    dir: "",
    required: true,
    files: &[
        ModelFile {
            name: "conformer-ca-es-sherpa/encoder.int8.onnx",
            url: Some(
                "https://huggingface.co/quicodomingo/murmur-models/resolve/main/conformer-ca-es-sherpa/encoder.int8.onnx",
            ),
            bytes: 137_421_298,
            sha256: "1ddf969643c7174424709c2e9bfc1d4a57cf11588102ee680164e051f6493f7b",
        },
        ModelFile {
            name: "conformer-ca-es-sherpa/decoder.int8.onnx",
            url: Some(
                "https://huggingface.co/quicodomingo/murmur-models/resolve/main/conformer-ca-es-sherpa/decoder.int8.onnx",
            ),
            bytes: 3_464_344,
            sha256: "a6453fc579a36afcc26fea99a31a7a70135bee4e6c7370cbc37af40c9b446b0c",
        },
        ModelFile {
            name: "conformer-ca-es-sherpa/joiner.int8.onnx",
            url: Some(
                "https://huggingface.co/quicodomingo/murmur-models/resolve/main/conformer-ca-es-sherpa/joiner.int8.onnx",
            ),
            bytes: 913_589,
            sha256: "ef69fefc38781a2bbcba2c6ef339fa82e555c57639b27864fa29475da94e5250",
        },
        ModelFile {
            name: "conformer-ca-es-sherpa/tokens.txt",
            url: Some(
                "https://huggingface.co/quicodomingo/murmur-models/resolve/main/conformer-ca-es-sherpa/tokens.txt",
            ),
            bytes: 1_955,
            sha256: "0451223f8209d957022aa856857a470731b2dbbcf2ea39e83dee2f298d45682c",
        },
        ModelFile {
            name: "silero_vad.onnx",
            url: Some(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            ),
            bytes: 643_854,
            sha256: "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        },
        ModelFile {
            name: "nemo_en_titanet_small.onnx",
            url: Some(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx",
            ),
            bytes: 40_257_283,
            sha256: "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e",
        },
        ModelFile {
            name: "3dspeaker_eres2net_base.onnx",
            url: Some(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
            ),
            bytes: 39_593_761,
            sha256: "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b",
        },
        ModelFile {
            name: "sherpa-onnx-pyannote-segmentation-3-0/model.onnx",
            url: Some(
                "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
            ),
            bytes: 5_992_913,
            sha256: "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079",
        },
    ],
};

const MOBILE_LLM: OptionalModel = OptionalModel {
    id: "llm-mobile",
    dir: "llm",
    required: false,
    files: &[ModelFile {
        name: "gemma-4-E2B-it-Q4_0.gguf",
        url: Some(
            "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/2a8538c2e2d4687953a86c422c7c623cfd022880/gemma-4-E2B-it-Q4_0.gguf",
        ),
        bytes: 2_841_481_184,
        sha256: "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52",
    }],
};

const SMALL_WHISPER: OptionalModel = OptionalModel {
    id: "whisper-small",
    dir: "whisper-ggml",
    required: false,
    files: &[ModelFile {
        name: "ggml-small-q5_1.bin",
        url: Some(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin",
        ),
        bytes: 190_085_487,
        sha256: "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
    }],
};

fn catalog() -> Vec<&'static OptionalModel> {
    vec![
        &MOBILE_TRANSCRIPTION,
        &models::ENGLISH,
        &models::PUNCTUATION,
        &models::WHISPER_CA,
        &models::WHISPER,
        &SMALL_WHISPER,
        &MOBILE_LLM,
        &models::LLM,
    ]
}

fn model(id: &str) -> anyhow::Result<&'static OptionalModel> {
    catalog()
        .into_iter()
        .find(|m| m.id == id)
        .ok_or_else(|| anyhow!("unknown model: {id}"))
}

fn text<'a>(v: &'a Value, key: &str) -> anyhow::Result<&'a str> {
    v.get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow!("missing {key}"))
}
fn strings(v: &Value) -> Vec<String> {
    v.as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(str::to_owned)
        .collect()
}

#[uniffi::export]
impl Murmur {
    /// Full-fidelity JSON operations used by Android and the optional direct MCP server.
    pub fn android_call(&self, action: String, payload: String) -> Result<String> {
        let p: Value = serde_json::from_str(&payload).map_err(anyhow::Error::from)?;
        self.android_action(&action, &p)
            .map(|v| v.to_string())
            .map_err(Into::into)
    }

    /// Releases the live ASR model before Whisper or a local LLM takes the memory budget.
    pub fn unload_models(&self) -> Result<()> {
        let live = lock(&self.live);
        if live.is_some() {
            return Err(MurmurError::Busy);
        }
        *lock(&self.models) = None;
        Ok(())
    }

    pub fn resume(&self, id: String, more_in_room: bool, partial_every: f32) -> Result<String> {
        let mut live_slot = lock(&self.live);
        if live_slot.is_some() {
            return Err(MurmurError::Busy);
        }
        let mut meeting = lock(&self.db)
            .get(&id)?
            .ok_or_else(|| anyhow!("meeting missing"))?;
        let language = meeting["language"].as_str().unwrap_or("ca-es");
        let models = self.models_for(LiveModel::for_language(language))?;
        // SAFETY: Live owns this Arc and drops its pipeline first, as in start_with_sources.
        let model_ref = unsafe { &*(&models.0 as *const murmur_core::engine::Models) };
        let microphone = meeting["sources"]["mic"].as_bool().unwrap_or(true);
        let system = meeting["sources"]["system"].as_bool().unwrap_or(false);
        let audio = self
            .audio_of(&meeting)
            .ok_or_else(|| anyhow!("meeting audio missing"))?;
        let offset = meeting["duration"].as_f64().unwrap_or(0.0);
        let recorder = Recorder::resume(&audio, if microphone && system { 2 } else { 1 })?;
        let (max_speakers, threshold) = *lock(&self.live_options);
        let mut pipeline = Pipeline::new(
            model_ref,
            microphone,
            system,
            if more_in_room || system {
                max_speakers
            } else {
                1
            },
            threshold,
            Some(recorder),
            vec![],
        )?;
        pipeline.set_offset(offset as f32);
        pipeline.set_partials(partial_every > 0.0);
        if partial_every > 0.0 {
            pipeline.set_partial_every(partial_every);
        }
        let glossary = lock(&self.db).glossary()?;
        pipeline.set_glossary(murmur_core::glossary::with_guests(
            &glossary,
            &strings(&meeting["guests"]),
        ));
        // Reuse a representative voice from each earlier speaker, not just their old numbers.
        let channels = playback::decode_channels(&audio)?;
        for (c, samples) in channels.iter().enumerate() {
            let base = if c == 1 || (!microphone && system) {
                100
            } else {
                0
            };
            let mut voices = std::collections::BTreeMap::new();
            for u in meeting["utterances"].as_array().into_iter().flatten() {
                let speaker = u["speaker"].as_u64().unwrap_or(0) as usize;
                if (speaker >= 100) != (base == 100) || voices.contains_key(&speaker) {
                    continue;
                }
                let start = (u["start"].as_f64().unwrap_or(0.0) * SAMPLE_RATE as f64) as usize;
                let end = (u["end"].as_f64().unwrap_or(0.0) * SAMPLE_RATE as f64) as usize;
                if end.saturating_sub(start) < SAMPLE_RATE {
                    continue;
                }
                if let Some(slice) = samples.get(start..end.min(samples.len())) {
                    if let Some(embedding) = models.0.embed(slice) {
                        voices.insert(speaker - base, embedding);
                    }
                }
            }
            let next = voices.keys().max().map_or(0, |s| s + 1);
            pipeline.known_speakers(
                if base == 100 {
                    Channel::System
                } else {
                    Channel::Mic
                },
                voices.into_iter().collect(),
                next,
            );
        }
        let created = now_ms();
        let mut parts = meeting["parts"].as_array().cloned().unwrap_or_default();
        parts.push(json!({ "startedAt": created, "at": offset }));
        meeting["parts"] = json!(parts);
        meeting["status"] = json!("recording");
        let lines = meeting["utterances"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        lock(&self.db).put(&meeting)?;
        let generation = format!(
            "{id}-r{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        );
        *live_slot = Some(Live {
            pipeline,
            _models: models,
            meeting,
            samples: 0,
            system_samples: 0,
            offset,
            line_generation: generation,
            lines,
        });
        Ok(id)
    }
}

impl Murmur {
    fn android_action(&self, action: &str, p: &Value) -> anyhow::Result<Value> {
        Ok(match action {
            "meetings" | "list" => json!({ "meetings": lock(&self.db).list()? }),
            "get" => json!({ "meeting": lock(&self.db).get(text(p,"id")?)? }),
            "put" | "checkpoint" => {
                let meeting = p.get("meeting").unwrap_or(p);
                lock(&self.db).put(meeting)?;
                // Recording checkpoints must also update the in-memory metadata. Preserve
                // the engine's transcript until stop, and apply user edits by stable line ID.
                if let Some(live) = lock(&self.live).as_mut() {
                    if live.meeting["id"] == meeting["id"] {
                        for (k, v) in meeting.as_object().into_iter().flatten() {
                            if k != "utterances" {
                                live.meeting[k] = v.clone();
                            }
                        }
                        for edited in meeting["utterances"].as_array().into_iter().flatten() {
                            if edited["edited"] == true || edited["speakerLocked"] == true {
                                if let Some(line) = live
                                    .lines
                                    .iter_mut()
                                    .find(|line| line["id"] == edited["id"])
                                {
                                    *line = edited.clone();
                                }
                            }
                        }
                    }
                }
                self.note_edit(meeting["id"].as_str().unwrap_or_default());
                json!({})
            }
            "delete" => {
                self.delete_meeting(text(p, "id")?.into())
                    .map_err(|e| anyhow!(e))?;
                json!({})
            }
            "search" => {
                let hits = lock(&self.db).search(p["query"].as_str().unwrap_or_default(), 100)?;
                json!({ "hits": hits })
            }
            "rename_tag" | "tag_rename" => {
                lock(&self.db).rename_tag(text(p, "from")?, text(p, "to")?)?;
                json!({})
            }
            "delete_tag" | "tag_delete" => {
                lock(&self.db).delete_tag(text(p, "name")?)?;
                json!({})
            }
            "set_language" => {
                murmur_core::i18n::set(p["language"].as_str().unwrap_or("ca"));
                json!({})
            }
            "start" => {
                *lock(&self.live_options) = (p["maxSpeakers"].as_u64().unwrap_or(MAX_SPEAKERS as u64).clamp(1, 50) as usize,
                    (p["threshold"].as_f64().unwrap_or(THRESHOLD as f64) as f32).clamp(0.1, 0.99));
                json!({ "id": self.start_with_sources(p["title"].as_str().unwrap_or_default().into(),p["language"].as_str().unwrap_or("ca-es").into(),p["roomSpeakers"].as_bool().unwrap_or(false),p["partialEvery"].as_f64().unwrap_or(1.5) as f32,p["mic"].as_bool().unwrap_or(true),p["system"].as_bool().unwrap_or(false)).map_err(|e| anyhow!(e))? })
            }
            "resume" => {
                *lock(&self.live_options) = (p["maxSpeakers"].as_u64().unwrap_or(MAX_SPEAKERS as u64).clamp(1, 50) as usize,
                    (p["threshold"].as_f64().unwrap_or(THRESHOLD as f64) as f32).clamp(0.1, 0.99));
                json!({ "id": self.resume(text(p,"id")?.into(),p["roomSpeakers"].as_bool().unwrap_or(false),p["partialEvery"].as_f64().unwrap_or(1.5) as f32).map_err(|e| anyhow!(e))? })
            }
            "stop" => {
                let id = self
                    .stop(p.get("extra").unwrap_or(p).to_string())
                    .map_err(|e| anyhow!(e))?;
                json!({ "id": id, "meeting": lock(&self.db).get(&id)? })
            }
            "discard" => {
                self.discard().map_err(|e| anyhow!(e))?;
                json!({})
            }
            "unload" => {
                self.unload_models().map_err(|e| anyhow!(e))?;
                json!({})
            }
            "edit_text" => {
                let id = text(p, "id")?;
                let line = text(p, "line")?;
                let value = text(p, "text")?;
                self.edit(id, |m| {
                    for u in m["utterances"].as_array_mut().into_iter().flatten() {
                        if u["id"] == line {
                            u["text"] = json!(value);
                            u["edited"] = json!(true);
                        }
                    }
                })
                .map_err(|e| anyhow!(e))?;
                json!({})
            }
            "speaker_name" => {
                self.set_speaker_name(
                    text(p, "id")?.into(),
                    p["speaker"].as_i64().unwrap_or(0),
                    text(p, "name")?.into(),
                )
                .map_err(|e| anyhow!(e))?;
                json!({})
            }
            "line_speaker" => {
                self.set_line_speaker(
                    text(p, "id")?.into(),
                    text(p, "line")?.into(),
                    p["speaker"].as_i64().unwrap_or(0),
                )
                .map_err(|e| anyhow!(e))?;
                json!({})
            }
            "merge_speakers" => {
                self.merge_speakers(
                    text(p, "id")?.into(),
                    p["from"].as_i64().unwrap_or(0),
                    p["into"].as_i64().or_else(|| p["to"].as_i64()).unwrap_or(0),
                )
                .map_err(|e| anyhow!(e))?;
                json!({})
            }
            "glossary" => {
                if let Some(terms) = p.get("terms") {
                    lock(&self.db).set_glossary(&strings(terms))?;
                }
                let db = lock(&self.db);
                json!({ "terms": db.glossary()?, "hits": db.glossary_hits()? })
            }
            "glossary_correct" => {
                let terms = murmur_core::glossary::with_guests(&lock(&self.db).glossary()?, &strings(&p["guests"]));
                let corrected = strings(&p["texts"]).iter().map(|text| murmur_core::glossary::correct(text, &terms)).collect::<Vec<_>>();
                lock(&self.db).add_glossary_hits(&murmur_core::glossary::take_hits())?;
                json!({ "texts": corrected })
            }
            "voice_status" => json!({ "savedAt": self.voice_saved() }),
            "voice_delete" => {
                self.delete_voice().map_err(|e| anyhow!(e))?;
                json!({})
            }
            "voice_enroll" => {
                let samples = if p["samples"].is_array() { serde_json::from_value(p["samples"].clone())? }
                    else { read_audio(Path::new(text(p, "path")?))? };
                json!({ "savedAt": self.enroll_voice(samples).map_err(|e| anyhow!(e))? })
            }
            "recover_capture" => {
                let id = text(p, "id")?;
                let mut meeting = lock(&self.db).get(id)?.ok_or_else(|| anyhow!("meeting missing"))?;
                if let Some(seconds) = recover_capture(&self.data_dir, &meeting, p["offset"].as_f64().unwrap_or(0.0))? {
                    meeting["duration"] = json!(seconds);
                }
                meeting["status"] = json!("done");
                lock(&self.db).put(&meeting)?;
                json!({ "meeting": meeting })
            }
            "voice_progress" => {
                let samples: Vec<f32> = serde_json::from_value(p["samples"].clone())?;
                json!({ "said": self.voice_progress(text(p,"sentence")?.into(),samples).map_err(|e| anyhow!(e))? })
            }
            "model_catalog" | "models" => json!({ "models": catalog().iter().map(|m| {
                let mut value = serde_json::to_value(m.status(&self.models_dir)).unwrap();
                value["files"] = json!(m.files.iter().map(|f| json!({ "path": Path::new(m.dir).join(f.name).to_string_lossy(), "url": f.url, "bytes": f.bytes, "sha256": f.sha256 })).collect::<Vec<_>>());
                value
            }).collect::<Vec<_>>() }),
            "model_download" => {
                model(text(p, "id")?)?.download(
                    &self.models_dir,
                    &AtomicBool::new(false),
                    |_, _| {},
                )?;
                json!({})
            }
            "model_delete" => {
                self.unload_models().map_err(|e| anyhow!(e))?;
                model(text(p, "id")?)?.delete(&self.models_dir)?;
                json!({})
            }
            "decode_audio" => {
                let (samples, original_channels) = read_audio_channel(
                    Path::new(text(p, "path")?),
                    p["channel"].as_u64().map(|c| c as usize),
                )?;
                let dest = PathBuf::from(text(p, "dest")?);
                write_wav(&dest, &samples)?;
                json!({ "path": dest.to_string_lossy(), "seconds": samples.len() as f64 / SAMPLE_RATE as f64, "channels": 1, "originalChannels": original_channels, "channelCount": original_channels, "sampleRate": SAMPLE_RATE })
            }
            "diarize" => {
                self.unload_models().map_err(|e| anyhow!(e))?;
                let samples = read_audio_channel(
                    Path::new(text(p, "path")?),
                    p["channel"].as_u64().map(|c| c as usize),
                )?
                .0;
                let spans: Vec<diarize::Span> = p["spans"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .map(|s| diarize::Span {
                        start: s["start"].as_f64().unwrap_or(0.0) as f32,
                        end: s["end"].as_f64().unwrap_or(0.0) as f32,
                    })
                    .collect();
                if spans.is_empty() {
                    let turns =
                        diarize::diarize(&self.models_dir, &samples, diarize::Options::default())?;
                    json!({ "assignments": turns.iter().map(|t| json!({ "start": t.start, "end": t.end, "speaker": t.speaker })).collect::<Vec<_>>() })
                } else {
                    let assignments = diarize::verify(
                        &self.models_dir,
                        &samples,
                        &spans,
                        p["base"].as_u64().unwrap_or(0) as usize,
                        p["maxSpeakers"].as_u64().unwrap_or(MAX_SPEAKERS as u64) as usize,
                        || {},
                    )?;
                    json!({ "assignments": assignments })
                }
            }
            "speech_spans" => {
                use sherpa_onnx::{SileroVadModelConfig, VadModelConfig, VoiceActivityDetector};
                let samples = read_audio(Path::new(text(p, "path")?))?;
                let config = VadModelConfig {
                    silero_vad: SileroVadModelConfig {
                        model: Some(
                            self.models_dir
                                .join("silero_vad.onnx")
                                .to_string_lossy()
                                .into_owned(),
                        ),
                        threshold: 0.5,
                        min_silence_duration: 0.5,
                        min_speech_duration: 0.25,
                        window_size: 512,
                        max_speech_duration: 15.0,
                    },
                    sample_rate: SAMPLE_RATE as i32,
                    num_threads: 1,
                    ..Default::default()
                };
                let vad = VoiceActivityDetector::create(&config, 60.0)
                    .ok_or_else(|| anyhow!("VAD model could not be loaded"))?;
                let mut spans = Vec::new();
                let mut take = |vad: &VoiceActivityDetector| {
                    while let Some(segment) = vad.front() {
                        let start = segment.start() as f64 / SAMPLE_RATE as f64;
                        spans.push(json!({"start":start,"end":start + segment.samples().len() as f64 / SAMPLE_RATE as f64}));
                        vad.pop();
                    }
                };
                for window in samples.chunks(512) {
                    vad.accept_waveform(window);
                    take(&vad);
                }
                vad.flush();
                take(&vad);
                json!({"spans":spans})
            }
            "punctuate" => {
                #[cfg(feature = "punctuation")]
                {
                    self.unload_models().map_err(|e| anyhow!(e))?;
                    let texts = strings(&p["texts"]);
                    let speakers = p["speakers"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .map(|s| s.as_i64().unwrap_or(0) as i32)
                        .collect::<Vec<_>>();
                    let mut punctuator = murmur_core::punctuate::Punctuator::load(
                        &models::PUNCTUATION.path(&self.models_dir),
                    )?;
                    json!({ "texts": punctuator.punctuate(&texts,&speakers)? })
                }
                #[cfg(not(feature = "punctuation"))]
                {
                    return Err(anyhow!(
                        "punctuation runtime was not included in this build"
                    ));
                }
            }
            "pair" => {
                let key: Vec<u8> = serde_json::from_value(p["privateKey"].clone())?;
                let paired = self
                    .pair(
                        text(p, "uri")?.into(),
                        strings(&p["addresses"]),
                        key,
                        p["name"].as_str().unwrap_or("Android").into(),
                    )
                    .map_err(|e| anyhow!(e))?;
                json!({ "computer": { "name": paired.name, "key": paired.key, "address": paired.address } })
            }
            "new_sync_key" => {
                json!({ "key": crate::pairing::new_sync_key().map_err(|e| anyhow!(e))? })
            }
            "forget_computer" => {
                self.forget_computer().map_err(|e| anyhow!(e))?;
                json!({})
            }
            "sync" => {
                let private: Vec<u8> = serde_json::from_value(p["privateKey"].clone())?;
                let public: Vec<u8> = serde_json::from_value(p["computerKey"].clone())?;
                let report = self
                    .sync(
                        strings(&p["addresses"]),
                        private,
                        public,
                        p["name"].as_str().unwrap_or("Android").into(),
                        p["keepAudio"].as_bool().unwrap_or(true),
                    )
                    .map_err(|e| anyhow!(e))?;
                json!({ "address": report.address, "name": report.computer_name, "sent": report.sent, "revised": report.revised, "received": report.revised, "failed": report.failed })
            }
            "status" => {
                json!({ "recording": self.is_recording(), "unsent": self.unsent_count().map_err(|e| anyhow!(e))?, "punctuation": cfg!(feature="punctuation") })
            }
            _ => return Err(anyhow!("unknown engine action: {action}")),
        })
    }
}

/// The same file decoder as the desktop, including Opus carried by Matroska/WebM.
fn read_audio(path: &Path) -> anyhow::Result<Vec<f32>> {
    read_audio_channel(path, None).map(|(samples, _)| samples)
}

fn read_audio_channel(path: &Path, selected: Option<usize>) -> anyhow::Result<(Vec<f32>, usize)> {
    if let Ok(channels) = playback::decode_channels(path) {
        let count = channels.len();
        if let Some(c) = selected {
            return Ok((
                channels
                    .get(c)
                    .ok_or_else(|| anyhow!("audio channel missing"))?
                    .clone(),
                count,
            ));
        }
        let mixed = if count == 1 {
            channels[0].clone()
        } else {
            (0..channels[0].len())
                .map(|i| (channels.iter().map(|c| c[i]).sum::<f32>() * 0.7).clamp(-1.0, 1.0))
                .collect()
        };
        return Ok((mixed, count));
    }
    use symphonia::core::{
        audio::SampleBuffer,
        codecs::{CODEC_TYPE_NULL, CODEC_TYPE_OPUS, DecoderOptions},
        errors::Error,
        formats::FormatOptions,
        io::MediaSourceStream,
        meta::MetadataOptions,
        probe::Hint,
    };
    let stream = MediaSourceStream::new(Box::new(std::fs::File::open(path)?), Default::default());
    let mut hint = Hint::new();
    if let Some(ext) = path.extension().and_then(|s| s.to_str()) {
        hint.with_extension(ext);
    }
    let mut format = symphonia::default::get_probe()
        .format(
            &hint,
            stream,
            &FormatOptions::default(),
            &MetadataOptions::default(),
        )?
        .format;
    let track = format
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != CODEC_TYPE_NULL && t.codec_params.sample_rate.is_some())
        .ok_or_else(|| anyhow!("no audio track"))?;
    let id = track.id;
    let mut rate = track.codec_params.sample_rate.unwrap_or(SAMPLE_RATE as u32);
    let channels = track.codec_params.channels.map_or(2, |c| c.count());
    if selected.is_some_and(|c| c >= channels) {
        return Err(anyhow!("audio channel missing"));
    }
    let mut opus = if track.codec_params.codec == CODEC_TYPE_OPUS {
        rate = 48_000;
        Some(opus::Decoder::new(
            rate,
            if channels == 1 {
                opus::Channels::Mono
            } else {
                opus::Channels::Stereo
            },
        )?)
    } else {
        None
    };
    let mut decoder = if opus.is_none() {
        Some(
            symphonia::default::get_codecs()
                .make(&track.codec_params, &DecoderOptions::default())?,
        )
    } else {
        None
    };
    let mut out = Vec::new();
    let mut resampler = Resampler::new(rate);
    let mut pcm = vec![0.0; 5760 * 2];
    loop {
        let packet = match format.next_packet() {
            Ok(p) => p,
            Err(Error::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(e.into()),
        };
        if packet.track_id() != id {
            continue;
        }
        let mono: Vec<f32> = if let Some(opus) = opus.as_mut() {
            let n = opus.decode_float(&packet.data, &mut pcm, false)?;
            pcm[..n * channels.min(2)]
                .chunks_exact(channels.min(2))
                .map(|f| selected.map_or_else(|| f.iter().sum::<f32>() / f.len() as f32, |c| f[c]))
                .collect()
        } else {
            let decoded = match decoder.as_mut().unwrap().decode(&packet) {
                Ok(d) => d,
                Err(Error::DecodeError(_)) => continue,
                Err(e) => return Err(e.into()),
            };
            let count = decoded.spec().channels.count().max(1);
            let mut buffer = SampleBuffer::<f32>::new(decoded.capacity() as u64, *decoded.spec());
            buffer.copy_interleaved_ref(decoded);
            buffer
                .samples()
                .chunks_exact(count)
                .map(|f| selected.map_or_else(|| f.iter().sum::<f32>() / count as f32, |c| f[c]))
                .collect()
        };
        resampler.process(&mono, &mut out);
    }
    if out.is_empty() {
        return Err(anyhow!("no audio samples"));
    }
    Ok((out, channels))
}

/// Linear interpolation with state across packets, matching the desktop capture resampler.
struct Resampler {
    ratio: f64,
    position: f64,
    previous: f32,
    first: bool,
}
impl Resampler {
    fn new(rate: u32) -> Self {
        Self {
            ratio: rate as f64 / SAMPLE_RATE as f64,
            position: 0.0,
            previous: 0.0,
            first: true,
        }
    }
    fn process(&mut self, samples: &[f32], out: &mut Vec<f32>) {
        if samples.is_empty() {
            return;
        }
        if self.ratio == 1.0 {
            out.extend_from_slice(samples);
            return;
        }
        if self.first {
            self.previous = samples[0];
            self.first = false;
        }
        while self.position < samples.len() as f64 {
            let index = self.position.floor() as usize;
            let fraction = (self.position - index as f64) as f32;
            let before = if index == 0 {
                self.previous
            } else {
                samples[index - 1]
            };
            let after = samples[index];
            out.push(before + (after - before) * fraction);
            self.position += self.ratio;
        }
        self.position -= samples.len() as f64;
        self.previous = *samples.last().unwrap();
    }
}

fn write_wav(path: &Path, samples: &[f32]) -> anyhow::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut wav = hound::WavWriter::create(
        path,
        hound::WavSpec {
            channels: 1,
            sample_rate: SAMPLE_RATE as u32,
            bits_per_sample: 16,
            sample_format: hound::SampleFormat::Int,
        },
    )
    .context("create wav")?;
    for sample in samples {
        wav.write_sample((sample.clamp(-1.0, 1.0) * 32767.0) as i16)?;
    }
    wav.finalize()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn model_profiles_have_integrity_and_compact_transcription() {
        assert!(
            MOBILE_TRANSCRIPTION
                .files
                .iter()
                .map(|f| f.bytes)
                .sum::<u64>()
                < 250_000_000
        );
        for m in catalog() {
            for f in m.files {
                assert_eq!(f.sha256.len(), 64);
                assert!(f.url.unwrap().starts_with("https://"));
                assert!(f.bytes > 0);
            }
        }
        assert!(
            MOBILE_TRANSCRIPTION
                .files
                .iter()
                .any(|f| f.name == "conformer-ca-es-sherpa/encoder.int8.onnx")
        );
    }

    #[test]
    fn resampling_is_independent_of_packet_boundaries() {
        let input: Vec<f32> = (0..48_000).map(|i| (i as f32 / 40.0).sin()).collect();
        let mut whole = Vec::new();
        Resampler::new(48_000).process(&input, &mut whole);
        let mut split = Vec::new();
        let mut r = Resampler::new(48_000);
        for packet in input.chunks(511) {
            r.process(packet, &mut split);
        }
        assert_eq!(whole.len(), 16_000);
        assert_eq!(whole, split);
    }

    #[test]
    fn wav_decode_preserves_duration_and_mixes_channels() {
        let dir =
            std::env::temp_dir().join(format!("murmur-android-decode-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("test.wav");
        let mut w = hound::WavWriter::create(
            &path,
            hound::WavSpec {
                channels: 2,
                sample_rate: 16_000,
                bits_per_sample: 16,
                sample_format: hound::SampleFormat::Int,
            },
        )
        .unwrap();
        for _ in 0..16_000 {
            w.write_sample(16_000i16).unwrap();
            w.write_sample(-8_000i16).unwrap();
        }
        w.finalize().unwrap();
        let decoded = read_audio(&path).unwrap();
        assert_eq!(decoded.len(), 16_000);
        assert!((decoded[100] - 4_000.0 / 32_768.0).abs() < 0.001);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn facade_preserves_rich_notes_and_locks_manual_edits() {
        let dir =
            std::env::temp_dir().join(format!("murmur-android-facade-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let m = Murmur::new("/nowhere".into(), dir.to_string_lossy().into()).unwrap();
        let document = json!({"type":"doc","content":[{"type":"heading","attrs":{"level":2,"t":4.5},"content":[{"type":"text","text":"Projecte AINA","marks":[{"type":"bold"}]}]}]});
        let meeting = json!({"id":"android-fixture","title":"Pressupost AINA","createdAt":1.0,"duration":8.0,"status":"done","notesDoc":document,"utterances":[{"id":"line-1","start":0.0,"end":3.0,"speaker":0,"text":"primera versió"}]});
        m.android_action("put", &json!({"meeting":meeting}))
            .unwrap();
        m.android_action(
            "edit_text",
            &json!({"id":"android-fixture","line":"line-1","text":"versió corregida"}),
        )
        .unwrap();
        m.android_action(
            "line_speaker",
            &json!({"id":"android-fixture","line":"line-1","speaker":2}),
        )
        .unwrap();
        m.android_action(
            "glossary",
            &json!({"terms":["WordPress","wordPress","AINA"]}),
        )
        .unwrap();
        let stored = m
            .android_action("get", &json!({"id":"android-fixture"}))
            .unwrap();
        assert_eq!(stored["meeting"]["notesDoc"], document);
        assert_eq!(
            stored["meeting"]["utterances"][0]["text"],
            "versió corregida"
        );
        assert_eq!(stored["meeting"]["utterances"][0]["edited"], true);
        assert_eq!(stored["meeting"]["utterances"][0]["speakerLocked"], true);
        assert_eq!(
            m.android_action("glossary", &json!({})).unwrap()["terms"],
            json!(["WordPress", "AINA"])
        );
        assert!(
            !m.android_action("search", &json!({"query":"pressupost"}))
                .unwrap()["hits"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        let _ = std::fs::remove_dir_all(dir);
    }
}
