# Privacy Warden — Canva App

A Canva App that surfaces a phone's **Privacy Warden** posture inside Canva's
editor sidebar. Useful for designers handling sensitive client data who want a
glanceable signal that their device is clean before they upload assets.

## What this app shows

```
╭───────────────────────────────────╮
│  Privacy Warden                   │
│  ─────────────────────────────    │
│                                   │
│        ┌─────────┐                │
│        │   92    │  ← live score  │
│        │  /100   │                │
│        └─────────┘                │
│                                   │
│   ● Watchdogs ON · 0 telemetry    │
│   ● Last alert: 3 min ago         │
│                                   │
│   [   Refresh   ]                 │
│                                   │
│   ─────────────────────────       │
│   3 recent alerts                 │
│   • Suspicious SMS · 22 min ago   │
│   • Mic-on (Game) · 1 hr ago      │
│   • OTP-storm · 3 hr ago          │
│                                   │
│   ─ powered by privacy-warden ─   │
╰───────────────────────────────────╯
```

The app polls a Privacy Warden Tank endpoint (or a local mock if unavailable),
shows the live privacy score, and offers a refresh button.

## Files

| Path | Role |
|---|---|
| `app.json` | Canva App manifest (id, name, permissions) |
| `package.json` | Dependencies — `@canva/app-ui-kit`, `@canva/design`, React |
| `src/index.tsx` | Entry point — mounts React into Canva's iframe |
| `src/app.tsx` | Main UI built from `@canva/app-ui-kit` components |
| `src/api.ts` | Privacy Warden Tank client (with mock fallback) |
| `tsconfig.json` | TypeScript config compatible with Canva Apps |

## Run locally — fastest path

The official `@canva/cli` does the webpack scaffolding for you. Best workflow:

```sh
# 1. Scaffold an empty Canva App via the official template
npm install -g @canva/cli@latest
canva login
canva apps create privacy-warden-app \
  --template hello_world \
  --distribution public \
  --installDependencies

# 2. Copy our source files into the new scaffold
cp -r src/* privacy-warden-app/src/
cp app.json privacy-warden-app/app.json

# 3. Add our extra deps if not already present
cd privacy-warden-app
npm install @canva/app-ui-kit @canva/design

# 4. Start the dev server
npm start
```

Then in Canva (`canva.com → Apps → Your apps`) point your app at
`https://localhost:8080` and click **Preview**. You'll see the Privacy Warden
panel appear in Canva's right rail.

## Connecting to a real Tank

By default `src/api.ts` returns a mock score so the app demoes without backend
setup. To wire to your actual Tank:

```ts
// src/api.ts
const TANK_URL = "https://your-tank-host.example.com";
```

The Tank exposes `/api/state` returning `{ score, watchdogs, alerts[] }`.
See `tank/app/main.py` in the parent repo.

## Distribution

Once tested:

```sh
canva apps publish     # submit for review
```

Canva reviews public apps in 5-10 business days.
