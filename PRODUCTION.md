# Nudge: Production Deployment Guide

## Quick start (Docker Compose)

```bash
cp .env.example .env
# Fill in all required values in .env (see comments inside the file)
docker compose up -d
```

---

## Dashboard hosting and authentication

The Docker/Railway image includes the dashboard. Serving it from the same origin
as the API is the default: no frontend build or third-party cookie is needed.
Build the image from the repository root:

```bash
docker build -f backend/Dockerfile -t nudge .
```

For HTTPS production set `SPRING_PROFILES_ACTIVE=prod`, `COOKIE_SECURE=true`,
`APP_BASE_URL=https://nudge.example.com` and explicit `CORS_ALLOWED_ORIGINS`.
The `/actuator/health` endpoint is public and exposes status only.

For separate Vercel hosting, set `NUDGE_API_BASE=https://api.example.com` and run
`bash build-frontend.sh` (Vercel runs this automatically). `NUDGE_WS_URL` is optional;
when set, use `https://api.example.com/ws`, **not** `wss://`: SockJS requires HTTP(S).
The build generates `frontend/js/deployment.js` and can safely be repeated.

Prefer same-site custom domains, e.g. `app.example.com` and `api.example.com`.
For independent sites such as Vercel and Railway default domains, set
`COOKIE_SAME_SITE=None` and `COOKIE_SECURE=true` on the backend. Cookies may still
be blocked by browser third-party-cookie policies; same-origin hosting avoids that.
Cookie-authenticated writes must carry an allowed `Origin` (or same-origin Referer).
Bearer clients remain supported without cookie-based CSRF protection.

## Browser extension

Load `extension/` in Chrome or Edge, open the popup, enter the HTTPS backend origin
and click Save. Grant access to that server when the browser prompts. Changing
servers clears locally stored credentials; sign in to the new server.
Add the extension's origin to the backend's `CORS_ALLOWED_ORIGINS` when needed.

Tracking is limited to one recipient per compose window. To send to several people,
turn tracking off or send separate messages. The API accepts batch creation for
separate messages, but shared pixels cannot identify individual readers.
The extension waits for registration and pixel insertion before replaying Send.
An error leaves the draft unsent. Retrying after an edit may leave an unused tracking
record from the previous preparation; it can be archived from the dashboard.

## Database and migration

Back up the database before upgrading. Flyway applies V1–V5 automatically under
`prod`; Hibernate validates the resulting schema. V5 expands subjects to 500 characters
and adds an index for genuine-event counts and latest-event lookups.
Use an empty database for first deployment. If adopting an existing schema, review
the Flyway baseline and column definitions before starting the production application.

## Notifications and scaling

SMTP failures leave scheduled reminders pending for retry. Connection, read and
write timeouts bound SMTP calls. Delivery is at least once: a crash after sending
but before clearing a reminder may produce a duplicate.

This deployment supports a single backend instance. The STOMP broker, bot timing
cache and rate limiter are in memory. Multiple instances require an external broker,
a shared rate limiter/cache and coordination of scheduled reminder delivery.
A dedicated Redis/RabbitMQ deployment is a separate infrastructure change.

## Secrets

See [.env.example](.env.example). Generate independent JWT and AES keys with:

```bash
openssl rand -base64 32
```

Secrets are excluded from the Docker build context. The frontend build writes only
public API URLs. Never put private API keys in frontend variables.
