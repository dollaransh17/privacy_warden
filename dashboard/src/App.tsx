import { useEffect, useMemo, useState } from "react";
import {
  Shield, Activity, Ban, AlertCircle, BarChart3, Globe,
  Zap, Layers, ArrowUpRight, Bell, Search, Settings,
  ChevronDown, Smartphone, Inbox, PanelLeftClose, PanelLeft,
} from "lucide-react";
import {
  AreaChart, Area, XAxis, YAxis, ResponsiveContainer, Tooltip,
  PieChart, Pie, Cell, BarChart, Bar, LineChart, Line, CartesianGrid,
} from "recharts";
import {
  fetchJson, type Alert, type Stats, type TimelinePoint,
  type SeveritySlice, type CategorySlice, type Meta,
} from "./api";

const SEV: Record<string, { color: string; label: string; chip: string }> = {
  CRITICAL: { color: "#ef4444", label: "Critical", chip: "bg-red-500/15 text-red-300 ring-1 ring-red-500/30" },
  HIGH:     { color: "#f97316", label: "High",     chip: "bg-orange-500/15 text-orange-300 ring-1 ring-orange-500/30" },
  MEDIUM:   { color: "#eab308", label: "Medium",   chip: "bg-yellow-500/15 text-yellow-300 ring-1 ring-yellow-500/30" },
  LOW:      { color: "#10b981", label: "Low",      chip: "bg-emerald-500/15 text-emerald-300 ring-1 ring-emerald-500/30" },
};

const CAT_COLORS: Record<string, string> = {
  advertising: "#ef4444",
  "session-replay": "#f97316",
  attribution: "#eab308",
  analytics: "#3b82f6",
  engagement: "#8b5cf6",
  identification: "#ec4899",
  "crash-reporting": "#10b981",
  "feature-flags": "#6b7280",
};

export default function App() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [timeline, setTimeline] = useState<TimelinePoint[]>([]);
  const [severity, setSeverity] = useState<SeveritySlice[]>([]);
  const [categories, setCategories] = useState<CategorySlice[]>([]);
  const [meta, setMeta] = useState<Meta | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try { return localStorage.getItem("pw.sidebar.collapsed") === "1"; } catch { return false; }
  });

  const toggleSidebar = () => {
    setCollapsed(c => {
      const next = !c;
      try { localStorage.setItem("pw.sidebar.collapsed", next ? "1" : "0"); } catch {}
      return next;
    });
  };

  useEffect(() => {
    const load = async () => {
      try {
        const [s, a, t, sev, cat, m] = await Promise.all([
          fetchJson<Stats>("/api/stats"),
          fetchJson<Alert[]>("/api/alerts?limit=30"),
          fetchJson<TimelinePoint[]>("/api/timeline?hours=24"),
          fetchJson<SeveritySlice[]>("/api/severity"),
          fetchJson<CategorySlice[]>("/api/categories"),
          fetchJson<Meta>("/meta"),
        ]);
        setStats(s); setAlerts(a); setTimeline(t);
        setSeverity(sev); setCategories(cat); setMeta(m);
        setErr(null);
      } catch (e: any) { setErr(e.message); }
    };
    load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, []);

  const blockRate = useMemo(() => {
    const total = stats?.alerts_total ?? 0;
    return total === 0 ? 0 : Math.round(((stats?.rules_blocked ?? 0) / total) * 100);
  }, [stats]);

  return (
    <div className="flex min-h-screen">
      <Sidebar collapsed={collapsed} onToggle={toggleSidebar} />
      <div className="flex-1 min-w-0 flex flex-col">
        <Topbar err={err} collapsed={collapsed} onToggle={toggleSidebar} />
        <main className="flex-1 px-8 py-8 max-w-[1400px] w-full mx-auto">
          <PageHeader meta={meta} blockRate={blockRate} />
          <Stats stats={stats} timeline={timeline} severity={severity} />
          <ChartGrid timeline={timeline} severity={severity} categories={categories} />
          <Lists stats={stats} />
          <AlertList alerts={alerts} />
        </main>
      </div>
    </div>
  );
}

// ── Sidebar ───────────────────────────────────────────────────────────────────

