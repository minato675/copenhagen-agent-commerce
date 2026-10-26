export const amount = value => value === null || value === undefined ? '미확정' : `${value.toLocaleString('ko-KR')}원`;
export function payload(result, kind) {
  const value = result?.structuredContent ?? result;
  if (result?.isError || value?.error) {
    const error = new Error(value?.error?.message || '요청을 처리하지 못했습니다.');
    error.outcome = value?.error?.outcome ?? 'UNKNOWN';
    error.code = value?.error?.code;
    error.authRequired = ['AUTHENTICATION_REQUIRED', 'INVALID_TOKEN', 'ACCOUNT_NOT_CONNECTED', 'INSUFFICIENT_SCOPE'].includes(error.code);
    throw error;
  }
  if (value?.kind !== kind || !value.data || typeof value.data !== 'object') throw new Error('응답을 확인하지 못했습니다.');
  return value.data;
}
export function searchArguments(form) {
  const result = { pageSize: 4 };
  for (const key of ['query', 'categoryId', 'merchantId', 'brand', 'sort']) {
    if (form[key]) result[key] = form[key];
  }
  for (const key of ['minPrice', 'maxPrice']) {
    if (form[key] !== '' && form[key] !== undefined) {
      const value = Number(form[key]);
      if (!Number.isSafeInteger(value) || value < 0) throw new Error('가격은 0 이상의 정수로 입력해주세요.');
      result[key] = value;
    }
  }
  if (result.minPrice > result.maxPrice) throw new Error('최소 가격이 최대 가격보다 큽니다.');
  return result;
}
export function variantLabel(detail, variant) {
  const names = new Map((detail.optionGroups ?? []).flatMap(group => (group.values ?? []).map(v => [v.id, v.name])));
  return (variant.optionValueIds ?? []).map(id => names.get(id) ?? id).join(' · ') || '기본 옵션';
}
export function pendingRequest(tool, args, now = Date.now()) {
  return { tool, args: { ...args, idempotencyKey: crypto.randomUUID() }, createdAt: now, phase: 'NEW', uncertain: false };
}
export function restorablePending(value) {
  if (!value || !['add_cart_item', 'update_cart_item', 'delete_cart_item', 'prepare_purchase_handoff'].includes(value.tool)
      || !value.args || !/^[0-9a-f-]{36}$/.test(value.args.idempotencyKey) || !Number.isFinite(value.createdAt)) return null;
  // Old saved requests might already have reached Core. Never treat them as unsent drafts.
  return { ...value, phase: value.phase ?? 'UNKNOWN', uncertain: value.uncertain ?? true };
}
export function failedPending(request, error) {
  if (error.authRequired) return { ...request, phase: 'AUTH_REQUIRED' };
  if (error.outcome === 'NOT_APPLIED' && !request.uncertain) return null;
  return { ...request, phase: 'UNKNOWN', uncertain: true };
}
export function canDiscardPending(request) {
  return !!request && !request.uncertain && ['NEW', 'AUTH_REQUIRED', 'PREPARING'].includes(request.phase);
}
