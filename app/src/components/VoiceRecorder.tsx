import { useEffect, useRef, useState } from "react";
import { Check, Lock } from "lucide-react";
import { useTranslation } from "react-i18next";

import * as backend from "@/lib/backend";
import { cn } from "@/lib/utils";

/** The longest it records, if the sentence never ends (voice.rs keeps it under 30). */
const MAX_SECONDS = 20;

type Phase = "idle" | "recording" | "saving" | "saved";

/**
 * "La teva veu" (voice.rs): the user reads a sentence and Murmur saves a print of their voice.
 * The words already said dim as the live engine hears them, like a karaoke (lib.rs record_voice
 * stops a moment after the last one); the level says it hears the user.
 * Shared by the onboarding and Settings › Àudio.
 */
export function VoiceRecorder({ device, saved, onSaved }: { device: string | null; saved: boolean; onSaved: (at: number) => void }) {
  const { t, i18n } = useTranslation("onboarding");
  const [phase, setPhase] = useState<Phase>(saved ? "saved" : "idle");
  const [elapsed, setElapsed] = useState(0);
  const [level, setLevel] = useState(0);
  // Words of the sentence the live engine has heard.
  const [said, setSaid] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const started = useRef(0);
  const sentence = t("voice.sentence");
  const words = sentence.split(" ");

  useEffect(() => {
    if (phase !== "recording") return;
    const timer = setInterval(() => setElapsed((Date.now() - started.current) / 1000), 100);
    const unlisten = backend.onVoiceLevel(setLevel);
    const unprogress = backend.onVoiceProgress((n) => setSaid((s) => Math.max(s, n)));
    return () => {
      clearInterval(timer);
      void unlisten.then((u) => u());
      void unprogress.then((u) => u());
      setLevel(0);
    };
  }, [phase]);

  const record = async () => {
    setError(null);
    setElapsed(0);
    setSaid(0);
    started.current = Date.now();
    setPhase("recording");
    try {
      const promise = backend.recordVoice(device, MAX_SECONDS, sentence, i18n.language);
      // Recording ends at the last word (stop below), on the button, or at MAX_SECONDS; then the print takes a moment.
      const at = await promise;
      setPhase("saved");
      onSaved(at);
    } catch (e) {
      setError(String(e));
      setPhase("idle");
    }
  };
  const stop = () => {
    setPhase("saving");
    backend.stopVoiceRecording().catch(() => {});
  };
  // The engine stops by itself after the last word (or at MAX_SECONDS): then it makes the print.
  useEffect(() => {
    if (phase === "recording" && (said >= words.length || elapsed >= MAX_SECONDS)) setPhase("saving");
  }, [phase, said, elapsed, words.length]);
  const shown = phase === "recording" || phase === "saving" ? said : 0;
  // Speech sits around rms 0.01–0.1: a log scale over 10 segments.
  const lit = level <= 0 ? 0 : Math.round(Math.max(0, Math.min(1, (Math.log10(level) + 3) / 2.5)) * 10);
  return (
    <div className="rounded-xl bg-mm-fill-subtle px-5 py-4">
      <p className="text-[17px] font-medium leading-[1.55]">
        {words.map((w, i) => (
          <span key={i} className={cn("transition-colors duration-200", i < shown && "text-mm-text-tertiary")}>
            {w}{" "}
          </span>
        ))}
      </p>
      <div className="mt-4 flex items-center gap-3.5">
        {phase === "saved" ? (
          <span className="grid size-11 shrink-0 place-items-center rounded-full bg-primary text-primary-foreground" aria-hidden>
            <Check className="size-5" strokeWidth={3} />
          </span>
        ) : (
          <button
            type="button"
            onClick={phase === "recording" ? stop : record}
            disabled={phase === "saving"}
            aria-label={phase === "recording" ? t("voice.stop") : t("voice.record")}
            className="grid size-11 shrink-0 place-items-center rounded-full bg-mm-recording-dot shadow-[0_0_0_4px_color-mix(in_srgb,var(--mm-recording-dot)_18%,transparent)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-50"
          >
            <span className={cn("size-3.5 bg-white", phase === "recording" ? "rounded-[3px]" : "rounded-full")} />
          </button>
        )}
        {phase === "recording" && (
          <div className="flex items-center gap-[3px]" role="meter" aria-label={t("voice.level")} aria-valuemin={0} aria-valuemax={10} aria-valuenow={lit}>
            {Array.from({ length: 10 }, (_, i) => (
              <span key={i} className={cn("h-3.5 w-1.5 rounded-[2px]", i < lit ? "bg-mm-toggle-on" : "bg-mm-meter-off")} />
            ))}
          </div>
        )}
        <div className="text-[13px] text-muted-foreground" aria-live="polite">
          {phase === "idle" && (error ? <span className="text-destructive">{error}</span> : t("voice.ready"))}
          {phase === "recording" && (
            <>
              <span className="font-semibold text-foreground">{t("voice.recording")}</span> · <span className="tabular-nums">0:{String(Math.floor(elapsed)).padStart(2, "0")}</span>
            </>
          )}
          {phase === "saving" && t("voice.saving")}
          {phase === "saved" && (
            <>
              <span className="font-semibold text-foreground">{t("voice.saved")}</span> ·{" "}
              <button type="button" onClick={record} className="text-mm-link hover:underline">
                {t("voice.again")}
              </button>
            </>
          )}
        </div>
      </div>
      {phase === "recording" && (
        <div className="mt-3 h-1 overflow-hidden rounded-full bg-mm-meter-off">
          <div className="h-full rounded-full bg-primary transition-[width] duration-150 ease-out" style={{ width: `${(shown / words.length) * 100}%` }} />
        </div>
      )}
    </div>
  );
}

/** What's kept and where: it's biometric data, and we say so. */
export function VoicePrivacy({ className }: { className?: string }) {
  const { t } = useTranslation("onboarding");
  return (
    <div className={cn("flex items-start gap-2.5 text-[12px] leading-normal text-muted-foreground", className)}>
      <Lock className="mt-0.5 size-3.5 shrink-0" aria-hidden />
      <span>{t("voice.privacy")}</span>
    </div>
  );
}
