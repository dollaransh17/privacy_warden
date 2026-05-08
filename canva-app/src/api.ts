/**
 * Privacy Warden Tank API client.
 *
 * Talks to a Privacy Warden Tank if TANK_URL is reachable, otherwise returns
 * a deterministic mock so the Canva App demoes without backend setup.
 *
 * The Tank's /api/state endpoint is implemented in tank/app/main.py and
 * returns a WardenStateSnapshot — see that file for the contract.
 */

export type Severity = "info" | "warn" | "danger";

export interface Alert {
  id: string;
  title: string;
  category: string;
  severity: Severity;
  agoMs: number;
}

export interface PrivacyState {
  score: number;            // 0 – 100
  scoreLabel: string;       // "Excellent" | "Good" | "At risk" | "Critical"
  watchdogsOn: boolean;
  telemetryCount: number;   // always 0 for Privacy Warden
  lastAlertAgoMs: number | null;
  alerts: Alert[];
}

const TANK_URL = process.env.TANK_URL ?? "";   // empty → use mock
const TIMEOUT_MS = 1500;

export async function fetchPrivacyState(): Promise<PrivacyState> {
  if (!TANK_URL) {
    return mockState();
  }
  try {
    const res = await fetchWithTimeout(`${TANK_URL}/api/state`, TIMEOUT_MS);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const raw = await res.json();
    return normalize(raw);
  } catch (e) {
    console.warn("[privacy-warden] tank unreachable, using mock", e);
    return mockState();
  }
}

function fetchWithTimeout(url: string, ms: number): Promise<Response> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  return fetch(url, { signal: ctrl.signal }).finally(() => clearTimeout(timer));
}

function scoreLabelFor(score: number): string {
  if (score >= 90) return "Excellent";
  if (score >= 70) return "Good";
  if (score >= 40) return "At risk";
  return "Critical";
}

function normalize(raw: any): PrivacyState {
  const score = clamp(Number(raw?.score ?? 0), 0, 100);
  return {
    score,
    scoreLabel: scoreLabelFor(score),
    watchdogsOn: Boolean(raw?.watchdogs_on ?? raw?.watchdogsOn ?? true),
    telemetryCount: 0,
    lastAlertAgoMs: raw?.last_alert_ago_ms ?? null,
    alerts: Array.isArray(raw?.alerts)
      ? raw.alerts.map((a: any, i: number) => ({
          id: String(a.id ?? i),
          title: String(a.title ?? "Alert"),
          category: String(a.category ?? "system"),
          severity: (a.severity ?? "info") as Severity,
          agoMs: Number(a.ago_ms ?? a.agoMs ?? 0),
        }))
      : [],
  };
}

function clamp(n: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, n));
}

/**
 * Deterministic mock — returns a "healthy" state with three alerts so the
 * demo always has something to show. No randomness so screenshots are stable.
 */
function mockState(): PrivacyState {
  return {
    score: 92,
    scoreLabel: "Excellent",
    watchdogsOn: true,
    telemetryCount: 0,
    lastAlertAgoMs: 3 * 60_000,
    alerts: [
      {
        id: "1",
        title: "Suspicious SMS · UPI fraud pattern",
        category: "messages",
        severity: "warn",
        agoMs: 22 * 60_000,
      },
      {
        id: "2",
        title: "Mic-on while screen-off — Game Studio Free",
        category: "sensors",
        severity: "danger",
        agoMs: 60 * 60_000,
      },
      {
        id: "3",
        title: "OTP-storm · 3 senders / 5 min",
        category: "identity",
        severity: "warn",
        agoMs: 3 * 60 * 60_000,
      },
    ],
  };
}

export function formatAgo(ms: number | null): string {
  if (ms === null) return "—";
  const sec = Math.floor(ms / 1000);
  if (sec < 60)            return `${sec} sec ago`;
  if (sec < 3600)          return `${Math.floor(sec / 60)} min ago`;
  if (sec < 86_400)        return `${Math.floor(sec / 3600)} hr ago`;
  return `${Math.floor(sec / 86_400)} day ago`;
}
