import test from 'node:test';
import assert from 'node:assert/strict';
import { resultDisposition } from './card-result.js';
const search = data => ({ structuredContent: { kind: 'catalog.search', data } });
test('empty and failed results are collapsed, including authentication failures', () => {
  assert.equal(resultDisposition(search({ products: [], relatedSearches: [] }), 'search'), 'empty');
  assert.equal(resultDisposition({ isError: true, content: [] }, 'detail'), 'failed');
  assert.equal(resultDisposition({ error: { code: 'AUTH_REQUIRED' } }, 'cart'), 'failed');
});
test('partial or related successes and empty carts remain visible', () => {
  assert.equal(resultDisposition(search({ products: [{}], failedMerchants: ['a'] }), 'search'), 'render');
  assert.equal(resultDisposition(search({ products: [], relatedSearches: [{ products: [{}] }] }), 'search'), 'render');
  assert.equal(resultDisposition({ kind: 'cart.view', data: { items: [] } }, 'cart'), 'render');
});

test('batch cards render successful groups but collapse all empty or failed groups', () => {
  assert.equal(resultDisposition(search({ searchGroups: [{ error: 'CORE_UNAVAILABLE' },
    { data: { products: [{}] } }] }), 'search'), 'render');
  assert.equal(resultDisposition(search({ searchGroups: [{ error: 'CORE_UNAVAILABLE' },
    { data: { products: [] } }] }), 'search'), 'empty');
});
