import test from 'node:test';
import assert from 'node:assert/strict';
import { cardNotice, sameCartVersion, cardContentHeight } from './card-notice.js';
test('pending delivery is not claimed as applied; stale state directs to latest cart', () => {
  assert.equal(cardNotice({ pending: false, stale: false }), null);
  assert.match(cardNotice({ pending: true }).text, /처리 결과를 확인/);
  assert.doesNotMatch(cardNotice({ pending: true }).text, /변경됐어요/);
  assert.match(cardNotice({ pending: true, stale: true }).title, /이전 장바구니/);
});
test('version preflight fails closed instead of rebasing an old selection', () => {
  assert.equal(sameCartVersion(2, 2), true);
  for (const value of [3, 1, undefined, '2', NaN]) assert.equal(sameCartVersion(2, value), false);
});
test('fractional content height leaves rounding allowance without viewport feedback', () => {
  assert.equal(cardContentHeight(521.67), 526);
  assert.equal(cardContentHeight(1200), 1204);
});
