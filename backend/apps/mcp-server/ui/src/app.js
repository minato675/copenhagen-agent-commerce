import { App } from '@modelcontextprotocol/ext-apps';
import { callConnection, connectionData } from './connection.js';

const button = document.getElementById('check');
const status = document.getElementById('status');
const time = document.getElementById('checked-at');
const version = document.getElementById('version');
let app;
let sdkReady = false;
let connecting = false;
let busy = false;
let lastRequestId;

function showResult(result) {
  const data = connectionData(result);
  status.textContent = '연결됨';
  status.dataset.state = 'connected';
  time.textContent = new Date(data.checkedAt).toLocaleTimeString('ko-KR', { hour12: false });
  time.dateTime = data.checkedAt;
  version.textContent = data.uiVersion.slice(0, 8);
  lastRequestId = data.requestId;
}

function controls() {
  const ready = sdkReady || typeof window.openai?.callTool === 'function';
  button.disabled = busy || (connecting && !ready);
  button.textContent = busy ? '확인 중…' : ready ? '연결 확인' : connecting ? '연결 중…' : '다시 연결';
}

function nativeGlobals() {
  if (!lastRequestId && window.openai?.toolOutput) {
    try { showResult(window.openai.toolOutput); } catch { /* Wait for a valid tool result. */ }
  }
  if (!lastRequestId && !busy && typeof window.openai?.callTool === 'function') {
    status.textContent = '연결 확인 가능';
  }
  controls();
}

async function connect() {
  if (connecting) return;
  connecting = true;
  controls();
  if (app) await app.close().catch(() => {});
  app = new App({ name: 'Copenhagen', version: '1.0.0' },
    { availableDisplayModes: ['inline'] }, { autoResize: true });
  app.ontoolresult = result => {
    if (busy || lastRequestId) return;
    try { showResult(result); } catch { status.textContent = '연결 확인 필요'; }
  };
  app.ontoolcancelled = () => { status.textContent = '요청 취소'; };
  try {
    await app.connect(undefined, { timeout: 15000 });
    sdkReady = true;
    if (!lastRequestId) status.textContent = '연결 확인 가능';
  } catch {
    if (typeof window.openai?.callTool !== 'function') {
      status.textContent = '연결 확인 필요';
      status.dataset.state = 'error';
    }
  } finally {
    connecting = false;
    nativeGlobals();
  }
}

button.addEventListener('click', async () => {
  if (busy) return;
  if (!sdkReady && typeof window.openai?.callTool !== 'function') { await connect(); return; }
  busy = true;
  status.textContent = '확인 중';
  status.dataset.state = 'pending';
  controls();
  try {
    showResult(await callConnection(window.openai, app, sdkReady));
  } catch {
    status.textContent = '응답 없음 · 다시 확인';
    status.dataset.state = 'error';
  } finally {
    busy = false;
    controls();
  }
});

window.addEventListener('openai:set_globals', nativeGlobals);
window.addEventListener('pageshow', nativeGlobals);
document.addEventListener('visibilitychange', () => { if (!document.hidden) nativeGlobals(); });
nativeGlobals();
void connect();
