import test from 'node:test';
import assert from 'node:assert/strict';
import { createHostSizing } from './host-sizing.js';

function fixture() {
  const reports = [], frames = new Map(); let next = 0;
  const context = { state: { mode: 'inline', confirmed: true, busy: false }, size: { width: 360, height: 540 },
    app: { sendSizeChanged: value => { reports.push(value); } } };
  const sizing = createHostSizing({ getApp: () => context.app, measure: () => context.size, getDisplayState: () => context.state,
    scheduleFrame: callback => { frames.set(++next, callback); return next; }, cancelFrame: id => frames.delete(id) });
  function flush() { const callbacks = [...frames.values()]; frames.clear(); for (const callback of callbacks) callback(); }
  return { context, sizing, reports, frames, flush };
}

test('inline resize is coalesced and unchanged positive dimensions are not repeated', () => {
  const f = fixture();
  f.sizing.notify(); f.sizing.notify(); f.sizing.notify();
  assert.equal(f.frames.size, 1);
  f.flush(); assert.deepEqual(f.reports, [{ width: 360, height: 540 }]);
  f.sizing.notify(); f.flush(); assert.equal(f.reports.length, 1);
  f.context.size = { width: 360.2, height: 541.4 };
  f.sizing.notify(); f.flush(); assert.deepEqual(f.reports.at(-1), { width: 361, height: 542 });
});

test('unknown mode, transition, fullscreen and pip do not report inline content height', () => {
  const f = fixture();
  for (const state of [{ mode: 'inline', confirmed: false }, { mode: 'inline', confirmed: true, busy: true },
    { mode: 'fullscreen', confirmed: true }, { mode: 'pip', confirmed: true }]) {
    f.context.state = state; f.sizing.notify(); f.flush();
  }
  assert.deepEqual(f.reports, []);
});

test('a queued inline report is canceled when fullscreen starts before animation frame', () => {
  const f = fixture(); f.sizing.notify();
  f.context.state.busy = true; f.sizing.notify();
  assert.equal(f.frames.size, 0); f.flush(); assert.equal(f.reports.length, 0);
  f.context.state = { mode: 'inline', confirmed: true, busy: false }; f.sizing.notify();
  f.context.state.mode = 'fullscreen'; f.flush(); assert.equal(f.reports.length, 0);
});

test('confirmed inline reentry reports its dimensions again even if unchanged', () => {
  const f = fixture(); f.sizing.notify(); f.flush();
  f.context.state.mode = 'fullscreen'; f.sizing.notify();
  f.context.state.mode = 'inline'; f.sizing.notify(); f.flush();
  assert.equal(f.reports.length, 2);
  assert.deepEqual(f.reports[0], f.reports[1]);
});

test('hidden or malformed measurements are never forwarded and visible recovery is reported', () => {
  const f = fixture(); f.sizing.notify(); f.flush();
  for (const size of [{ width: 0, height: 0 }, { width: 360, height: -1 }, { width: NaN, height: 20 },
    { width: 360, height: Infinity }, { width: '360', height: 540 }, null]) {
    f.context.size = size; f.sizing.notify(); f.flush();
  }
  assert.equal(f.reports.length, 1);
  f.context.size = { width: 360, height: 540 }; f.sizing.notify(); f.flush();
  assert.equal(f.reports.length, 2);
});

test('a newly connected bridge receives size and a rejected notification can recover', async () => {
  const f = fixture(); f.context.app = null; f.sizing.notify(); f.flush();
  let calls = 0;
  f.context.app = { sendSizeChanged: async () => { if (++calls === 1) throw new Error('disconnected'); } };
  f.sizing.notify(); f.flush(); await Promise.resolve();
  f.sizing.notify(); f.flush(); assert.equal(calls, 2);
  f.context.app = { sendSizeChanged: value => f.reports.push(value) };
  f.sizing.notify(); f.flush(); assert.equal(f.reports.length, 1);
});

test('dispose cancels pending reporting and ignores later resize events', () => {
  const f = fixture(); f.sizing.notify(); f.sizing.dispose();
  f.flush(); f.sizing.notify(); assert.equal(f.frames.size, 0); assert.deepEqual(f.reports, []);
});
