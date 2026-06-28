<h1 align="center">Nudge: Email Open Tracker</h1>

> Know the moment your email is read. No reply needed.

Nudge is a **browser extension** (Chrome & Edge) paired with a self-hosted backend that notifies you in real-time the instant a recipient opens your email. It scores engagement, generates AI-powered follow-ups, and suggests the best time to send.

---

## Features

| | |
|---|---|
| **Real-time notifications** | WebSocket push the moment an email is opened or a link clicked |
| **Lead scoring** | Reply Probability Score (0–100) based on opens, recency, frequency, and clicks |
| **AI follow-ups** | GPT-4o-mini generates a personalized follow-up draft |
| **Best send time** | AI analysis of historical open data to suggest the optimal day & hour |
| **Follow-up scheduler** | Schedule a reminder; notified via WebSocket + email when due |
| **Bot detection** | Filters Apple MPP, Google Image Proxy, Exchange Safe Links, Proofpoint, and more |
| **Email notifications** | SMTP fallback when no WebSocket session is active |
| **Content encryption** | Email bodies stored with AES-256-GCM; key rotation supported |
| **Rate limiting** | Built-in per-IP rate limiter on all API endpoints |
| **Multi-recipient** | Track multiple recipients per email, one pixel per recipient |
| **Archive & restore** | Soft-delete emails; restore or permanently delete from the archive |

---

## Architecture

```
nudge/
├── backend/      Spring Boot 3.2 (Java 17) (REST API + WebSocket)
├── frontend/     Vanilla HTML/CSS/JS (Dashboard UI)
├── extension/    Browser Extension v1.2.0 (Manifest V3, Chrome & Edge)
└── database/     PostgreSQL schema + Flyway migration scripts
```

**Two ways to track an email:**
- **Extension** (recommended): install → compose → pixel injected automatically on Send
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
| Docker + Compose | *(optional, containerised deployment)* |

---

## Quick Start

### Option A: Docker Compose (recommended)

```bash
cp .env.example .env
# Fill in JWT_SECRET, ENCRYPTION_KEY, DB_PASSWORD (see .env.example for all variables)
docker compose up -d
```

The API and frontend are available at `http://localhost:8080`.

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

The `dev` profile is active by default. It supplies safe local defaults for `JWT_SECRET` and `ENCRYPTION_KEY` — no manual configuration is needed for local development. Flyway runs the migration scripts automatically on startup.

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

> Edge natively supports Chrome Manifest V3 — the same `/extension` folder works on both browsers without modification.

---

## Environment Variables

All secrets are supplied via environment variables. Copy `.env.example` to `.env` before deploying.

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `localhost:5432/nudge` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | *(required)* | Database password |
| `JWT_SECRET` | *(dev default)* | Base64-encoded 32-byte key — **change for production** |
| `ENCRYPTION_KEY` | *(dev default)* | Base64 AES-256 key for email content — **change for production** |
| `ENCRYPTION_KEY_V2` | *(empty)* | Optional second key for seamless key rotation |
| `ENCRYPTION_ACTIVE_KEY_VERSION` | `v1` | Which key version to use for new writes |
| `APP_BASE_URL` | `http://localhost:8080` | Public-facing URL (used in tracking pixel URLs) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080` | Comma-separated allowed CORS origins |
| `COOKIE_SECURE` | `false` | Adds `Secure` flag to JWT cookie — set `true` in production (requires HTTPS) |
| `TRUSTED_PROXY_RANGES` | `127.0.0.1,::1` | CIDR ranges of trusted proxies (for `X-Forwarded-For`) |
| `OPENAI_API_KEY` | *(empty)* | OpenAI key — falls back to template text without it |
| `NOTIFICATION_EMAIL_ENABLED` | `false` dev / `true` prod | Enable SMTP email notification fallback |
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
| POST | `/api/auth/logout` | Revoke current token server-side (requires JWT) |
| DELETE | `/api/auth/account` | Permanently delete the account (requires JWT) |

**POST `/api/auth/register` and `/api/auth/login` body:**
```json
{ "email": "you@example.com", "password": "secret" }
```

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
| DELETE | `/api/emails/{id}` | Soft-delete (archive) an email |
| GET | `/api/emails/archived` | List archived emails |
| POST | `/api/emails/{id}/restore` | Restore an archived email |
| DELETE | `/api/emails/{id}/permanent` | Permanently delete an email and all its events |
| POST | `/api/emails/{id}/schedule` | Schedule a follow-up reminder |

**GET `/api/emails` query params:** `?page=0&size=50` (max 200 per page)

**POST `/api/emails` body:**
```json
{
  "subject": "Follow up on our meeting",
  "recipientEmails": ["john@company.com", "jane@company.com"],
  "content": "Hi John, just wanted to follow up..."
}
```

> `recipientEmails` (array, preferred) or `recipientEmail` (single string, backwards-compatible). At least one is required. Returns one `EmailDTO` per recipient.

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
| POST | `/api/ai/send-time` | Suggest the best day and hour to send |

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

Connect to `ws://localhost:8080/ws` using SockJS + STOMP.

