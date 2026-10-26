let counter = 0; const pending = new Map();
function rpc(method, params) { return new Promise((resolve, reject) => { const id = ++counter; pending.set(id, { resolve, reject }); parent.postMessage({ preview: true, id, method, params }, location.origin); }); }
export class App {
  async connect() {
    window.addEventListener('message', event => {
      if (event.source !== parent || event.origin !== location.origin || !event.data.preview) return;
      const m = event.data;
      if (m.method === 'result') this.ontoolresult?.(m.params);
      if (m.method === 'input') this.ontoolinput?.(m.params);
      if (m.method === 'cancelled') this.ontoolcancelled?.(m.params);
      if (m.method === 'theme') this.onhostcontextchanged?.(m.params);
      if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(m.error)) : p.resolve(m.result); }
    });
    await rpc('initialize', { view: document.body.dataset.view });
  }
  callServerTool(request) { return rpc('call', request); }
  sendMessage(message) { return rpc('message', message); }
  updateModelContext(context) { return rpc('context', context); }
  sendSizeChanged(size) { parent.postMessage({ preview: true, method: 'size', params: size }, location.origin); return Promise.resolve(); }
}
