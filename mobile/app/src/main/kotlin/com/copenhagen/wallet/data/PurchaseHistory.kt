package com.copenhagen.wallet.data

enum class HistoryTab(val title: String) { PURCHASES("구매 기록"), APPROVALS("승인 기록"), ORDERS("주문·배송") }
enum class PurchaseHistoryFilter(val label: String) {
    ALL("전체"), IN_PROGRESS("진행 중"), COMPLETED("완료"), PARTIAL("일부 완료"), ATTENTION("미완료")
}

private val checkoutPending = setOf("CREATING", "AWAITING_APPROVAL", "COMPLETING")
private val checkoutUnpaid = setOf("FAILED", "CANCELLED", "REJECTED", "EXPIRED")

/** A completed amount comes only from Core checkout results, never Pay's APPROVED total. */
data class PurchaseAmounts(val currency: String, val requested: Long, val completed: Long,
    val processing: Long, val unpaid: Long, val unknown: Long)

data class PurchaseHistoryEntry(val purchase: Purchase, val approvals: Map<String, Approval> = emptyMap()) {
    val id get() = purchase.purchaseRequestId
    val checkouts get() = purchase.merchantCheckouts
    val approvalIds get() = checkouts.mapNotNull { it.approvalId?.takeIf(::validId) }.distinct()
    val status: String get() = when {
        checkouts.isEmpty() -> purchase.status.takeIf {
            it in setOf("PENDING_ADDRESS", "CREATING_CHECKOUTS", "FAILED", "CANCELLED", "REJECTED", "EXPIRED")
        } ?: "UNKNOWN"
        checkouts.any { it.status !in checkoutPending + checkoutUnpaid + "COMPLETED" } -> "UNKNOWN"
        checkouts.all { it.status == "COMPLETED" } -> "COMPLETED"
        checkouts.any { it.status == "COMPLETING" } -> "COMPLETING"
        checkouts.any { it.status == "CREATING" } -> "CREATING_CHECKOUTS"
        checkouts.any { it.status == "AWAITING_APPROVAL" } -> "AWAITING_APPROVAL"
        checkouts.any { it.status == "COMPLETED" } -> "PARTIALLY_COMPLETED"
        checkouts.any { it.status == "FAILED" } -> "FAILED"
        checkouts.any { it.status == "REJECTED" } -> "REJECTED"
        checkouts.all { it.status == "EXPIRED" } -> "EXPIRED"
        else -> "CANCELLED"
    }
    val inProgress get() = status in setOf("PENDING_ADDRESS", "CREATING_CHECKOUTS", "AWAITING_APPROVAL", "COMPLETING")
    val completedCount get() = checkouts.count { it.status == "COMPLETED" }
    val title: String get() {
        // Both grouped and older per-merchant approvals describe one purchase here. Count
        // Core's merchants, and use a name only from metadata already joined by its references.
        val name = approvalIds.firstNotNullOfOrNull { id ->
            approvals[id]?.let { approval ->
                approval.merchants.firstOrNull { it.merchantName.isNotBlank() }?.merchantName
                    ?: approval.merchantName.takeIf { it.isNotBlank() }
            }
        }
        if (name != null && checkouts.isNotEmpty())
            return if (checkouts.size == 1) name else "$name 외 ${checkouts.size - 1}곳"
        return when (checkouts.size) {
            0 -> "구매 요청"
            1 -> merchantName(checkouts.first())
            else -> "판매처 ${checkouts.size}곳 구매"
        }
    }
    fun merchant(checkout: PurchaseCheckout) = approvals[checkout.approvalId]?.merchants
        ?.singleOrNull { it.merchantId == checkout.merchantId }
    fun merchantName(checkout: PurchaseCheckout): String = merchant(checkout)?.merchantName?.takeIf { it.isNotBlank() }
        ?: approvals[checkout.approvalId]?.takeIf { approval ->
            checkouts.count { it.approvalId == approval.id } == 1
        }?.merchantName?.takeIf { it.isNotBlank() } ?: "판매처 ${checkouts.indexOf(checkout) + 1}"
    fun items(checkout: PurchaseCheckout) = merchant(checkout)?.summary
        ?: approvals[checkout.approvalId]?.takeIf { it.merchants.isEmpty() &&
            checkouts.count { row -> row.approvalId == it.id } == 1 }?.summary.orEmpty()
    val amounts get() = checkouts.groupBy { it.currency }.map { (currency, rows) ->
        fun sum(predicate: (PurchaseCheckout) -> Boolean) = rows.filter(predicate)
            .fold(0L) { sum, row -> Math.addExact(sum, row.totalAmount) }
        PurchaseAmounts(currency, sum { true }, sum { it.status == "COMPLETED" },
            sum { it.status in checkoutPending }, sum { it.status in checkoutUnpaid },
            sum { it.status !in checkoutPending + checkoutUnpaid + "COMPLETED" })
    }
    fun matches(filter: PurchaseHistoryFilter, query: String): Boolean {
        val inFilter = when (filter) {
            PurchaseHistoryFilter.ALL -> true
            PurchaseHistoryFilter.IN_PROGRESS -> inProgress
            PurchaseHistoryFilter.COMPLETED -> status == "COMPLETED"
            PurchaseHistoryFilter.PARTIAL -> status == "PARTIALLY_COMPLETED"
            PurchaseHistoryFilter.ATTENTION -> !inProgress && status !in setOf("COMPLETED", "PARTIALLY_COMPLETED")
        }
        val searchable = listOf(id, title) + checkouts.map { it.merchantId } +
            approvals.values.flatMap { listOf(it.displayName) + it.merchants.map { m -> m.merchantName } + it.displaySummary.map { item -> item.name } }
        return inFilter && searchable.any { it.contains(query.trim(), ignoreCase = true) }
    }
}

fun Purchase.requireHistoryRecord() {
    require(validId(purchaseRequestId) && merchantCheckouts.map { it.merchantId }.distinct().size == merchantCheckouts.size &&
        merchantCheckouts.all { it.merchantId.isNotBlank() && it.totalAmount >= 0 && it.currency.matches(Regex("[A-Z]{3}")) &&
            (it.approvalId == null || validId(it.approvalId)) }) { "구매 기록의 정보를 확인할 수 없어요." }
    // Check arithmetic before any screen can render a financial total.
    PurchaseHistoryEntry(this).amounts
}

/** Join on server references and exact financial terms; names or equal totals alone are insufficient. */
fun Approval.matchesCheckouts(rows: List<PurchaseCheckout>): Boolean = runCatching {
    rows.isNotEmpty() && rows.all { it.approvalId == id && it.currency == currency && it.totalAmount >= 0 } &&
        rows.map { it.merchantId }.distinct().size == rows.size &&
        rows.fold(0L) { sum, row -> Math.addExact(sum, row.totalAmount) } == amountMinor &&
        if (merchants.isNotEmpty()) {
            merchants.size == rows.size && rows.all { row -> merchants.singleOrNull {
                it.merchantId == row.merchantId && it.subtotalAmountMinor == row.totalAmount
            } != null }
        } else merchantCount == rows.size
}.getOrDefault(false)

fun checkoutStatusLabel(status: String) = when (status) {
    "CREATING" -> "결제 준비 중"
    "AWAITING_APPROVAL" -> "승인 대기"
    "COMPLETING" -> "결제 처리 중"
    "COMPLETED" -> "결제 완료"
    "FAILED" -> "결제 실패"
    "CANCELLED" -> "결제 취소"
    "REJECTED" -> "승인 거절"
    "EXPIRED" -> "요청 만료"
    else -> "상태 확인 필요"
}
