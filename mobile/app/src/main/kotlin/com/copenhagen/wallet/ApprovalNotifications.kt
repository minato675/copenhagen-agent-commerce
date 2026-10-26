package com.copenhagen.wallet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.copenhagen.wallet.data.validId
import com.copenhagen.wallet.data.RequestTarget
import com.copenhagen.wallet.data.PayPush
import com.copenhagen.wallet.features.PushDeliveryJournal

/** Notification taps carry identity only; the existing authenticated flow reads all terms. */
object ApprovalNotifications {
    const val CHANNEL = "approvals"
    const val APPROVAL_ID = "approvalId"
    const val PURCHASE_ID = "purchaseRequestId"
    const val PAYMENT_ID = "paymentId"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL, "결제 승인 요청", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("purchase_handoffs", "배송지 선택 요청", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("payment_results", "결제 결과", NotificationManager.IMPORTANCE_DEFAULT)))
    }

    /** Firebase and the isolated fixture use the same parsing, deduplication and posting path. */
    @Synchronized
    fun receive(context: Context, data: Map<String, String>,
        activity: Class<out ApprovalActivity> = ApprovalActivity::class.java, titlePrefix: String = ""): Boolean {
        val push = PayPush.fromPayload(data) ?: return false
        val prefs = context.getSharedPreferences("wallet.minimal", Context.MODE_PRIVATE)
        val journal = PushDeliveryJournal(prefs.getString("notificationDeliveryJournal", null))
        if (!journal.shouldPost(push)) return false
        if (!postNotification(context, push.target, activity, titlePrefix + push.title, push.body, push.channel)) return false
        journal.record(push)
        // Do not consume an event when OS permission/channel settings prevented its notification.
        prefs.edit().putString("notificationDeliveryJournal", journal.snapshot()).commit()
        return true
    }

    fun post(context: Context, id: String,
        activity: Class<out ApprovalActivity> = ApprovalActivity::class.java,
        title: String = "결제 승인 요청"): Boolean {
        if (!validId(id)) return false
        return post(context, RequestTarget(RequestTarget.Kind.APPROVAL, id), activity, title)
    }

    fun post(context: Context, target: RequestTarget,
        activity: Class<out ApprovalActivity> = ApprovalActivity::class.java,
        title: String = PayPush("legacy", target, "").title): Boolean {
        val push = PayPush("legacy", target, "")
        return postNotification(context, target, activity, title, push.body, push.channel)
    }

    private fun postNotification(context: Context, target: RequestTarget, activity: Class<out ApprovalActivity>,
        title: String, body: String, channel: String): Boolean {
        createChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val intent = entryIntent(context, activity, target)
            // Reuse an active approval host; never clear its in-flight operation or parent task.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        return try {
            manager.notify(if (target.kind == RequestTarget.Kind.APPROVAL) target.id else target.identity, 0, notification)
            if (target.kind == RequestTarget.Kind.PAYMENT_RESULT) manager.cancel(target.id, 0)
            true
        } catch (_: SecurityException) {
            false // Permission may change after the explicit check.
        }
    }

    fun entryIntent(context: Context, activity: Class<out ApprovalActivity>, target: RequestTarget) =
        Intent(context, activity).setData("copenhagen://${target.identity}".toUri())
            .putExtra(if (target.kind == RequestTarget.Kind.PURCHASE) PURCHASE_ID else APPROVAL_ID, target.id)
            .apply { target.paymentId?.let { putExtra(PAYMENT_ID, it) } }

    fun target(intent: Intent?): RequestTarget? {
        if (intent == null) return null
        if (intent.hasExtra(PAYMENT_ID)) {
            val payment = intent.getStringExtra(PAYMENT_ID)?.takeIf(::validId) ?: return null
            val approval = intent.getStringExtra(APPROVAL_ID)?.takeIf(::validId) ?: return null
            if (!intent.getStringExtra(PURCHASE_ID).isNullOrEmpty()) return null
            return RequestTarget(RequestTarget.Kind.PAYMENT_RESULT, approval, payment)
        }
        val extras = RequestTarget.fromPayload(buildMap {
            intent.getStringExtra(APPROVAL_ID)?.let { put(APPROVAL_ID, it) }
            intent.getStringExtra(PURCHASE_ID)?.let { put(PURCHASE_ID, it) }
        })
        if (intent.hasExtra(APPROVAL_ID) || intent.hasExtra(PURCHASE_ID)) return extras
        val uri = intent.data ?: return null
        if (uri.scheme != "copenhagen" || uri.pathSegments.size != 1) return null
        val kind = RequestTarget.Kind.entries.find { it.path == uri.host && it != RequestTarget.Kind.PAYMENT_RESULT } ?: return null
        return uri.pathSegments.single().takeIf(::validId)?.let { RequestTarget(kind, it) }
    }

    fun approvalId(intent: Intent?): String? {
        val extra = intent?.getStringExtra(APPROVAL_ID)
        val uri = intent?.data
        val fromData = uri?.takeIf { it.scheme == "copenhagen" && it.host == "approvals" && it.pathSegments.size == 1 }
            ?.pathSegments?.firstOrNull()
        return (extra ?: fromData)?.takeIf(::validId)
    }
}
