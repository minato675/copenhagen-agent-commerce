export const terminalPurchaseStatuses = new Set(['COMPLETED', 'PARTIALLY_COMPLETED', 'REJECTED', 'CANCELLED', 'FAILED', 'EXPIRED']);
export function purchaseProgress(value) {
  const labels = {
    PENDING_ADDRESS: '앱에서 배송지를 등록하거나 선택해주세요. 같은 구매 요청을 이어갑니다.',
    CREATING_CHECKOUTS: '배송 가능 여부, 재고와 최종 금액을 확인하고 있습니다.',
    AWAITING_APPROVAL: '앱 알림을 눌러 최종 금액을 확인하고 결제를 승인해주세요.',
    COMPLETING: '승인을 확인했습니다. 결제 결과를 확인하고 있습니다.',
    COMPLETED: '모든 판매처의 결제가 완료되었습니다.',
    PARTIALLY_COMPLETED: '일부 판매처만 결제되었습니다. 판매처별 결과를 확인해주세요.',
    REJECTED: '앱에서 결제 승인을 거절했습니다.',
    CANCELLED: '구매가 취소되었습니다.',
    FAILED: '구매를 완료하지 못했습니다. 앱에서 실패 사유를 확인해주세요.',
    EXPIRED: '구매 요청 또는 결제 승인이 만료되었습니다.'
  };
  if (!value || !labels[value.status] || !Array.isArray(value.notifications)
      || !Array.isArray(value.merchantCheckouts)) throw new Error('구매 상태를 확인하지 못했습니다.');
  const failed = value.notifications.some(n => ['FAILED', 'EXHAUSTED', 'UNAVAILABLE'].includes(n.status));
  const pending = value.notifications.some(n => ['PENDING', 'RUNNING'].includes(n.status));
  return { text: labels[value.status] + (failed ? ' 알림 전송을 확인하지 못했습니다. 앱을 직접 열거나 알림을 재전송해주세요.'
    : pending ? ' 알림 전송을 기다리고 있습니다.' : ''),
    terminal: terminalPurchaseStatuses.has(value.status), failed };
}
