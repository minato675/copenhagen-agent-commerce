export function createHostSizing({ getApp, measure, getDisplayState,
  scheduleFrame = callback => requestAnimationFrame(callback), cancelFrame = id => cancelAnimationFrame(id) }) {
  let frame = null, last, eligible = false, disposed = false;
  const inline = () => {
    const state = getDisplayState();
    return state?.confirmed === true && state.mode === 'inline' && !state.busy;
  };
  function reset() {
    if (frame !== null) cancelFrame(frame);
    frame = null; last = undefined; eligible = false;
  }
  function notify() {
    if (disposed) return;
    if (!inline()) { reset(); return; }
    if (!eligible) last = undefined;
    eligible = true;
    if (frame !== null) return;
    frame = scheduleFrame(() => {
      frame = null;
      if (disposed || !inline()) { reset(); return; }
      const app = getApp();
      if (typeof app?.sendSizeChanged !== 'function') return;
      let size;
      try { size = measure(); } catch { last = undefined; return; }
      if (!Number.isFinite(size?.width) || !Number.isFinite(size?.height) || size.width <= 0 || size.height <= 0) {
        last = undefined; return;
      }
      const width = Math.ceil(size.width), height = Math.ceil(size.height);
      if (last?.app === app && last.width === width && last.height === height) return;
      const report = { app, width, height }; last = report;
      try {
        Promise.resolve(app.sendSizeChanged({ width, height })).catch(() => { if (last === report) last = undefined; });
      } catch { if (last === report) last = undefined; }
    });
  }
  function dispose() { disposed = true; reset(); }
  return { notify, dispose };
}
