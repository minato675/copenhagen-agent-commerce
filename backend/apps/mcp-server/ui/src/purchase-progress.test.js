import test from 'node:test';
import assert from 'node:assert/strict';
import { purchaseProgress } from './purchase-progress.js';

const state = (status, notifications = []) => ({ status, notifications, merchantCheckouts: [] });
test('pending and completing never claim payment success', () => {
  for (const status of ['PENDING_ADDRESS', 'CREATING_CHECKOUTS', 'AWAITING_APPROVAL', 'COMPLETING']) {
    assert.equal(purchaseProgress(state(status)).terminal, false);
    assert.doesNotMatch(purchaseProgress(state(status)).text, /결제가 완료/);
  }
});
test('reject, expire, partial and notification failure remain distinguishable', () => {
  assert.match(purchaseProgress(state('REJECTED')).text, /거절/);
  assert.match(purchaseProgress(state('EXPIRED')).text, /만료/);
  assert.match(purchaseProgress(state('PARTIALLY_COMPLETED')).text, /일부/);
  assert.match(purchaseProgress(state('AWAITING_APPROVAL', [{ status: 'EXHAUSTED' }])).text, /재전송/);
  assert.equal(purchaseProgress(state('COMPLETED')).terminal, true);
});
test('unknown status cannot become a successful checkout', () => {
  assert.throws(() => purchaseProgress(state('SUCCESS')));
});
