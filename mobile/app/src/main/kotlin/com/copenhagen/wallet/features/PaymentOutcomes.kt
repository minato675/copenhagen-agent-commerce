package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/** Pay's purchase-level session stops at APPROVED. Core owns checkout completion. */
class PaymentOutcomes(private val request: suspend (String) -> JsonElement) {
    var approvalId by mutableStateOf<String?>(null); private set
    var purchase by mutableStateOf<Purchase?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    private var revision = 0L
    private val purchaseIds = mutableMapOf<String, String>()

    fun remember(value: Purchase) {
        if (!validId(value.purchaseRequestId)) return
        value.merchantCheckouts.mapNotNull { it.approvalId }.filter(::validId).forEach {
            purchaseIds[it] = value.purchaseRequestId
        }
    }

    suspend fun load(approval: Approval) {
        val read = ++revision
        if (approvalId != approval.id) { purchase = null; error = null }
        approvalId = approval.id
        try {
            val purchaseId = purchaseIds[approval.id] ?: run {
                val recent = request("/purchase-requests").read<List<Purchase>>()
                if (read != revision) return
                recent.singleOrNull { row -> row.merchantCheckouts.any { it.approvalId == approval.id } }
                    ?.purchaseRequestId?.takeIf(::validId)
            }
            if (purchaseId == null) {
                if (read == revision) error = "구매 결과를 찾지 못했어요. 주문 기록에서 확인해 주세요."
                return
            }
            val found = request("/purchase-requests/$purchaseId").read<Purchase>()
            if (read != revision) return
            val related = found.merchantCheckouts.filter { it.approvalId == approval.id }
            check(found.purchaseRequestId == purchaseId && related.isNotEmpty() &&
                related.all { it.currency == approval.currency && it.totalAmount >= 0 } &&
                related.fold(0L) { sum, checkout -> Math.addExact(sum, checkout.totalAmount) } == approval.amountMinor &&
                (approval.merchants.isEmpty() || related.map { it.merchantId }.toSet() == approval.merchants.map { it.merchantId }.toSet())) {
                "구매 결과가 승인 요청과 일치하지 않아요. 다시 확인해 주세요."
            }
            remember(found)
            purchase = found
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (expired: SessionExpired) { throw expired }
        catch (failure: Exception) {
            if (read == revision) error = failure.userMessage()
        }
    }

    fun statusFor(id: String): String? = purchase?.takeIf { approvalId == id }?.let { value ->
        val statuses = value.merchantCheckouts.filter { it.approvalId == id }.map { it.status }
        when {
            statuses.isEmpty() -> null
            statuses.all { it == "COMPLETED" } -> "COMPLETED"
            // Unknown or unfinished merchant states cannot become a final result.
            statuses.any { it !in setOf("COMPLETED", "FAILED", "CANCELLED", "REJECTED", "EXPIRED") } -> null
            statuses.any { it == "COMPLETED" } -> "PARTIALLY_COMPLETED"
            statuses.any { it == "FAILED" } -> "FAILED"
            statuses.any { it == "REJECTED" } -> "REJECTED"
            statuses.all { it == "EXPIRED" } -> "EXPIRED"
            else -> "CANCELLED"
        }
    }

    fun clear() { revision++; approvalId = null; purchase = null; error = null; purchaseIds.clear() }
}
