# Nudge — AI-Powered Email Tracker

> "Never get ghosted again. Know exactly when and how to follow up."

Nudge tracks email opens in real-time, scores engagement, and generates AI-powered follow-ups.

---

## Architecture

```
nudge/
├── backend/          Spring Boot 3 (Java 17) — REST API + WebSocket
├── frontend/         Vanilla HTML/CSS/JS — Dashboard UI
├── extension/        Chrome Extension (Manifest V3) — Gmail integration
└── database/         PostgreSQL schema / init scripts
```

---

## Prerequisites

| Tool       | Version       |
|------------|---------------|
| Java       | 17+           |
| Maven      | 3.8+          |
| PostgreSQL | 14+           |
| Chrome     | Latest        |
| OpenAI Key | (optional)    |

---

## Quick Start

### 1. Database

```bash
psql -U postgres -c "CREATE DATABASE nudge;"
```

### 2. Backend

```bash
cd backend

# Optional — AI follow-ups require this; fallback text is used without it
export OPENAI_API_KEY=sk-...

# Edit DB credentials if needed
nano src/main/resources/application.properties

mvn spring-boot:run
```

The API starts at `http://localhost:8080`.

#### Key environment variables

| Variable         | Default           | Description                       |
|------------------|-------------------|-----------------------------------|
| `OPENAI_API_KEY` | *(empty)*         | OpenAI key for AI follow-ups      |
| DB URL           | `localhost/nudge` | See `application.properties`      |
| JWT secret       | Base64 string     | **Change before going to production** |

### 3. Frontend

```bash
cd frontend

# Option A: open directly (simplest)
xdg-open index.html      # Linux
open index.html          # macOS

# Option B: static server
npx serve .
# or
python3 -m http.server 3000
```

### 4. Chrome Extension

1. Open Chrome → `chrome://extensions/`
2. Enable **Developer mode** (top right)
3. Click **Load unpacked** → select the `/extension` folder
4. The Nudge icon appears in your toolbar — click it to sign in

---

## API Reference

Authentication uses an **httpOnly cookie** (`nudge_jwt`) set on login. All protected endpoints read the cookie automatically — no `Authorization` header needed from the browser. API clients (e.g. the Chrome extension) may alternatively send `Authorization: Bearer <token>`.

### Authentication (public)

| Method | Path                   | Description                                      |
|--------|------------------------|--------------------------------------------------|
| POST   | `/api/auth/register`   | Create a new account                             |
| POST   | `/api/auth/login`      | Exchange credentials for a JWT                   |
| PUT    | `/api/auth/password`   | Change password (requires JWT) — returns new JWT |
| POST   | `/api/auth/logout`     | Revoke current token server-side (requires JWT)  |

**POST `/api/auth/register` and `/api/auth/login` body:**
```json
{ "email": "you@example.com", "password": "secret" }
```

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

| Method | Path                         | Description                                    |
|--------|------------------------------|------------------------------------------------|
| GET    | `/api/emails`                | List active tracked emails (paginated)         |
| POST   | `/api/emails`                | Register a new email for tracking              |
| GET    | `/api/emails/{id}`           | Get a single email with full stats             |
| DELETE | `/api/emails/{id}`           | Soft-delete (archive) an email                 |
| GET    | `/api/emails/archived`       | List archived emails                           |
| POST   | `/api/emails/{id}/restore`   | Restore an archived email                      |
| DELETE | `/api/emails/{id}/permanent` | Permanently delete an email and all its events |
| POST   | `/api/emails/{id}/schedule`  | Schedule a follow-up reminder                  |

**GET `/api/emails` query params:** `?page=0&size=50`

**POST `/api/emails` body:**
```json
{
  "subject": "Follow up on our meeting",
  "recipientEmail": "john@company.com",
  "content": "Hi John, just wanted to follow up..."
}
```

For multiple recipients, use `recipientEmails` instead (one `TrackedEmail` per recipient, each with its own `trackingId`):
```json
{
  "subject": "Product demo",
  "recipientEmails": ["alice@co.com", "bob@co.com"],
  "content": "Hi, here is the link..."
}
```

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

### Tracking (public — called automatically by email clients)

