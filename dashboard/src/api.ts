const BASE = (import.meta as any).env?.VITE_TANK_URL || "http://localhost:8443";

export async function fetchJson<T = unknown>(path: string): Promise<T> {
  const r = await fetch(`${BASE}${path}`);
  if (!r.ok) throw new Error(`${path} ${r.status}`);
  return r.json();
}

export type Stats = {
  flows_total: number;
  alerts_total: number;
  rules_blocked: number;
  top_apps: { app_label: string; n: number }[];
  top_companies: { company: string | null; n: number }[];
};

export type Alert = {
  id: string;
  device_id: string;
  app_label: string;
  domain: string;
  severity: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  explanation: string;
  suggested_action: "BLOCK" | "ALLOW" | "WATCH";
  user_decision: string | null;
  created_at: string;
  tracker_json?: string | null;
};

export type TimelinePoint = { bucket: string; n: number; critical: number };
export type SeveritySlice = { severity: string; n: number };
export type CategorySlice = { category: string; n: number };

export type Meta = {
  service: string;
  version: string;
  public_key: string;
  tracker_db_size: number;
};