The server authenticates via the `nudge_jwt` httpOnly cookie sent automatically by the browser on the SockJS handshake — no explicit token parameter needed.

Subscribe to: `/user/queue/notifications`

**Notification types:**

| Type | Trigger |
|------|---------|
| `EMAIL_OPENED` | A genuine (non-bot) open event is recorded |
| `EMAIL_CLICKED` | A click event is recorded |
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

## Lead Scoring

The **Reply Probability Score** (0–100) is computed from genuine opens only — bot and proxy pre-fetches are detected and excluded automatically (see [Bot Detection](#bot-detection)). The `openCount` field in the API response follows the same rule.

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

Scores ≥ 70 are flagged as **Hot Leads** 🔥.

---

## Bot Detection

Two complementary heuristics filter non-human opens before scoring or notification:

1. **User-Agent pattern matching**: known mail scanners and image proxies are flagged:
   - Apple Mail Privacy Protection (`apple-pubsub`, `previewsapp`)
   - Google Image Proxy (`googleimageproxy`, `googlebot`)
   - Microsoft (`microsoft-exchange`, `outlook-ios`, `outlookandroid`, `safelinks`)
   - Bing (`bingbot`)
   - Security gateways: Mimecast, Proofpoint, Barracuda, URLScan

2. **Rapid-succession timing**: two opens on the same tracking ID within 30 seconds → the second is flagged as a suspected pre-fetch.

The first open for any tracking ID is never auto-flagged. Flagged events are stored with `suspectedBot = true` and excluded from lead scores and real-time notifications.

---

## Email Notifications (SMTP Fallback)

When `NOTIFICATION_EMAIL_ENABLED=true`, Nudge sends a plain-text email to the sender:
- When a tracked email is opened and no active WebSocket session exists
- When a scheduled follow-up reminder becomes due

Configure via `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, and `MAIL_PASSWORD`. STARTTLS is enabled by default on port 587.

---

## Content Encryption

Email bodies are encrypted at rest using **AES-256-GCM** before being stored in the database. Each value is stored with a unique 12-byte random IV and a 128-bit authentication tag. The wire format is:

```
{version}:{Base64(IV ‖ ciphertext + authTag)}
```

**Key rotation** is supported without downtime: set `ENCRYPTION_KEY_V2` and `ENCRYPTION_ACTIVE_KEY_VERSION=v2`. New values are encrypted with v2 while old v1 values remain readable. Re-encrypt old rows at your own pace.

---

## Extension Usage (Chrome & Edge)

1. Sign in via the popup with your Nudge account credentials
2. Open Gmail, Outlook, Proton Mail, Infomaniak, or Yahoo Mail and compose a new email
3. A **"📨 Nudge: ON"** button appears next to the Send button
4. Click Send: Nudge registers the email and injects the tracking pixel automatically
5. The moment the recipient opens your email, you get an instant notification

### Without the extension (any email client)

1. Go to the dashboard → **Track Email**
2. Fill in the subject, recipient, and body
3. Copy the generated pixel HTML: `<img src="..." width="1" height="1" style="display:none"/>`
4. Paste it into your email before sending

---

## Production Checklist

- [ ] Set a strong `JWT_SECRET` (`openssl rand -base64 32`)
- [ ] Set a strong `ENCRYPTION_KEY` (`openssl rand -base64 32`)
- [ ] Set `SPRING_PROFILES_ACTIVE=prod` (Flyway manages the schema; Hibernate is set to `validate`)
- [ ] Use HTTPS (tracking pixels won't load over HTTP in many email clients)
- [ ] Set `COOKIE_SECURE=true` (adds the `Secure` flag to the JWT cookie, requires HTTPS)
- [ ] Set `APP_BASE_URL` to your production domain
- [ ] Set `CORS_ALLOWED_ORIGINS` to your frontend domain and extension origin (no wildcard)
- [ ] Set `OPENAI_API_KEY` for AI follow-ups
- [ ] Set `NOTIFICATION_EMAIL_ENABLED=true` and configure SMTP credentials
- [ ] Configure a real database with proper credentials
- [ ] Update the extension `DEFAULT_API_BASE` to point to your production backend
- [ ] Replace in-memory rate limiter with Redis + Bucket4j for multi-instance deployments

See [PRODUCTION.md](PRODUCTION.md) for Docker Compose setup, extension production configuration, and WebSocket broker upgrade instructions.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 17, Spring Boot 3.2 |
| Database | PostgreSQL 16 + Spring Data JPA + Flyway |
| Auth | JWT (jjwt 0.11.5) + BCrypt |
| Encryption | AES-256-GCM (versioned key envelope) |
| Real-time | WebSocket + STOMP + SockJS |
| AI | OpenAI `gpt-4o-mini` via REST |
| Frontend | HTML5 + CSS3 + Vanilla JS |
| Extension | Manifest V3 v1.2.0 (Chrome & Edge) |
| Deployment | Docker + Docker Compose |
