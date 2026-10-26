// Only host-observed/client states; never estimates server completion percentages.
export function createCardProgress(render, { schedule = setTimeout, cancel = clearTimeout, delay = 8000 } = {}) {
  let generation = 0, timer, disposed = false;
  const publish = (phase, text) => { if (!disposed) render({ phase, text }); };
  const clear = () => { if (timer != null) cancel(timer); timer = null; };
  return {
    begin(phase, text, delayedText = '응답이 평소보다 늦어지고 있어요. 잠시 기다려주세요.') {
      clear(); const token = ++generation;
      publish(phase, text);
      timer = schedule(() => { if (token === generation) publish(phase === 'authorization' ? 'authorization' : 'delayed', delayedText); }, delay);
      return token;
    },
    finish(token, phase, text) {
      if (disposed || token !== generation) return false;
      clear(); generation++; publish(phase, text); return true;
    },
    show(phase, text) { clear(); generation++; publish(phase, text); },
    dispose() { disposed = true; clear(); generation++; },
  };
}

export function createActionGate() {
  let active = false;
  return {
    get active() { return active; },
    async run(action) {
      if (active) return false;
      active = true;
      try { await action(); return true; } finally { active = false; }
    },
  };
}

export async function awaitDelivery(invoke, timeout = 15000) {
  let timer;
  try {
    return await Promise.race([Promise.resolve().then(invoke), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('요청 전달 여부를 확인하지 못했어요. 대화의 후속 결과를 확인해주세요.')), timeout);
    })]);
  } finally { clearTimeout(timer); }
}
