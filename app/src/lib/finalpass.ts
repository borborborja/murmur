import { modelInstalled } from "./models";
import type { Language, ModelId } from "./types";

/**
 * Which Whisper writes a meeting again after recording (retranscribe.rs model_for). In Catalan, the
 * BSC's (whisper-3cat-balearic, the best for every variety we measured: bench/dialects.py); in
 * Spanish and English, large-v3-turbo. Mixed meetings ("ca-es") get none: told one language,
 * Whisper turns the other into it.
 */
export function preferredFinalModel(language: Language): ModelId | null {
  if (language === "ca-es") return null;
  return language === "ca" ? "whisper-ca" : "whisper";
}

/** The one that will run now: in Catalan without the BSC's model yet, the turbo, as before. */
export function finalPassModel(language: Language): ModelId | null {
  const preferred = preferredFinalModel(language);
  if (preferred === "whisper-ca" && !modelInstalled("whisper-ca")) return "whisper";
  return preferred;
}
