export function connectionData(result) {
  if (result?.isError) throw new Error('Server rejected the request');
  const data = result?.structuredContent ?? result;
  if (data?.service !== 'copenhagen-mcp' || data.status !== 'connected'
      || !/^[a-f0-9]{16}$/.test(data.uiVersion ?? '')
      || typeof data.requestId !== 'string' || !data.requestId
      || !Number.isFinite(Date.parse(data.checkedAt))) {
    throw new Error('Invalid connection response');
  }
  return data;
}

export async function callConnection(native, app, sdkReady, timeoutMs = 10000) {
  let timer;
  try {
    return connectionData(await Promise.race([
      Promise.resolve().then(() => {
        if (typeof native?.callTool === 'function') return native.callTool('check_connection', {});
        if (sdkReady) return app.callServerTool({ name: 'check_connection', arguments: {} });
        throw new Error('Host is not connected');
      }),
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Request timed out')), timeoutMs); }),
    ]));
  } finally {
    clearTimeout(timer);
  }
}
