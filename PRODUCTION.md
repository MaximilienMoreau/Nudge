# Nudge: Production Deployment Guide

## Quick start (Docker Compose)

```bash
cp .env.example .env
# Fill in all required values in .env (see comments inside the file)
docker compose up -d
```

---

## Browser Extension: Production Configuration (Chrome & Edge)

The extension's `background.js` and `popup.js` both use `API_BASE`.
Before publishing to the Chrome Web Store or Edge Add-ons store, update this value:

1. Open [extension/background.js](extension/background.js) and change:
   ```js
   const API_BASE = 'https://api.your-domain.com';
   ```
2. Open [extension/popup.js](extension/popup.js) and apply the same change.
3. Update `host_permissions` in your `manifest.json` to match:
   ```json
   "host_permissions": ["https://api.your-domain.com/*"]
   ```
4. The extension communicates over **HTTPS only** in production — the JWT in the
   `Authorization` header is sent over TLS. Never ship `http://` in production.
5. Add your extension's origin to the `CORS_ALLOWED_ORIGINS` environment variable
   on the backend so the browser does not block cross-origin requests:
   - Chrome: `chrome-extension://<extension-id>`
   - Edge: `extension://<extension-id>`

> The same `/extension` folder can be submitted to both stores without modification —
> Edge natively supports Chrome Manifest V3 extensions.

---

## WebSocket Broker: Moving to Production

The default setup uses Spring's **in-memory STOMP broker** (`enableSimpleBroker`).
This works for a single server instance but has two limitations:

- **No persistence** — notifications sent while the server is restarting are lost.
- **No multi-instance** — if you run multiple backend pods, a WebSocket connection
  on pod A cannot receive messages sent from pod B.

### Upgrade to an external broker (RabbitMQ / ActiveMQ)

1. Add the dependency to `pom.xml`:
   ```xml
   <dependency>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-starter-reactor-netty</artifactId>
   </dependency>
   ```
2. Replace `config.enableSimpleBroker(...)` in `WebSocketConfig.java` with:
   ```java
   config.enableStompBrokerRelay("/topic", "/queue")
         .setRelayHost(env.getProperty("BROKER_HOST", "localhost"))
         .setRelayPort(61613)
         .setClientLogin("guest")
         .setClientPasscode("guest");
   ```
3. Start a RabbitMQ instance with the STOMP plugin enabled:
   ```bash
   docker run -d --name rabbitmq \
     -p 61613:61613 \
     -e RABBITMQ_DEFAULT_USER=guest \
     -e RABBITMQ_DEFAULT_PASS=guest \
     rabbitmq:3-management
   # Enable STOMP plugin
   docker exec rabbitmq rabbitmq-plugins enable rabbitmq_stomp
   ```
4. Add to `docker-compose.yml`:
   ```yaml
   rabbitmq:
     image: rabbitmq:3-management
     environment:
       RABBITMQ_DEFAULT_USER: guest
       RABBITMQ_DEFAULT_PASS: guest
     ports:
       - "61613:61613"
       - "15672:15672"
   ```
5. Add `BROKER_HOST=rabbitmq` to your `.env`.

---

## Environment variables reference

See [.env.example](.env.example) for the full list of required and optional variables.

## Generating secure secrets

```bash
# JWT_SECRET (Base64-encoded 32-byte key)
openssl rand -base64 32

# ENCRYPTION_KEY (Base64-encoded 32-byte AES-256 key)
openssl rand -base64 32
```
