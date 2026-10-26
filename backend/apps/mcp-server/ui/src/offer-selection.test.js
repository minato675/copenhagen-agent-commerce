import test from 'node:test';
import assert from 'node:assert/strict';
import { sortedOffers } from './offer-selection.js';
import { actionLabel } from './commerce-actions.js';

test('lowest known price leads without treating missing prices as free or mutating input', () => {
  const offers = [{ merchantId: 'unknown', priceAmount: null }, { merchantId: 'high', priceAmount: 1500 },
    { merchantId: 'low', priceAmount: 1000 }, { merchantId: 'tie', priceAmount: 1000 }];
  assert.deepEqual(sortedOffers(offers).map(o => o.merchantId), ['low', 'tie', 'high', 'unknown']);
  assert.equal(offers[0].merchantId, 'unknown');
  assert.equal(sortedOffers([{ priceAmount: null }, { priceAmount: 0 }])[0].priceAmount, 0);
});

test('visible action text contains no raw tool name or arguments', () => {
  assert.equal(actionLabel('get_product_detail'), '선택한 상품의 옵션을 보여주세요.');
  assert.equal(actionLabel('add_cart_item'), '선택한 상품을 장바구니에 담아주세요.');
  assert.throws(() => actionLabel('unrecognized'));
});
