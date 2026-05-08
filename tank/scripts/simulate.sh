#!/usr/bin/env bash
# Continuously feeds synthetic flow events into the local Tank — useful for dashboard demos.
# Usage: ./scripts/simulate.sh [tank_url]
set -euo pipefail
URL="${1:-http://127.0.0.1:8443}"

snis=(
  "graph.facebook.com" "app-measurement.com" "api.mixpanel.com"
  "api.appsflyer.com" "sdk.iad-01.braze.com" "api.clevertap.com"
  "in.hotjar.com" "events.smartlook.com" "api.segment.io"
  "api.amplitude.com" "log.byteoversea.com" "api.adjust.com"
  "googleads.g.doubleclick.net" "ads.linkedin.com" "api.snapchat.com"
)
apps=(
  "in.swiggy.android:Swiggy" "net.one97.paytmapp:Paytm"
  "com.phonepe.app:PhonePe" "com.truecaller:Truecaller"
  "com.zomato.android:Zomato" "in.startv.hotstar:Hotstar"
  "in.flipkart.android:Flipkart" "com.dunzo.user:Dunzo"
  "com.olacabs.customer:Ola" "in.amazon.mShop.android:Amazon"
)

echo "Streaming events to $URL — Ctrl+C to stop"
while true; do
  sni=${snis[$((RANDOM % ${#snis[@]}))]}
  app=${apps[$((RANDOM % ${#apps[@]}))]}
  pkg="${app%%:*}"; lbl="${app##*:}"
  curl -sS -X POST "$URL/api/_sim/event" \
    -H 'content-type: application/json' \
    -d "{\"device_id\":\"demo\",\"app_package\":\"$pkg\",\"app_label\":\"$lbl\",\"sni\":\"$sni\",\"bytes_up\":$((RANDOM%2000+128)),\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" \
    > /dev/null && echo "  ↗ $lbl → $sni"
  sleep $(awk "BEGIN{print 0.5+rand()*2}")
done