function Sidebar({ collapsed, onToggle }: { collapsed: boolean; onToggle: () => void }) {
  const items = [
    { icon: BarChart3, label: "Overview", active: true },
    { icon: Inbox, label: "Alerts", badge: "12" },
    { icon: Smartphone, label: "Devices" },
    { icon: Globe, label: "Trackers" },
    { icon: Layers, label: "Policy" },
    { icon: Activity, label: "Logs" },
  ];
  const layers = [
    { id: "L1", n: "Communication", c: "bg-blue-500" },
    { id: "L2", n: "Channel", c: "bg-cyan-500" },
    { id: "L3", n: "Gateway", c: "bg-violet-500" },
    { id: "L4", n: "Pi Engine", c: "bg-pink-500" },
    { id: "L5", n: "Skills", c: "bg-emerald-500" },
  ];

  return (
    <aside
      className={`shrink-0 bg-panel border-r border-line flex flex-col transition-[width] duration-200 ease-out ${
        collapsed ? "w-[64px]" : "w-[232px]"
      }`}>
      <div className={`h-14 flex items-center gap-2.5 border-b border-line ${collapsed ? "justify-center px-0" : "px-5"}`}>
        <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-blue-500 to-blue-700 flex items-center justify-center shadow-lg shadow-blue-500/20 shrink-0">
          <Shield className="w-4 h-4 text-white" strokeWidth={2.5} />
        </div>
        {!collapsed && (
          <div className="overflow-hidden whitespace-nowrap">
            <div className="text-[14px] font-semibold leading-tight">Privacy Warden</div>
            <div className="text-[10px] text-mute font-mono leading-tight">tank · v0.1.0</div>
          </div>
        )}
      </div>

      <nav className={`flex-1 ${collapsed ? "p-2" : "p-3"}`}>
        {!collapsed && (
          <div className="text-[10px] uppercase tracking-[0.12em] text-mute px-2 mb-2 mt-1">Workspace</div>
        )}
        {items.map((it, i) => (
          <a
            key={i}
            href="#"
            title={collapsed ? it.label : undefined}
            className={`flex items-center gap-2.5 rounded-md text-[13px] mb-0.5 ${
              collapsed ? "justify-center w-10 h-10 mx-auto" : "px-2.5 py-1.5"
            } ${
              it.active
                ? "bg-accent/15 text-ink ring-1 ring-accent/30"
                : "text-ink2 hover:bg-line/50 hover:text-ink"
            }`}>
            <it.icon className="w-4 h-4 shrink-0" />
            {!collapsed && (
              <>
                <span className="flex-1 truncate">{it.label}</span>
                {it.badge && (
                  <span className="text-[10px] num bg-accent/20 text-blue-300 px-1.5 py-0.5 rounded">
                    {it.badge}
                  </span>
                )}
              </>
            )}
            {collapsed && it.badge && (
              <span className="absolute -mt-6 -mr-6 text-[9px] num bg-accent text-white w-3.5 h-3.5 rounded-full flex items-center justify-center">
                •
              </span>
            )}
          </a>
        ))}

        {!collapsed && (
          <div className="text-[10px] uppercase tracking-[0.12em] text-mute px-2 mb-2 mt-6">OpenClaw layers</div>
        )}
        {collapsed && <div className="my-3 mx-2 h-px bg-line" />}
        {layers.map(l => (
          collapsed ? (
            <div key={l.id} title={`${l.id} · ${l.n}`} className="flex items-center justify-center w-10 h-7 mx-auto text-[10px] font-mono text-mute relative">
              <span className={`w-1.5 h-1.5 rounded-full ${l.c} mr-1`} />
              {l.id}
            </div>
          ) : (
            <div key={l.id} className="flex items-center gap-2.5 px-2.5 py-1 text-[12px] text-ink2">
              <span className={`w-1.5 h-1.5 rounded-full ${l.c}`} />
              <span className="font-mono text-[11px] text-mute w-5">{l.id}</span>
              <span className="flex-1">{l.n}</span>
              <span className="text-emerald-400 text-[10px]">●</span>
            </div>
          )
        ))}
      </nav>

      <div className={`border-t border-line ${collapsed ? "p-2" : "p-3"} space-y-1`}>
        <button
          onClick={onToggle}
          title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          className={`flex items-center gap-2.5 rounded-md text-[13px] text-ink2 hover:bg-line/50 hover:text-ink ${
            collapsed ? "justify-center w-10 h-10 mx-auto" : "w-full px-2.5 py-2"
          }`}>
          {collapsed ? <PanelLeft className="w-4 h-4" /> : <PanelLeftClose className="w-4 h-4" />}
          {!collapsed && <span>Collapse</span>}
        </button>
        <button
          title={collapsed ? "Settings" : undefined}
          className={`flex items-center gap-2.5 rounded-md text-[13px] text-ink2 hover:bg-line/50 hover:text-ink ${
            collapsed ? "justify-center w-10 h-10 mx-auto" : "w-full px-2.5 py-2"
          }`}>
          <Settings className="w-4 h-4" />
          {!collapsed && <span>Settings</span>}
        </button>
      </div>
    </aside>
  );
}

