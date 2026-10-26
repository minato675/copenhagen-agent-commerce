package com.copenhagen.wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.copenhagen.wallet.ui.App
import com.copenhagen.wallet.ui.HarborLaunch
import com.copenhagen.wallet.ui.HarborLaunchState
import com.copenhagen.wallet.ui.prepareHarborLaunch
import com.copenhagen.wallet.data.RequestTarget

class MainActivity : FragmentActivity() {
    private val app: AppModel by lazy { WalletSession.model(application) }
    private val launchState by lazy { ViewModelProvider(this)[HarborLaunchState::class.java] }
    private var windowFocused by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Copenhagen)
        super.onCreate(savedInstanceState)
        prepareHarborLaunch()
        app.enterWallet()
        enableEdgeToEdge()
        if (!BuildConfig.ALLOW_SCREEN_CAPTURE) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ApprovalNotifications.createChannel(this)
        setContent {
            HarborLaunch(launchState, lifecycle, windowFocused) {
                LaunchedEffect(app.notificationsRegistered) {
                    if (app.notificationsRegistered && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                App(app, this)
            }
        }
        if (savedInstanceState == null) acceptLink(intent)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); app.enterWallet(); setIntent(intent); acceptLink(intent)
    }
    private fun acceptLink(intent: Intent?) {
        intent?.getStringExtra(ApprovalNotifications.PURCHASE_ID)?.let {
            intent.removeExtra(ApprovalNotifications.PURCHASE_ID)
            if (com.copenhagen.wallet.data.validId(it)) app.acceptRequestLink(RequestTarget(RequestTarget.Kind.PURCHASE, it))
            return
        }
        intent?.getStringExtra("approvalId")?.let {
            intent.removeExtra("approvalId")
            app.acceptApprovalLink(it)
            return
        }
        val uri = intent?.data ?: return
        if (uri.scheme == "https" && uri.host == BuildConfig.APP_LINK_HOST &&
            uri.pathSegments.size == 2 && com.copenhagen.wallet.data.validId(uri.pathSegments[1])) {
            RequestTarget.Kind.entries.find { it.path == uri.pathSegments[0] && it != RequestTarget.Kind.PAYMENT_RESULT }?.let {
                app.acceptRequestLink(RequestTarget(it, uri.pathSegments[1]))
            }
        }
    }
    override fun onStart() { super.onStart(); app.enterWallet(); app.foregrounded(this) }
    override fun onStop() { super.onStop(); app.backgrounded(this, isChangingConfigurations) }
}
