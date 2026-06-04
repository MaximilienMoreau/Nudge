#!/usr/bin/env bash
# build-frontend.sh — Injects production API URLs into config.js before Vercel serves the static site.
# Called automatically by Vercel via the buildCommand in vercel.json.
#
# Required Vercel environment variables (set in Vercel project → Settings → Environment Variables):
#   NUDGE_API_BASE  — HTTPS URL of the Railway backend, e.g. https://nudge-api.railway.app
#   NUDGE_WS_URL    — WSS URL of the Railway backend,   e.g. wss://nudge-api.railway.app/ws

set -euo pipefail

: "${NUDGE_API_BASE:?NUDGE_API_BASE is required — set it in Vercel project settings}"
: "${NUDGE_WS_URL:?NUDGE_WS_URL is required — set it in Vercel project settings}"

echo "[nudge-build] Injecting API URLs into frontend/js/config.js"
echo "  API_BASE → $NUDGE_API_BASE"
echo "  WS_URL   → $NUDGE_WS_URL"

sed -i "s|API_BASE: 'http://localhost:8080'|API_BASE: '$NUDGE_API_BASE'|g"      frontend/js/config.js
sed -i "s|WS_URL: 'http://localhost:8080/ws'|WS_URL: '$NUDGE_WS_URL'|g"         frontend/js/config.js

echo "[nudge-build] Done."