// ── Topbar ────────────────────────────────────────────────────────────────────

function Topbar({ err, collapsed, onToggle }: { err: string | null; collapsed: boolean; onToggle: () => void }) {
  return (
    <header className="h-14 border-b border-line bg-bg/80 backdrop-blur sticky top-0 z-40 flex items-center justify-between px-6">
      <div className="flex items-center gap-3 text-[13px]">
        <button
          onClick={onToggle}
          title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          className="p-1.5 rounded-md hover:bg-line/50 text-ink2 hover:text-ink -ml-1.5">
          {collapsed ? <PanelLeft className="w-4 h-4" /> : <PanelLeftClose className="w-4 h-4" />}
        </button>
        <span className="text-mute">Workspace</span>
        <span className="text-mute">/</span>
        <span className="font-medium">Personal device</span>
        <ChevronDown className="w-3.5 h-3.5 text-mute" />
      </div>
      <div className="flex items-center gap-3">
        <div className="relative">
          <Search className="w-3.5 h-3.5 text-mute absolute left-2.5 top-1/2 -translate-y-1/2" />
          <input
            placeholder="Search alerts, domains, apps..."
            className="bg-surface border border-line rounded-md pl-8 pr-3 py-1.5 text-[12px] w-72 placeholder:text-mute focus:outline-none focus:border-accent/50 focus:ring-2 focus:ring-accent/10"
          />
          <kbd className="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] text-mute font-mono bg-line/50 px-1.5 py-0.5 rounded">⌘K</kbd>
        </div>
        <button className="relative p-1.5 rounded-md hover:bg-line/50 text-ink2">
          <Bell className="w-4 h-4" />
          <span className="absolute top-1 right-1 w-1.5 h-1.5 bg-red-500 rounded-full" />
        </button>
        <div className="h-5 w-px bg-line" />
        <div className="flex items-center gap-2 px-2 py-1 rounded-md bg-surface border border-line">
          <span className="relative flex w-2 h-2">
            <span className={`absolute inline-flex h-full w-full rounded-full opacity-75 ${err ? "bg-red-400 animate-ping" : "bg-emerald-400 animate-ping"}`}/>
            <span className={`relative inline-flex rounded-full h-2 w-2 ${err ? "bg-red-500" : "bg-emerald-500"}`}/>
          </span>
          <span className="text-[12px] font-medium">{err ? "Tank offline" : "Live"}</span>
        </div>
        <div className="w-7 h-7 rounded-full bg-gradient-to-br from-violet-500 to-blue-500 flex items-center justify-center text-[11px] font-semibold">
          AS
        </div>
      </div>
    </header>
  );
}

// ── Page header ───────────────────────────────────────────────────────────────

