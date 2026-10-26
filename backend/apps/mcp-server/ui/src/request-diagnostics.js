// Keep only timings and protocol identifiers, never arguments, tokens or error messages.
const samples = [];
export async function measuredCall(tool, invoke, clock = () => performance.now()) {
  const start = clock();
  let result;
  try { result = await invoke(); return result; }
  finally {
    const meta = result?._meta?.['copenhagen/diagnostics'];
    samples.push({ tool, elapsedMs: Math.max(0, clock() - start),
      serverMillis: Number.isFinite(meta?.serverMillis) ? meta.serverMillis : null,
      requestId: typeof meta?.requestId === 'string' && /^[0-9a-f-]{36}$/.test(meta.requestId) ? meta.requestId : null,
      outcome: !result ? 'TRANSPORT_ERROR' : result.isError ? 'TOOL_ERROR' : 'SUCCESS' });
    if (samples.length > 200) samples.shift();
  }
}
export function requestDiagnostics() { return samples.map(sample => ({ ...sample })); }
export function latencySummary(values) {
  const sorted = values.filter(Number.isFinite).sort((a, b) => a - b);
  const percentile = p => sorted.length ? sorted[Math.ceil(sorted.length * p) - 1] : null;
  return { count: sorted.length, p50: percentile(.5), p95: percentile(.95) };
}
