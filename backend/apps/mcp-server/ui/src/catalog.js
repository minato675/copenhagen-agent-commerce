const images = new Set(['3p-image.kurly.com', 'cdn.daisomall.co.kr', 'img-cf.kurly.com',
  'img.danuri.io', 'product-image.kurly.com']);
export const publicId = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_-]{0,95}$/.test(value);
export const validPrice = value => value === null || (Number.isSafeInteger(value) && value >= 0 && value <= 1e12);
export const money = value => value === null ? '가격 미확인' : `${value.toLocaleString('ko-KR')}원`;
export const availability = value => ({ IN_STOCK: '판매 중', OUT_OF_STOCK: '품절',
  DISCONTINUED: '판매 종료', UNKNOWN: '재고 미확인' })[value] ?? '재고 미확인';
export function safeImage(value) {
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password
    && images.has(url.hostname) ? url.href : ''; } catch { return ''; }
}
export function filters(input = {}) {
  const result = { page: 1, pageSize: 4, sort: 'RELEVANCE' };
  for (const [key, max] of [['query', 120], ['category', 160], ['brand', 120], ['merchantId', 96]]) {
    const value = String(input[key] ?? '').trim();
    if (value.length > max) throw new Error('검색 조건이 너무 깁니다.');
    if (value) result[key] = value;
  }
  if (result.merchantId && !publicId(result.merchantId)) throw new Error('판매처를 다시 선택해주세요.');
  for (const key of ['minPrice', 'maxPrice']) {
    if (input[key] !== undefined && input[key] !== null && input[key] !== '') {
      const value = Number(input[key]);
      if (!validPrice(value)) throw new Error('가격은 0 이상의 정수로 입력해주세요.');
      result[key] = value;
    }
  }
  if (result.minPrice !== undefined && result.maxPrice !== undefined && result.minPrice > result.maxPrice) {
    throw new Error('최소 가격이 최대 가격보다 큽니다.');
  }
  if (input.page !== undefined) {
    if (!Number.isInteger(input.page) || input.page < 1 || input.page > 10000) throw new Error('잘못된 페이지입니다.');
    result.page = input.page;
  }
  if (input.sort !== undefined) {
    if (!['RELEVANCE', 'PRICE_ASC', 'PRICE_DESC'].includes(input.sort)) throw new Error('잘못된 정렬입니다.');
    result.sort = input.sort;
  }
  return result;
}
export function searchData(result) {
  const source = result?.structuredContent ?? result;
  const data = Array.isArray(source?.products) ? { ...source, products: source.products.map(p => ({ ...p,
    offers: Array.isArray(p?.offers) ? p.offers.map(unknownPrice) : p?.offers })) } : source;
  if (result?.isError || data?.kind !== 'catalog.search' || !['QA', 'LIVE'].includes(data.mode)
      || !['OK', 'EMPTY', 'PARTIAL', 'CLARIFY'].includes(data.status)
      || !Array.isArray(data.products) || data.products.length > 20
      || !Array.isArray(data.categories) || data.categories.length > 200
      || !data.categories.every(x => typeof x === 'string' && x.length <= 160)
      || !Array.isArray(data.merchants) || data.merchants.length > 10
      || !data.merchants.every(x => publicId(x.merchantId) && typeof x.name === 'string')
      || !Array.isArray(data.failedMerchants) || !data.failedMerchants.every(publicId)
      || !Number.isInteger(data.total) || data.total < 0 || typeof data.hasNext !== 'boolean'
      || !Number.isFinite(Date.parse(data.checkedAt))) throw new Error('상품 응답을 확인할 수 없습니다.');
  filters(data.query);
  for (const p of data.products) {
    if (!publicId(p.productId) || typeof p.name !== 'string' || p.name.length > 240 || !p.name.trim()
        || typeof p.brand !== 'string' || typeof p.category !== 'string' || typeof p.imageUrl !== 'string'
        || !Array.isArray(p.offers) || !p.offers.length || p.offers.length > 10
        || !p.offers.every(o => publicId(o.merchantId) && typeof o.merchantName === 'string'
          && validPrice(o.price) && o.currency === 'KRW'
          && ['IN_STOCK', 'OUT_OF_STOCK', 'DISCONTINUED', 'UNKNOWN'].includes(o.availability))) {
      throw new Error('상품 응답을 확인할 수 없습니다.');
    }
  }
  return data;
}
export async function timedCall(call, timeout = 10000) {
  let timer;
  try {
    return await Promise.race([Promise.resolve().then(call), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('응답이 없습니다. 다시 시도해주세요.')), timeout);
    })]);
  } finally { clearTimeout(timer); }
}

