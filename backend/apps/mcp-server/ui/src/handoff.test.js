import test from 'node:test';
import assert from 'node:assert/strict';
import { handoffData, handoffGuidance, handoffSession } from './handoff.js';

const base = 'https://example.com/purchase';
const cart = { cartId: 'cart-a', version: 1 };
const ready = request => ({ kind: 'purchase.handoff', mode: 'QA', request, status: 'READY', reason: 'NONE',
  purchaseRef: 'purchase-a', appLink: `${base}#purchase-a`, expiresAt: new Date(Date.now() + 60000).toISOString(),
  checkedAt: new Date().toISOString(), nextAction: 'OPEN_APP' });
test('lost response and restored widget retry preserve one request; simultaneous calls do not duplicate', async () => {
  const session = handoffSession(undefined, base); session.begin(cart);
  const original = session.pending;
  let finish, count = 0;
  const pending = session.run(() => { count++; return new Promise(resolve => { finish = resolve; }); });
  await session.run(() => { count++; });
  assert.equal(count, 1);
  finish({ ...ready(original), request: { ...original, cartId: 'wrong' } });
  await assert.rejects(pending);
  assert.deepEqual(session.pending, original);
  const restored = handoffSession(session.pending, base);
  assert.throws(() => restored.link(cart));
  await restored.run(async (name, request) => { assert.deepEqual(request, original); return ready(request); });
  assert.equal(restored.link(cart), `${base}#purchase-a`);
  assert.throws(() => restored.link(cart, Date.now() + 120000));
  restored.observe({ ...cart, version: 2 }); assert.throws(() => restored.link(cart));
});
test('UNKNOWN keeps key while terminal conflict permits explicit new request', async () => {
  const session = handoffSession(undefined, base); session.begin(cart);
  const original = session.pending;
  const terminal = (status, reason, nextAction) => ({ ...ready(original), status, reason, nextAction,
    purchaseRef: null, expiresAt: null, appLink: null });
  await session.run(async () => terminal('UNKNOWN', 'OUTCOME_UNKNOWN', 'RETRY_SAME_REQUEST'));
  assert.deepEqual(session.pending, original);
  assert.throws(() => session.begin(cart));
  await session.run(async () => terminal('CONFLICT', 'VERSION_CONFLICT', 'REVIEW_CART'));
  assert.equal(session.pending, undefined); session.begin(cart);
  assert.notEqual(session.pending.idempotencyKey, original.idempotencyKey);
});
test('a cart change retires a confirmed handoff without turning it into an unknown outcome', async () => {
  let changes = 0;
  const session = handoffSession(undefined, base, () => { changes++; });
  session.observe(cart); session.begin(cart);
  const original = session.pending;
  await session.run(async (_name, request) => ready(request));
  session.observe({ ...cart, version: 0 });
  session.observe(cart);
  assert.deepEqual(session.pending, original);
  assert.equal(session.link(cart), `${base}#purchase-a`);
  const before = changes;
  session.observe({ ...cart, version: 2 });
  assert.equal(changes, before + 1);
  assert.equal(session.pending, undefined);
  assert.equal(session.receipt, undefined);
  assert.equal(session.unresolved, false);
  assert.throws(() => session.link(cart));
  session.begin({ ...cart, version: 2 });
  assert.notEqual(session.pending.idempotencyKey, original.idempotencyKey);
});
test('restored and unknown requests survive cart changes until the exact request is resolved', async () => {
  const original = { cartId: cart.cartId, expectedVersion: cart.version, idempotencyKey: 'request-key-00001' };
  const session = handoffSession(original, base);
  session.observe({ ...cart, version: 2 });
  assert.equal(session.unresolved, true);
  assert.deepEqual(session.pending, original);
  await session.run(async (_name, request) => {
    assert.deepEqual(request, original);
    return { ...ready(request), status: 'UNKNOWN', reason: 'OUTCOME_UNKNOWN', nextAction: 'RETRY_SAME_REQUEST',
      purchaseRef: null, expiresAt: null, appLink: null };
  });
  assert.deepEqual(session.pending, original);
  assert.equal(session.unresolved, true);
  await session.run(async (_name, request) => { assert.deepEqual(request, original); return ready(request); });
  assert.equal(session.pending, undefined);
  assert.equal(session.receipt, undefined);
  assert.equal(session.unresolved, false);
  assert.throws(() => session.link(cart));
});
test('a cart change during recovery cannot expose a link for the old snapshot', async () => {
  const original = { cartId: cart.cartId, expectedVersion: cart.version, idempotencyKey: 'request-key-00001' };
  const session = handoffSession(original, base);
  session.observe(cart);
  let finish;
  const recovery = session.run(() => new Promise(resolve => { finish = resolve; }));
  session.observe({ cartId: 'cart-b', version: 0 });
  assert.deepEqual(session.pending, original);
  assert.equal(session.running, true);
  finish(ready(original)); await recovery;
  assert.equal(session.pending, undefined);
  assert.equal(session.unresolved, false);
  assert.throws(() => session.link(cart));
});
test('untrusted links and mismatched or expired responses never open', () => {
  const request = { cartId: 'cart-a', expectedVersion: 1, idempotencyKey: 'request-key-00001' };
  for (const link of ['javascript:alert(1)', `${base}.evil#purchase-a`, `${base}?token=secret#purchase-a`,
    `${base}#wrong`, 'https://example.com@evil.test/purchase#purchase-a']) {
    assert.throws(() => handoffData({ ...ready(request), appLink: link }, request, base));
  }
  assert.throws(() => handoffData({ ...ready(request), nextAction: 'REVIEW_CART' }, request, base));
  assert.equal(handoffData(ready(request), request, base, Date.now() + 120000).appLink, null);
  assert.equal(handoffSession({ ...request, token: 'untrusted' }, base).pending, undefined);
  assert.equal(handoffSession({ ...request, cartId: null }, base).pending, undefined);
  assert.equal(handoffSession({ ...request, idempotencyKey: 1234567890123456 }, base).pending, undefined);
});
test('local app scenarios explain next action without claiming approval or payment', () => {
  assert.match(handoffGuidance.LOGIN_REQUIRED, /로그인 후 같은 구매/);
  assert.match(handoffGuidance.NOT_INSTALLED, /같은 링크/);
  assert.match(handoffGuidance.CANCELLED, /결제되지/);
  assert.match(handoffGuidance.EXPIRED, /장바구니/);
});
test('host-omitted nullable fields preserve terminal outcomes without accepting a link', () => {
  const request = { cartId: 'cart-a', expectedVersion: 1, idempotencyKey: 'request-key-00001' };
  const result = { kind: 'purchase.handoff', mode: 'QA', request, status: 'REJECTED', reason: 'EMPTY_CART',
    checkedAt: new Date().toISOString(), nextAction: 'REVIEW_CART' };
  assert.equal(handoffData(result, request, base).appLink, null);
  assert.equal(handoffData(result, request, base).reason, 'EMPTY_CART');
  assert.throws(() => handoffData({ ...result, appLink: `${base}#purchase-a` }, request, base));
});
