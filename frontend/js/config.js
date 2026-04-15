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
