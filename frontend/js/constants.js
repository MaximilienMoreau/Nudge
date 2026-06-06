/**
 * constants.js — Shared frontend constants.
 * Loaded before dashboard.js and websocket.js.
 */

const HOT_LEAD_THRESHOLD = 70;

// Columns whose values are ISO date strings — sorted as strings, not numbers
const DATE_SORT_COLS = new Set(['createdAt', 'lastOpenedAt', 'lastClickedAt', 'archivedAt']);
