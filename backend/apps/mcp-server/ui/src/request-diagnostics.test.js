import test from 'node:test';
import assert from 'node:assert/strict';
import { measuredCall, requestDiagnostics, latencySummary } from './request-diagnostics.js';
test('측정에는 요청 인자나 응답 본문을 저장하지 않는다', async () => {
  let now = 10;
  await measuredCall('search_products', async () => { now = 32; return { secret: 'private' }; }, () => now);
  const sample = requestDiagnostics().at(-1);
  assert.equal(sample.elapsedMs, 22);
  assert.equal(JSON.stringify(sample).includes('private'), false);
  assert.deepEqual(latencySummary([4, 1, 3, 2, NaN]), { count: 4, p50: 2, p95: 4 });
});
test('실패도 계측하고 원래 예외를 보존한다', async () => {
  const failure = new Error('private detail');
  await assert.rejects(measuredCall('get_cart', () => Promise.reject(failure)), error => error === failure);
  assert.equal(requestDiagnostics().at(-1).outcome, 'TRANSPORT_ERROR');
  assert.equal(JSON.stringify(requestDiagnostics()).includes('private'), false);
});
