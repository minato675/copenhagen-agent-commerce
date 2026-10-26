import { App } from '@modelcontextprotocol/ext-apps';
import { createCartUi } from './cart-app.js';
import { createHostSizing } from './host-sizing.js';
import { acceptHostSearch, availability, detailData, detailPrompt, filters, money, safeImage, searchData, timedCall } from './catalog.js';

const $ = id => document.getElementById(id);
const formFields = ['query', 'category', 'merchantId', 'minPrice', 'maxPrice', 'brand', 'sort'];
let app, sdkReady = false, connecting = false, busy = false, generation = 0, current, lastQuery = {};
let detailGeneration = 0, sizing, observedMode;
const element = (tag, text, className) => {
  const item = document.createElement(tag); if (text !== undefined) item.textContent = text;
  if (className) item.className = className; return item;
};
const cart = createCartUi(callTool, element, saveWidgetState, openLink);
// Observe host mode only to report inline height; this UI never requests a mode change.
function observeHostMode(context) {
  if (['inline', 'fullscreen', 'pip'].includes(context?.displayMode)) observedMode = context.displayMode;
  sizing?.notify();
}
sizing = createHostSizing({ getApp: () => sdkReady ? app : null, getDisplayState: () => ({ mode: observedMode, confirmed: observedMode !== undefined }),
  measure: () => ({ width: window.innerWidth, height: document.querySelector('main').getBoundingClientRect().height }) });
