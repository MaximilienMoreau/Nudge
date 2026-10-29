import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { deploymentConfig } from './build-frontend.mjs';

test('static hosting derives a valid HTTP SockJS URL', () => {
  assert.deepEqual(deploymentConfig({ NUDGE_API_BASE: 'https://api.example/' }), {
    API_BASE: 'https://api.example', WS_URL: 'https://api.example/ws'
  });
});
test('rejects insecure remote servers and WebSocket schemes for SockJS', () => {
  assert.throws(() => deploymentConfig({ NUDGE_API_BASE: 'http://remote.example' }));
  assert.throws(() => deploymentConfig({ NUDGE_API_BASE: 'https://api.example', NUDGE_WS_URL: 'wss://api.example/ws' }));
  assert.throws(() => deploymentConfig({ NUDGE_API_BASE: 'https://user:password@api.example' }));
});

test('repeated builds replace deployment URLs without editing source configuration', () => {
  const directory = mkdtempSync(join(tmpdir(), 'nudge-build-test-'));
  try {
    mkdirSync(join(directory, 'frontend/js'), { recursive: true });
    for (const host of ['https://first.example', 'https://second.example']) {
      execFileSync(process.execPath, [resolve('scripts/build-frontend.mjs')], {
        cwd: directory, env: { ...process.env, NUDGE_API_BASE: host, NUDGE_WS_URL: `${host}/ws` }
      });
    }
    const output = readFileSync(join(directory, 'frontend/js/deployment.js'), 'utf8');
    assert.match(output, /second\.example/);
    assert.doesNotMatch(output, /first\.example/);
  } finally { rmSync(directory, { recursive: true }); }
});
