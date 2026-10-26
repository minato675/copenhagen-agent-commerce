package com.copenhagen.wallet.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalMoneyTest {
    @Test fun krwRetainsExistingKoreanWholeWonFormatting() {
        assertEquals("64,800원", approvalMoney(64800, "KRW"))
        assertEquals("0원", approvalMoney(0, "KRW"))
    }
    @Test fun eachCurrencyUsesItsOwnMinorUnitExponent() {
        assertEquals("648.00 USD", approvalMoney(64800, "USD"))
        assertEquals("64,800 JPY", approvalMoney(64800, "JPY"))
        assertEquals("64.800 KWD", approvalMoney(64800, "KWD"))
        assertEquals("0.01 USD", approvalMoney(1, "USD"))
        assertEquals("-0.001 KWD", approvalMoney(-1, "KWD"))
    }
    @Test fun longAmountsNeverConvertThroughFloatingPointOrLoseMinorUnits() {
        assertEquals("92,233,720,368,547,758.07 USD", approvalMoney(Long.MAX_VALUE, "USD"))
        assertEquals("-9,223,372,036,854,775.808 KWD", approvalMoney(Long.MIN_VALUE, "KWD"))
    }
    @Test fun unknownCurrencyNeverInventsFractionalMetadata() {
        assertEquals("64,800 UNKNOWN (최소 단위)", approvalMoney(64800, "UNKNOWN"))
        assertEquals("64,800 XXX (최소 단위)", approvalMoney(64800, "XXX"))
    }
}