new ResizeObserver(() => sizing.notify()).observe(document.querySelector('main'));
window.addEventListener('resize', () => sizing.notify(), { passive: true });
observeHostMode(window.openai);
function saveWidgetState() {
  window.openai?.setWidgetState?.({ version: 3, query: lastQuery, cartPending: cart.pending(), handoffPending: cart.handoffPending() });
}
async function openLink(href) {
  if (typeof window.openai?.openExternal === 'function') {
    const result = await timedCall(() => window.openai.openExternal({ href, redirectUrl: false }));
    if (result?.isError) throw new Error('링크 열기가 거절됐습니다. 같은 링크로 다시 시도해주세요.');
  } else if (sdkReady) {
    const result = await timedCall(() => app.openLink({ url: href }));
    if (result?.isError) throw new Error('링크 열기가 거절됐습니다. 같은 링크로 다시 시도해주세요.');
  } else throw new Error('현재 호스트에서 링크 열기를 지원하지 않습니다. ChatGPT 앱에서 확인해주세요.');
}
function status(message, error = false) { $('status').textContent = message; $('status').dataset.error = String(error); }
function controls() {
  for (const control of document.querySelectorAll('form input,form select,form button,#retry')) control.disabled = busy;
  $('previous').disabled = busy || !current || current.query.page <= 1;
  $('next').disabled = busy || !current?.hasNext;
}
async function callTool(name, args) {
  try {
    return await timedCall(() => {
      if (sdkReady) return app.callServerTool({ name, arguments: args });
      if (typeof window.openai?.callTool === 'function') return window.openai.callTool(name, args);
      throw new Error('Host unavailable');
    });
  } catch { throw new Error('상품 서버에 연결할 수 없습니다. 다시 시도해주세요.'); }
}
function options(id, values, placeholder, selected) {
  const select = $(id); select.replaceChildren(new Option(placeholder, ''));
  for (const [value, label] of values) select.add(new Option(label, value));
  if (selected && !values.some(([value]) => value === selected)) select.add(new Option(selected, selected));
  select.value = selected ?? '';
}
function compare(product) {
  ++detailGeneration;
  $('comparison-title').textContent = '판매처 비교';
  $('comparison-actions').replaceChildren();
  const body = $('comparison-body'); body.replaceChildren(element('p', product.name, 'dialog-product'));
  for (const offer of product.offers) {
    const row = element('section', undefined, 'offer');
    const info = element('div');
    info.append(element('div', offer.merchantName), element('div', money(offer.price), 'price'),
      element('div', availability(offer.availability), 'sub'));
    const detail = element('button', '옵션 · 재고 확인');
    detail.addEventListener('click', () => void loadDetail({ productId: product.productId, merchantId: offer.merchantId }));
    row.append(info, detail);
    body.append(row);
  }
  $('comparison').showModal();
}
async function loadDetail(request) {
  const id = ++detailGeneration; const body = $('comparison-body');
  const actions = $('comparison-actions'); actions.replaceChildren();
  $('comparison-title').textContent = '옵션 · 재고 확인';
  body.replaceChildren(element('p', '상품 상세 정보를 조회하고 있습니다.'));
  try {
    const data = detailData(await callTool('get_product_detail', request), request);
    if (id !== detailGeneration || !$('comparison').open) return;
    if (data.status === 'NOT_FOUND') { body.replaceChildren(element('p', '해당 판매처에서 상품을 찾을 수 없습니다.')); return; }
    const p = data.product;
    const heading = element('div', undefined, 'detail-heading');
    const image = safeImage(p.imageUrl);
    if (image) { const img = element('img'); img.src = image; img.alt = ''; img.referrerPolicy = 'no-referrer'; heading.append(img); }
    const title = element('div'); title.append(element('p', p.name, 'dialog-product'), element('p', p.offer.merchantName, 'sub'));
    heading.append(title); body.replaceChildren(heading);
    body.append(element('p', p.selectionStatus === 'REQUIRED' ? '옵션 선택 후 가격을 확인할 수 있어요.'
      : p.selectionStatus === 'INVALID' ? '유효하지 않은 옵션입니다. 다시 선택해주세요.' : money(p.offer.price), 'price detail-price'));
    if (p.variants.length) {
      const label = element('label', p.optionsRequired ? '필수 옵션' : '확인된 옵션', 'detail-options');
      const select = element('select'); select.add(new Option('옵션을 선택해주세요', ''));
      select.setAttribute('aria-label', p.optionsRequired ? '필수 옵션' : '확인된 옵션');
      for (const option of p.variants) {
        const item = new Option(`${option.label} · ${money(option.price)} · ${availability(option.availability)}`, option.optionId);
        item.disabled = ['OUT_OF_STOCK', 'DISCONTINUED'].includes(option.availability) || option.purchaseAllowed === false;
        select.add(item);
      }
      select.value = p.selectedOptionId ?? '';
      select.addEventListener('change', () => {
        const args = { productId: request.productId, merchantId: request.merchantId };
        if (select.value) args.optionId = select.value;
        void loadDetail(args);
      });
      label.append(select); body.append(label);
    } else body.append(element('p', '확인된 옵션 정보가 없습니다.', 'sub'));
    if (!p.optionsComplete) body.append(element('p', '일부 옵션만 표시됩니다. 전체 옵션은 아직 확인되지 않았습니다.', 'sub'));
    body.append(element('p', `${availability(p.offer.availability)} · 수량 ${p.stockQuantity === null ? '미확인' : p.stockQuantity}`, 'sub'));
    const selected = p.variants.find(v => v.optionId === p.selectedOptionId);
    if (selected?.purchaseAllowed === false) body.append(element('p', '수집 시점에 구매 불가로 표시된 옵션입니다.', 'sub'));
    body.append(element('p', p.shipping.status === 'UNKNOWN' ? '배송 조건 · 배송비 미확인'
      : `${p.shipping.description} · 배송비 ${money(p.shipping.fee)}`, 'sub'));
    const explain = element('button', '채팅으로 자세히 알아보기', 'text-button');
    cart.appendAdd(actions, p, request);
    explain.addEventListener('click', async () => {
      explain.disabled = true;
      try {
        const prompt = detailPrompt(request);
        if (sdkReady) {
          const result = await timedCall(() => app.sendMessage({ role: 'user', content: [{ type: 'text', text: prompt }] }));
          if (result?.isError) throw new Error('채팅 요청이 거절되었습니다. 다시 시도해주세요.');
        } else if (typeof window.openai?.sendFollowUpMessage === 'function') {
          await timedCall(() => window.openai.sendFollowUpMessage({ prompt }));
        } else {
          const note = element('p', '로컬 미리보기에는 ChatGPT 채팅이 연결되지 않았습니다. 아래 요청을 ChatGPT에서 사용하세요.', 'sub');
          const text = element('textarea'); text.readOnly = true; text.value = prompt; text.rows = 5;
          text.style.width = '100%'; text.setAttribute('aria-label', 'ChatGPT 상세 요청');
          body.append(note, text); return;
        }
        explain.textContent = '채팅에 요청했어요';
      } catch (error) { body.append(element('p', error.message, 'sub')); explain.disabled = false; }
    });
    actions.append(explain);
    body.append(element('p', `확인 ${new Date(data.checkedAt).toLocaleTimeString('ko-KR', { hour12: false })}`, 'muted'));
  } catch (error) {
    if (id !== detailGeneration || !$('comparison').open) return;
    const retry = element('button', '상세 다시 시도'); retry.addEventListener('click', () => void loadDetail(request));
    body.replaceChildren(element('p', error.message), retry);
  }
}
function render(data) {
  current = data; lastQuery = filters(data.query);
  $('mode').textContent = '상품 탐색';
  $('notice').textContent = '가격 · 재고는 조회 시점 기준';
  $('checked-at').textContent = `확인 ${new Date(data.checkedAt).toLocaleTimeString('ko-KR', { hour12: false })}`;
  options('category', data.categories.map(x => [x, x]), '전체 상품군', data.query.category);
  options('merchantId', data.merchants.map(x => [x.merchantId, x.name]), '전체 판매처', data.query.merchantId);
  for (const key of formFields.filter(x => !['category', 'merchantId'].includes(x))) $(key).value = data.query[key] ?? '';
  $('sort').value = data.query.sort ?? 'RELEVANCE';
  $('search-filters').open = data.status === 'CLARIFY';
  const filterCount = ['category', 'merchantId', 'minPrice', 'maxPrice', 'brand'].filter(k => lastQuery[k] !== undefined).length
    + (data.query.sort && data.query.sort !== 'RELEVANCE' ? 1 : 0);
  $('filter-label').textContent = filterCount ? `필터 ${filterCount}개 적용` : '필터 · 상품군 · 판매처';
  $('page').textContent = `${data.query.page ?? 1} 페이지 · ${data.total.toLocaleString('ko-KR')}개`;
  const cards = $('cards'); cards.replaceChildren(); cards.dataset.count = String(data.products.length);
  for (const product of data.products) {
    const card = element('article', undefined, 'card');
    const picture = element('div', '이미지 없음', 'picture'); const image = safeImage(product.imageUrl);
    if (image) {
      const img = element('img'); img.src = image; img.alt = ''; img.loading = 'lazy'; img.referrerPolicy = 'no-referrer';
      img.addEventListener('error', () => picture.replaceChildren(element('span', '이미지 없음')), { once: true });
      picture.replaceChildren(img);
    }
    const body = element('div', undefined, 'card-body'); const name = element('h2', product.name, 'name'); name.title = product.name;
    const prices = product.offers.map(x => x.price).filter(x => x !== null);
    const compareButton = element('button');
    compareButton.append(element('span', `판매처 ${product.offers.length}곳 비교`), element('span', '→'));
    compareButton.addEventListener('click', () => compare(product));
    body.append(element('div', product.brand || product.category, 'muted'), name,
      element('div', prices.length ? `${money(Math.min(...prices))}부터` : '가격 미확인', 'price'), compareButton);
    card.append(picture, body); cards.append(card);
  }
  if (!data.products.length) cards.append(element('p', data.status === 'CLARIFY' ? '위에서 원하는 상품군을 선택해주세요.'
    : data.status === 'PARTIAL' ? '일부 판매처 응답이 없습니다. 다시 시도해주세요.' : '검색 결과가 없습니다. 조건을 바꿔보세요.', 'empty'));
  status(data.status === 'CLARIFY' ? '여러 상품군이 검색됐어요. 상품군을 먼저 골라주세요.'
    : data.status === 'PARTIAL' ? `일부 판매처 조회 실패 (${data.failedMerchants.length}곳) · 확인된 결과만 표시합니다.`
    : data.products.length ? '상품을 선택해 판매처별 가격을 확인하세요.' : '검색 결과 없음');
  $('retry').hidden = data.status !== 'PARTIAL';
  cards.scrollLeft = 0;
  requestAnimationFrame(updateCarousel);
  saveWidgetState();
  controls();
}
async function search(input) {
  if (busy) return;
  let query; try { query = filters(input); } catch (error) { status(error.message, true); return; }
  lastQuery = query; busy = true; const request = ++generation; controls(); status('상품을 조회하고 있습니다.'); $('retry').hidden = true;
  try {
    const result = searchData(await callTool('search_products', query));
    if (request === generation) render(result);
  } catch (error) {
    if (request === generation) { status(error.message, true); $('retry').hidden = false; }
  } finally { if (request === generation) { busy = false; controls(); } }
}
function formQuery() { return Object.fromEntries(formFields.map(key => [key, $(key).value])); }
function updateCarousel() {
  const rail = $('cards'); const cards = [...rail.querySelectorAll('.card')];
  const overflow = rail.scrollWidth > rail.clientWidth + 2;
  $('carousel-controls').hidden = !overflow;
  $('slide-previous').disabled = rail.scrollLeft <= 2;
  $('slide-next').disabled = rail.scrollLeft + rail.clientWidth >= rail.scrollWidth - 2;
  const start = rail.getBoundingClientRect().left;
  const distances = cards.map(card => Math.abs(card.getBoundingClientRect().left - start));
  const index = distances.indexOf(Math.min(...distances));
  $('slide-position').textContent = `${Math.max(0, index) + 1} / ${cards.length}`;
}
function slide(direction) {
  const rail = $('cards'); const first = rail.querySelector('.card');
  if (!first) return;
  const gap = Number.parseFloat(getComputedStyle(rail).columnGap) || 0;
  rail.scrollBy({ left: direction * (first.getBoundingClientRect().width + gap),
    behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
}
$('slide-previous').onclick = () => slide(-1);
$('slide-next').onclick = () => slide(1);
$('cards').addEventListener('scroll', updateCarousel, { passive: true });
new ResizeObserver(updateCarousel).observe($('cards'));
$('search-form').addEventListener('submit', event => { event.preventDefault(); void search(formQuery()); });
for (const id of ['category', 'merchantId', 'sort']) $(id).addEventListener('change', () => void search(formQuery()));
$('reset').addEventListener('click', () => void search({}));
$('retry').addEventListener('click', async () => { if (!sdkReady && !window.openai?.callTool) await connect(); await search(lastQuery); });
$('previous').addEventListener('click', () => void search({ ...lastQuery, page: lastQuery.page - 1 }));
$('next').addEventListener('click', () => void search({ ...lastQuery, page: lastQuery.page + 1 }));
$('close-dialog').addEventListener('click', () => $('comparison').close());
$('comparison').addEventListener('close', () => { ++detailGeneration; });
function hostResult(result) {
  try {
    const data = searchData(result);
    if (!acceptHostSearch(data, current, busy)) return;
    ++generation; busy = false; render(data);
  } catch { /* Ignore unrelated host tool results. */ }
}
function globals(event) {
  observeHostMode(event?.detail?.globals);
  const output = event?.detail?.globals?.toolOutput;
  if (output) hostResult(output);
}
window.addEventListener('openai:set_globals', globals);
async function connect() {
  if (connecting || sdkReady) return;
  connecting = true;
  try {
    if (app) await app.close().catch(() => {});
    app = new App({ name: 'Copenhagen Catalog', version: '2.0.0' },
      { availableDisplayModes: ['inline'] }, { autoResize: false });
    app.ontoolresult = hostResult;
    app.onhostcontextchanged = observeHostMode;
    await app.connect(undefined, { timeout: 5000 }); sdkReady = true;
    observeHostMode(app.getHostContext());
  } catch { /* Feature-detected native bridge supports existing ChatGPT mobile hosts. */ }
  finally { connecting = false; sizing.notify(); }
}
async function start() {
  // A restored host result can contain old merchant names and stock. Keep its query,
  // but revalidate against Core instead of treating the conversation snapshot as live data.
  let restored;
  try { if ([1, 2, 3].includes(window.openai?.widgetState?.version)) restored = filters(window.openai.widgetState.query); } catch { }
  if (window.openai?.toolOutput) hostResult(window.openai.toolOutput);
  if (typeof window.openai?.callTool !== 'function') await connect();
  else void connect();
  await search(restored ?? lastQuery);
  void cart.refresh().catch(() => {});
}
window.addEventListener('pageshow', () => {
  controls(); observeHostMode(window.openai);
  if (!sdkReady && !window.openai?.callTool) void connect();
});
void start();
