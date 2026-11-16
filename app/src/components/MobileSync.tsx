import { useEffect, useState } from "react";
import { ChevronRight, Smartphone } from "lucide-react";
import { useTranslation } from "react-i18next";

import * as backend from "@/lib/backend";
import { ago } from "@/lib/format";
import type { MeetingSummary } from "@/lib/types";
import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

/** The local network can come and go without an event: look again now and then. */
const RECHECK_MS = 30_000;

/** The phones' state (mobile.rs), kept up to date. `null` until it's known, or outside the app. */
function useMobileStatus(): backend.MobileStatus | null {
  const [status, setStatus] = useState<backend.MobileStatus | null>(null);
  useEffect(() => {
    if (!backend.isTauri()) return;
    let alive = true;
    const load = () => backend.mobileStatus().then((s) => alive && setStatus(s), () => {});
    load();
    const timer = setInterval(load, RECHECK_MS);
    const unlisten = backend.onMobileStatus((s) => alive && setStatus(s));
    return () => {
      alive = false;
      clearInterval(timer);
      void unlisten.then((f) => f());
    };
  }, []);
  return status;
}

type State = "problem" | "receiving" | "syncing" | "waiting" | "ok";

/**
 * The sidebar footer's phone: where syncing with the paired phones is, like the computer at the
 * top of the phone's list (docs/mockups/mobil.html, 10). Not "connected": a phone only connects
 * for the few seconds it syncs. Hidden with no phone paired. Clicking it says more, and leads to
 * Settings › Mobile.
 */
export function MobileSync({ meetings, onSettings }: { meetings: MeetingSummary[]; onSettings: () => void }) {
  const { t } = useTranslation("sidebar");
  const status = useMobileStatus();
  const [open, setOpen] = useState(false);
  if (!status?.phones.length) return null;

  // Meetings from a phone still waiting for (or in) their final pass: the queue in gate.rs.
  const waiting = meetings.filter((m) => m.device && m.status === "processing").length;
  const activity = status.activity;
  const state: State = status.problem ? "problem" : activity?.receiving != null ? "receiving" : activity ? "syncing" : waiting ? "waiting" : "ok";
  const name = status.phones.length === 1 ? status.phones[0].name : t("mobile.phones", { count: status.phones.length });
  const lastSeen = Math.max(0, ...status.phones.map((p) => p.lastSeen ?? 0));

  const headline = {
    problem: t(`mobile.problem.${status.problem}`),
    receiving: activity?.receiving ? t("mobile.receiving", { title: activity.receiving }) : t("mobile.receivingUntitled"),
    syncing: t("mobile.syncing"),
    waiting: t("mobile.waitingShort", { count: waiting }),
    ok: t("mobile.upToDate"),
  }[state];
  const busy = state === "receiving" || state === "syncing";

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={t("mobile.label", { state: headline })}
          title={headline}
          className="relative grid size-7 place-items-center rounded-md text-muted-foreground hover:bg-black/5 hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring dark:hover:bg-white/10"
        >
          <Smartphone className="size-4" />
          {/* Syncing: a ring that turns around the phone. With "Reduce motion" it stays still, half drawn. */}
          {busy && (
            <span
              aria-hidden
              className="pointer-events-none absolute inset-0.5 animate-spin rounded-full border-[1.5px] border-transparent border-t-foreground border-r-foreground motion-reduce:animate-none"
            />
          )}
          {state === "problem" ? (
            <span aria-hidden className="absolute -top-0.5 -right-0.5 grid h-3.5 min-w-3.5 place-items-center rounded-full border-2 border-sidebar bg-mm-recording-label px-0.5 text-[9px] font-bold leading-none text-mm-on-recording-pill">
              !
            </span>
          ) : (
            waiting > 0 &&
            !busy && (
              <span aria-hidden className="absolute -top-0.5 -right-0.5 grid h-3.5 min-w-3.5 place-items-center rounded-full border-2 border-sidebar bg-foreground px-0.5 text-[9px] font-bold leading-none tabular-nums text-background">
                {waiting}
              </span>
            )
          )}
        </button>
      </PopoverTrigger>
      <PopoverContent side="top" align="end" sideOffset={8} className="w-64 gap-3 p-3">
        <div className="flex items-center gap-2.5">
          <div className="grid size-9 shrink-0 place-items-center rounded-[10px] bg-mm-fill-subtle">
            <Smartphone className="size-[18px]" />
          </div>
          <div className="min-w-0" role="status">
            <div className="truncate text-[13px] font-semibold">{name}</div>
            <div className={cn("text-[12px] leading-snug", state === "problem" ? "text-mm-recording-label" : "text-text-secondary")}>{headline}</div>
          </div>
        </div>
        <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1.5 border-t border-border pt-2.5 text-[12px]">
          <dt className="text-text-secondary">{t("mobile.waiting")}</dt>
          <dd className="text-right tabular-nums">{waiting ? t("mobile.meetings", { count: waiting }) : t("mobile.none")}</dd>
          <dt className="text-text-secondary">{t("mobile.lastSync")}</dt>
          <dd className="text-right">{lastSeen ? ago(lastSeen * 1000) : t("mobile.never")}</dd>
        </dl>
        <button
          type="button"
          onClick={() => {
            setOpen(false);
            onSettings();
          }}
          className="-mx-1 flex items-center justify-between rounded-md px-1 py-1 text-left text-[12px] text-text-secondary hover:bg-black/5 hover:text-foreground dark:hover:bg-white/10"
        >
          {t("mobile.settings")}
          <ChevronRight className="size-3.5" />
        </button>
      </PopoverContent>
    </Popover>
  );
}
