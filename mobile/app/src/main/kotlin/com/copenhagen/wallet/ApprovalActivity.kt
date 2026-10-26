package com.copenhagen.wallet

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.copenhagen.wallet.data.validId
import com.copenhagen.wallet.data.RequestTarget
import com.copenhagen.wallet.ui.App
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** A notification-only task. Android retains the user's previous app/Home behind it. */
open class ApprovalActivity : FragmentActivity() {
    protected val app: AppModel by lazy { WalletSession.model(application) }
    protected open val secureWindow = !BuildConfig.ALLOW_SCREEN_CAPTURE
    private var returning = false
    private var entrySession by mutableStateOf<Long?>(null)
    private var retainedTarget: RequestTarget? = null
    private fun ownsEntry() = entrySession != null && app.notificationSessionId == entrySession

    @Composable
    protected open fun RenderContent(content: @Composable () -> Unit) { content() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (secureWindow) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val savedPurchase = savedInstanceState?.getString(ApprovalNotifications.PURCHASE_ID)?.takeIf(::validId)
        val savedApproval = savedInstanceState?.getString(ApprovalNotifications.APPROVAL_ID)?.takeIf(::validId)
        val savedPayment = savedInstanceState?.getString(ApprovalNotifications.PAYMENT_ID)?.takeIf(::validId)
        val target = if (savedPayment != null && savedApproval != null) RequestTarget(RequestTarget.Kind.PAYMENT_RESULT, savedApproval, savedPayment)
            else savedPurchase?.let { RequestTarget(RequestTarget.Kind.PURCHASE, it) }
            ?: savedApproval?.let { RequestTarget(RequestTarget.Kind.APPROVAL, it) }
            ?: ApprovalNotifications.target(intent)
        if (target == null) {
            finishAndRemoveTask()
            return
        }
        // Idempotent for configuration changes, and restores entry after process recreation.
        app.beginNotificationEntry(target)
        entrySession = app.notificationSessionId
        app.notificationTarget?.let { retained ->
            retainedTarget = retained
            setIntent(ApprovalNotifications.entryIntent(this, javaClass, retained))
        }
        // Task ownership must remain observable while this window's recomposer is stopped.
        lifecycleScope.launch {
            snapshotFlow { app.notificationSessionId to entrySession }.collect {
                if (!ownsEntry()) finishUnownedEntry()
            }
        }
        setContent {
            if (ownsEntry()) RenderContent {
                App(app, this, onReturn = ::returnToCaller)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val target = ApprovalNotifications.target(intent) ?: return
        app.beginNotificationEntry(target)
        // A rejected second notification cannot replace the original entry on recreation.
        if (app.notificationTarget == target) {
            entrySession = app.notificationSessionId
            retainedTarget = target
            setIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        retainedTarget?.paymentId?.let { outState.putString(ApprovalNotifications.PAYMENT_ID, it) }
        retainedTarget?.let { outState.putString(
            if (it.kind == RequestTarget.Kind.PURCHASE) ApprovalNotifications.PURCHASE_ID else ApprovalNotifications.APPROVAL_ID, it.id) }
        super.onSaveInstanceState(outState)
    }

    private fun returnToCaller() {
        if (returning) return
        returning = true
        if (ownsEntry()) app.endNotificationEntry()
        finishAndRemoveTask()
    }

    private fun finishUnownedEntry() {
        if (returning) return
        returning = true
        finishAndRemoveTask()
    }

    override fun onStart() {
        super.onStart()
        if (ownsEntry()) app.foregrounded(this) else finishUnownedEntry()
    }
    override fun onStop() { super.onStop(); app.backgrounded(this, isChangingConfigurations) }
}
