import test from 'node:test';
import assert from 'node:assert/strict';
import { requestTimeout, createReadCoalescer, liveTimedCall } from './live-requests.js';
test('동일 조회만 합치고 완료 후에는 다시 조회한다', async () => {
  const coalesce = createReadCoalescer(); let count = 0, resolve;
  const invoke = () => { count++; return new Promise(r => { resolve = r; }); };
  const first = coalesce('search_products', { query: 'a', pageSize: 4 }, invoke);
  const second = coalesce('search_products', { pageSize: 4, query: 'a' }, invoke);
  await Promise.resolve(); assert.equal(count, 1); assert.equal(first, second);
  resolve('done'); await first;
  await coalesce('search_products', { query: 'a', pageSize: 4 }, () => { count++; });
  assert.equal(count, 2);
});
test('변경은 합치거나 자동 재시도하지 않는다', async () => {
  const coalesce = createReadCoalescer(); let count = 0;
  await coalesce('add_cart_item', {}, async () => { count++; });
  assert.equal(count, 1);
  await assert.rejects(liveTimedCall(() => new Promise(() => {}), 'add_cart_item', 5),
    error => error.outcome === 'UNKNOWN' && error.code === 'UI_TIMEOUT');
  assert.equal(count, 1);
});
test('인증 대기·조회·변경 제한을 구분하고 실패한 조회를 캐시하지 않는다', async () => {
  assert.ok(requestTimeout('prepare_cart') > requestTimeout('add_cart_item'));
  assert.ok(requestTimeout('add_cart_item') > requestTimeout('get_cart'));
  const coalesce = createReadCoalescer();
  await assert.rejects(coalesce('get_cart', {}, () => Promise.reject(new Error('failed'))));
  assert.equal(await coalesce('get_cart', {}, () => 'new'), 'new');
});
