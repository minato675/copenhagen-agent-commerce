@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.copenhagen.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.*

private val paymentScreens = setOf(Screen.P02, Screen.P03, Screen.P04, Screen.P05, Screen.P07)
private fun routeIdentity(route: Route): String = if (route.screen in paymentScreens) "payment-${route.id}" else "${route.screen}-${route.id}"

internal val LocalNotificationReturn = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable fun App(app: AppModel, activity: FragmentActivity, onReturn: (() -> Unit)? = null) = CopenhagenTheme {
    val screen = app.route.screen
    val reduced = LocalReducedMotion.current
    val retained = key(app.signedIn) { rememberSaveableStateHolder() }
    val notification = onReturn != null
    val toolbarTitle = when {
        app.paymentResultTarget?.id == app.route.id && app.signedIn && screen in paymentScreens -> "결제 결과"
        notification && (!screen.focused || screen == Screen.N01) -> app.notificationTarget?.title ?: "요청 확인"
        screen.focused && app.signedIn -> screen.title
        else -> null
    }
    val canLeave = !app.busy || app.approvals.submitting || app.purchases.submitting || notification && (screen == Screen.U01 || app.paymentResultTarget != null)
    val back: () -> Unit = {
        if (canLeave) {
            if (notification && (screen in setOf(Screen.P02, Screen.P05, Screen.P07, Screen.U01) || !app.canBack || !screen.focused)) onReturn?.invoke()
            else app.back()
        }
    }
    BackHandler(enabled = notification || app.busy || app.canBack, onBack = back)
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this, LocalNotificationReturn provides onReturn) {
            Scaffold(containerColor = Carbon.Canvas,
                topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp).heightIn(min = 60.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (app.canBack || notification) IconButton(back, enabled = canLeave) { Glyph(WalletIcon.Back, "뒤로") }
                        else Spacer(Modifier.width(8.dp))
                        if (toolbarTitle == null) BrandWordmark(Modifier.weight(1f))
                        else Text(toolbarTitle, Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        if (notification) {
                            IconButton({ onReturn?.invoke() }, enabled = canLeave) { Glyph(WalletIcon.Close, "닫고 돌아가기") }
                        } else if (!screen.focused && app.signedIn) {
                            IconButton({ app.execute { app.refresh() } }, enabled = !app.busy) { Glyph(WalletIcon.Refresh, "새로고침", tint = Carbon.Muted) }
                            IconButton({ app.open(Screen.S01) }, enabled = !app.busy) { Glyph(WalletIcon.Profile, "내 정보 및 설정") }
                        } else if (!app.signedIn || app.pin?.status != "ACTIVE") {
                            if (screen != Screen.X01) IconButton({ app.show(Screen.X01) }, enabled = !app.busy) { Glyph(WalletIcon.Settings, "서버 설정") }
                        } else Glyph(WalletIcon.Shield, null, Modifier.padding(end = 12.dp).size(20.dp), Carbon.Muted)
                    }
                },
                bottomBar = { if (!notification && !screen.focused && app.signedIn) NavigationDock(app) }
            ) { insets ->
                Column(Modifier.fillMaxSize().padding(insets)) {
                    // Read work keeps the actual content and geometry; payments have their own durable surface.
                    app.error?.let { message ->
                        Row(Modifier.fillMaxWidth().background(Carbon.Error.copy(alpha = .04f)).padding(start = 20.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), color = Carbon.Error, style = MaterialTheme.typography.bodyMedium)
                            IconButton({ app.error = null }) { Glyph(WalletIcon.Close, "오류 닫기", tint = Carbon.Error) }
                        }
                    }
                    if (!notification && app.signedIn && app.pin?.status == "ACTIVE" && (app.approvals.submitting || app.approvals.uncertain) && screen !in paymentScreens) {
                        TextButton(app::resumeApproval, Modifier.fillMaxWidth().background(Carbon.Layer)) {
                            Glyph(WalletIcon.Clock, tint = Carbon.Warning); Spacer(Modifier.width(8.dp))
                            Text("진행 중인 결제 · 상태 보기", color = Carbon.Ink)
                        }
                    }
                    if (!notification && app.signedIn && app.purchases.pendingId != null && screen != Screen.U01) {
                        TextButton({ app.purchases.pendingId?.let(app::openPurchase) }, Modifier.fillMaxWidth().background(Carbon.Layer)) {
                            Text("배송지 선택 · 결과 확인", color = Carbon.Ink)
                        }
                    }
                    AnimatedContent(app.route, modifier = Modifier.fillMaxSize(), contentKey = ::routeIdentity,
                        transitionSpec = {
                            val transform = if (reduced || !app.signedIn && initialState.screen != targetState.screen && initialState.screen !in setOf(Screen.A01, Screen.A02, Screen.A03)) EnterTransition.None togetherWith ExitTransition.None
                            else {
                                val direction = if (app.navigationBack) -1 else 1
                                (slideInHorizontally(Motion.navigation(IntOffset(1, 1))) { direction * it / 12 } + fadeIn(tween(Motion.Content))) togetherWith
                                    (slideOutHorizontally(Motion.navigation(IntOffset(1, 1))) { -direction * it / 16 } + fadeOut(tween(120)))
                            }
                            // The route fills the constrained viewport. Local/shared surfaces own size
                            // changes; avoid a second full-viewport measure animation on every route.
                            transform.using(null)
                        }, label = "route-continuity") { route ->
                        val displayed = route.screen
                        CompositionLocalProvider(LocalDisplayedRoute provides route, LocalVisibilityScope provides this) {
                            retained.SaveableStateProvider(routeIdentity(route)) {
                                Box(Modifier.fillMaxSize().then(if (routeIdentity(route) != routeIdentity(app.route)) Modifier.clearAndSetSemantics { } else Modifier)) {
                                    if (displayed == Screen.N01 || notification && !displayed.focused) {
                                        Page {
                                            Heading(app.notificationTarget?.title ?: "요청 확인")
                                            if (app.busy) Text("불러오는 중", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                                            else Notice("요청을 불러오지 못했어요.")
                                            if (!app.busy) Action("다시 불러오기") {
                                                app.reloadNotificationEntry()
                                            }
                                        }
                                    } else when (displayed) {
                                        Screen.N01 -> Unit
                                        Screen.A01, Screen.A02, Screen.A03, Screen.A04, Screen.A05, Screen.A06, Screen.A07, Screen.A08, Screen.P06 -> AuthScreen(app, activity)
                                        Screen.W01 -> WalletScreen(app)
                                        Screen.W02 -> CardManagerScreen(app)
                                        Screen.C01 -> CardEntryScreen(app)
                                        Screen.C02 -> ConsentScreen(app)
                                        Screen.C03 -> CardResultScreen(app)
                                        Screen.D01 -> AddressesScreen(app)
                                        Screen.D02 -> AddressEditorScreen(app)
                                        Screen.D03 -> PostalSearchScreen(app)
                                        Screen.U01 -> PurchaseScreen(app)
                                        Screen.P01 -> InboxScreen(app)
                                        Screen.P02, Screen.P03, Screen.P04, Screen.P05, Screen.P07 -> PaymentFlowScreen(app, activity, displayed)
                                        Screen.H01 -> HistoryScreen(app)
                                        Screen.H02 -> OrderDetailScreen(app)
                                        Screen.H03 -> PurchaseHistoryDetailScreen(app)
                                        Screen.S01 -> SettingsScreen(app, activity)
                                        Screen.S02 -> ChangePinScreen(app)
                                        Screen.S03 -> ConnectionsScreen(app)
                                        Screen.T01 -> CartScreen(app)
                                        Screen.G01 -> ProductSearchScreen(app)
                                        Screen.G02 -> ProductDetailScreen(app)
                                        Screen.X01 -> ServerScreen(app)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun NavigationDock(app: AppModel) {
    val labels = listOf("지갑", "결제", "기록")
    val icons = listOf(WalletIcon.Home, WalletIcon.Pay, WalletIcon.History)
    val active = Screen.tabs.indexOf(app.tab).coerceAtLeast(0)
    Column(Modifier.background(Carbon.Surface).navigationBarsPadding()) {
        HorizontalDivider(color = Carbon.Line)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val cell = maxWidth / 3
            val position by animateDpAsState(cell * active, if (LocalReducedMotion.current) snap() else Motion.navigation(.5.dp), label = "destination-indicator")
            Box(Modifier.offset { IntOffset(position.roundToPx(), 0) }.width(cell).height(60.dp).padding(horizontal = 8.dp).background(Carbon.Layer, RoundedCornerShape(22.dp)))
            Row {
                Screen.tabs.forEachIndexed { index, tab ->
                    val source = remember { MutableInteractionSource() }
                    val focused by source.collectIsFocusedAsState()
                    Column(Modifier.weight(1f).heightIn(min = 60.dp).semantics { selected = tab == app.tab }
                        .then(if (focused) Modifier.border(2.dp, Carbon.Blue, RoundedCornerShape(22.dp)) else Modifier)
                        .pressMotion(source).clickable(source, indication = null, role = Role.Tab, enabled = !app.busy || app.approvals.submitting || app.purchases.submitting) { app.selectTab(tab) }.padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Glyph(icons[index], tint = if (index == active) Carbon.Ink else Carbon.Muted)
                        Text(labels[index], color = if (index == active) Carbon.Ink else Carbon.Muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
