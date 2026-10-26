export function cartEditState(original, draft, { busy = false, pending = false } = {}) {
  const quantity = Number(draft.quantity);
  const valid = String(draft.quantity).trim() !== '' && Number.isInteger(quantity) && quantity >= Math.max(1, draft.minQuantity ?? 1) && quantity <= Math.min(99, draft.maxQuantity ?? 99) && draft.purchasable !== false;
  const dirty = quantity !== original.quantity || draft.merchantVariantId !== original.merchantVariantId;
  return { valid, dirty, canApply: valid && dirty && !busy && !pending };
}

export function createCartDraft(items, saved = {}, persist = () => {}) {
  const edits = new Map(items.map(item => [item.cartItemId, { quantity: String(item.quantity), merchantVariantId: item.merchantVariantId, deleted: false, unitPriceAmount: item.unitPriceAmount ?? item.lineTotalAmount / item.quantity }]));
  for (const [id, edit] of edits) {
    const previous = saved[id];
    // Restore quantity/deletion only; option price and availability must be revalidated.
    if (previous) Object.assign(edit, { quantity: String(previous.quantity), deleted: previous.deleted === true });
  }
  const save = () => persist(Object.fromEntries([...edits].map(([id, edit]) => [id, { quantity: edit.quantity, deleted: edit.deleted }])));
  return {
    get: id => edits.get(id),
    quantity(id, value) { edits.get(id).quantity = value; save(); },
    option(id, variant, label) {
      Object.assign(edits.get(id), { merchantVariantId: variant.merchantVariantId,
        unitPriceAmount: variant.priceAmount, minQuantity: variant.minQuantity, maxQuantity: variant.maxQuantity,
        purchasable: variant.purchasable, optionLabel: label });
    },
    toggleDelete(id) { const edit = edits.get(id); edit.deleted = !edit.deleted; save(); },
    snapshot() {
      const changes = [], labels = [];
      let valid = true, subtotal = 0;
      for (const item of items) {
        const edit = edits.get(item.cartItemId), state = cartEditState(item, edit);
        if (edit.deleted) {
          changes.push({ operation: 'DELETE', cartItemId: item.cartItemId });
          labels.push(`${item.name} (${item.merchantName || '판매처 확인 필요'}; ${item.selectedOptions?.map(o => o.value).join(' · ') || '기본 옵션'}) 삭제`);
          continue;
        }
        valid &&= state.valid;
        subtotal += edit.unitPriceAmount * Number(edit.quantity);
        if (state.dirty) {
          changes.push({ operation: 'UPDATE', cartItemId: item.cartItemId, merchantVariantId: edit.merchantVariantId, quantity: Number(edit.quantity) });
          labels.push(`${item.name} (${item.merchantName || '판매처 확인 필요'}; ${edit.optionLabel || item.selectedOptions?.map(o => o.value).join(' · ') || '기본 옵션'}) 최종 수량 ${edit.quantity}개`);
        }
      }
      return { changes, valid: valid && changes.length <= 30, dirty: changes.length > 0,
        subtotal: valid && Number.isSafeInteger(subtotal) ? subtotal : null,
        label: `장바구니 변경사항을 한 번에 적용해주세요: ${labels.join(', ')}.` };
    },
  };
}
