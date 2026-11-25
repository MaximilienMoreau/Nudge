#!/usr/bin/env bash
# Expose a local Nudge backend on a temporary public HTTPS address, to test tracking
# with real mailboxes. Stop with Ctrl+C: the address stops working.
#
#   scripts/dev-tunnel.sh <extension-id>
#
# The extension id is shown in chrome://extensions. Set PORT to run beside another
# backend. The backend runs with the prod
# profile, generated secrets (.tunnel.env, ignored by git) and its own database, so
# the well-known development keys and data are never exposed.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
extension_id="${1:-}"
secrets="$root/.tunnel.env"
port="${PORT:-8080}"
database=nudge_tunnel

fail() { echo "error: $*" >&2; exit 1; }

[[ "$extension_id" =~ ^[a-p]{32}$ ]] || fail "usage: $0 <extension-id> (32 letters, see chrome://extensions)"
for tool in mvn openssl psql curl; do command -v "$tool" >/dev/null || fail "$tool is not installed"; done
command -v cloudflared >/dev/null || fail "cloudflared is not installed. See
  https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/"
if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then fail "port $port is in use: stop the other backend, or choose another port with PORT=8090 $0 ..."; fi

if [[ ! -f "$secrets" ]]; then
  (umask 077; printf 'JWT_SECRET=%s\nENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)" "$(openssl rand -base64 32)" > "$secrets")
  echo "Generated $secrets"
fi
set -a; source "$secrets"; set +a

export DB_USERNAME="${DB_USERNAME:-postgres}"
# PostgreSQL only listens locally: the development password stays private.
export DB_PASSWORD="${DB_PASSWORD:-$(sed -n 's/^DB_PASSWORD=//p' "$root/backend/src/main/resources/application-dev.properties")}"
export DB_URL="jdbc:postgresql://localhost:5432/$database"
sql() { PGPASSWORD="$DB_PASSWORD" psql -w -qAt -h localhost -U "$DB_USERNAME" -d postgres -c "$1"; }
[[ "$(sql "SELECT 1 FROM pg_database WHERE datname = '$database'")" == 1 ]] || sql "CREATE DATABASE $database"

log="$(mktemp)"
cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:$port" >"$log" 2>&1 &
tunnel=$!
trap 'kill "$tunnel" 2>/dev/null || true; rm -f "$log"' EXIT

url=""
for _ in $(seq 60); do
  url="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$log" | grep -v '//api\.' | head -1 || true)"
  [[ -n "$url" ]] && break
  kill -0 "$tunnel" 2>/dev/null || break
  sleep 0.5
done
[[ -n "$url" ]] || { cat "$log" >&2; fail "the tunnel did not start"; }

cat <<INFO

  Public address: $url   (new one at each start)
  1. Open $url once the backend has started, and create an account
  2. Nudge popup > Settings > Server URL: $url, then sign in
  3. Send a test email. Ctrl+C here closes the tunnel.

  Emails sent now stop reporting opens once the tunnel is closed.

INFO

# The profile is passed to Maven: pom.xml defaults to dev and wins over SPRING_PROFILES_ACTIVE.
export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=$port
export APP_BASE_URL="$url" CORS_ALLOWED_ORIGINS="$url,chrome-extension://$extension_id"
export COOKIE_SECURE=true COOKIE_SAME_SITE=Strict NOTIFICATION_EMAIL_ENABLED=false
mvn -q -f "$root/backend/pom.xml" spring-boot:run -Dspring-boot.run.profiles=prod
