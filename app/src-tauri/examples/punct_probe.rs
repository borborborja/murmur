//! Punctuate a plain transcript with the ONNX model (checks it runs on sherpa's onnxruntime).
//!     cargo run --release --example punct_probe -- ../../models/punct-ca ../../results/reunio/conformer-ca-es-rnnt.txt
//! With `--lines`, each line is an utterance, as in the app, and the output is one per line.
use std::path::Path;
use std::time::Instant;
use transcriptor_lib::punctuate::Punctuator;

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let t0 = Instant::now();
    let mut p = Punctuator::load(Path::new(&args[1]))?;
    println!("carregat en {:.1}s", t0.elapsed().as_secs_f32());
    let text = std::fs::read_to_string(&args[2])?;
    if args.get(3).is_some_and(|a| a == "--lines") {
        let lines: Vec<String> = text.lines().map(str::to_string).collect();
        for line in p.punctuate(&lines, &vec![0; lines.len()])? {
            println!("{line}");
        }
        return Ok(());
    }
    let t0 = Instant::now();
    let out = p.punctuate(&[text], &[0])?;
    println!("puntuat en {:.1}s\n{}", t0.elapsed().as_secs_f32(), &out[0][..out[0].len().min(1500)]);
    Ok(())
}