function PageHeader({ meta, blockRate }: { meta: Meta | null; blockRate: number }) {
  return (
    <div className="flex items-end justify-between mb-7">
      <div>
        <div className="text-[12px] text-mute mb-1.5 flex items-center gap-1.5">
          <Zap className="w-3 h-3 text-accent" />
          Realtime · refreshing every 3s
        </div>
        <h1 className="text-[26px] font-semibold leading-tight">Privacy dashboard</h1>
        <p className="text-[14px] text-ink2 mt-1">
          Tracker activity across your devices · {meta?.tracker_db_size ?? 0} signatures loaded
        </p>
      </div>
      <div className="surface px-4 py-3 flex items-center gap-4">
        <div>
          <div className="text-[11px] text-mute uppercase tracking-wider">Block rate</div>
          <div className="num text-2xl font-semibold leading-none mt-1">{blockRate}<span className="text-mute text-base">%</span></div>
        </div>
        <div className="h-10 w-px bg-line" />
        <div className="flex items-center gap-1.5 text-[12px] text-emerald-400">
          <ArrowUpRight className="w-3.5 h-3.5" />
          <span className="num font-medium">+12%</span>
          <span className="text-mute">vs yesterday</span>
        </div>
      </div>
    </div>
  );
}

// ── Stats ─────────────────────────────────────────────────────────────────────

