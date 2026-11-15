import { useState, type CSSProperties } from "react";
import { useTranslation } from "react-i18next";

import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { LogoIcon } from "@/components/Logo";
import type { WhatsNewItem, WhatsNewLink, WhatsNewRelease } from "@/lib/whatsnew";

/** "0.13.0" → "0.13": the big number in the band. */
const shortVersion = (v: string) => v.replace(/\.0$/, "");

/** The versions skipped, up to three ("0.12.1, 0.12.0, 0.11.4…"). */
const skippedList = (older: WhatsNewRelease[]) =>
  older
    .slice(0, 3)
    .map((r) => r.version)
    .join(", ") + (older.length > 3 ? "…" : "");

/** Each note comes in a little after the one before (motion-reduce: all at once). */
const STAGGER_MS = 40;

/**
 * "Novetats de Murmur" (lib/whatsnew.ts): after an update, what the new version brings. A band
 * in the icon's colours with the version in big and the aurora behind it; the notes numbered,
 * each with its link as a button and, for a big one, an image. Versions skipped in between fold
 * into one line under it. Opened from the settings (`manual`), it has no "don't show again":
 * that switch is right there.
 */
export function WhatsNewDialog({
  releases,
  manual,
  onLink,
  onClose,
}: {
  releases: WhatsNewRelease[];
  manual: boolean;
  onLink: (tab: WhatsNewLink) => void;
  onClose: (dontShowAgain: boolean) => void;
}) {
  const { t } = useTranslation("whatsnew");
  const [dontShow, setDontShow] = useState(false);
  const [showOlder, setShowOlder] = useState(false);
  const [latest, ...older] = releases;
  if (!latest) return null;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose(dontShow)}>
      <DialogContent showCloseButton={false} className="gap-0 overflow-hidden p-0 sm:max-w-[480px]">
        <div className="relative overflow-hidden bg-mm-logo-background px-[22px] pt-[22px] pb-5 text-mm-logo-bar-1">
          <Aurora />
          <div className="relative flex items-center gap-3.5">
            <LogoIcon size={72} className="-mx-2 -my-3" />
            <DialogTitle className="flex flex-col">
              <span className="text-[11px] font-semibold tracking-[0.08em] text-mm-logo-bar-2 uppercase">{t("heading")}</span>
              <span className="mt-1 text-[40px] leading-none font-bold tracking-[-0.02em] tabular-nums">{shortVersion(latest.version)}</span>
            </DialogTitle>
          </div>
          <DialogDescription className="relative mt-3 text-[12px] text-mm-logo-bar-1/65">{latest.date}</DialogDescription>
        </div>

        <div className="max-h-[440px] overflow-y-auto px-[22px]">
          <Notes items={latest.items} onLink={onLink} />
          {older.length > 0 && (
            <div className="border-t border-dashed border-border">
              <button
                type="button"
                aria-expanded={showOlder}
                onClick={() => setShowOlder((s) => !s)}
                className="flex w-full items-center justify-between gap-3 py-2.5 text-left text-[12px] text-muted-foreground hover:text-foreground"
              >
                <span>{t("skipped", { count: older.length, versions: skippedList(older) })}</span>
                <span className={showOlder ? "rotate-90 transition-transform motion-reduce:transition-none" : "transition-transform motion-reduce:transition-none"} aria-hidden>
                  ›
                </span>
              </button>
              {showOlder &&
                older.map((r) => (
                  <section key={r.version} className="pb-2">
                    <h3 className="pt-1 text-[11px] font-medium text-muted-foreground">{t("alsoIn", { version: r.version, date: r.date })}</h3>
                    <Notes items={r.items} onLink={onLink} compact />
                  </section>
                ))}
            </div>
          )}
        </div>

        <div className="flex items-center gap-3 border-t border-border px-5 py-3">
          {manual ? (
            <span className="flex-1" />
          ) : (
            <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-2 text-[12px] text-muted-foreground">
              <input type="checkbox" checked={dontShow} onChange={(e) => setDontShow(e.target.checked)} className="size-3.5 accent-primary" />
              {t("dontShow")}
            </label>
          )}
          <button type="button" onClick={() => onClose(dontShow)} className="h-7 shrink-0 rounded-[7px] bg-mm-accent px-3.5 text-[13px] font-semibold text-mm-on-accent hover:opacity-90">
            {t("ok")}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** The aurora of the final pass (lib/reveal.ts), still and blurred, behind the version. */
function Aurora() {
  return (
    <div
      aria-hidden
      className="pointer-events-none absolute -top-10 -right-5 h-[200px] w-[320px] opacity-85 blur-[38px]"
      style={{
        background:
          "radial-gradient(closest-side at 30% 55%, var(--mm-speaker-1), transparent), radial-gradient(closest-side at 62% 40%, var(--mm-speaker-4), transparent), radial-gradient(closest-side at 85% 70%, var(--mm-reveal-warm), transparent)",
      }}
    />
  );
}

function Notes({ items, onLink, compact }: { items: WhatsNewItem[]; onLink: (tab: WhatsNewLink) => void; compact?: boolean }) {
  return (
    <ol className="divide-y divide-border">
      {items.map(({ title, text, link, linkLabel, image, imageDark }, i) => (
        <li
          key={title}
          style={compact ? undefined : ({ animationDelay: `${i * STAGGER_MS}ms` } as CSSProperties)}
          className={
            compact
              ? "py-2"
              : "grid grid-cols-[28px_1fr] gap-x-2.5 py-3.5 animate-in fade-in-0 slide-in-from-bottom-1 fill-mode-both duration-300 motion-reduce:animate-none"
          }
        >
          {!compact && <span className="pt-0.5 text-[12px] text-mm-text-tertiary tabular-nums">{String(i + 1).padStart(2, "0")}</span>}
          <div className="min-w-0">
            {image && !compact && (
              <>
                <img src={image} alt="" className={imageDark ? "mb-3 w-full rounded-[10px] border border-border dark:hidden" : "mb-3 w-full rounded-[10px] border border-border"} />
                {imageDark && <img src={imageDark} alt="" className="mb-3 hidden w-full rounded-[10px] border border-border dark:block" />}
              </>
            )}
            <div className={compact ? "text-[12px] font-medium" : "text-[14px] font-semibold"}>{title}</div>
            <p className={compact ? "mt-0.5 text-[11px] leading-snug text-muted-foreground" : "mt-0.5 text-[13px] leading-[1.45] text-muted-foreground"}>{text}</p>
            {link && linkLabel && (
              <button
                type="button"
                onClick={() => onLink(link)}
                className={
                  compact
                    ? "mt-1 text-[12px] text-mm-link hover:underline"
                    : "mt-2 h-[26px] rounded-full border border-border bg-mm-fill-subtle px-2.5 text-[12px] font-medium hover:bg-accent"
                }
              >
                {linkLabel}
                {!compact && <span className="text-mm-text-tertiary"> ›</span>}
              </button>
            )}
          </div>
        </li>
      ))}
    </ol>
  );
}
