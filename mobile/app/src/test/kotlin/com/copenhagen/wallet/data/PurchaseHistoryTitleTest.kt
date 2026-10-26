package com.copenhagen.wallet.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PurchaseHistoryTitleTest {
    private val purchaseId = "11111111-1111-4111-8111-111111111111"
    private val firstApprovalId = "22222222-2222-4222-8222-222222222222"
    private val secondApprovalId = "33333333-3333-4333-8333-333333333333"
    private fun checkout(merchant: String, approval: String) =
        PurchaseCheckout(merchant, approval, "COMPLETED", 900, "KRW")
    private fun purchase(vararg rows: PurchaseCheckout) = Purchase(purchaseId, "COMPLETED", purchaseId,
        1, "2099-01-01T00:00:00Z", "SHOW_RESULT", merchantCheckouts = rows.toList())
    private fun approval(id: String, name: String, count: Int = 1) = Approval(id,
        merchantName = name, amountMinor = 900L * count, currency = "KRW", status = "APPROVED",
        expiresAt = "2099-01-01T00:00:00Z", merchantCount = count)

    @Test fun oneSellerUsesOnlyItsName() {
        val value = purchase(checkout("store-a", firstApprovalId))
        assertEquals("코펜하겐 스토어", PurchaseHistoryEntry(value,
            mapOf(firstApprovalId to approval(firstApprovalId, "코펜하겐 스토어"))).title)
    }

    @Test fun groupedApprovalUsesPrimarySellerAndRemainingSellerCount() {
        val value = purchase(checkout("store-a", firstApprovalId), checkout("store-b", firstApprovalId),
            checkout("store-c", firstApprovalId))
        val listApproval = approval(firstApprovalId, "코펜하겐 스토어", 3)
        val detailApproval = listApproval.copy(merchantName = "", merchants = listOf(
            ApprovalMerchant("store-a", "코펜하겐 스토어", "checkout-a", "hash-a", listOf(ItemSummary("가방")), 900),
            ApprovalMerchant("store-b", "노르딕 문구점", "checkout-b", "hash-b", listOf(ItemSummary("노트")), 900),
            ApprovalMerchant("store-c", "서울 서점", "checkout-c", "hash-c", listOf(ItemSummary("책")), 900)))
        for (approval in listOf(listApproval, detailApproval))
            assertEquals("코펜하겐 스토어 외 2곳", PurchaseHistoryEntry(value, mapOf(firstApprovalId to approval)).title)
    }

    @Test fun separateApprovalsStillUseOnePurchaseTitle() {
        val value = purchase(checkout("store-a", firstApprovalId), checkout("store-b", secondApprovalId))
        val names = mapOf(firstApprovalId to approval(firstApprovalId, "코펜하겐 스토어"),
            secondApprovalId to approval(secondApprovalId, "노르딕 문구점"))
        assertEquals("코펜하겐 스토어 외 1곳", PurchaseHistoryEntry(value, names).title)
        assertEquals("노르딕 문구점 외 1곳", PurchaseHistoryEntry(value, names - firstApprovalId).title)
    }

    @Test fun missingOrBlankNamesKeepAnExplicitSellerCount() {
        val one = purchase(checkout("store-a", firstApprovalId))
        val two = purchase(checkout("store-a", firstApprovalId), checkout("store-b", firstApprovalId))
        assertEquals("판매처 1", PurchaseHistoryEntry(one).title)
        assertEquals("판매처 2곳 구매", PurchaseHistoryEntry(two).title)
        assertEquals("판매처 2곳 구매", PurchaseHistoryEntry(two,
            mapOf(firstApprovalId to approval(firstApprovalId, " ", 2))).title)
        assertEquals("구매 요청", PurchaseHistoryEntry(purchase()).title)
    }
}
