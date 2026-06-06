/**
 * config.js — Single source of truth for all frontend JS files.
 *
 * A1/A2: Centralise API_BASE and WS_URL here so there is exactly one place
 * to update when the backend URL changes (dev → staging → prod).
 *
 * Load this script FIRST in every HTML page:
 *   <script src="js/config.js"></script>
 */
const NUDGE_CONFIG = {
  /** Backend REST API base URL (no trailing slash) */
  API_BASE: 'http://localhost:8080',

  /** WebSocket endpoint (SockJS) */
  WS_URL: 'http://localhost:8080/ws'
};

// ── Theme (light / dark) ──────────────────────────────────────

function toggleTheme() {
  const isLight = document.documentElement.getAttribute('data-theme') === 'light';
  if (isLight) {
    document.documentElement.removeAttribute('data-theme');
    localStorage.setItem('nudge_theme', 'dark');
  } else {
    document.documentElement.setAttribute('data-theme', 'light');
    localStorage.setItem('nudge_theme', 'light');
  }
  const btn = document.getElementById('theme-toggle-btn');
  if (btn) btn.textContent = isLight ? '☀️' : '🌙';
}

document.addEventListener('DOMContentLoaded', () => {
  const btn = document.getElementById('theme-toggle-btn');
  if (btn) {
    btn.textContent =
      document.documentElement.getAttribute('data-theme') === 'light' ? '🌙' : '☀️';
  }
});
