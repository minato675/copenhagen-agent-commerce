package com.copenhagen.wallet

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.copenhagen.wallet.data.Store
import com.copenhagen.wallet.ui.App
import com.copenhagen.wallet.ui.HarborLaunch
import com.copenhagen.wallet.ui.HarborLaunchState
import com.copenhagen.wallet.ui.prepareHarborLaunch
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** This application exists only in the .preview APK. Its HTTP clients cannot reach a real host. */
class PreviewApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                require(uri.host == "127.0.0.1" && uri.port == 8787 && uri.scheme == "http") {
                    "테스트 앱은 로컬 테스트 서버에만 연결할 수 있어요."
                }
                return listOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        Store(this).saveHosts("http://127.0.0.1:8787", "http://127.0.0.1:8787", "http://127.0.0.1:8787")
    }
}

/** Same app/model/authentication/domain, with a permanent fixture label and recordable window. */
class PreviewActivity : FragmentActivity() {
    private val app: AppModel by lazy { WalletSession.model(application) }
    private val launchState by lazy { ViewModelProvider(this)[HarborLaunchState::class.java] }
    private var windowFocused by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Copenhagen)
        super.onCreate(savedInstanceState)
        prepareHarborLaunch()
        app.enterWallet()
        enableEdgeToEdge()
        setContent {
            FixtureFrame {
                HarborLaunch(launchState, lifecycle, windowFocused) { App(app, this@PreviewActivity) }
            }
        }
        if (savedInstanceState == null) acceptFixtureIntent(intent)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app.enterWallet()
        setIntent(intent)
        acceptFixtureIntent(intent)
    }
    private fun acceptFixtureIntent(intent: Intent?) {
        intent?.getStringExtra("approvalId")?.let(app::acceptApprovalLink)
    }
    override fun onStart() { super.onStart(); app.enterWallet(); app.foregrounded(this) }
    override fun onStop() { super.onStop(); app.backgrounded(this, isChangingConfigurations) }
}

@Composable
internal fun FixtureFrame(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("로컬 테스트 · 실제 결제 없음", color = Color.White, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF704C00))
                .statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp))
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) { content() }
    }
}
