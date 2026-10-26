import { cardNotice, sameCartVersion, cardContentHeight } from './card-notice.js';
import { createCartDraft } from './cart-draft.js';
import { App } from '@modelcontextprotocol/ext-apps';
import { productImage } from './product-image.js';
import { amount, payload, variantLabel } from './live-state.js';
import { availability } from './catalog.js';
import { purchaseProgress } from './purchase-progress.js';
import { createHostSizing } from './host-sizing.js';
import { sendAction, actionSession, createCartPreparation, isCartWrite, viewAccepts } from './commerce-actions.js';
import { sortedOffers } from './offer-selection.js';
import { resultDisposition } from './card-result.js';
import { createReadCoalescer, liveTimedCall, requestTimeout } from './live-requests.js';
import { measuredCall, requestDiagnostics } from './request-diagnostics.js';
import { createCardProgress, createActionGate, awaitDelivery } from './card-progress.js';

const $ = id => document.getElementById(id);
const el = (tag, text, cls) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; };
const view = document.body.dataset.view;
let app, ready = false, sending = false, internalReads = 0, latest, latestInput = window.openai?.toolInput ?? {};
let resultTimer, terminal = false;
let refreshControls = () => {};
let staleCart = false, noticeReason;
function collapseResult() {
  clearTimeout(resultTimer); terminal = true; progress.show('idle', '');
  if (!latest) document.body.classList.add('result-collapsed');
  sizing.notify();
}
let cartDraft = window.openai?.widgetState?.commerceCartDraft;
let selection = window.openai?.widgetState?.commerceSelection;
function saveSelection() { window.openai?.setWidgetState?.({ version: 5, commercePending: session.pending, commerceSelection: selection, commerceCartDraft: cartDraft }); }
const session = actionSession(window.openai?.widgetState?.commercePending,
  pending => window.openai?.setWidgetState?.({ version: 5, commercePending: pending, commerceSelection: selection, commerceCartDraft: cartDraft }));
const coalesce = createReadCoalescer();
const cartPreparation = createCartPreparation(() => read('prepare_cart', {}, 'cart.view'));
window.copenhagenDiagnostics = requestDiagnostics;
const progress = createCardProgress(({ phase, text }) => {
  $('status').dataset.phase = phase;
  $('status').textContent = text;
  $('status').hidden = !text;
  sizing.notify();
});
const status = text => progress.show(text ? 'notice' : 'idle', text);
const gate = createActionGate();
const button = (label, fn, secondary = false) => { const n = el('button', label, secondary ? 'secondary' : ''); n.onclick = async () => {
  if (n.disabled) return;
  await gate.run(async () => {
    const enabled = [...document.querySelectorAll('button,input,select')].filter(control => !control.disabled);
    enabled.forEach(control => { control.disabled = true; });
    try { await fn(); } catch (e) {
      progress.show(e.authRequired ? 'authorization' : 'error', e.authRequired
        ? '연결 또는 권한 동의를 완료해주세요. 선택한 옵션과 수량은 유지됩니다.'
        : e.code === 'UI_TIMEOUT' ? '권한 확인이 지연되고 있어요. 동의 창을 확인해주세요. 선택 정보는 유지됩니다.' : e.message);
    } finally { enabled.forEach(control => { if (control.isConnected) control.disabled = false; }); }
  });
  refreshControls(); renderCardNotice();
}; return n; };
const sizing = createHostSizing({ getApp: () => ready ? app : null,
  getDisplayState: () => ({ mode: 'inline', confirmed: true }),
  measure: () => ({ width: innerWidth, height: document.body.classList.contains('result-collapsed')
    ? 1 : cardContentHeight(document.querySelector('main').getBoundingClientRect().height) }) });