export function acceptHostSearch(data, current, busy) {
  return !busy && (!current || Date.parse(data.checkedAt) > Date.parse(current.checkedAt));
}

export function detailData(result, request) {
  const source = result?.structuredContent ?? result;
  // Host bridges may omit nullable values. Keep missing amounts unknown, never zero.
  const product = source?.product;
  const data = source && { ...source, product: product ? { ...product,
    offer: unknownPrice(product.offer),
    variants: Array.isArray(product.variants) ? product.variants.map(unknownPrice) : product.variants,
    stockQuantity: product.stockQuantity ?? null,
    shipping: product.shipping && { ...product.shipping, fee: product.shipping.fee ?? null }
  } : product ?? null };
  if (result?.isError || data?.kind !== 'catalog.detail' || !['QA', 'LIVE'].includes(data.mode)
      || !['FOUND', 'NOT_FOUND'].includes(data.status) || !Number.isFinite(Date.parse(data.checkedAt))
      || data.request?.productId !== request.productId || data.request?.merchantId !== request.merchantId
      || (data.request?.optionId ?? '') !== (request.optionId ?? '')) throw new Error('상품 상세 응답이 일치하지 않습니다.');
  if (data.status === 'NOT_FOUND') { if (data.product !== null) throw new Error('잘못된 상세 응답입니다.'); return data; }
  const p = data.product;
  if (!p || p.productId !== request.productId || p.offer?.merchantId !== request.merchantId
      || typeof p.name !== 'string' || !validPrice(p.offer.price) || p.offer.currency !== 'KRW'
      || !['IN_STOCK', 'OUT_OF_STOCK', 'DISCONTINUED', 'UNKNOWN'].includes(p.offer.availability)
      || !Array.isArray(p.variants) || p.variants.length > 50
      || !p.variants.every(v => publicId(v.optionId) && typeof v.label === 'string' && validPrice(v.price)
        && ['IN_STOCK', 'OUT_OF_STOCK', 'DISCONTINUED', 'UNKNOWN'].includes(v.availability))
      || !['NONE', 'REQUIRED', 'VALID', 'INVALID', 'UNKNOWN'].includes(p.selectionStatus)
      || !['KNOWN', 'UNKNOWN'].includes(p.shipping?.status) || !validPrice(p.shipping.fee)
      || (p.stockQuantity !== null && (!Number.isSafeInteger(p.stockQuantity) || p.stockQuantity < 0))) {
    throw new Error('상품 상세 정보를 확인할 수 없습니다.');
  }
  return data;
}
function unknownPrice(value) { return value && { ...value, price: value.price ?? null }; }
export function detailPrompt(request) {
  if (!publicId(request.productId) || !publicId(request.merchantId)
      || (request.optionId !== undefined && !publicId(request.optionId))) throw new Error('잘못된 상품 참조입니다.');
  const refs = { productId: request.productId, merchantId: request.merchantId };
  if (request.optionId) refs.optionId = request.optionId;
  return `코펜하겐 get_product_detail로 ${JSON.stringify(refs)} 상품의 상세 설명과 확인된 옵션·재고·배송 조건을 알려줘. 미확인 정보는 구분하고 새 상품 UI는 만들지 말아줘.`;
}
