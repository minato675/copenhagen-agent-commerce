import { handoffGuidance, handoffSession } from './handoff.js';

export function createHandoffUi(callTool, openLink, getCart, save, changed = () => {}) {
  const $ = id => document.getElementById(id);
  const enabled = document.body.dataset.handoffEnabled === 'true';
  const session = handoffSession(window.openai?.widgetState?.handoffPending,
    document.body.dataset.handoffLinkBase, () => { save(); changed(); });
  const reasons = { EMPTY_CART: '장바구니에 상품을 먼저 담아주세요.',
    MULTIPLE_MERCHANTS: '현재는 판매처 한 곳씩 인계할 수 있어요. 장바구니에서 판매처를 맞춰주세요.',
    UNAVAILABLE: '인계를 준비할 수 없습니다. 잠시 후 장바구니에서 다시 시도해주세요.',
    VERSION_CONFLICT: '장바구니가 변경됐습니다. 다시 조회한 뒤 진행해주세요.',
    KEY_CONFLICT: '요청 내용이 달라졌습니다. 장바구니를 다시 확인해주세요.',
    EXPIRED: handoffGuidance.EXPIRED, OUTCOME_UNKNOWN: '결과가 미확정입니다. 같은 요청으로 확인해주세요.' };
  const message = text => { $('handoff-status').textContent = text; };
  function controls() {
    $('handoff-retry').hidden = !session.pending;
    $('handoff-retry').disabled = session.running;
    $('handoff-open').hidden = !session.receipt;
    $('handoff-open').disabled = session.running;
    $('handoff-retry').textContent = session.receipt ? '인계 상태 다시 확인' : '같은 요청으로 확인';
    changed();
  }
  async function run() {
    if (session.running) return;
    $('handoff-expiry').textContent = '';
    message('구매 인계를 준비하고 있습니다.');
    const work = session.run(callTool); controls();
    try {
      const data = await work;
      if (!data) return;
      if (data.status === 'READY' && session.receipt) {
        $('handoff-title').textContent = data.mode === 'QA' ? 'QA 구매 인계' : '앱에서 이어가기';
        $('handoff-open').textContent = data.mode === 'QA' ? 'QA 링크 열기' : '앱 열기';
        message(data.mode === 'QA' ? '인계 준비 완료 · 실제 앱 대신 OpenAI 안내 문서를 엽니다. 결제되지 않아요.'
          : '앱에서 배송지와 결제 수단을 확인해주세요. 아직 결제되지 않았어요.');
        $('handoff-expiry').textContent = `${new Date(data.expiresAt).toLocaleTimeString('ko-KR', { hour12: false })}까지 유효`;
      } else {
        message(data.status === 'READY' ? reasons.VERSION_CONFLICT : reasons[data.reason]);
        $('handoff-expiry').textContent = '';
      }
    } catch { message('인계 결과를 확인하지 못했습니다. 중복 생성을 막기 위해 같은 요청으로 확인해주세요.'); }
    finally { controls(); }
  }
  if (enabled) {
    $('handoff-close').onclick = () => $('handoff-dialog').close();
    $('handoff-back').onclick = () => { $('handoff-dialog').close(); $('cart-open').click(); };
    $('handoff-retry').onclick = () => void run();
    $('handoff-open').onclick = async () => {
      try {
        const link = session.link(getCart());
        $('handoff-open').disabled = true;
        await openLink(link);
        message('링크 열기를 요청했어요. 앱 이동·결제 완료 여부는 이 화면에서 확정하지 않습니다.');
      } catch (error) { message(error.message || '링크를 열지 못했습니다. 같은 링크로 다시 시도해주세요.'); }
      finally { controls(); }
    };
    for (const button of document.querySelectorAll('[data-handoff-help]')) {
      button.onclick = () => message(handoffGuidance[button.dataset.handoffHelp]);
    }
    // Browser-only fixture controls never claim to observe a real app's state.
    if (document.body.dataset.localPreview === 'true') $('handoff-scenarios').hidden = false;
    $('handoff-scenario').onchange = event => message(handoffGuidance[event.target.value] || 'Mock 안내를 선택해주세요.');
  }
  return {
    enabled, pending: () => session.pending, running: () => enabled && session.running,
    blocked: () => enabled && (session.running || session.unresolved),
    observe: cart => { if (enabled) { session.observe(cart); controls(); } },
    async recover(cart) {
      if (!enabled) return true;
      if (session.running) return false;
      session.observe(cart);
      // Reconcile only an existing request. Never create a handoff or open a link here.
      if (session.unresolved) await run();
      session.observe(cart); controls();
      return !session.running && !session.unresolved;
    },
    async start(cart) {
      if (!enabled || session.running) return;
      $('cart-dialog').close(); $('handoff-dialog').showModal();
      $('handoff-expiry').textContent = '';
      if (!session.pending) session.begin(cart);
      await run();
    }
  };
}
