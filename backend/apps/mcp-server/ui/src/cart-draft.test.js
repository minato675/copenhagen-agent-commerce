import test from 'node:test';
import assert from 'node:assert/strict';
import { cartEditState, createCartDraft } from './cart-draft.js';
const original = { quantity: 2, merchantVariantId: 'small' };
const state = (quantity, flags = {}, variant = 'small') => cartEditState(original, { quantity, merchantVariantId: variant }, flags);
test('unchanged and reverted quantities do not submit; option change remains meaningful', () => {
  assert.equal(state('2').canApply, false);
  assert.equal(state('3').canApply, true);
  assert.equal(state('2').canApply, false);
  assert.equal(state('2', {}, 'large').canApply, true);
});

const items = [{ cartItemId: 'a', name: '빵', quantity: 2, merchantVariantId: 'v1', unitPriceAmount: 1000 },
  { cartItemId: 'b', name: '치즈', quantity: 1, merchantVariantId: 'v2', unitPriceAmount: 2000 }];
test('batch drafts coalesce rapid input and deletion into one final intent without mutating the cart', () => {
  const draft = createCartDraft(items);
  draft.quantity('a', '3'); draft.quantity('a', '4'); draft.toggleDelete('b');
  const state = draft.snapshot();
  assert.equal(state.subtotal, 4000);
  assert.deepEqual(state.changes, [{ operation: 'UPDATE', cartItemId: 'a', merchantVariantId: 'v1', quantity: 4 },
    { operation: 'DELETE', cartItemId: 'b' }]);
  assert.equal(items[0].quantity, 2);
  assert.match(state.label, /빵.*최종 수량 4개, 치즈.*삭제/);
  assert.ok(!state.label.includes('cartItemId'));
});
test('reverting drafts and undoing deletion excludes unchanged items', () => {
  const draft = createCartDraft(items);
  draft.quantity('a', '3'); draft.quantity('a', '2'); draft.toggleDelete('b'); draft.toggleDelete('b');
  assert.equal(draft.snapshot().dirty, false);
  assert.deepEqual(draft.snapshot().changes, []);
  draft.quantity('a', ''); assert.equal(draft.snapshot().valid, false);
  draft.toggleDelete('a'); assert.equal(draft.snapshot().valid, true);
  draft.toggleDelete('a'); assert.equal(draft.snapshot().valid, false);
});
test('option changes carry variant and quantity together and preview the selected price', () => {
  const draft = createCartDraft(items);
  draft.option('a', { merchantVariantId: 'v-large', priceAmount: 1500, minQuantity: 2, maxQuantity: 4, purchasable: true }, '대용량');
  assert.equal(draft.snapshot().subtotal, 5000);
  assert.deepEqual(draft.snapshot().changes[0], { operation: 'UPDATE', cartItemId: 'a', merchantVariantId: 'v-large', quantity: 2 });
  assert.match(draft.snapshot().label, /대용량/);
  draft.quantity('a', '5'); assert.equal(draft.snapshot().valid, false);
  draft.quantity('a', '3'); assert.equal(draft.snapshot().valid, true);
  draft.option('a', { merchantVariantId: 'v-out', priceAmount: 1500, purchasable: false }, '품절');
  assert.equal(draft.snapshot().valid, false);
});
test('invalid drafts never enable writes', () => {
  for (const quantity of ['', ' ', 'no', '0', '-1', '100', '1.5']) assert.equal(state(quantity).canApply, false);
  for (const quantity of ['1', '99']) assert.equal(state(quantity).canApply, true);
});
test('busy or pending delivery locks mutations; preflight failure can recover', () => {
  assert.equal(state('3', { busy: true }).canApply, false);
  assert.equal(state('3', { pending: true }).canApply, false);
  assert.equal(state('3').canApply, true);
});

test('quantity and deletion draft survives remount without assuming option availability', () => {
  let saved;
  const draft = createCartDraft(items, {}, value => { saved = structuredClone(value); });
  draft.quantity('a', '3'); draft.toggleDelete('b');
  const restored = createCartDraft(items, saved);
  assert.equal(restored.get('a').quantity, '3');
  assert.equal(restored.get('b').deleted, true);
  assert.deepEqual(restored.snapshot().changes, draft.snapshot().changes);
});
