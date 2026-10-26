import test from 'node:test';
import assert from 'node:assert/strict';
import { sendAction } from './commerce-actions.js';
const reference = '00000000-0000-4000-8000-000000000001';
test('selection arguments travel privately and reference stays in acknowledged model context', async () => {
  const intent = { tool: 'get_product_detail', args: { merchantId: 'merchant-c', merchantProductId: 'onion-1' } };
  let stored, context;
  const host = { callServerTool: async request => {
    assert.equal(request.name, 'prepare_widget_action'); stored = structuredClone(request.arguments);
    return { structuredContent: { kind: 'action.reference', data: { reference } } };
  }, updateModelContext: async value => { context = value; }, sendMessage: async message => {
    const text = message.content[0].text;
    assert.match(text, /선택한 상품의 옵션/); assert.ok(!text.includes(reference)); assert.equal(context.structuredContent.copenhagenAction.reference, reference);
    for (const hidden of ['merchantId', 'onion-1', 'get_product_detail', 'idempotencyKey', '{']) assert.ok(!text.includes(hidden));
  } };
  await sendAction(host, intent, fn => fn());
  assert.deepEqual(stored, { tool: intent.tool, arguments: intent.args, intent: '선택한 상품의 옵션을 보여주세요.' });
});
test('failed or malformed preparation sends no ambiguous chat message', async () => {
  for (const response of [{ isError: true }, { structuredContent: { kind: 'action.reference', data: { reference: 'invalid' } } }]) {
    await assert.rejects(sendAction({ callServerTool: async () => response,
      sendMessage: () => assert.fail('must not send') }, { tool: 'get_cart', args: {} }, fn => fn()));
  }
});
test('uncertain send never automatically retries or changes mutation key', async () => {
  let calls = 0;
  const intent = { tool: 'add_cart_item', args: { idempotencyKey: reference, quantity: 2 } };
  await assert.rejects(sendAction({ callServerTool: async request => {
    assert.deepEqual(request.arguments.arguments, intent.args);
    return { structuredContent: { kind: 'action.reference', data: { reference } } };
  }, updateModelContext: async () => {}, sendMessage: async () => { calls++; throw Error('timeout'); } }, intent, fn => fn()), /timeout/);
  assert.equal(calls, 1); assert.equal(intent.args.idempotencyKey, reference);
});


test('context delivery failure never sends an ambiguous message', async () => {
  await assert.rejects(sendAction({
    callServerTool: async () => ({ structuredContent: { kind: 'action.reference', data: { reference } } }),
    updateModelContext: async () => { throw Error('context unavailable'); },
    sendMessage: () => assert.fail('must not send'),
  }, { tool: 'get_cart', args: {} }, fn => fn()), /context unavailable/);
});

test('natural language preserves the selected product and quantity without ids', async () => {
  const { actionRequest } = await import('./commerce-actions.js');
  const text = actionRequest({ tool: 'update_cart_item', label: '통밀식빵의 수량을 2개로 변경해주세요.' }, reference).content[0].text;
  assert.equal(text, '통밀식빵의 수량을 2개로 변경해주세요.');
  assert.ok(!text.includes(reference));
});

test('missing model context bridge still delivers the exact intent stored on server', async () => {
  let stored, sent;
  await sendAction({ callServerTool: async request => {
    stored = request.arguments.intent;
    return { structuredContent: { kind: 'action.reference', data: { reference } } };
  }, sendMessage: async message => { sent = message.content[0].text; } },
  { tool: 'apply_cart_changes', args: {}, label: '빵 (상점; 기본 옵션) 최종 수량 3개' }, fn => fn());
  assert.equal(stored, sent);
  assert.doesNotMatch(sent, /reference|idempotencyKey/);
});
