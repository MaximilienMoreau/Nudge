<h1 align="center">Nudge: Email Open Tracker</h1>

> Know the moment your email is read. No reply needed.

Nudge is a **browser extension** (Chrome & Edge) paired with a self-hosted backend that
tells you whether the emails you send have been opened: in the extension popup, in
Gmail's Sent list, on a dashboard and through browser notifications.

It works with an invisible image (a *tracking pixel*) added to the email when you send
it. Loading that image is what counts as an open. This has limits that no tracker can
remove: see [Read status](#read-status).

---

## Features

**Read status**

| | |
|---|---|
| **Reliable status** | Ignores your own views, mail scanners and automatic loading (Apple, Proton); counts Gmail, Yahoo and Outlook mobile opens |
| **Popup** | Your 10 latest tracked emails with their status |
| **Browser notifications** | Within about a minute of an open or a link click |
| **Gmail ticks** | ✓ / ✓? / ✓✓ in the Sent list |
| **Dashboard** | Search, sort, counters, history of each email (opens counted or ignored, and why), live updates over WebSocket, archive & restore |
| **Email notifications** | Optional SMTP fallback when the dashboard is not open |

**Extras**

| | |
|---|---|
| **Engagement score** | 0–100 estimate from opens, recency, frequency and clicks |
| **AI follow-ups** | GPT-4o-mini drafts a follow-up (template text without an OpenAI key) |
| **Best send time** | Suggests a day and hour from past opens, in your time zone |
| **Follow-up reminders** | Scheduled through the API; notified by WebSocket and email |
| **Link click tracking** | Through hand-made tracked links; the extension does not rewrite links |

**Security**

| | |
|---|---|
| **Password reset** | Single-use link by email, valid 30 minutes |
| **Message text** | Not stored by default. When you choose to store it, encrypted with AES-256-GCM; key rotation supported |
| **Rate limiting** | On login, register, and pixel/click tracking endpoints |
| **Recipient tracking** | One recipient per tracked email, so that an open can be attributed |

---

## Architecture

```
nudge/
├── backend/      Spring Boot 4.1 (Java 17) (REST API + WebSocket)
├── frontend/     Vanilla HTML/CSS/JS (Dashboard UI)
├── extension/    Browser Extension v1.4.0 (Manifest V3, Chrome & Edge)
├── scripts/      Frontend build, development tunnel, JavaScript tests
└── database/     Database creation script (tables: backend/src/main/resources/db/migration/)
```

**Two ways to track an email:**
- **Extension** (recommended): install → compose → pixel added automatically on Send
- **Manual pixel**: create tracking via the dashboard → copy pixel → paste into any email client

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+ |
| Maven | 3.8+ |
| PostgreSQL | 14+ |
| Chrome or Edge | Latest |
| OpenAI Key | *(optional, AI features only)* |
| cloudflared | *(optional, to [test with a real mailbox](#testing-with-a-real-mailbox))* |
| Node.js | 20+ *(optional, JavaScript tests)* |
| Docker + Compose | *(optional, containerised deployment)* |

---

## Quick Start

### Option A: Docker Compose (recommended)

```bash
cp .env.example .env
# Fill in JWT_SECRET, ENCRYPTION_KEY, DB_PASSWORD (see .env.example for all variables)
docker compose up -d
```

To sign in from the extension, add its origin to `CORS_ALLOWED_ORIGINS` in `.env`
(the id is shown in `chrome://extensions`):

```bash
CORS_ALLOWED_ORIGINS=http://localhost:8080,chrome-extension://<extension-id>
```

The API and bundled dashboard are available at `http://localhost:8080`. No separate
frontend server is required. The image exposes a public `/actuator/health` endpoint
without database details.

### Option B: Local (bare metal)

#### 1. Database

```bash
psql -U postgres -c "CREATE DATABASE nudge;"
```

The `dev` profile connects to `localhost:5432/nudge` (user `postgres`, password `password`). Override if needed:

```bash
DB_USERNAME=youruser DB_PASSWORD=yourpassword mvn spring-boot:run
```

#### 2. Backend

```bash
cd backend

# Optional — AI follow-ups require this; graceful fallback text is used without it
export OPENAI_API_KEY=sk-...

mvn spring-boot:run
```

The `dev` profile is active by default. It supplies safe local defaults for `JWT_SECRET` and `ENCRYPTION_KEY` — no manual configuration is needed for local development. In this profile, Hibernate creates or updates the tables and Flyway is disabled. The `prod` profile runs Flyway migrations and validates the schema.

The API and frontend both start at `http://localhost:8080`.

#### 3. Frontend

The dashboard is served directly by Spring Boot at `http://localhost:8080` — no separate server needed.

```bash
# Option A: served by backend (recommended)
# Just open http://localhost:8080 after starting the backend.

# Option B: standalone static server
npx serve frontend/
python3 -m http.server 3000 -d frontend/
```

#### 4. Browser Extension (Chrome or Edge)

**Chrome:**
1. `chrome://extensions/` → Enable **Developer mode** (top right)
2. Click **Load unpacked** → select the `/extension` folder
3. The Nudge icon appears in your toolbar — click it to sign in

**Edge:**
1. `edge://extensions/` → Enable **Developer mode** (left sidebar)
2. Click **Load unpacked** → select the `/extension` folder

Then click the Nudge icon, check the server address in **Settings**, and sign in. The
extension asks for these permissions:

| Permission | Why |
|---|---|
| Supported webmails | Add the pixel when you send; show the ticks in Gmail |
| `googleusercontent.com`, `yusercontent.com` | Keep your own browser from loading your pixels through the Gmail and Yahoo image proxies |
| `declarativeNetRequestWithHostAccess` | Same, without reading your traffic |
| `scripting` | Copy the structure of the current page, only when you ask for it |
| `notifications`, `alarms` | Notify you, checking the server every minute |
| `storage`, `activeTab` | Session and settings; detect the current webmail |

> After updating the code, reload the extension on this page.

> Edge natively supports Chrome Manifest V3 — the same `/extension` folder works on both browsers without modification.

---

## Environment Variables

All secrets are supplied via environment variables. Copy `.env.example` to `.env` before deploying with Docker Compose. For a local Maven run, export the variables in your shell; Spring Boot does not automatically load the root `.env` file.

Docker Compose forwards authentication cookie settings, trusted proxy ranges, key rotation settings, SMTP settings and the scheduler interval.

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/nudge` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | *(required)* | Database password |
| `JWT_SECRET` | *(dev default)* | Base64-encoded 32-byte key — **change for production** |
| `JWT_REMEMBER_EXPIRATION_MS` | `2592000000` | Browser extension session length in ms (default: 30 days) |
| `ENCRYPTION_KEY` | *(dev default)* | Base64 AES-256 key for email content — **change for production** |
| `ENCRYPTION_KEY_V2` | *(empty)* | Optional second key for seamless key rotation |
| `ENCRYPTION_ACTIVE_KEY_VERSION` | `v1` | Which key version to use for new writes |
| `APP_BASE_URL` | `http://localhost:8080` | Public-facing URL (used in tracking pixel URLs) |
| `APP_FRONTEND_URL` | *(`APP_BASE_URL`)* | Where the dashboard is served, if elsewhere (password reset links) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080,http://localhost:3000` | Comma-separated allowed CORS origins; dev uses `*`, Compose defaults to `http://localhost:8080` |
| `COOKIE_SECURE` | `false` | Adds `Secure` flag to JWT cookie — set `true` in production (requires HTTPS) |
| `COOKIE_SAME_SITE` | `Strict` | `Strict`, `Lax` or `None`; cross-site hosting needs `None` and `COOKIE_SECURE=true`. Cookie writes validate the request origin. |
| `TRUSTED_PROXY_RANGES` | `127.0.0.1,::1` | CIDR ranges of trusted proxies (for `X-Forwarded-For`) |
| `OPENAI_API_KEY` | *(empty)* | OpenAI key — falls back to template text without it |
| `NOTIFICATION_EMAIL_ENABLED` | `false` dev / `true` prod / `false` Compose | Enable SMTP email notification fallback |
| `MAIL_HOST` | `smtp.gmail.com` | SMTP server host |
| `MAIL_PORT` | `587` | SMTP server port |
| `MAIL_USERNAME` | *(empty)* | SMTP username |
| `MAIL_PASSWORD` | *(empty)* | SMTP password |
| `NOTIFICATION_EMAIL_FROM` | `noreply@nudge.app` | From address for outgoing notifications |
| `FOLLOWUP_SCHEDULER_INTERVAL_MS` | `3600000` | Follow-up check interval in ms (default: 1 hour) |

Generate strong keys with:

```bash
openssl rand -base64 32   # JWT_SECRET
openssl rand -base64 32   # ENCRYPTION_KEY
```

---

## API Reference

Authentication uses an **httpOnly cookie** (`nudge_jwt`) set on login. All protected endpoints read the cookie automatically — no `Authorization` header needed from the browser. API clients (e.g. the Chrome extension) may alternatively send `Authorization: Bearer <token>`.

### Authentication (public)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/register` | Create a new account |
| POST | `/api/auth/login` | Exchange credentials for a JWT |
| PUT | `/api/auth/password` | Change password (requires JWT) — returns new JWT |
| POST | `/api/auth/forgot-password` | Send a reset link. Same answer whether or not the account exists; `503` if the server cannot send emails |
| POST | `/api/auth/reset-password` | Set a new password with the emailed token; signs out every session |
| POST | `/api/auth/logout` | Revoke current token server-side (requires JWT) |
| DELETE | `/api/auth/account` | Permanently delete the account (requires JWT) |

**POST `/api/auth/register` and `/api/auth/login` body:**
```json
{ "email": "you@example.com", "password": "ExamplePass1!" }
```

> Login also accepts `"rememberMe": true`: it returns a 30-day bearer token and sets no cookie. The extension uses it.

> Registration requires at least 8 characters, including an uppercase letter and a number or special character.

**DELETE `/api/auth/account` body:**
```json
{ "password": "current-password" }
```

> Password confirmation prevents accidental or CSRF-triggered deletion. All tracked emails and events cascade-delete automatically.

**Response (`AuthResponse`):**
```json
{
  "token": "eyJ...",
  "email": "you@example.com",
  "userId": 1,
  "createdAt": "2026-01-01T12:00:00"
}
```

---

### Emails (requires JWT)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/emails` | List active tracked emails (paginated) |
| POST | `/api/emails` | Register a new email for tracking |
| GET | `/api/emails/{id}` | Get a single email with full stats |
| GET | `/api/emails/{id}/events` | Latest 200 opens and clicks, counted or not, with the reason (UTC) |
| DELETE | `/api/emails/{id}` | Soft-delete (archive) an email |
| GET | `/api/emails/archived` | List archived emails |
| POST | `/api/emails/{id}/restore` | Restore an archived email |
| DELETE | `/api/emails/{id}/permanent` | Permanently delete an email and all its events |
| POST | `/api/emails/{id}/schedule` | Schedule a follow-up reminder |

**GET `/api/emails` query params:** `?page=0&size=50&search=&sort=createdAt&ascending=false`
(max 200 per page). Search and sorting apply across the complete active mailbox.
Sort fields: `subject`, `recipientEmail`, `createdAt`, `status`, `leadScore`,
`lastOpenedAt`, `lastActivityAt`, `clickCount`, `openCount`, `archivedAt`.

`GET /api/emails/stats` returns global active-mailbox `total`, `opened`, `hot` and
`average` counters, plus `unconfirmed`, independently of the current search or page. List responses omit
the decrypted body; request `GET /api/emails/{id}` for content.

**POST `/api/emails` body:**
```json
{
  "subject": "Follow up on our meeting",
  "recipientEmails": ["john@company.com", "jane@company.com"],
  "content": "Hi John, just wanted to follow up..."
}
```

> `recipientEmails` (batch creation) or `recipientEmail` (single string). At least
> one is required. Returns one `EmailDTO` per recipient. For attribution, send each
> recipient a separate email containing only their pixel. A shared multi-recipient
> message cannot identify its reader. The extension permits one recipient while
> tracking is enabled; turn tracking off to send a group email.

**POST `/api/emails/{id}/schedule` body:**
```json
{ "scheduledAt": "2026-04-20T09:00:00" }
```

**Email response (`EmailDTO`):**
```json
{
  "id": 42,
  "subject": "Follow up on our meeting",
  "recipientEmail": "john@company.com",
  "trackingPixelUrl": "http://localhost:8080/track/open/{uuid}",
  "clickTrackingBaseUrl": "http://localhost:8080/track/click/{uuid}",
  "leadScore": 75,
  "status": "Opened Multiple Times",
  "openCount": 3,
  "clickCount": 1,
  "createdAt": "2026-01-01T12:00:00",
  "lastOpenedAt": "2026-01-02T09:00:00",
  "lastClickedAt": "2026-01-02T09:01:00"
}
```

---

### Tracking (public, called automatically by email clients)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/track/open/{trackingId}` | Returns 1×1 GIF and logs an OPEN event |
| GET | `/track/click/{trackingId}?url=` | Logs a CLICK event and 302-redirects to `url` |

> **`url` must use `http` or `https`.** Any other scheme (`javascript:`, `data:`, `file:`, etc.) or a missing `url` parameter returns `400 Bad Request`. Always URL-encode the destination.

Embed in emails:
```html
<!-- Tracking pixel (invisible) -->
<img src="http://localhost:8080/track/open/{trackingId}" width="1" height="1" style="display:none" alt=""/>

<!-- Tracked link -->
<a href="http://localhost:8080/track/click/{trackingId}?url=https%3A%2F%2Fyour-link.com">Click here</a>
```

---

### AI (requires JWT)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/ai/followup` | Generate an AI follow-up email |
| POST | `/api/ai/send-time` | Suggest the best day and hour to send. Optional body: `{ "timeZone": "Europe/Paris" }` (UTC by default) |

**POST `/api/ai/followup` body:**
```json
{
  "emailId": 42,
  "daysSinceSent": 3
}
```

> `engagementScore` and `openCount` are not accepted from the client — the server reads them from the database to prevent tampering.

---

### WebSocket

Connect to `http://localhost:8080/ws` using SockJS + STOMP (SockJS takes an `http(s)://` endpoint, not `ws://`, and negotiates the WebSocket upgrade internally).

The server authenticates via the `nudge_jwt` httpOnly cookie sent automatically by the browser on the SockJS handshake — no explicit token parameter needed.

Subscribe to: `/user/queue/notifications`

**Notification types:**

| Type | Trigger |
|------|---------|
| `EMAIL_OPENED` | An open counted as a read is recorded |
| `EMAIL_CLICKED` | A non-bot click event is recorded |
| `FOLLOW_UP_REMINDER` | A scheduled follow-up reminder becomes due |

**Payload:**
```json
{
  "type": "EMAIL_OPENED",
  "emailId": 42,
  "subject": "Follow up on our meeting",
  "recipientEmail": "john@company.com",
  "openCount": 2,
  "leadScore": 65,
  "timestamp": "2026-01-15T10:30:00"
}
```

---

## Extras: Engagement Score and Insights

The score and the send-time suggestion are in the dashboard's **Insights** tab; a
follow-up draft is available on each email. None of them affects the read status.

The **engagement score** (0–100) only uses opens and clicks that were counted as reads.
The `openCount` field in the API response follows the same rule.

| Signal | Points |
|--------|--------|
| Opens volume | 15 per open, max 40 |
| Recency | Continuous exponential decay: `40 × e^(−λt)`, half-life = 6 h |
| Frequency (> 5×) | 20 |
| Frequency (> 3×) | 15 |
| Frequency (> 1×) | 10 |
| Click (1 click) | 10 |
| Click (≥ 2 clicks) | 20 |

Recency examples: just opened → 40 pts · 6 h ago → 20 pts · 12 h ago → 10 pts · 48 h+ → ~0 pts.

Scores ≥ 70 are flagged as **hot leads** 🔥.

Run the server in UTC (the Docker default): the score assumes it. AI follow-ups work
better when the message text is stored: see **Privacy** in the extension settings.

---

## Read Status

| Mark | API status | Meaning |
|---|--------|---------|
| ✓ | `Not Opened` | No open detected. The email may have been read with images blocked |
| ✓? | `Unconfirmed` | Only an automatic loading was seen (Apple Mail, Proton Mail): impossible to tell |
| ✓✓ | `Opened` | One open, or a link click |
| ✓✓ | `Opened Multiple Times` | Two opens or more |

A pixel load is stored but **not counted as a read** when it is:

| Reason | How it is recognised |
|--------|----------------------|
| `SCANNER` | User-Agent of a mail scanner or security gateway (Mimecast, Proofpoint, Barracuda, Safe Links, URLScan, crawlers…) |
| `APPLE_PRIVACY` | Apple Mail Privacy Protection, which loads images whether or not the email is read. Its User-Agent is a bare `Mozilla/5.0` |
| `PROTON_PRELOAD` | Email sent to a Proton address (`proton.me`, `protonmail.com`, `protonmail.ch`, `pm.me`): Proton [loads images on reception](https://proton.me/support/email-tracker-protection). Custom domains hosted by Proton cannot be recognised |
| `RAPID_REFETCH` | Second load of the same pixel within 30 seconds |
| `SENDER` | Same IP and same User-Agent as the browser that registered the email, within 5 minutes of registration |

Image proxies that fetch when the recipient displays the email (Gmail, Yahoo) and
Outlook mobile **are counted as reads**.

**What to expect, by recipient**

| Recipient's mailbox | Result |
|---|---|
| Gmail, Yahoo, Outlook | ✓✓ when the email is displayed with images |
| Apple Mail (privacy protection on), Proton Mail | ✓? at best: images are loaded without the recipient reading |
| Tuta | ✓ unless the recipient chooses to [load images](https://tuta.com/blog/what-is-pixel-tracking) |
| HEY | ✓: HEY [removes 1×1 images](https://www.hey.com/spy-trackers/) and tells the recipient who placed them |

A link click counts as a read everywhere.

**Your own views.** The extension answers your own pixels with a local image, one rule
per email you sent (compose window, Sent folder, quoted replies). Emails you receive
from other Nudge users are still reported to them. The rules are created when you send
and, for your 200 latest emails, when you sign in. To recognise you without the
extension, the server stores the IP and User-Agent that registered each email.

**Known limits**

- A recipient who blocks images is never seen as having read.
- Reading your own sent email on another device, or in a browser without the extension,
  can be counted as an open.
- A colleague with the same IP and browser version as you is ignored for 5 minutes after
  registration.
- Emails opened before the V6 migration keep the status they had.
- Gmail's handling of image addresses and Outlook mobile's User-Agent are not officially
  documented and may change.

> Testing locally: opening a pixel in the browser that just registered the email is
> ignored for 5 minutes. Use another browser or device.

---

## Email Notifications (SMTP Fallback)

When `NOTIFICATION_EMAIL_ENABLED=true`, Nudge sends a plain-text email to the sender:
- When a tracked email is opened and no active WebSocket session exists
- When a scheduled follow-up reminder becomes due

Configure via `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, and `MAIL_PASSWORD`. STARTTLS is enabled by default on port 587.

---

## Content Encryption

When stored, email bodies are encrypted at rest using **AES-256-GCM** before being stored in the database. Each value is stored with a unique 12-byte random IV and a 128-bit authentication tag. The wire format is:

```
{version}:{Base64(IV ‖ ciphertext + authTag)}
```

**Key rotation** is supported without downtime: set `ENCRYPTION_KEY_V2` and `ENCRYPTION_ACTIVE_KEY_VERSION=v2`. New values are encrypted with v2 while old v1 values remain readable. Re-encrypt old rows at your own pace.

---

## Extension Usage (Chrome & Edge)

### Sending

1. Sign in from the Nudge popup
2. Open Gmail, Outlook, Proton Mail, Tuta, Infomaniak, or Yahoo Mail and compose a new email to **one** recipient
3. A **Nudge** panel appears next to the Send button, with tracking on. Turn it off to send without tracking or to several people
4. Click Send: Nudge pauses sending, registers the email, adds the pixel, then resumes sending. If preparation fails, the draft stays open: retry or turn tracking off

**Gmail replies and forwards** written inside a conversation are tracked too. When a
reply has several recipients, or its recipient is not recognised, it is sent *without*
tracking and a message says so. Pixels of quoted emails are removed when sending, so that
reading a reply does not mark earlier emails as opened.

**Scheduled messages and "Send and archive"** are tracked. For a scheduled message, the
pixel is added when the scheduling window opens: wait for *Tracking prepared* before
picking a date. If it cannot be added, the message is scheduled without tracking and a
notice says so. Scheduled messages get no tick in Gmail, since their sending time differs.
Cancelling leaves an unused record in the dashboard, which can be archived.

**Message text.** Only the subject and the recipient are registered. To give AI
follow-ups more context, turn on **Settings → Privacy → Store the text of my emails**.

**Webmails**

| Webmail | Detection | Based on |
|---|---|---|
| Gmail | English, French | The gmail.js library |
| Proton Mail, Tuta | Any language | Their published source code (November 2026) |
| Outlook, Infomaniak, Yahoo | English, French | Assumptions about their pages: to be checked |
| HEY | Not supported | Its pages are not documented, and HEY opposes tracking pixels |

None of them guarantees a stable page structure. To add or repair a webmail, open a new
message, then in the Nudge popup: **Settings → Copy page structure**, and share the result.
Texts, names and addresses are masked, and nothing is sent.

| Not tracked | |
|---|---|
| Ctrl+Shift+S in Tuta | Sent normally, without tracking |
| Replies written in Outlook's reading pane | May not be recognised: check that the Nudge panel is displayed |
| Mobile and desktop apps, other browsers | Use a [manual pixel](#without-the-extension-any-email-client) |

No Nudge panel next to Send means no tracking.

### Knowing whether it was read

- **Popup**: your 10 latest emails, their status, the number of opens and the last one.
- **Notifications**: the server is checked every minute. Above 3 emails at once they are
  grouped. Opens that happened while the browser was closed are notified when it
  restarts. Turn them off in **Settings**.
- **Gmail ticks**: in the Sent list, refreshed every minute. A row gets a tick only when
  its subject, recipient and time of sending all match a tracked email, so a tick may be
  missing (for example once the conversation shows the date of a reply).

### Session

The extension stays signed in for 30 days (`JWT_REMEMBER_EXPIRATION_MS`). The token is
kept in the browser profile, out of reach of web pages. Signing out of the dashboard or
changing the password also signs the extension out.

> A red **!** on the icon means you are signed out: nothing is notified, and sending with tracking on is refused, until you sign in again.

### Without the extension (any email client)

1. Go to the dashboard → **Track Email**
2. Fill in the subject, recipient, and body
3. Copy the generated pixel HTML
4. Paste it into your email before sending

---

## Testing with a Real Mailbox

A pixel hosted on `localhost` cannot be loaded by anyone else: Gmail's servers, for
instance, cannot reach your machine, so nothing is ever reported as opened.
`scripts/dev-tunnel.sh` puts a local backend behind a temporary public HTTPS address.

**1. Install cloudflared** (no account needed), unless `cloudflared --version` already answers.
On Debian, Ubuntu or WSL (x86-64):

```bash
curl -L -o cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
sudo dpkg -i cloudflared.deb
```

Other systems: [Cloudflare downloads](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/).
The script also needs `mvn`, `psql`, `openssl`, `curl` and a local PostgreSQL.

**2. Load the extension** and copy its id (32 letters) from `chrome://extensions`.

**3. Start the tunnel:**

```bash
scripts/dev-tunnel.sh <extension-id>
PORT=8090 scripts/dev-tunnel.sh <extension-id>   # beside a backend already on 8080
```

**4. Connect:** open the displayed address and create an account. In the Nudge popup,
set **Settings → Server URL** to that address, accept the permission, then sign in.

**5. Test:**

| Step | Expected |
|---|---|
| Send an email from Gmail to another Gmail address | ✓ in the Sent list and in the popup |
| Read it in your own Sent folder | Still ✓ |
| Open it from the other account, in a browser without the extension | ✓✓ and a notification within about a minute |

**6. Stop** with Ctrl+C, then set the server URL back.

| | |
|---|---|
| **Address** | Changes at each start. Emails sent through an earlier address stop reporting opens |
| **Isolation** | `prod` profile, secrets generated in `.tunnel.env` (ignored by git), separate `nudge_tunnel` database: your development keys and data are not exposed |
| **Exposure** | Anyone who knows the address can reach the backend and create an account while the tunnel is open |
| **Spam** | `trycloudflare.com` addresses may send test emails to the spam folder, where images are not loaded |
| **Reset** | `dropdb -h localhost -U postgres nudge_tunnel` and delete `.tunnel.env` |

For lasting use, deploy the backend: see [PRODUCTION.md](PRODUCTION.md).

---

## Troubleshooting

| Problem | Cause and fix |
|---|---|
| `BUILD FAILURE` on `mvn spring-boot:run` | The cause is higher up: search the output for `Caused by`, the last one is the most precise |
| `FATAL: database "nudge" does not exist` | `createdb -h localhost -U postgres nudge` |
| Changes to `.env` have no effect | Only Docker Compose reads it. Export the variables for a Maven run |
| `Cannot reach Nudge backend` when signing in from the extension | The backend is stopped, the server URL is wrong, or the extension's origin is missing from `CORS_ALLOWED_ORIGINS` (`prod` profile) |
| Emails always stay ✓ | `APP_BASE_URL` is not publicly reachable over HTTPS when the email is sent, or the recipient blocks images |
| Email marked opened as soon as it is sent | The extension was not reloaded after an update, or it has no access to the server: save the server URL again |
| No Nudge panel next to Send | The webmail layout or language is not recognised, or the tab was open before the extension was reloaded: reload it |
| `Recipient not recognised` | The webmail shows a name instead of the address. Type the address in the Nudge panel, or turn tracking off to send |
| Forgotten password, no email configured | With the `dev` profile, the reset link is printed in the server log |
| No notification | Signed out (red **!**), turned off in Settings, or blocked by the operating system |
| No tick in Gmail | See the matching rule in [Extension Usage](#knowing-whether-it-was-read). Reload the Gmail tab after reloading the extension |

---

## Production Checklist

- [ ] Set a strong `JWT_SECRET` (`openssl rand -base64 32`)
- [ ] Set a strong `ENCRYPTION_KEY` (`openssl rand -base64 32`)
- [ ] Set `SPRING_PROFILES_ACTIVE=prod` (Flyway manages the schema; Hibernate is set to `validate`)
- [ ] Use HTTPS (tracking pixels won't load over HTTP in many email clients)
- [ ] Set `COOKIE_SECURE=true` (adds the `Secure` flag to the JWT cookie, requires HTTPS)
- [ ] Set `APP_BASE_URL` to your production domain
- [ ] Set `CORS_ALLOWED_ORIGINS` to your frontend domain and extension origin, `chrome-extension://<id>` (no wildcard)
- [ ] Optionally, set `OPENAI_API_KEY` for AI follow-ups
- [ ] Optionally, set `NOTIFICATION_EMAIL_ENABLED=true` and configure SMTP credentials
- [ ] Tell recipients about tracking where the law requires it
- [ ] Configure a real database with proper credentials
- [ ] Set the production server in the extension popup and grant access when prompted
- [ ] Replace in-memory rate limiter with Redis + Bucket4j for multi-instance deployments

See [PRODUCTION.md](PRODUCTION.md) for Docker Compose setup, extension production configuration, and WebSocket broker upgrade instructions.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 17, Spring Boot 4.1 |
| Database | PostgreSQL 16 + Spring Data JPA + Flyway |
| Auth | JWT (jjwt 0.13.0) + BCrypt |
| Encryption | AES-256-GCM (versioned key envelope) |
| Real-time | WebSocket + STOMP + SockJS |
| AI | OpenAI `gpt-4o-mini` via REST |
| Frontend | HTML5 + CSS3 + Vanilla JS |
| Extension | Manifest V3 v1.4.0 (Chrome & Edge) |
| Deployment | Docker + Docker Compose |

## Verification

```bash
mvn -f backend/pom.xml verify
node --test scripts/*.test.mjs
```

`verify` runs unit tests and PostgreSQL repository tests via Testcontainers when Docker
is available. To also run full HTTP, Flyway schema-validation and dashboard aggregation
tests, provide a dedicated disposable database:

```bash
NUDGE_TEST_DB_URL=jdbc:postgresql://localhost:5432/nudge_test \
NUDGE_TEST_DB_USERNAME=nudge_test NUDGE_TEST_DB_PASSWORD=nudge_test \
mvn -f backend/pom.xml verify
```

Never point integration tests at a production database. GitHub Actions runs these
checks and builds the Docker image. Browser-extension tests cover send sequencing,
backend failure, duplicate clicks, multiple recipients, draft changes, own-pixel rules,
notifications, session expiry, tick matching and English/French labels; actual
Gmail/Outlook/Proton/Tuta/Infomaniak/Yahoo DOM compatibility still requires browser checks.
