import test from 'node:test';
import assert from 'node:assert/strict';
import { callConnection, connectionData } from './connection.js';

const data = { service: 'copenhagen-mcp', status: 'connected', checkedAt: '2026-09-16T00:00:00Z',
  requestId: 'server-response', uiVersion: '0123456789abcdef' };

test('native Android bridge takes precedence and accepts structured results', async () => {
  const native = { callTool: async (name, args) => {
    assert.equal(name, 'check_connection'); assert.deepEqual(args, {}); return { structuredContent: data };
  } };
  assert.deepEqual(await callConnection(native, { callServerTool() { assert.fail('unexpected SDK call'); } }, true), data);
});
test('standard bridge performs a tools/call when the native bridge is absent', async () => {
  const app = { callServerTool: async request => {
    assert.deepEqual(request, { name: 'check_connection', arguments: {} }); return { structuredContent: data };
  } };
  assert.deepEqual(await callConnection({}, app, true), data);
});
test('native flattened toolOutput is accepted', () => assert.deepEqual(connectionData(data), data));
test('malformed, error and unrelated tool responses never become connected', () => {
  for (const result of [null, {}, { isError: true, structuredContent: data }, { ...data, status: 'failed' },
    { ...data, checkedAt: 'invalid' }, { ...data, uiVersion: null }, { ...data, requestId: '' }]) {
    assert.throws(() => connectionData(result));
  }
});
test('missing host fails explicitly', async () => assert.rejects(callConnection({}, {}, false)));
test('timeout permits a later retry without reusing the old result', async () => {
  await assert.rejects(callConnection({ callTool: () => new Promise(() => {}) }, null, false, 5), /timed out/);
  assert.deepEqual(await callConnection({ callTool: async () => data }, null, false), data);
});
