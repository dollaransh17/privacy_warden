/**
 * Privacy Warden — Canva App
 *
 * Surfaces device privacy posture inside Canva's editor sidebar.
 * Built entirely with @canva/app-ui-kit components so it stays visually
 * consistent with the rest of Canva's editor chrome (no custom CSS).
 */
import { useCallback, useEffect, useState } from "react";
import {
  Alert as UiAlert,
  Badge,
  Box,
  Button,
  Columns,
  Column,
  Divider,
  EyeIcon,
  LightBulbIcon,
  LockClosedIcon,
  ReloadIcon,
  Rows,
  Text,
  Title,
} from "@canva/app-ui-kit";

import {
  fetchPrivacyState,
  formatAgo,
  type Alert as PwAlert,
  type PrivacyState,
  type Severity,
} from "./api";

export const App = () => {
  const [state, setState] = useState<PrivacyState | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const s = await fetchPrivacyState();
      setState(s);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load state");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return (
    <Box padding="2u">
      <Rows spacing="2u">
        {/* Header */}
        <Box>
          <Title size="medium">Privacy Warden</Title>
          <Text size="small" tone="tertiary">
            Glanceable privacy posture for the device you're designing on
          </Text>
        </Box>

        <Divider />

        {/* Body */}
        {error && (
          <UiAlert tone="critical" title="Could not load privacy state">
            {error}
          </UiAlert>
        )}
        {!error && (state ? <ScoreBlock state={state} /> : <Skeleton />)}

        <Divider />

        {/* Refresh */}
        <Button
          variant="primary"
          icon={ReloadIcon}
          loading={loading}
          onClick={refresh}
          stretch
        >
          Refresh
        </Button>

        {/* Alerts */}
        {state?.alerts.length ? (
          <>
            <Divider />
            <Title size="xsmall">Recent alerts</Title>
            <Rows spacing="1u">
              {state.alerts.slice(0, 3).map((a) => (
                <AlertRow key={a.id} alert={a} />
              ))}
            </Rows>
          </>
        ) : null}

        <Divider />

        {/* Footer */}
        <Box display="flex" justifyContent="center">
          <Text size="xsmall" tone="tertiary">
            powered by privacy-warden
          </Text>
        </Box>
      </Rows>
    </Box>
  );
};

// ── ScoreBlock ────────────────────────────────────────────────────────────
function ScoreBlock({ state }: { state: PrivacyState }) {
  return (
    <Rows spacing="1.5u">
      <Box display="flex" alignItems="center" justifyContent="center">
        <Box>
          <Text size="xlarge" tone="primary" alignment="center">
            <span style={{ fontSize: 56, fontWeight: 700, lineHeight: 1 }}>
              {state.score}
            </span>
          </Text>
          <Text size="small" tone="tertiary" alignment="center">
            of 100 · {state.scoreLabel}
          </Text>
        </Box>
      </Box>

      <Columns spacing="1u" alignY="center">
        <Column>
          <Box display="flex" alignItems="center">
            <LockClosedIcon />
            <Box paddingStart="0.5u">
              <Text size="small">
                {state.watchdogsOn ? "Watchdogs ON" : "Watchdogs OFF"}
              </Text>
            </Box>
          </Box>
        </Column>
        <Column>
          <Box display="flex" alignItems="center">
            <EyeIcon />
            <Box paddingStart="0.5u">
              <Text size="small">{state.telemetryCount} telemetry</Text>
            </Box>
          </Box>
        </Column>
      </Columns>

      <Box display="flex" alignItems="center">
        <LightBulbIcon />
        <Box paddingStart="0.5u">
          <Text size="small" tone="tertiary">
            Last alert: {formatAgo(state.lastAlertAgoMs)}
          </Text>
        </Box>
      </Box>
    </Rows>
  );
}

// ── AlertRow ──────────────────────────────────────────────────────────────
function AlertRow({ alert }: { alert: PwAlert }) {
  return (
    <Box>
      <Columns spacing="1u" alignY="center">
        <Column width="content">
          <SeverityBadge severity={alert.severity} />
        </Column>
        <Column>
          <Text size="small">{alert.title}</Text>
          <Text size="xsmall" tone="tertiary">
            {alert.category} · {formatAgo(alert.agoMs)}
          </Text>
        </Column>
      </Columns>
    </Box>
  );
}

function SeverityBadge({ severity }: { severity: Severity }) {
  const tone =
    severity === "danger" ? "critical" :
    severity === "warn"   ? "warn"     :
                            "neutral";
  const text =
    severity === "danger" ? "High"    :
    severity === "warn"   ? "Medium"  :
                            "Info";
  return <Badge tone={tone} text={text} />;
}

// ── Skeleton ──────────────────────────────────────────────────────────────
function Skeleton() {
  return (
    <Box padding="2u" display="flex" justifyContent="center">
      <Text tone="tertiary">Loading privacy state…</Text>
    </Box>
  );
}
