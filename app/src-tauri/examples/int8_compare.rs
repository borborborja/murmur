//! The live text of a WAV with the full-precision Conformer and with the int8 one the phone uses
//! (docs/MOBIL.md), without the provisional text, to compare them.
//!   cargo run --release --example int8_compare -- ../../data/reunio-3min.wav
use std::path::PathBuf;
use std::time::Instant;

use murmur_core::engine::{Event, LiveModel, LoadOptions, Models, SAMPLE_RATE, models_dir};
use murmur_core::pipeline::{Pipeline, Source};

fn main() -> anyhow::Result<()> {
    let wav = PathBuf::from(std::env::args().nth(1).expect("int8_compare <file.wav>"));
    let bytes = std::fs::read(&wav)?;
    let data = bytes.windows(4).position(|w| w == b"data").expect("not a WAV") + 8;
    let samples: Vec<f32> = bytes[data..].chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]]) as f32 / 32768.0).collect();
    for int8 in [false, true] {
        let models = Models::load_with(&models_dir(), LiveModel::CatalanSpanish, LoadOptions { int8, threads: 2 })?;
        let mut pipeline = Pipeline::new(&models, true, false, 6, 0.7, None, vec![])?;
        pipeline.set_partials(false);
        let started = Instant::now();
        let mut events = vec![];
        for chunk in samples.chunks(SAMPLE_RATE / 10) {
            events.extend(pipeline.push(Source::Mic, chunk));
        }
        events.extend(pipeline.finish()?.0);
        let rtf = started.elapsed().as_secs_f64() / (samples.len() as f64 / SAMPLE_RATE as f64);
        println!("=== {} · RTF {rtf:.3}", if int8 { "int8" } else { "fp32" });
        for e in events {
            if let Event::Final { start, speaker, text, .. } = e {
                println!("[{start:.0}] {speaker}: {text}");
            }
        }
    }
    Ok(())
}
