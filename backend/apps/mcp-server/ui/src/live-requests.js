const reads = new Set(['search_products', 'get_product_detail', 'get_cart', 'get_purchase_status']);
export function requestTimeout(name) { return name === 'prepare_cart' ? 120000 : reads.has(name) ? 15000 : 30000; }
export function createReadCoalescer() {
  const inFlight = new Map();
  return (name, args, invoke) => {
    if (!reads.has(name)) return invoke();
    const key = JSON.stringify([name, Object.keys(args).sort().map(key => [key, args[key]])]);
    if (inFlight.has(key)) return inFlight.get(key);
    const promise = Promise.resolve().then(invoke).finally(() => inFlight.delete(key));
    inFlight.set(key, promise);
    return promise;
  };
}
export async function liveTimedCall(invoke, name, timeout = requestTimeout(name)) {
  let timer;
  try {
    return await Promise.race([Promise.resolve().then(invoke), new Promise((_, reject) => {
      timer = setTimeout(() => {
        const error = new Error(name === 'prepare_cart'
          ? '권한 확인 대기가 길어지고 있습니다. 동의 창을 확인한 뒤 같은 요청으로 이어서 처리해주세요.'
          : reads.has(name) ? '조회가 지연되고 있습니다. 다시 조회해주세요.'
            : '응답 대기 시간이 지났습니다. 서버 처리 결과는 미확정입니다. 같은 요청으로 확인해주세요.');
        error.code = 'UI_TIMEOUT'; error.outcome = 'UNKNOWN'; reject(error);
      }, timeout);
    })]);
  } finally { clearTimeout(timer); }
}
