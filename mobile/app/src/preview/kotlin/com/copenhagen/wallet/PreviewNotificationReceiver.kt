package com.copenhagen.wallet

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.copenhagen.wallet.data.RequestTarget
import com.copenhagen.wallet.data.PayPush

/** Preview-only emitter: posts the real explicit PendingIntent without Firebase or live services. */
class PreviewNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (intent.hasExtra("eventId")) {
            val data = PayPush.fields.mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }.toMap()
            val posted = ApprovalNotifications.receive(context, data, PreviewApprovalActivity::class.java, "로컬 테스트 · ")
            if (isOrderedBroadcast) {
                resultCode = if (posted) Activity.RESULT_OK else Activity.RESULT_CANCELED
                resultData = if (posted) "Contract push posted" else "Duplicate, stale, invalid or disabled push ignored"
            }
            return
        }
        val target = ApprovalNotifications.target(intent) ?: return
        val posted = ApprovalNotifications.post(context, target, PreviewApprovalActivity::class.java,
            title = if (target.kind == RequestTarget.Kind.PURCHASE) "로컬 테스트 · 배송지 선택 요청" else "로컬 테스트 · 결제 승인 요청")
        if (isOrderedBroadcast) {
            resultCode = if (posted) Activity.RESULT_OK else Activity.RESULT_CANCELED
            resultData = if (posted) "Local fixture notification posted" else "Preview notification permission unavailable"
        }
    }

    companion object { const val ACTION = "com.copenhagen.wallet.preview.NOTIFY" }
}
