import { writeFileSync } from 'node:fs';

export function deploymentConfig(env) {
  const api = new URL(env.NUDGE_API_BASE);
  if (!['http:', 'https:'].includes(api.protocol) || api.username || api.password || api.search || api.hash || api.pathname !== '/') {
    throw new Error('NUDGE_API_BASE must be an HTTP(S) origin');
  }
  if (api.protocol !== 'https:' && !['localhost', '127.0.0.1'].includes(api.hostname)) {
    throw new Error('Production API must use HTTPS');
  }
  const ws = new URL(env.NUDGE_WS_URL || `${api.origin}/ws`);
  if (!['http:', 'https:'].includes(ws.protocol)) throw new Error('SockJS requires an HTTP(S) URL, not ws:// or wss://');
  if (ws.origin !== api.origin || ws.pathname !== '/ws' || ws.username || ws.password || ws.search || ws.hash) {
    throw new Error('NUDGE_WS_URL must be the API origin followed by /ws');
  }
  return { API_BASE: api.origin, WS_URL: ws.href };
}

if (import.meta.url === new URL(process.argv[1], 'file:').href) {
  const config = deploymentConfig(process.env);
  // Generate a separate public file: no shell interpolation and repeated builds are safe.
  writeFileSync('frontend/js/deployment.js', `window.NUDGE_DEPLOYMENT = ${JSON.stringify(config)};\n`);
}
