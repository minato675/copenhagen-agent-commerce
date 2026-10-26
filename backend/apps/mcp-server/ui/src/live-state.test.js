import test from 'node:test';
import assert from 'node:assert/strict';
import { amount, payload, searchArguments, variantLabel, pendingRequest, restorablePending, failedPending, canDiscardPending } from './live-state.js';

test('전체 상품군은 categoryId를 생략하고 미확정 금액을 0으로 만들지 않는다', () => {
  assert.deepEqual(searchArguments({ query: '삼성', categoryId: '', minPrice: '', maxPrice: '' }), { pageSize: 4, query: '삼성' });
  assert.equal(amount(null), '미확정'); assert.equal(amount(0), '0원');
});
test('Core가 반환한 variant 식별자와 옵션 이름을 사용한다', () => {
  assert.equal(variantLabel({ optionGroups: [{ values: [{ id: 'blue', name: '블루' }, { id: '250', name: '250mm' }] }] },
    { optionValueIds: ['blue', '250'] }), '블루 · 250mm');
});
test('복구 요청의 UUID 키와 본문을 보존한다', () => {
  const pending = pendingRequest('update_cart_item', { cartVersion: 12, cartItemId: 'i1', merchantVariantId: 'v1', quantity: 2 });
  assert.deepEqual(restorablePending(JSON.parse(JSON.stringify(pending))), pending);
  assert.match(pending.args.idempotencyKey, /^[0-9a-f-]{36}$/);
});
test('성공 응답과 결과 불명 오류를 구분한다', () => {
  assert.equal(payload({ kind: 'cart.mutation', data: { outcome: 'APPLIED', cart: null } }, 'cart.mutation').outcome, 'APPLIED');
  assert.throws(() => payload({ isError: true, structuredContent: { error: { outcome: 'UNKNOWN', message: '결과 확인 필요' } } }, 'cart.mutation'),
    error => error.outcome === 'UNKNOWN');
});
test('인증 거절은 선택과 멱등키를 보존하고 실행 전 요청만 취소한다', () => {
  const request = pendingRequest('add_cart_item', { merchantVariantId: 'v1', quantity: 2 });
  let failure;
  try { payload({ error: { code: 'INSUFFICIENT_SCOPE', outcome: 'NOT_APPLIED' } }, 'cart.mutation'); }
  catch (error) { failure = error; }
  const pending = failedPending(request, failure);
  assert.equal(pending.phase, 'AUTH_REQUIRED');
  assert.deepEqual(pending.args, request.args);
  assert.equal(canDiscardPending(pending), true);
  assert.deepEqual(restorablePending(JSON.parse(JSON.stringify(pending))), pending);
});
test('이전 전송 결과 불명은 재인증 거절 후에도 취소나 새 키 생성을 허용하지 않는다', () => {
  const request = { ...pendingRequest('delete_cart_item', { cartItemId: 'i1' }), uncertain: true };
  const denied = failedPending(request, { authRequired: true, outcome: 'NOT_APPLIED' });
  assert.equal(canDiscardPending(denied), false);
  assert.equal(denied.args.idempotencyKey, request.args.idempotencyKey);
  assert.equal(failedPending(denied, { outcome: 'NOT_APPLIED' }).uncertain, true);
});
test('구형 저장 요청은 이미 전송됐을 수 있으므로 결과 불명으로 복원한다', () => {
  const request = pendingRequest('update_cart_item', { quantity: 3 });
  delete request.phase; delete request.uncertain;
  assert.equal(restorablePending(request).uncertain, true);
  assert.equal(canDiscardPending(restorablePending(request)), false);
});
