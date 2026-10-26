package com.copenhagen.wallet.data

import org.junit.Assert.*
import org.junit.Test

class ProductImageContractTest {
    @Test fun oldAndImageEnabledSnapshotsBothDecode() {
        val old = json.decodeFromString<ItemSummary>("""{"name":"상품","quantity":2,"amountMinor":12000}""")
        val updated = json.decodeFromString<ItemSummary>("""{"name":"상품","quantity":2,"amountMinor":12000,"imageUrl":"https://images.example.com/item.jpg"}""")
        assertNull(old.imageUrl)
        assertEquals("https://images.example.com/item.jpg", updated.imageUrl)
        assertEquals(old, updated.copy(imageUrl = null))
    }

    @Test fun imageChangesDoNotChangeReviewedAgreementButItemAndMerchantChangesDo() {
        val item = ItemSummary("상품", "기본", 2, 12000)
        val original = Approval("approval", "가게", "checkout", 12000, "KRW", "PENDING", expiresAt = "2099-01-01T00:00:00Z",
            checkoutHash = "hash", summary = listOf(item))
        assertTrue(original.sameReviewAs(original.copy(summary = listOf(item.copy(imageUrl = "https://images.example.com/item.jpg")))))
        assertFalse(original.sameReviewAs(original.copy(summary = listOf(item.copy(quantity = 3)))))
        val grouped = original.copy(merchants = listOf(ApprovalMerchant("shop-a", "가게", "checkout", "hash", listOf(item), 12000)))
        assertTrue(grouped.sameReviewAs(grouped.copy(merchants = grouped.merchants.map { it.copy(summary = listOf(item.copy(imageUrl = "https://images.example.com/item.jpg"))) })))
        assertFalse(grouped.sameReviewAs(grouped.copy(merchants = grouped.merchants.map { it.copy(checkoutHash = "changed") })))
    }
}