function Stats({ stats, timeline, severity }: {
  stats: Stats | null; timeline: TimelinePoint[]; severity: SeveritySlice[];
}) {
  const spark = timeline.map(t => ({ x: t.bucket.slice(11, 13), y: t.n }));
  const sparkCrit = timeline.map(t => ({ x: t.bucket.slice(11, 13), y: t.critical }));
  const criticalCount = severity.find(s => s.severity === "CRITICAL")?.n ?? 0;

  const cards = [
    {
      icon: Activity, label: "Flows observed", value: stats?.flows_total,
      hint: "across all apps", spark, color: "#3b82f6",
    },
    {
      icon: AlertCircle, label: "Alerts raised", value: stats?.alerts_total,
      hint: "in last 24h", spark, color: "#f59e0b",
    },
    {
      icon: Ban, label: "Trackers blocked", value: stats?.rules_blocked,
      hint: "auto + user-confirmed", spark: sparkCrit, color: "#ef4444",
    },
    {
      icon: Globe, label: "Companies seen", value: stats?.top_companies.length ?? 0,
      hint: "unique destinations", spark, color: "#10b981",
    },
  ];

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-7">
      {cards.map((c, i) => (
        <div key={i} className="card card-hover p-4 relative overflow-hidden">
          <div className="flex items-start justify-between mb-3">
            <div className="p-2 rounded-md" style={{ background: `${c.color}20`, color: c.color }}>
              <c.icon className="w-4 h-4" strokeWidth={2.5} />
            </div>
            {c.label === "Alerts raised" && criticalCount > 0 && (
              <span className="text-[10px] uppercase tracking-wider px-2 py-0.5 bg-red-500/15 text-red-300 ring-1 ring-red-500/30 rounded">
                {criticalCount} critical
              </span>
            )}
          </div>
          <div className="num text-[28px] font-semibold leading-none">{c.value ?? "—"}</div>
          <div className="text-[12px] text-mute mt-1">{c.label}</div>
          <div className="text-[11px] text-mute/70 mt-0.5">{c.hint}</div>
          {c.spark.length > 1 && (
            <div className="absolute right-0 bottom-0 w-32 h-12 opacity-80">
              <ResponsiveContainer>
                <LineChart data={c.spark}>
                  <Line type="monotone" dataKey="y" stroke={c.color} strokeWidth={1.5} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

// ── Charts grid ───────────────────────────────────────────────────────────────

function ChartGrid({ timeline, severity, categories }: {
  timeline: TimelinePoint[]; severity: SeveritySlice[]; categories: CategorySlice[];
}) {
  const timeData = timeline.map(t => ({
    h: t.bucket.slice(11, 13) + ":00",
    total: t.n,
    critical: t.critical,
    other: t.n - t.critical,
  }));
  const sevData = severity.map(s => ({
    name: SEV[s.severity]?.label ?? s.severity,
    value: s.n,
    color: SEV[s.severity]?.color ?? "#6b7280",
  }));
  const total = sevData.reduce((a, x) => a + x.value, 0);
  const catData = [...categories]
    .sort((a, b) => b.n - a.n)
    .map(c => ({ name: c.category || "—", n: c.n, color: CAT_COLORS[c.category || ""] || "#6b7280" }));

  return (
    <section className="grid grid-cols-1 lg:grid-cols-12 gap-4 mb-7">
      {/* Activity timeline — wide */}
      <div className="card card-hover p-5 lg:col-span-8">
        <div className="flex items-start justify-between mb-4">
          <div>
            <h3 className="text-[14px] font-semibold">Activity timeline</h3>
            <p className="text-[12px] text-mute mt-0.5">Tracker events grouped by hour</p>
          </div>
          <div className="flex items-center gap-3 text-[11px]">
            <Legend color="#3b82f6" label="Other"/>
            <Legend color="#ef4444" label="Critical"/>
          </div>
        </div>
        <div className="h-[260px] -ml-2">
          {timeData.length === 0 ? <Empty/> : (
            <ResponsiveContainer>
              <AreaChart data={timeData} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                <defs>
                  <linearGradient id="ga-other" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.4}/>
                    <stop offset="100%" stopColor="#3b82f6" stopOpacity={0.02}/>
                  </linearGradient>
                  <linearGradient id="ga-crit" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#ef4444" stopOpacity={0.5}/>
                    <stop offset="100%" stopColor="#ef4444" stopOpacity={0.05}/>
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#232830" vertical={false} />
                <XAxis dataKey="h" tick={{ fill: "#7a8492", fontSize: 11 }} axisLine={false} tickLine={false} tickMargin={10} />
                <YAxis tick={{ fill: "#7a8492", fontSize: 11 }} axisLine={false} tickLine={false} width={32} />
                <Tooltip contentStyle={tooltipStyle} cursor={{ stroke: "#3b82f6", strokeWidth: 1, strokeOpacity: 0.4 }} />
                <Area type="monotone" dataKey="other" stackId="1" stroke="#3b82f6" strokeWidth={2} fill="url(#ga-other)" />
                <Area type="monotone" dataKey="critical" stackId="1" stroke="#ef4444" strokeWidth={2} fill="url(#ga-crit)" />
              </AreaChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      {/* Severity donut */}
      <div className="card card-hover p-5 lg:col-span-4">
        <div className="flex items-start justify-between mb-4">
          <div>
            <h3 className="text-[14px] font-semibold">Severity</h3>
            <p className="text-[12px] text-mute mt-0.5">{total} alerts total</p>
          </div>
        </div>
        <div className="h-[200px] relative">
          {sevData.length === 0 ? <Empty/> : (
            <>
              <ResponsiveContainer>
                <PieChart>
                  <Pie data={sevData} dataKey="value" innerRadius={62} outerRadius={88}
                    paddingAngle={2} stroke="#14171c" strokeWidth={3}>
                    {sevData.map((d, i) => <Cell key={i} fill={d.color} />)}
                  </Pie>
                  <Tooltip contentStyle={tooltipStyle} />
                </PieChart>
              </ResponsiveContainer>
              <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
                <div className="num text-[28px] font-semibold leading-none">{total}</div>
                <div className="text-[11px] text-mute mt-1">total</div>
              </div>
            </>
          )}
        </div>
        <div className="grid grid-cols-2 gap-x-3 gap-y-1.5 mt-2">
          {sevData.map(d => (
            <div key={d.name} className="flex items-center justify-between text-[12px]">
              <span className="flex items-center gap-2 text-ink2">
                <span className="w-2 h-2 rounded-full" style={{ background: d.color }} />
                {d.name}
              </span>
              <span className="num text-ink font-medium">{d.value}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Category bar chart */}
      <div className="card card-hover p-5 lg:col-span-12">
        <div className="flex items-start justify-between mb-4">
          <div>
            <h3 className="text-[14px] font-semibold">Tracker categories</h3>
            <p className="text-[12px] text-mute mt-0.5">What kind of data is being exfiltrated</p>
          </div>
        </div>
        <div className="h-[220px] -ml-2">
          {catData.length === 0 ? <Empty/> : (
            <ResponsiveContainer>
              <BarChart data={catData} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#232830" vertical={false} />
                <XAxis dataKey="name" tick={{ fill: "#b9c0cc", fontSize: 12 }} axisLine={false} tickLine={false} tickMargin={10} />
                <YAxis tick={{ fill: "#7a8492", fontSize: 11 }} axisLine={false} tickLine={false} width={32} />
                <Tooltip contentStyle={tooltipStyle} cursor={{ fill: "#181c23" }} />
                <Bar dataKey="n" radius={[6, 6, 0, 0]} maxBarSize={56}>
                  {catData.map((d, i) => <Cell key={i} fill={d.color} />)}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>
    </section>
  );
}

// ── Lists ─────────────────────────────────────────────────────────────────────

function Lists({ stats }: { stats: Stats | null }) {
  const apps = stats?.top_apps || [];
  const cos = stats?.top_companies || [];
  const aMax = Math.max(1, ...apps.map(a => a.n));
  const cMax = Math.max(1, ...cos.map(c => c.n));

  return (
    <section className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-7">
      <div className="card card-hover">
        <div className="px-5 py-4 border-b border-line flex items-center justify-between">
          <div>
            <h3 className="text-[14px] font-semibold">Leakiest apps</h3>
            <p className="text-[12px] text-mute mt-0.5">By tracker activity</p>
          </div>
          <Smartphone className="w-4 h-4 text-mute" />
        </div>
        <ul className="p-2">
          {apps.length === 0 && <li className="px-3 py-8 text-center text-mute text-sm">No data</li>}
          {apps.slice(0, 8).map((a, i) => (
            <li key={i} className="px-3 py-2 rounded-md hover:bg-surface transition-colors">
              <div className="flex items-center justify-between mb-1.5">
                <div className="flex items-center gap-3">
                  <span className="text-mute text-[11px] num font-mono w-4">{String(i+1).padStart(2, "0")}</span>
                  <div className="w-7 h-7 rounded-md bg-gradient-to-br from-blue-500/20 to-violet-500/20 ring-1 ring-line flex items-center justify-center text-[11px] font-bold">
                    {(a.app_label || "?").slice(0, 2).toUpperCase()}
                  </div>
                  <span className="text-[14px] font-medium">{a.app_label || "Unknown"}</span>
                </div>
                <span className="num text-[12px] text-ink2">{a.n} <span className="text-mute">leaks</span></span>
              </div>
              <div className="h-1 bg-line rounded-full overflow-hidden ml-14">
                <div className="h-full bg-gradient-to-r from-blue-500 to-blue-400 rounded-full transition-all duration-700"
                  style={{ width: `${(a.n / aMax) * 100}%` }} />
              </div>
            </li>
          ))}
        </ul>
      </div>

      <div className="card card-hover">
        <div className="px-5 py-4 border-b border-line flex items-center justify-between">
          <div>
            <h3 className="text-[14px] font-semibold">Top tracker companies</h3>
            <p className="text-[12px] text-mute mt-0.5">Where the data flows</p>
          </div>
          <Globe className="w-4 h-4 text-mute" />
        </div>
        <ul className="p-2">
          {cos.length === 0 && <li className="px-3 py-8 text-center text-mute text-sm">No data</li>}
          {cos.slice(0, 8).map((c, i) => (
            <li key={i} className="px-3 py-2 rounded-md hover:bg-surface transition-colors">
              <div className="flex items-center justify-between mb-1.5">
                <div className="flex items-center gap-3">
                  <span className="text-mute text-[11px] num font-mono w-4">{String(i+1).padStart(2, "0")}</span>
                  <div className="w-7 h-7 rounded-md bg-gradient-to-br from-red-500/20 to-orange-500/20 ring-1 ring-line flex items-center justify-center text-[11px] font-bold">
                    {(c.company || "?").slice(0, 2).toUpperCase()}
                  </div>
                  <span className="text-[14px] font-medium">{c.company || "—"}</span>
                </div>
                <span className="num text-[12px] text-ink2">{c.n} <span className="text-mute">hits</span></span>
              </div>
              <div className="h-1 bg-line rounded-full overflow-hidden ml-14">
                <div className="h-full bg-gradient-to-r from-red-500 to-orange-400 rounded-full transition-all duration-700"
                  style={{ width: `${(c.n / cMax) * 100}%` }} />
              </div>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

// ── Alert list ────────────────────────────────────────────────────────────────

function AlertList({ alerts }: { alerts: Alert[] }) {
  return (
    <section className="card card-hover mb-8">
      <div className="px-5 py-4 border-b border-line flex items-center justify-between">
        <div>
          <h3 className="text-[14px] font-semibold flex items-center gap-2">
            Recent alerts
            <span className="relative flex w-1.5 h-1.5">
              <span className="absolute inline-flex h-full w-full rounded-full bg-emerald-400 animate-ping opacity-75"/>
              <span className="relative rounded-full w-1.5 h-1.5 bg-emerald-500"/>
            </span>
          </h3>
          <p className="text-[12px] text-mute mt-0.5">{alerts.length} latest events from your phone</p>
        </div>
        <button className="text-[12px] text-ink2 hover:text-ink px-2.5 py-1 rounded-md hover:bg-surface">Filter</button>
      </div>
      <ul className="max-h-[560px] overflow-y-auto">
        {alerts.length === 0 && (
          <li className="px-5 py-12 text-center text-mute text-sm">
            No alerts yet — Tank is listening on <code className="text-ink2 bg-surface px-1.5 py-0.5 rounded">/ws/phone</code>
          </li>
        )}
        {alerts.map(a => {
          const sev = SEV[a.severity] ?? SEV.LOW;
          let tracker: any = null;
          try { tracker = a.tracker_json ? JSON.parse(a.tracker_json) : null; } catch {}
          const time = new Date(a.created_at).toISOString().slice(11, 19);
          return (
            <li key={a.id} className="px-5 py-3.5 border-b border-line last:border-0 hover:bg-surface transition-colors group">
              <div className="flex items-start gap-3">
                <span className={`text-[10px] uppercase font-bold tracking-wider px-2 py-0.5 rounded ${sev.chip} mt-0.5 shrink-0 num`}>
                  {sev.label}
                </span>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap text-[14px]">
                    <div className="w-5 h-5 rounded bg-gradient-to-br from-blue-500/30 to-violet-500/30 ring-1 ring-line flex items-center justify-center text-[9px] font-bold">
                      {(a.app_label || "?").slice(0, 1).toUpperCase()}
                    </div>
                    <span className="font-semibold">{a.app_label || "Unknown"}</span>
                    <span className="text-mute">→</span>
                    <code className="text-[12px] font-mono text-ink2 bg-surface px-2 py-0.5 rounded ring-1 ring-line">{a.domain}</code>
                    {tracker?.company && (
                      <span className="text-[11px] text-mute flex items-center gap-1">
                        <span className="w-1 h-1 rounded-full bg-mute"/>
                        {tracker.company}
                        <span className="w-1 h-1 rounded-full bg-mute"/>
                        <span className="px-1.5 py-0.5 rounded text-[10px]"
                          style={{ background: (CAT_COLORS[tracker.category] || "#6b7280") + "20", color: CAT_COLORS[tracker.category] || "#9ca3af" }}>
                          {tracker.category}
                        </span>
                      </span>
                    )}
                  </div>
                  <p className="text-[13px] text-ink2 mt-1.5 leading-relaxed">{a.explanation}</p>
                </div>
                <div className="text-right shrink-0">
                  <div className="num text-[11px] text-mute font-mono">{time}</div>
                  <div className="mt-1">
                    {a.user_decision ? (
                      <span className="text-[10px] uppercase font-semibold tracking-wider px-2 py-0.5 rounded bg-emerald-500/15 text-emerald-300 ring-1 ring-emerald-500/30">
                        ✓ {a.user_decision}
                      </span>
                    ) : (
                      <span className="text-[10px] uppercase font-medium tracking-wider px-2 py-0.5 rounded bg-surface text-mute ring-1 ring-line">
                        → {a.suggested_action.toLowerCase()}
                      </span>
                    )}
                  </div>
                </div>
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

// ── helpers ───────────────────────────────────────────────────────────────────

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span className="flex items-center gap-1.5 text-mute">
      <span className="w-2 h-2 rounded-sm" style={{ background: color }} />
      {label}
    </span>
  );
}

function Empty() { return <div className="h-full flex items-center justify-center text-mute text-sm">No data yet</div>; }

const tooltipStyle = {
  background: "#0b0d10",
  border: "1px solid #353c47",
  borderRadius: 8,
  fontSize: 12,
  color: "#e7eaf0",
  padding: "8px 12px",
  boxShadow: "0 8px 24px rgba(0,0,0,0.4)",
};
