import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import * as state from './live-state.js';
import * as requests from './live-requests.js';
import { purchaseProgress } from './purchase-progress.js';
import * as diagnostics from './request-diagnostics.js';

const source = (await readFile(new URL('./integration-app.js', import.meta.url), 'utf8')).replace(/^import .*;\r?$/gm, '');
const cart = { cartId: 'cart1', version: 1, items: [], merchantSummaries: [], summary: {} };
const result = (kind, data) => ({ structuredContent: { kind, data } });
const flush = () => new Promise(resolve => setImmediate(resolve));
function harness(handler, saved = {}) {
  const nodes = new Map(), all = [];
  class Element {
    constructor() { this.children = []; this.dataset = {}; this.value = ''; this.open = false; all.push(this); }
    append(...nodes) { this.children.push(...nodes); }
    after(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    add(node) { this.append(node); }
    setAttribute() {}
    showModal() { this.open = true; }
    close() { this.open = false; }
    getBoundingClientRect() { return { width: 800, height: 600 }; }
  }
  const document = {
    hidden: true,
    getElementById: id => all.find(n => n.id === id) ?? nodes.get(id) ?? (id.startsWith('purchase-') ? null : (() => { const n = new Element(); nodes.set(id, n); return n; })()),
    createElement: () => new Element(),
    querySelector: () => new Element(),
    querySelectorAll: selector => selector === '[data-discard-pending]' ? all.filter(n => 'discardPending' in n.dataset) : [],
  };
  const window = { openai: { widgetState: saved, setWidgetState: value => { window.saved = value; } }, addEventListener() {} };
  class App { connect() { return Promise.resolve(); } callServerTool({ name, arguments: args }) { return handler(name, args); } }
  const context = vm.createContext({ ...state, ...requests, ...diagnostics, window, document, App,
    crypto, performance, setTimeout, clearTimeout, console,
    ResizeObserver: class { observe() {} }, Option: class {},
    createHostSizing: () => ({ notify() {} }), safeImage: () => null, availability: x => x, purchaseProgress });
  vm.runInContext(source + '\nglobalThis.api = { document, restartPurchase, refreshPurchase, setCart: value => { currentCart = value; }, mutate, retryPending, discardButton, search, accept, state: () => ({ pending, busy, currentSearch }) };', context);
  return { ...context.api, window };
}

test('LIVE 인증 거절 후 같은 선택과 키로 이어서 담고 중복 클릭을 막는다', async () => {
  let grant = false, writes = 0, sent;
  const ui = harness(async (name, args) => {
    if (name === 'prepare_cart') return grant ? result('cart.view', cart)
      : { isError: true, structuredContent: { error: { code: 'INSUFFICIENT_SCOPE', outcome: 'NOT_APPLIED' } } };
    writes++; sent = args; return result('cart.mutation', { outcome: 'APPLIED', cart });
  });
  await flush();
  await ui.mutate('add_cart_item', { merchantVariantId: 'v1', quantity: 2 });
  const key = ui.state().pending.args.idempotencyKey;
  assert.equal(ui.state().pending.phase, 'AUTH_REQUIRED'); assert.equal(ui.state().busy, false);
  assert.equal(writes, 0); grant = true;
  await Promise.all([ui.retryPending(), ui.retryPending()]);
  assert.equal(writes, 1); assert.equal(sent.idempotencyKey, key); assert.equal(sent.quantity, 2);
  assert.equal(ui.state().pending, null);
});

test('LIVE 준비 요청 취소 후 늦은 권한 응답은 장바구니를 변경하지 않는다', async () => {
  let finish, writes = 0;
  const ui = harness((name) => name === 'prepare_cart' ? new Promise(resolve => { finish = resolve; }) : writes++);
  await flush();
  const pending = ui.mutate('add_cart_item', { quantity: 1 }); await flush();
  await ui.discardButton().onclick();
  finish(result('cart.view', cart)); await pending;
  assert.equal(writes, 0); assert.equal(ui.state().pending, null); assert.equal(ui.state().busy, false);
});

test('LIVE 변경 성공 후 조회 실패는 변경 요청을 남기거나 다시 전송하지 않는다', async () => {
  let writes = 0;
  const ui = harness(async name => {
    if (name === 'prepare_cart') return result('cart.view', cart);
    if (name === 'get_cart') throw new Error('조회 실패');
    writes++; return result('cart.mutation', { outcome: 'APPLIED', cart: null });
  });
  await flush(); await ui.mutate('add_cart_item', { quantity: 1 }); await ui.retryPending();
  assert.equal(writes, 1); assert.equal(ui.state().pending, null);
});

test('LIVE 위젯 재진입은 전송 본문과 키를 그대로 복구한다', async () => {
  const pending = { ...state.pendingRequest('add_cart_item', { cartVersion: 1, quantity: 2 }), phase: 'UNKNOWN', uncertain: true };
  let received;
  const ui = harness(async (name, args) => { assert.equal(name, 'add_cart_item'); received = args;
    return result('cart.mutation', { outcome: 'APPLIED', cart }); }, { livePending: pending });
  await flush(); await ui.retryPending();
  assert.equal(JSON.stringify(received), JSON.stringify(pending.args));
  assert.equal(ui.state().pending, null);
});

test('LIVE 이전 호스트 결과는 사용자가 새로 검색한 화면을 덮지 않는다', async () => {
  const data = { products: [], failedMerchants: [], filters: { categories: [], merchants: [] }, checkedAt: '2026-09-18T00:00:00Z' };
  const ui = harness(async () => result('catalog.search', data));
  await flush(); await ui.search({ query: 'new' });
  ui.accept(result('catalog.search', { ...data, checkedAt: '2026-09-17T00:00:00Z' }));
  assert.equal(ui.state().currentSearch.checkedAt, data.checkedAt);
});

const expiredReceipt = { purchaseRequestId: 'old-request', cartId: 'cart1', cartVersion: 1,
  expiresAt: '2020-01-01T00:00:00Z' };
const purchaseStatus = status => result('purchase.status', { status, notifications: [], merchantCheckouts: [] });
async function purchaseHarness(handler, saved = {}) {
  const ui = harness(handler, { liveReceipt: expiredReceipt, ...saved });
  await flush(); ui.setCart({ ...cart, items: [{ cartItemId: 'item1' }] });
  ui.document.getElementById('cart-continue').onclick(); await flush();
  return ui;
}
test('LIVE 만료된 구매는 동일 장바구니에서 명시적으로 한 번만 새 요청을 생성한다', async () => {
  const writes = []; let reads = 0;
  const ui = await purchaseHarness(async (name, args) => {
    if (name === 'get_purchase_status') { reads++; return purchaseStatus('EXPIRED'); }
    writes.push(args); return result('purchase.handoff', { ...expiredReceipt, purchaseRequestId: 'new-request' });
  });
  assert.equal(writes.length, 0);
  const restart = ui.document.getElementById('purchase-restart');
  assert.equal(restart.hidden, false);
  await Promise.all([restart.onclick(), restart.onclick()]); await flush();
  assert.equal(writes.length, 1); assert.equal(writes[0].cartVersion, 1);
  assert.equal(writes[0].cartId, 'cart1'); assert.ok(writes[0].idempotencyKey);
  assert.ok(reads >= 2); assert.equal(ui.window.saved.liveReceipt.purchaseRequestId, 'new-request');
});
for (const status of ['AWAITING_APPROVAL', 'COMPLETING', 'COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED']) {
  test(`LIVE 로컬 만료 시각이 지났어도 서버 ${status}이면 새 구매를 생성하지 않는다`, async () => {
    let writes = 0;
    const ui = await purchaseHarness(async name => {
      if (name === 'get_purchase_status') return purchaseStatus(status);
      writes++;
    });
    assert.equal(ui.document.getElementById('purchase-restart').hidden, true);
    await ui.restartPurchase(); assert.equal(writes, 0);
  });
}
test('LIVE 만료 확인 후 재시작 직전 상태 조회 실패는 새 구매를 막는다', async () => {
  let reads = 0, writes = 0;
  const ui = await purchaseHarness(async name => {
    if (name === 'get_purchase_status') { if (++reads > 1) throw new Error('timeout'); return purchaseStatus('EXPIRED'); }
    writes++;
  });
  await ui.restartPurchase(); assert.equal(writes, 0);
  assert.equal(ui.document.getElementById('purchase-restart').hidden, true);
});
test('LIVE 재시작 요청의 결과 불명은 새 키를 만들지 않고 같은 요청으로 복구한다', async () => {
  const writes = [];
  const ui = await purchaseHarness(async (name, args) => {
    if (name === 'get_purchase_status') return purchaseStatus('EXPIRED');
    writes.push(args); throw new Error('network unavailable');
  });
  await ui.restartPurchase(); const key = ui.state().pending.args.idempotencyKey;
  await ui.restartPurchase(); assert.equal(writes.length, 1);
  await ui.retryPending(); assert.equal(writes.length, 2);
  assert.equal(writes[1].idempotencyKey, key);
});
test('LIVE 복원된 미확정 구매 요청이 있으면 만료된 영수증으로도 새 요청을 만들지 않는다', async () => {
  const pending = { ...state.pendingRequest('prepare_purchase_handoff', { cartId: 'cart1', cartVersion: 1 }), uncertain: true };
  let writes = 0;
  const ui = await purchaseHarness(async name => {
    if (name === 'get_purchase_status') return purchaseStatus('EXPIRED');
    writes++;
  }, { livePending: pending });
  await ui.restartPurchase(); assert.equal(writes, 0);
  assert.equal(ui.state().pending.args.idempotencyKey, pending.args.idempotencyKey);
});

test('LIVE 재시작 직전 서버 상태가 진행 중으로 바뀌면 기존 요청을 유지한다', async () => {
  let reads = 0, writes = 0;
  const ui = await purchaseHarness(async name => {
    if (name === 'get_purchase_status') return purchaseStatus(++reads === 1 ? 'EXPIRED' : 'COMPLETING');
    writes++;
  });
  await ui.restartPurchase(); assert.equal(writes, 0);
  assert.equal(ui.document.getElementById('purchase-restart').hidden, true);
});
test('LIVE 각각 만료된 구매를 재시작할 때는 서로 다른 멱등 키를 사용한다', async () => {
  const keys = [];
  const ui = await purchaseHarness(async (name, args) => {
    if (name === 'get_purchase_status') return purchaseStatus('EXPIRED');
    keys.push(args.idempotencyKey);
    return result('purchase.handoff', { ...expiredReceipt, purchaseRequestId: `new-${keys.length}` });
  });
  await ui.restartPurchase(); await flush(); await ui.restartPurchase(); await flush();
  assert.equal(keys.length, 2); assert.notEqual(keys[0], keys[1]);
});
