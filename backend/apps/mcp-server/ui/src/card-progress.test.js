import test from 'node:test';
import assert from 'node:assert/strict';
import { createCardProgress, createActionGate, awaitDelivery } from './card-progress.js';

test('late delay callbacks and completion cannot overwrite a newer request', () => {
  const states = [], callbacks = [];
  const p = createCardProgress(s => states.push(s), { schedule: fn => { callbacks.push(fn); return callbacks.length; }, cancel() {} });
  const old = p.begin('loading', 'old');
  const current = p.begin('sending', 'new');
  callbacks[0]();
  assert.equal(p.finish(old, 'complete', 'stale'), false);
  assert.deepEqual(states.at(-1), { phase: 'sending', text: 'new' });
  assert.equal(p.finish(current, 'delivered', '전달됨'), true);
  callbacks[1]();
  assert.equal(states.at(-1).phase, 'delivered');
});

test('authorization delay never appears as server progress; disposal silences timers', () => {
  const states = []; let delayed;
  const p = createCardProgress(s => states.push(s), { schedule: fn => { delayed = fn; return 1; }, cancel() {} });
  p.begin('authorization', '권한 확인', '동의 창 확인'); delayed();
  assert.deepEqual(states.at(-1), { phase: 'authorization', text: '동의 창 확인' });
  p.dispose(); const count = states.length; delayed(); p.show('complete', 'late');
  assert.equal(states.length, count);
});

test('action gate blocks concurrent mutations during permission preparation and unlocks on error', async () => {
  const gate = createActionGate(); let reject;
  const first = gate.run(() => new Promise((_, fail) => { reject = fail; }));
  assert.equal(await gate.run(() => { throw Error('must not execute'); }), false);
  reject(Error('permission denied')); await assert.rejects(first, /permission denied/);
  assert.equal(gate.active, false);
  assert.equal(await gate.run(async () => {}), true);
});

test('delivery timeout does not report a late acknowledgement as completion', async () => {
  let resolve;
  const response = new Promise(r => { resolve = r; });
  await assert.rejects(awaitDelivery(() => response, 5), /전달 여부/);
  resolve({});
  assert.deepEqual(await awaitDelivery(() => Promise.resolve({})), {});
});
