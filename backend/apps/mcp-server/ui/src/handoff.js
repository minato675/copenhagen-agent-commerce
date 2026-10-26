const identifier = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
export function handoffRequest(value) {
  if (!value || Object.keys(value).sort().join(',') !== 'cartId,expectedVersion,idempotencyKey'
      || typeof value.cartId !== 'string' || !identifier.test(value.cartId)
      || !Number.isSafeInteger(value.expectedVersion) || value.expectedVersion < 0
      || typeof value.idempotencyKey !== 'string' || !/^[A-Za-z0-9_-]{16,96}$/.test(value.idempotencyKey)) {
    throw new Error('인계 요청을 다시 확인해주세요.');
  }
  return { ...value };
}
export function handoffData(result, request, base, now = Date.now()) {
  const data = result?.structuredContent ?? result;
  const echo = handoffRequest(data?.request);
  const combinations = { READY: ['NONE'], REJECTED: ['EMPTY_CART', 'MULTIPLE_MERCHANTS', 'UNAVAILABLE'],
    CONFLICT: ['VERSION_CONFLICT', 'KEY_CONFLICT'], UNKNOWN: ['OUTCOME_UNKNOWN'], EXPIRED: ['EXPIRED'] };
  if (result?.isError || data.kind !== 'purchase.handoff' || !['QA', 'LIVE'].includes(data.mode)
      || !Object.keys(echo).every(key => echo[key] === request[key])
      || !combinations[data.status]?.includes(data.reason) || !Number.isFinite(Date.parse(data.checkedAt))
      || data.nextAction !== (data.status === 'READY' ? 'OPEN_APP' : data.status === 'UNKNOWN' ? 'RETRY_SAME_REQUEST' : 'REVIEW_CART')) {
    throw new Error('인계 응답을 확인하지 못했습니다. 같은 요청으로 다시 확인해주세요.');
  }
  if (data.status === 'READY') {
    const url = new URL(base);
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.port
        || typeof data.purchaseRef !== 'string' || !identifier.test(data.purchaseRef)
        || data.appLink !== `${base}#${data.purchaseRef}`
        || !Number.isFinite(Date.parse(data.expiresAt)) || Date.parse(data.expiresAt) <= Date.parse(data.checkedAt)) {
      throw new Error('허용되지 않은 인계 링크입니다.');
    }
    if (Date.parse(data.expiresAt) <= now) return { ...data, status: 'EXPIRED', reason: 'EXPIRED',
      nextAction: 'REVIEW_CART', appLink: null, purchaseRef: null, expiresAt: null };
  } else if (data.appLink != null || data.purchaseRef != null || data.expiresAt != null) {
    throw new Error('인계 상태와 링크가 일치하지 않습니다.');
  }
  return data.status === 'READY' ? data : { ...data, appLink: null, purchaseRef: null, expiresAt: null };
}
export function handoffSession(restored, base, changed = () => {}) {
  let request, receipt, observedCart, running = false;
  try { if (restored) request = handoffRequest(restored); } catch { /* Restored state is untrusted. */ }
  function retireConfirmed() {
    // A confirmed handoff belongs to one cart snapshot. An unknown outcome must still
    // be resolved with its original request, even when the cart has since changed.
    if (receipt && observedCart && (request.cartId !== observedCart.cartId
        || observedCart.version > request.expectedVersion)) {
      request = undefined; receipt = undefined; return true;
    }
    return false;
  }
  return {
    get pending() { return request ? { ...request } : undefined; },
    get receipt() { return receipt; },
    get running() { return running; },
    get unresolved() { return !!request && !receipt; },
    begin(cart) {
      if (running || request) throw new Error('이전 인계 요청을 먼저 확인해주세요.');
      request = handoffRequest({ cartId: cart.cartId, expectedVersion: cart.version, idempotencyKey: crypto.randomUUID() });
      receipt = undefined; changed();
    },
    observe(cart) {
      if (!observedCart || observedCart.cartId !== cart.cartId || cart.version >= observedCart.version) {
        observedCart = { cartId: cart.cartId, version: cart.version };
      }
      if (retireConfirmed()) changed();
    },
    async run(callTool) {
      if (running || !request) return;
      running = true; receipt = undefined;
      try {
        const result = handoffData(await callTool('prepare_purchase_handoff', { ...request }), request, base);
        receipt = result.status === 'READY' ? result : undefined;
        if (!['READY', 'UNKNOWN'].includes(result.status)) request = undefined;
        retireConfirmed();
        changed(); return result;
      } finally { running = false; }
    },
    link(cart, now = Date.now()) {
      if (!receipt || request.cartId !== cart.cartId || request.expectedVersion !== cart.version
          || Date.parse(receipt.expiresAt) <= now) throw new Error('장바구니 변경 또는 만료 여부를 다시 확인해주세요.');
      return receipt.appLink;
    }
  };
}

export const handoffGuidance = {
  LOGIN_REQUIRED: '앱 로그인 후 같은 구매를 이어가세요. 로그인 결과와 구매 소유권은 앱에서 확인합니다.',
  NOT_INSTALLED: '앱이 열리지 않으면 설치 여부를 확인한 뒤 같은 링크를 다시 여세요. 설치 주소는 연동 후 제공됩니다.',
  CANCELLED: '앱 이동을 취소해도 결제되지 않습니다. 준비된 링크를 다시 열 수 있어요.',
  EXPIRED: '인계가 만료됐습니다. 장바구니를 확인한 뒤 새로 준비해주세요.'
};
