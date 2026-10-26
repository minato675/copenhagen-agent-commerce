import test from 'node:test';
import assert from 'node:assert/strict';
import { cartSnapshot, cartView, currencyAmount, mutationSession } from './cart.js';

const request = { cartId: 'cart-a', expectedVersion: 0, productId: 'product-a', merchantId: 'qa-a',
  optionId: '', quantity: 2, idempotencyKey: 'request-key-000001' };
const line = { lineId: 'line-a', productId: 'product-a', merchantId: 'qa-a', merchantName: '가상몰 A',
  name: '상품', optionId: '', optionLabel: '', imageUrl: '', quantity: 2, unitPrice: 1000, lineTotal: 2000,
  currency: 'KRW', priceStatus: 'ESTIMATED' };
const cart = { cartId: 'cart-a', version: 1, lines: [line], approvalState: 'REVALIDATION_REQUIRED' };
const response = { kind: 'cart.mutation', mode: 'QA', action: 'ADD', status: 'APPLIED', reason: 'NONE',
  request, cart, affectedLineId: 'line-a' };

test('uncertain result keeps identical idempotency key and payload after widget restoration', async () => {
  let saved;
  const session = mutationSession(null, value => { saved = value; });
  session.begin('add_cart_item', request);
  await assert.rejects(session.run(async () => { throw new Error('timeout after commit'); }));
  assert.deepEqual(saved, { tool: 'add_cart_item', request });
  assert.throws(() => session.begin('add_cart_item', { ...request, idempotencyKey: 'fresh-key-00000001' }));
  const restored = mutationSession(saved);
  await restored.run(async (tool, args) => { assert.equal(tool, 'add_cart_item'); assert.deepEqual(args, request); return response; });
  assert.equal(restored.pending, null);
});
test('double click never issues a second in-flight mutation', async () => {
  const session = mutationSession(); session.begin('add_cart_item', request);
  let finish, calls = 0;
  const first = session.run(() => { calls++; return new Promise(resolve => { finish = resolve; }); });
  await assert.rejects(session.run(() => { calls++; return response; }));
  finish(response); await first; assert.equal(calls, 1);
});
test('mismatched confirmation and host errors retain pending request', async () => {
  const session = mutationSession(); session.begin('add_cart_item', request);
  await assert.rejects(session.run(async () => ({ ...response, request: { ...request, quantity: 3 } })));
  await assert.rejects(session.run(async () => ({ isError: true, structuredContent: response })));
  assert.deepEqual(session.pending.request, request);
});
test('definitive conflict clears pending without declaring success', async () => {
  const session = mutationSession(); session.begin('add_cart_item', request);
  const data = await session.run(async () => ({ ...response, status: 'CONFLICT', reason: 'VERSION_CONFLICT', affectedLineId: null }));
  assert.equal(data.status, 'CONFLICT'); assert.equal(session.pending, null);
});
test('unknown price is preserved when a host omits nullable amounts and zero remains zero', () => {
  const unknown = cartSnapshot({ ...cart, lines: [{ ...line, unitPrice: undefined, lineTotal: undefined, priceStatus: 'UNKNOWN' }] });
  assert.equal(unknown.lines[0].unitPrice, null);
  assert.equal(cartSnapshot({ ...cart, lines: [{ ...line, unitPrice: 0, lineTotal: 0 }] }).lines[0].unitPrice, 0);
  assert.throws(() => cartSnapshot({ ...cart, lines: [{ ...line, lineTotal: 2001 }] }));
  assert.throws(() => cartSnapshot({ ...cart, lines: [line, { ...line, lineId: 'another' }] }));
});
test('missing shipping and discount remain unknown and currency uses minor units', () => {
  const data = cartView({ kind: 'cart.view', mode: 'QA', status: 'INCOMPLETE', cart,
    groups: [{ merchantId: 'qa-a', currency: 'KRW', status: 'UNKNOWN', itemSubtotal: 2000 }],
    totals: [{ currency: 'KRW', status: 'UNKNOWN', itemSubtotal: 2000 }], checkedAt: '2026-09-16T00:00:00Z' });
  assert.equal(data.totals[0].total, null); assert.equal(data.totals[0].shipping, null);
  assert.match(currencyAmount(1234, 'USD'), /12\.34/); assert.match(currencyAmount(1234, 'KRW'), /1,234/);
  assert.equal(currencyAmount(null, 'KRW'), '미확정');
});
test('update retry preserves viewed version and absolute quantity after an uncertain response', async () => {
  const input = { cartId: 'cart-a', lineId: 'line-a', expectedVersion: 1, quantity: 3, optionId: 'option-c',
    idempotencyKey: 'request-key-update1' };
  const session = mutationSession(); session.begin('update_cart_item', input);
  await assert.rejects(session.run(async () => { throw new Error('lost response'); }));
  const restored = mutationSession(session.pending);
  const result = await restored.run(async (tool, args) => {
    assert.equal(tool, 'update_cart_item'); assert.deepEqual(args, input);
    return { ...response, action: 'UPDATE', request: input, cart: { ...cart, version: 2,
      lines: [{ ...line, quantity: 3, optionId: 'option-c', lineTotal: 3000 }] } };
  });
  assert.equal(result.status, 'APPLIED'); assert.equal(restored.pending, null);
});
test('delete confirmation must remove the requested line before clearing the pending request', async () => {
  const input = { cartId: 'cart-a', lineId: 'line-a', expectedVersion: 1, idempotencyKey: 'request-key-delete' };
  const session = mutationSession(); session.begin('delete_cart_item', input);
  await assert.rejects(session.run(async () => ({ ...response, action: 'DELETE', request: input, cart: { ...cart, version: 2 } })));
  assert.ok(session.pending);
  await session.run(async () => ({ ...response, action: 'DELETE', request: input, cart: { ...cart, version: 2, lines: [] } }));
  assert.equal(session.pending, null);
});