new ResizeObserver(() => sizing.notify()).observe(document.querySelector('main'));
async function read(name, args, kind) {
  internalReads++;
  try {
    const result = await coalesce(name, args, () => measuredCall(name, () => liveTimedCall(() => ready
      ? app.callServerTool({ name, arguments: args }, { timeout: requestTimeout(name) + 1000 })
      : window.openai?.callTool ? window.openai.callTool(name, args) : Promise.reject(new Error('연결을 기다려주세요.')), name)));
    return payload(result, kind);
  } finally { internalReads--; }
}
async function deliver(intent) {
  if (sending) return;
  sending = true;
  const token = progress.begin('sending', '새 결과 카드를 요청하고 있어요.', '요청 전달이 늦어지고 있어요. 대화의 후속 결과를 기다려주세요.');
  try {
    let result;
    if (!ready) throw new Error('연결을 확인한 뒤 다시 눌러주세요.');
    internalReads++;
    try { result = await sendAction(app, intent, awaitDelivery); } finally { internalReads--; }
    if (result?.isError) throw new Error('요청 전달 결과를 확인하지 못했어요. 대화의 후속 결과를 확인해주세요.');
    progress.finish(token, 'delivered', '요청을 전달했어요. 실제 변경 여부는 대화의 처리 결과를 확인해주세요.');
  } finally { sending = false; }
}
async function next(tool, args = {}, label) {
  if (isCartWrite(tool) || tool === 'prepare_purchase_handoff') {
    progress.begin('authorization', '장바구니 이용 권한을 확인하고 있어요.', '동의 창이 열려 있다면 완료해주세요. 선택한 정보는 유지됩니다.');
    const current = await cartPreparation.ensure();
    if (args.cartVersion != null && !sameCartVersion(args.cartVersion, current.version)) {
      staleCart = true;
      throw new Error('장바구니가 변경됐어요. 최신 장바구니에서 다시 선택해주세요.');
    }
  }
  const intent = session.prepare(tool, args, label);
  if (label) intent.label = label;
  await deliver(intent);
}
function renderCardNotice() {
  const notice = cardNotice({ pending: !!session.pending, stale: staleCart });
  if (noticeReason === notice?.title) return;
  noticeReason = notice?.title;
  const panel = $('card-notice'); panel.replaceChildren(); panel.hidden = !notice;
  if (notice) {
    if (session.pending?.label) panel.append(el('p', `요청한 변경: ${session.pending.label}`));
    panel.append(el('strong', `ⓘ ${notice.title}`), el('p', notice.text),
      button('최신 장바구니 보기 ↗', () => next('get_cart', {}, '최신 장바구니를 보여주세요.'), true));
  }
  sizing.notify();
}
function heading(title, subtitle, eyebrow) { $('title').textContent = title; $('subtitle').textContent = subtitle; $('eyebrow').textContent = eyebrow; }
function productRail(label) {
  const section = el('section'), controls = el('div', null, 'rail-controls'), grid = el('div', null, 'search-grid');
  grid.setAttribute('role', 'region'); grid.setAttribute('aria-label', label); grid.tabIndex = 0;
  for (const [text, direction, title] of [['‹', -1, '이전 상품'], ['›', 1, '다음 상품']]) {
    const control = button(text, () => grid.scrollBy({ left: direction * 232, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' }), true);
    control.setAttribute('aria-label', title); controls.append(control);
  }
  const update = () => {
    controls.hidden = grid.scrollWidth <= grid.clientWidth + 1;
    controls.children[0].disabled = grid.scrollLeft <= 1;
    controls.children[1].disabled = grid.scrollLeft + grid.clientWidth >= grid.scrollWidth - 1;
  };
  grid.addEventListener('scroll', update); new ResizeObserver(update).observe(grid);
  requestAnimationFrame(update);
  section.append(controls, grid); return { section, grid };
}
function productCard(product, label) {
  const card = el('article', null, 'card'), body = el('div', null, 'card-body');
  const [best, ...others] = sortedOffers(product.offers);
  body.append(el('h2', product.name, 'name'), el('p', Number.isSafeInteger(best?.priceAmount) ? amount(best.priceAmount) : '가격 확인 필요', 'price'));
  if (label) body.append(el('span', label, 'match-label'));
  if (best) {
    body.append(el('p', best.merchantName, 'price-caption'));
    const choose = offer => next('get_product_detail', { merchantId: offer.merchantId, merchantProductId: offer.merchantProductId }, `${product.name} (${offer.merchantName})의 옵션을 보여주세요.`);
    body.append(button('옵션 선택', () => choose(best)));
    if (others.length) {
      const details = el('details', null, 'other-offers');
      details.append(el('summary', `다른 몰 보기 (${others.length})`));
      for (const offer of others) {
        const row = el('div', null, 'other-offer');
        row.append(el('span', offer.merchantName), el('strong', Number.isSafeInteger(offer.priceAmount) ? amount(offer.priceAmount) : '가격 확인 필요'),
          button(`${offer.merchantName} 선택`, () => choose(offer), true));
        details.append(row);
      }
      body.append(details);
    }
  }
  card.append(productImage(product.imageUrl), body); return card;
}
function renderSearch(data) {
  if (data.searchGroups) {
    for (const group of data.searchGroups) {
      if (!group.data) continue;
      $('content').append(el('h2', group.request?.query || '검색 상품'));
      renderSearch(group.data);
    }
    return;
  }
  heading('상품 검색 결과', '', '');
  const { section, grid } = productRail('검색 상품');
  for (const p of data.products ?? []) grid.append(productCard(p, ''));
  if (data.relatedSearches?.length) {
    for (const related of data.relatedSearches) {
      for (const p of related.products ?? []) grid.append(productCard(p, `${related.query?.query ?? '유사 상품'} · 비슷한 상품`));
      if (related.unavailable || related.failedMerchants?.length) section.append(el('p', `${related.query?.query ?? '연관 검색'} 일부 결과를 불러오지 못했어요.`, 'sub'));
      if (related.nextCursor || related.unavailable) section.append(button(`${related.query?.query} 더 보기 ↗`, () => next('search_products', { ...related.query, ...(related.nextCursor ? { cursor: related.nextCursor } : {}) }), true));
    }
  }
  if (!grid.children.length) grid.append(el('p', '조건에 맞는 상품이 없어요. 채팅에서 조건을 조정해주세요.', 'empty'));
  if (data.failedMerchants?.length) section.append(el('p', '일부 판매처가 응답하지 않아 확인된 상품만 보여드려요.', 'sub search-note'));
  if (data.nextCursor) section.append(button('상품 더 보기', () => next('search_products', { ...data.query, cursor: data.nextCursor }), true));
  $('content').append(section);
}
function renderDetail(data) {
  heading('옵션 선택', '', '');
  const layout = el('div', null, 'view-detail'), panel = el('div', null, 'detail-panel');
  panel.append(el('p', data.merchantName || '선택한 판매처', 'product-meta'), el('h2', data.name));
  const price = el('p', '', 'price'), stock = el('p', '', 'sub'), select = el('select'); select.setAttribute('aria-label', '상품 옵션');
  select.add(new Option('옵션을 선택해주세요', ''));
  for (const v of data.variants) { const option = new Option(`${variantLabel(data, v)} · ${amount(v.priceAmount)}`, v.merchantVariantId); option.disabled = !v.purchasable; select.add(option); }
  if (data.variants.length === 1 && data.variants[0].purchasable) select.value = data.variants[0].merchantVariantId;
  const options = el('label', '필수 옵션', 'detail-options'); options.append(select);
  const quantity = el('input'); quantity.type = 'number'; quantity.step = '1'; quantity.value = '1';
  if (selection?.merchantId === data.merchantId && selection?.merchantProductId === data.merchantProductId) {
    if (data.variants.some(v => v.merchantVariantId === selection.merchantVariantId && v.purchasable)) select.value = selection.merchantVariantId;
    if (Number.isInteger(selection.quantity) && selection.quantity > 0) quantity.value = selection.quantity;
  }
  const remember = () => { selection = { merchantId: data.merchantId, merchantProductId: data.merchantProductId,
    merchantVariantId: select.value, quantity: Number(quantity.value) }; saveSelection(); };
  quantity.onchange = remember;
  const qty = el('label', '수량'); qty.append(quantity);
  const add = button('장바구니에 담기 ↗', async () => {
    if (session.pending) throw new Error('이미 담기를 요청했어요. 대화에 표시된 장바구니에서 이어서 이용해주세요.');
    const v = data.variants.find(v => v.merchantVariantId === select.value), count = Number(quantity.value);
    if (!v?.purchasable || !Number.isInteger(count) || count < v.minQuantity || count > v.maxQuantity) throw new Error('구매 가능한 옵션과 수량을 확인해주세요.');
    remember(); progress.begin('authorization', '장바구니 이용 권한을 확인하고 있어요.', '동의 창이 열려 있다면 완료해주세요. 옵션과 수량은 유지됩니다.');
    const cart = await cartPreparation.ensure();
    await next('add_cart_item', { cartVersion: cart.version, merchantId: data.merchantId,
      merchantProductId: data.merchantProductId, merchantVariantId: v.merchantVariantId, quantity: count, selectionBasis: 'USER_SELECTED' }, `${data.name}, ${variantLabel(data, v)} 옵션을 ${count}개 담아주세요.`);
  });
  const update = () => { const v = data.variants.find(v => v.merchantVariantId === select.value);
    price.textContent = v ? amount(v.priceAmount) : '옵션 선택 후 가격 확인';
    stock.textContent = v ? `${availability(v.availability)}${v.stockQuantity == null ? '' : ` · 재고 ${v.stockQuantity}개`}` : '';
    quantity.min = v?.minQuantity ?? 1; quantity.max = v?.maxQuantity ?? 99; const count = Number(quantity.value);
    add.disabled = !!session.pending || staleCart || gate.active || !v?.purchasable || !Number.isInteger(count) || count < quantity.min || count > quantity.max;
  }; select.onchange = () => { update(); remember(); }; quantity.addEventListener('input', update);
  refreshControls = update; update();
  const actions = el('div', null, 'cart-add-actions'); actions.append(qty, add);
  const facts = el('div', null, 'detail-facts'); facts.append(stock);
  if (Number.isSafeInteger(data.shipping?.feeAmount)) facts.append(el('p', `배송비 ${amount(data.shipping.feeAmount)}`, 'sub'));
  panel.append(price, options, facts, actions); layout.append(productImage(data.imageUrl), panel); $('content').append(layout);
}
function renderCart(data) {
  heading(`장바구니 · ${data.items.length}개 상품`, '', '');
  const list = el('section', null, 'receipt-list'), grid = el('div', null, 'receipt-items');
  list.setAttribute('aria-label', '장바구니 상품'); list.append(grid);
  if (!data.items.length) list.append(el('p', '장바구니가 비어 있어요. 채팅에서 원하는 상품을 찾아보세요.', 'empty'));
  const draft = createCartDraft(data.items.map(item => ({ ...item,
    merchantName: data.merchantSummaries.find(m => m.merchantId === item.merchantId)?.merchantName })),
    cartDraft?.cartId === data.cartId && cartDraft?.version === data.version ? cartDraft.edits : {},
    edits => { cartDraft = { cartId: data.cartId, version: data.version, edits }; saveSelection(); });
  const rowUpdates = [];
  const merchants = new Map(data.merchantSummaries.map(m => [m.merchantId, m.merchantName]));
  for (const item of data.items) if (!merchants.has(item.merchantId)) merchants.set(item.merchantId, '판매처');
  for (const [id, name] of merchants) {
    for (const item of data.items.filter(i => i.merchantId === id)) {
      const row = el('article', null, 'receipt-row'), info = el('div', null, 'receipt-info');
      info.append(el('h2', item.name, 'name'), el('p', name, 'price-caption'));
      const linePrice = el('p', amount(item.lineTotalAmount), 'receipt-price');
      const actions = el('div', null, 'receipt-controls'), quantity = el('input'); quantity.type = 'number'; quantity.min = '1'; quantity.max = '99'; quantity.value = draft.get(item.cartItemId).quantity;
      const label = el('label', '수량'); label.append(quantity);
      const optionArea = el('div', null, 'receipt-option'), optionSelect = el('select');
      optionSelect.setAttribute('aria-label', `${item.name} 옵션`); optionSelect.hidden = true;
      let variants = [], detail, optionsLoaded = false;
      const loadOptions = button(`${item.selectedOptions.map(o => o.value).join(' · ') || '옵션 확인'} ▾`, async () => {
        detail = await read('get_product_detail', { merchantId: item.merchantId, merchantProductId: item.merchantProductId }, 'catalog.detail');
        if (detail.merchantId !== item.merchantId || detail.merchantProductId !== item.merchantProductId) throw new Error('상품 옵션을 확인하지 못했어요.');
        variants = detail.variants ?? [];
        optionSelect.replaceChildren();
        for (const variant of variants) {
          const option = new Option(`${variantLabel(detail, variant)} · ${amount(variant.priceAmount)}`, variant.merchantVariantId);
          option.disabled = !variant.purchasable; optionSelect.add(option);
        }
        optionSelect.value = draft.get(item.cartItemId).merchantVariantId;
        if (!optionSelect.value) {
          const current = new Option('현재 옵션을 판매하지 않아요. 다시 선택해주세요.', '', true, true);
          current.disabled = true; optionSelect.add(current);
        }
        optionsLoaded = true; loadOptions.hidden = true; optionSelect.hidden = false;
        const selected = variants.find(variant => variant.merchantVariantId === optionSelect.value);
        if (selected) draft.option(item.cartItemId, selected, variantLabel(detail, selected));
        else draft.option(item.cartItemId, { merchantVariantId: item.merchantVariantId, priceAmount: item.unitPriceAmount, purchasable: false }, '다시 선택 필요');
        if (variants.length === 1 && selected) optionHint.textContent = '선택 가능한 옵션이 하나예요.';
        else if (!variants.some(variant => variant.purchasable)) optionHint.textContent = '현재 선택 가능한 옵션이 없어요.';
        refreshControls();
      }, true);
      loadOptions.disabled = !item.merchantProductId;
      optionSelect.addEventListener('change', () => {
        const variant = variants.find(value => value.merchantVariantId === optionSelect.value);
        if (variant?.purchasable) draft.option(item.cartItemId, variant, variantLabel(detail, variant));
        refreshControls();
      });
      loadOptions.setAttribute('aria-label', `${item.name} 옵션 변경`);
      const optionHint = el('p', '', 'sub');
      optionArea.append(loadOptions, optionSelect, optionHint);
      info.insertBefore(optionArea, info.lastChild);
      const remove = button('삭제 선택', () => { draft.toggleDelete(item.cartItemId); refreshControls(); }, true);
      const update = () => {
        const edit = draft.get(item.cartItemId);
        quantity.disabled = edit.deleted || !!session.pending || staleCart || gate.active;
        remove.disabled = !!session.pending || staleCart || gate.active;
        loadOptions.disabled = remove.disabled || edit.deleted || !item.merchantProductId;
        optionSelect.disabled = remove.disabled || edit.deleted || (optionsLoaded && variants.length <= 1 && optionSelect.value !== '');
        quantity.min = edit.minQuantity ?? 1; quantity.max = Math.min(99, edit.maxQuantity ?? 99);
        remove.textContent = edit.deleted ? '삭제 취소' : '삭제 선택';
        remove.setAttribute('aria-pressed', String(edit.deleted));
        row.classList.toggle('draft-deleted', edit.deleted);
      };
      rowUpdates.push(update);
      quantity.setAttribute('aria-label', `${item.name} 수량`);
      quantity.addEventListener('input', () => { draft.quantity(item.cartItemId, quantity.value); refreshControls(); });
      actions.append(label, remove);
      row.append(productImage(item.imageUrl), info, linePrice, actions); grid.append(row);
    }
  }
  const summary = el('div', null, 'summary-panel'), total = el('div', null, 'cart-total');
  const totalKnown = Number.isSafeInteger(data.summary.totalAmount);
  const subtotal = data.summary.itemSubtotalAmount ?? data.items.reduce((sum, item) => sum + item.lineTotalAmount, 0);
  total.append(el('span', totalKnown ? '예상 결제금액' : '상품 금액'), el('strong', amount(totalKnown ? data.summary.totalAmount : subtotal)));
  summary.append(total);
  if (Number.isSafeInteger(data.summary.shippingAmount)) summary.append(el('p', `배송비 ${amount(data.summary.shippingAmount)}`, 'sub'));
  const preview = el('p', '', 'draft-notice'); preview.setAttribute('role', 'status');
  const apply = button('변경사항 적용 ↗', () => {
    const state = draft.snapshot();
    if (!state.valid || !state.dirty) return;
    return next('apply_cart_changes', { cartVersion: data.version, changes: state.changes }, state.label);
  });
  const purchase = button('앱으로 결제 요청 ↗', () => next('prepare_purchase_handoff', { cartId: data.cartId, cartVersion: data.version }));
  refreshControls = () => {
    rowUpdates.forEach(update => update());
    const state = draft.snapshot(), locked = !!session.pending || staleCart || gate.active;
    apply.disabled = locked || !state.valid || !state.dirty;
    purchase.disabled = locked || state.dirty || !state.valid || !data.items.length;
    preview.textContent = state.dirty
      ? !state.valid ? '판매 가능한 옵션과 수량 범위를 확인해주세요. 변경은 한 번에 30개 상품까지 가능합니다.'
        : `${state.changes.length}개 상품 변경 예정 · 상품 금액 미리보기 ${state.subtotal == null ? '계산 불가' : amount(state.subtotal)}`
      : '';
    preview.hidden = !state.dirty;
  };
  refreshControls();
  summary.append(preview, apply, purchase); $('content').append(list, summary);
}
function renderPurchase(data, kind) {
  heading('결제 승인은 앱에서', '휴대폰 알림을 열고 주문 내용과 최종 금액을 확인해주세요.', '04 / APPROVAL');
  const panel = el('div', null, 'purchase-panel');
  const progress = kind === 'purchase.status' ? purchaseProgress(data) : { text: '구매 요청이 접수됐어요. 현재 진행 상태를 확인해주세요.' };
  panel.append(el('div', '↗', 'purchase-symbol'), el('h2', kind === 'purchase.handoff' ? '구매 요청을 전달했어요' : '구매 진행 상태'), el('p', progress.text));
  const steps = el('div', null, 'purchase-steps'); for (const label of ['주문 확인', '앱 알림', '사용자 승인']) steps.append(el('span', label)); panel.append(steps);
  if (data.expiresAt) panel.append(el('p', `요청 유효 시각 ${new Date(data.expiresAt).toLocaleString('ko-KR')}`, 'sub'));
  $('content').append(panel);
  $('actions').append(button('현재 결제 상태 보기 ↗', () => next('get_purchase_status', { purchaseRequestId: data.purchaseRequestId })));
  if (!progress.terminal) $('actions').append(button('알림 다시 요청 ↗', () => next('resend_purchase_notification', { purchaseRequestId: data.purchaseRequestId }), true));
  if (data.status === 'EXPIRED') {
    panel.append(el('p', '요청이 만료됐어요. 채팅에서 장바구니를 요청해 구매를 이어갈 수 있어요.', 'sub'));
  }
}
function accept(result) {
  if (internalReads) return;
  const value = result?.structuredContent ?? result;
  if (resultDisposition(result, view) !== 'render') { collapseResult(); return; }
  if (!viewAccepts(view, value?.kind) || !value.data) return;
  clearTimeout(resultTimer); terminal = true; document.body.classList.remove('result-collapsed');
  refreshControls = () => {};
  latest = value; $('content').replaceChildren(); $('actions').replaceChildren(); status('');
  try {
    if (view === 'search') renderSearch({ ...value.data, query: value.data.query ?? latestInput });
    else if (view === 'detail') renderDetail(value.data);
    else if (view === 'cart') {
      const cart = value.kind === 'cart.mutation' ? value.data.cart : value.data;
      if (cart) renderCart(cart);
      else { heading(value.data.outcome === 'APPLIED' ? '장바구니 변경을 처리했어요' : '장바구니 처리 결과를 확인하지 못했어요', '', ''); }
    } else renderPurchase(value.data, value.kind);
    $('checked-at').textContent = value.data.checkedAt ? new Date(value.data.checkedAt).toLocaleTimeString('ko-KR') : '';
    renderCardNotice(); sizing.notify();
  } catch { status('표시할 정보를 확인하지 못했어요. 채팅에서 최신 결과를 다시 요청해주세요.'); }
}
app = new App({ name: 'Copenhagen Cards', version: '1.0.0' }, { availableDisplayModes: ['inline'] }, { autoResize: false });
function applyTheme(theme) {
  if (theme === 'dark' || theme === 'light') document.documentElement.dataset.theme = theme;
}
applyTheme(window.openai?.theme);
app.onhostcontextchanged = context => applyTheme(context.theme);
function waiting() {
  if (internalReads || latest || gate.active || terminal) return;
  if (!resultTimer) resultTimer = setTimeout(collapseResult, 30000);
  progress.begin('loading', '상품 정보를 준비하고 있어요.');
}
app.ontoolresult = accept;
app.ontoolinput = input => { if (!internalReads) latestInput = input.arguments ?? {}; waiting(); };
app.ontoolinputpartial = waiting;
app.ontoolcancelled = () => {
  if (internalReads || gate.active) return;
  collapseResult();
};
waiting();
window.addEventListener('pagehide', () => { clearTimeout(resultTimer); progress.dispose(); }, { once: true });
app.connect().then(() => { ready = true; applyTheme(app.getHostContext?.()?.theme); sizing.notify(); }).catch(() => { if (!latest) status('ChatGPT 연결을 기다리고 있어요.'); });
if (window.openai?.toolOutput) accept(window.openai.toolOutput);
window.addEventListener('openai:set_globals', event => { applyTheme(event.detail?.globals?.theme); if (event.detail?.globals?.toolOutput) accept(event.detail.globals.toolOutput); });
