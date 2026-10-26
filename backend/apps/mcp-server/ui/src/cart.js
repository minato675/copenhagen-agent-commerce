import { publicId, validPrice } from './catalog.js';

const amount = x => x === null || (Number.isSafeInteger(x) && x >= 0 && x <= 9e12);
const optionId = x => typeof x === 'string' && /^[A-Za-z0-9:_-]{0,96}$/.test(x);
const version = x => Number.isSafeInteger(x) && x >= 0;
const fail = () => { throw new Error('장바구니 응답을 확인할 수 없습니다.'); };
export function cartSnapshot(source) {
  if (!source || !publicId(source.cartId) || !version(source.version) || !Array.isArray(source.lines)
      || source.lines.length > 100 || source.approvalState !== 'REVALIDATION_REQUIRED') fail();
  const ids = new Set(), selections = new Set();
  const lines = source.lines.map(item => {
    const line = { ...item, unitPrice: item.unitPrice ?? null, lineTotal: item.lineTotal ?? null };
    const key = JSON.stringify([line.merchantId, line.productId, line.optionId]);
    if (!publicId(line.lineId) || ids.has(line.lineId) || selections.has(key)
        || !publicId(line.productId) || !publicId(line.merchantId) || !optionId(line.optionId)
        || !Number.isInteger(line.quantity) || line.quantity < 1 || line.quantity > 99
        || !validPrice(line.unitPrice) || !amount(line.lineTotal) || !/^[A-Z]{3}$/.test(line.currency)
        || !['ESTIMATED', 'CONFIRMED', 'UNKNOWN'].includes(line.priceStatus)
        || (line.unitPrice === null) !== (line.priceStatus === 'UNKNOWN')
        || (line.unitPrice === null ? line.lineTotal !== null : line.lineTotal !== line.unitPrice * line.quantity)
        || !['name', 'merchantName', 'optionLabel', 'imageUrl'].every(k => typeof line[k] === 'string')) fail();
    ids.add(line.lineId); selections.add(key); return line;
  });
  return { ...source, lines };
}
function quotes(items, merchant) {
  if (!Array.isArray(items) || items.length > 100) fail();
  const keys = new Set();
  return items.map(item => {
    const q = { ...item };
    for (const field of ['itemSubtotal', 'discount', 'shipping', 'total']) q[field] ??= null;
    const key = `${merchant ? q.merchantId : ''}:${q.currency}`;
    if (!/^[A-Z]{3}$/.test(q.currency) || (merchant && !publicId(q.merchantId)) || keys.has(key)
        || !['UNKNOWN', 'FAILED', 'ESTIMATED', 'CONFIRMED'].includes(q.status)
        || !['itemSubtotal', 'discount', 'shipping', 'total'].every(k => amount(q[k]))) fail();
    const unknown = ['itemSubtotal', 'discount', 'shipping'].some(k => q[k] === null);
    if (q.status === 'FAILED') {
      if (['itemSubtotal', 'discount', 'shipping', 'total'].some(k => q[k] !== null)) fail();
    } else if ((q.status === 'UNKNOWN') !== unknown || (q.total === null) !== unknown
        || (!unknown && q.total !== q.itemSubtotal - q.discount + q.shipping)) fail();
    keys.add(key); return q;
  });
}
export function cartView(result) {
  const data = result?.structuredContent ?? result;
  if (result?.isError || data?.kind !== 'cart.view' || !['QA', 'LIVE'].includes(data.mode)
      || !['EMPTY', 'ESTIMATED', 'CONFIRMED', 'INCOMPLETE', 'PARTIAL', 'UNAVAILABLE'].includes(data.status)
      || !Number.isFinite(Date.parse(data.checkedAt))) fail();
  return { ...data, cart: cartSnapshot(data.cart), groups: quotes(data.groups, true), totals: quotes(data.totals, false) };
}
export function validPending(value) {
  if (!value || !['add_cart_item', 'update_cart_item', 'delete_cart_item'].includes(value.tool)) return false;
  const r = value.request;
  const add = value.tool === 'add_cart_item', remove = value.tool === 'delete_cart_item';
  const keys = add ? 'cartId,expectedVersion,idempotencyKey,merchantId,optionId,productId,quantity'
    : remove ? 'cartId,expectedVersion,idempotencyKey,lineId' : 'cartId,expectedVersion,idempotencyKey,lineId,optionId,quantity';
  return r && Object.keys(r).sort().join(',') === keys
    && publicId(r.cartId) && version(r.expectedVersion) && /^[A-Za-z0-9_-]{16,96}$/.test(r.idempotencyKey)
    && (add ? publicId(r.productId) && publicId(r.merchantId) : publicId(r.lineId))
    && (remove || (optionId(r.optionId) && Number.isInteger(r.quantity) && r.quantity >= 1 && r.quantity <= 99));
}
export function mutationResult(result, pending) {
  const data = result?.structuredContent ?? result;
  const action = { add_cart_item: 'ADD', update_cart_item: 'UPDATE', delete_cart_item: 'DELETE' }[pending.tool];
  if (result?.isError || data?.kind !== 'cart.mutation' || data.action !== action
      || !['QA', 'LIVE'].includes(data.mode) || !['APPLIED', 'REJECTED', 'CONFLICT', 'UNKNOWN'].includes(data.status)
      || !data.request || Object.keys(data.request).length !== Object.keys(pending.request).length
      || !Object.keys(pending.request).every(k => data.request[k] === pending.request[k])) fail();
  const cart = data.cart ? cartSnapshot(data.cart) : null;
  if ((data.status === 'APPLIED') !== (data.reason === 'NONE') || (cart && cart.cartId !== pending.request.cartId)
      || (!cart && data.status !== 'UNKNOWN')) fail();
  if (data.status === 'APPLIED') {
    const line = cart.lines.find(x => x.lineId === data.affectedLineId);
    if (cart.version !== pending.request.expectedVersion + 1) fail();
    if (action === 'DELETE') {
      if (line || data.affectedLineId !== pending.request.lineId) fail();
    } else {
      if (!line || line.optionId !== pending.request.optionId) fail();
      if (action === 'ADD' ? line.productId !== pending.request.productId || line.merchantId !== pending.request.merchantId
          || line.quantity < pending.request.quantity : line.lineId !== pending.request.lineId
          || line.quantity !== pending.request.quantity) fail();
    }
  }
  return { ...data, cart };
}
export function mutationSession(initial, save = () => {}) {
  let pending = validPending(initial) ? structuredClone(initial) : null, busy = false;
  return {
    get pending() { return pending ? structuredClone(pending) : null; },
    begin(tool, request) {
      if (pending) throw new Error('이전 변경 결과를 먼저 확인해주세요.');
      const next = { tool, request: structuredClone(request) };
      if (!validPending(next)) throw new Error('선택한 상품과 수량을 다시 확인해주세요.');
      pending = next; save(this.pending);
    },
    async run(call) {
      if (!pending || busy) throw new Error('이미 처리 중입니다.');
      busy = true;
      try {
        const data = mutationResult(await call(pending.tool, structuredClone(pending.request)), pending);
        if (data.status !== 'UNKNOWN') { pending = null; save(null); }
        return data;
      } finally { busy = false; }
    }
  };
}
export function currencyAmount(value, currency) {
  if (value === null) return '미확정';
  if (currency === 'KRW') return `${value.toLocaleString('ko-KR')}원`;
  try {
    const formatter = new Intl.NumberFormat('ko-KR', { style: 'currency', currency });
    const scale = 10 ** formatter.resolvedOptions().maximumFractionDigits;
    return formatter.format(value / scale);
  } catch { return `${value.toLocaleString('ko-KR')} ${currency} (최소 단위)`; }
}