| Method | Path                              | Description                                    |
|--------|-----------------------------------|------------------------------------------------|
| GET    | `/track/open/{trackingId}`        | Returns 1×1 GIF and logs an OPEN event         |
| GET    | `/track/click/{trackingId}?url=`  | Logs a CLICK event and 302-redirects to `url`  |

Embed in emails:
```html
<!-- Tracking pixel (invisible) -->
<img src="http://localhost:8080/track/open/{trackingId}" width="1" height="1" style="display:none" alt=""/>

<!-- Tracked link -->
<a href="http://localhost:8080/track/click/{trackingId}?url=https%3A%2F%2Fyour-link.com">Click here</a>
```

---

### AI (requires JWT)

| Method | Path                  | Description                                         |
|--------|-----------------------|-----------------------------------------------------|
| POST   | `/api/ai/followup`    | Generate an AI follow-up email                      |
| POST   | `/api/ai/send-time`   | Suggest the best day and hour to send based on history |

**POST `/api/ai/followup` body:**
```json
{
  "emailId": 42,
  "daysSinceSent": 3
}
```

`openCount` and `engagementScore` are computed server-side from the database — do not pass them.

**Response:**
```json
{
  "suggestedSubject": "Re: Follow up on our meeting",
  "followUpText": "Hi John, I wanted to circle back..."
}
```

**POST `/api/ai/send-time`** — no body required.

**Response:**
```json
{
  "hasData": true,
  "bestDay": "Tuesday",
  "bestHour": "10:00",
  "suggestion": "Send on Tuesday morning",
  "rationale": "Based on 12 opens across your tracked emails"
}
```

---

### WebSocket

Connect to `ws://localhost:8080/ws` using SockJS + STOMP.

The server authenticates via the `nudge_jwt` httpOnly cookie sent automatically by the browser on the SockJS handshake — no explicit token parameter is needed.

Subscribe to: `/user/queue/notifications`

**Notification payload (EMAIL_OPENED / EMAIL_CLICKED):**
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

**Notification payload (FOLLOW_UP_REMINDER):**
```json
{
  "type": "FOLLOW_UP_REMINDER",
  "emailId": 42,
  "subject": "Follow up on our meeting",
  "recipientEmail": "john@company.com"
}
```

---

## Lead Scoring

The Reply Probability Score (0–100) is computed from:

| Signal              | Points              |
|---------------------|---------------------|
| Opens volume        | 15 per open, max 40 |
| Recency (< 1 hour)  | 40                  |
| Recency (< 1 day)   | 30                  |
| Recency (< 3 days)  | 20                  |
| Recency (< 7 days)  | 10                  |
| Frequency (> 5×)    | 20                  |
| Frequency (> 3×)    | 15                  |
| Frequency (> 1×)    | 10                  |
| Click (≥ 2 clicks)  | 20                  |
| Click (≥ 1 click)   | 10                  |

Scores ≥ 70 are flagged as **Hot Leads** 🔥.

---

## Chrome Extension Usage

1. Sign in via the popup with your Nudge account credentials
2. Open Gmail and compose a new email
3. A **"📨 Nudge: ON"** button appears next to the Send button
4. Click Send — Nudge automatically registers the email and injects the tracking pixel
5. When the recipient opens the email, you get an instant notification on your dashboard

---

## Production Checklist

- [ ] Change `jwt.secret` in `application.properties` to a strong random key
- [ ] Set `spring.jpa.hibernate.ddl-auto=validate` (not `update`)
- [ ] Configure a real database with proper credentials
- [ ] Use HTTPS — tracking pixels won't load over HTTP in many email clients
- [ ] Update `app.base.url` to your production domain
- [ ] Set `OPENAI_API_KEY` environment variable
- [ ] Configure CORS `allowedOriginPatterns` to your specific frontend domain
- [ ] Replace in-memory rate limiter with Redis + Bucket4j for multi-instance deployments

---

## Tech Stack

| Layer       | Technology                          |
|-------------|-------------------------------------|
| Backend     | Java 17, Spring Boot 3.2            |
| Database    | PostgreSQL + Spring Data JPA        |
| Auth        | JWT (jjwt 0.11.5) + BCrypt          |
| Real-time   | WebSocket + STOMP + SockJS          |
| AI          | OpenAI `gpt-4o-mini` via REST       |
| Frontend    | HTML5 + CSS3 + Vanilla JS           |
| Extension   | Chrome Extension Manifest V3        |
