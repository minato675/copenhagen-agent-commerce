import test from 'node:test';
import assert from 'node:assert/strict';
import { createHandoffUi } from './handoff-app.js';

const base = 'https://example.com/purchase';
const cart = { cartId: 'cart-a', version: 1 };
const request = { cartId: cart.cartId, expectedVersion: cart.version, idempotencyKey: 'request-key-00001' };
const ready = input => ({ kind: 'purchase.handoff', mode: 'QA', request: input, status: 'READY', reason: 'NONE',
  purchaseRef: 'purchase-a', appLink: `${base}#purchase-a`, expiresAt: new Date(Date.now() + 60000).toISOString(),
  checkedAt: new Date().toISOString(), nextAction: 'OPEN_APP' });
const terminal = (input, status, reason) => ({ ...ready(input), status, reason, purchaseRef: null,
  appLink: null, expiresAt: null, nextAction: status === 'UNKNOWN' ? 'RETRY_SAME_REQUEST' : 'REVIEW_CART' });

function mount(t, restored, callTool) {
  const previous = Object.fromEntries(['document', 'window'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  t.after(() => {
    for (const key of Object.keys(previous)) {
      if (previous[key]) Object.defineProperty(globalThis, key, previous[key]);
      else delete globalThis[key];
    }
  });
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      hidden: true, disabled: false, textContent: '', open: false, modalCalls: 0, closeCalls: 0,
      showModal() { this.open = true; this.modalCalls++; },
      close() { this.open = false; this.closeCalls++; },
      click() { if (!this.disabled) return this.onclick?.(); }
    });
    return elements.get(id);
  };
  globalThis.document = { body: { dataset: { handoffEnabled: 'true', handoffLinkBase: base } },
    getElementById: element, querySelectorAll: () => [] };
  globalThis.window = { openai: { widgetState: { handoffPending: restored } } };
  const opened = [], saved = [];
  let current = cart, ui;
  ui = createHandoffUi(callTool, async link => { opened.push(link); }, () => current,
    () => { saved.push(ui.pending()); });
  return { ui, element, opened, saved, setCart(value) { current = value; ui.observe(value); } };
}

test('restored handoff recovery reuses the request and unlocks cart without opening a modal or link', async t => {
  const calls = [];
  const view = mount(t, request, async (name, input) => { calls.push({ name, input }); return ready(input); });
  assert.equal(view.ui.blocked(), true);
  assert.equal(await view.ui.recover(cart), true);
  assert.deepEqual(calls, [{ name: 'prepare_purchase_handoff', input: request }]);
  assert.equal(view.ui.blocked(), false);
  assert.equal(view.element('handoff-open').hidden, false);
  assert.equal(view.element('handoff-dialog').modalCalls, 0);
  assert.equal(view.element('cart-dialog').closeCalls, 0);
  assert.deepEqual(view.opened, []);
  view.setCart({ ...cart, version: 2 });
  assert.equal(view.ui.blocked(), false);
  assert.equal(view.ui.pending(), undefined);
  assert.equal(view.element('handoff-open').hidden, true);
  assert.equal(view.saved.at(-1), undefined);
  await view.ui.recover({ ...cart, version: 2 });
  assert.equal(calls.length, 1);
});

test('cart recovery without a previous request cannot prepare a new purchase', async t => {
  let calls = 0;
  const view = mount(t, undefined, async () => { calls++; throw new Error('unexpected prepare'); });
  assert.equal(await view.ui.recover(cart), true);
  assert.equal(calls, 0);
  assert.equal(view.ui.pending(), undefined);
  assert.equal(view.element('handoff-dialog').modalCalls, 0);
  assert.deepEqual(view.opened, []);
});

test('unknown recovery keeps the original request and its retry button resolves the same request', async t => {
  const calls = [];
  const view = mount(t, request, async (_name, input) => {
    calls.push(input);
    return calls.length === 1 ? terminal(input, 'UNKNOWN', 'OUTCOME_UNKNOWN') : ready(input);
  });
  assert.equal(await view.ui.recover(cart), false);
  assert.deepEqual(view.ui.pending(), request);
  assert.equal(view.ui.blocked(), true);
  const retry = view.element('handoff-retry');
  assert.equal(retry.hidden, false);
  assert.equal(retry.disabled, false);
  assert.equal(view.element('handoff-open').hidden, true);
  retry.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, [request, request]);
  assert.equal(view.ui.blocked(), false);
  assert.deepEqual(view.opened, []);
});

test('transport failure preserves recovery state until a definitive response arrives', async t => {
  const calls = [];
  const view = mount(t, request, async (_name, input) => {
    calls.push(input);
    if (calls.length === 1) throw new Error('lost response');
    return terminal(input, 'CONFLICT', 'VERSION_CONFLICT');
  });
  assert.equal(await view.ui.recover({ ...cart, version: 2 }), false);
  assert.deepEqual(view.ui.pending(), request);
  assert.equal(view.element('handoff-retry').hidden, false);
  assert.equal(view.element('handoff-retry').disabled, false);
  assert.equal(await view.ui.recover({ ...cart, version: 2 }), true);
  assert.deepEqual(calls, [request, request]);
  assert.equal(view.ui.pending(), undefined);
  assert.equal(view.ui.blocked(), false);
  assert.equal(view.element('handoff-open').hidden, true);
  assert.deepEqual(view.opened, []);
});

test('concurrent recovery does not prepare twice or permit opening an unresolved link', async t => {
  let finish;
  const calls = [];
  const view = mount(t, request, (name, input) => {
    calls.push({ name, input }); return new Promise(resolve => { finish = resolve; });
  });
  const first = view.ui.recover(cart);
  assert.equal(view.ui.running(), true);
  assert.equal(view.ui.blocked(), true);
  assert.equal(view.element('handoff-retry').disabled, true);
  assert.equal(await view.ui.recover(cart), false);
  await view.ui.start(cart);
  assert.equal(calls.length, 1);
  assert.equal(view.element('handoff-dialog').modalCalls, 0);
  finish(ready(request));
  assert.equal(await first, true);
  assert.equal(view.ui.running(), false);
  assert.equal(view.ui.blocked(), false);
  assert.deepEqual(calls[0].input, request);
  assert.deepEqual(view.opened, []);
});

test('recovery of a superseded handoff never exposes the old cart link', async t => {
  const view = mount(t, request, async (_name, input) => ready(input));
  assert.equal(await view.ui.recover({ ...cart, version: 2 }), true);
  assert.equal(view.ui.pending(), undefined);
  assert.equal(view.ui.blocked(), false);
  assert.equal(view.element('handoff-open').hidden, true);
  assert.equal(view.element('handoff-expiry').textContent, '');
  assert.deepEqual(view.opened, []);
});
