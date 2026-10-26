import test from 'node:test';
import assert from 'node:assert/strict';
import { actionMessage, actionSession, createCartPreparation, isCartWrite, viewAccepts } from './commerce-actions.js';

const key = '00000000-0000-4000-8000-000000000001';
test('batch intent keeps its ordered changes and key on retry and locks a second submission', () => {
  let saved;
  const session = actionSession(null, value => { saved = structuredClone(value); }, () => key);
  const changes = [{ operation: 'DELETE', cartItemId: 'first' },
    { operation: 'UPDATE', cartItemId: 'second', merchantVariantId: 'basic', quantity: 3 }];
  const intent = session.prepare('apply_cart_changes', { cartVersion: 7, changes });
  assert.equal(isCartWrite(intent.tool), true);
  assert.deepEqual(actionSession(saved, () => {}).retry(), intent);
  assert.throws(() => session.prepare(intent.tool, intent.args), /이미 변경/);
});
test('write intent persists before delivery and retries preserve exact body and key after restore', () => {
  let stored;
  const session = actionSession(null, value => { stored = structuredClone(value); }, () => key, () => 1000);
  const intent = session.prepare('add_cart_item', { cartVersion: 1, quantity: 2 }, '통밀식빵 2개를 담아주세요.');
  assert.deepEqual(stored, intent);
  assert.throws(() => session.prepare('add_cart_item', { quantity: 3 }), /이미 변경/);
  const restored = actionSession(stored, () => assert.fail('must not replace pending'), () => assert.fail('must not create key'), () => 2000);
  assert.deepEqual(restored.retry(), intent);
  const message = actionMessage(intent.tool, intent.args);
  assert.deepEqual(JSON.parse(message.slice(message.lastIndexOf('\n') + 1)), { tool: intent.tool, arguments: intent.args });
});
test('old or unknown-age write intents cannot be replayed beyond server retention', () => {
  const saved = { tool: 'prepare_purchase_handoff', args: { idempotencyKey: key }, createdAt: 1000 };
  const expired = actionSession(saved, () => {}, () => key, () => 1000 + 86400000);
  assert.throws(() => expired.retry(), /시간이 지났어요/);
  assert.throws(() => expired.prepare(saved.tool, {}), /이미 변경/);
  delete saved.createdAt;
  assert.throws(() => actionSession(saved, () => {}).retry(), /시간이 지났어요/);
});
test('reads remain available for recovery without replacing pending write intent', () => {
  const session = actionSession(null, () => {}, () => key);
  const pending = session.prepare('update_cart_item', { quantity: 2 });
  assert.deepEqual(session.prepare('get_cart', {}), { tool: 'get_cart', args: {} });
  assert.equal(session.pending, pending);
  assert.throws(() => session.prepare('unknown_tool', {}));
});
test('each card accepts only its own result types; cart preflight cannot replace detail', () => {
  assert.equal(viewAccepts('detail', 'cart.view'), false);
  assert.equal(viewAccepts('search', 'catalog.detail'), false);
  assert.equal(viewAccepts('detail', 'catalog.detail'), true);
  assert.equal(viewAccepts('cart', 'cart.mutation'), true);
  assert.equal(viewAccepts('purchase', 'purchase.status'), true);
  assert.equal(viewAccepts('purchase', 'purchase.handoff'), true);
});
test('cart preparation coalesces consent requests and retries after failed authorization', async () => {
  let calls = 0;
  const preparation = createCartPreparation(async () => {
    if (++calls === 1) throw new Error('AUTH_REQUIRED');
    return { version: 3 };
  });
  await assert.rejects(preparation.ensure(), /AUTH_REQUIRED/);
  const [a, b] = await Promise.all([preparation.ensure(), preparation.ensure()]);
  assert.deepEqual(a, { version: 3 }); assert.equal(a, b);
  assert.equal(await preparation.ensure(), a); assert.equal(calls, 2);
  assert.equal(isCartWrite('add_cart_item'), true);
  assert.equal(isCartWrite('prepare_purchase_handoff'), false);
  assert.match(actionMessage('prepare_cart', {}), /prepare_cart/);
});
