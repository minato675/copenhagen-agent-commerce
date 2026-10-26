package com.copenhagen.wallet.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.*
import com.copenhagen.wallet.data.*

@Composable fun HistoryScreen(app: AppModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var purchaseQuery by rememberSaveable { mutableStateOf("") }
    val purchaseScroll = rememberLazyListState()
    val approvalScroll = rememberLazyListState()
    val orderScroll = rememberLazyListState()
    val scroll = when (app.historyTab) {
        HistoryTab.PURCHASES -> purchaseScroll
        HistoryTab.APPROVALS -> approvalScroll
        HistoryTab.ORDERS -> orderScroll
    }
    LaunchedEffect(app.receiptFocusId) { if (app.receiptFocusId != null) query = "" }
    val history = app.purchaseHistory
    LaunchedEffect(app.historyTab, purchaseQuery, history.filter, history.imageRevision) {
        history.prepareSearch(if (app.historyTab == HistoryTab.PURCHASES) purchaseQuery else "", history.filter)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = if (maxWidth < 360.dp) 16.dp else 20.dp
        LazyColumn(Modifier.fillMaxSize().imePadding(), state = scroll,
            contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "history-heading", contentType = "heading") { Heading("기록") }
            item(key = "history-tabs", contentType = "tabs") {
                Surface(shape = RoundedCornerShape(20.dp), color = Carbon.Surface) {
                    TabRow(app.historyTab.ordinal, containerColor = Carbon.Surface, contentColor = Carbon.Ink) {
                        HistoryTab.entries.forEach { tab ->
                            Tab(app.historyTab == tab, {
                                app.historyTab = tab; app.execute { app.refresh() }
                            }, enabled = !app.busy, text = { Text(tab.title) })
                        }
                    }
                }
            }
            when (app.historyTab) {
                HistoryTab.PURCHASES -> purchaseHistoryList(app, purchaseQuery) { purchaseQuery = it }
                HistoryTab.APPROVALS -> {
                    item(key = "approvals-description", contentType = "description") {
                        Text("승인은 결제에 동의한 기록이에요. 실제 결제 결과는 구매 기록에서 확인하세요.",
                            color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    item(key = "approvals-filters", contentType = "filters") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            approvalStatuses.forEach { status ->
                                HistoryFilter(app.approvals.historyStatus == status, statusLabel(status), app.busy) {
                                    app.approvals.historyStatus = status; app.execute { app.approvals.loadHistory() }
                                }
                            }
                        }
                    }
                    item(key = "approvals-count", contentType = "count") {
                        Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${statusLabel(app.approvals.historyStatus)} 기록", color = Carbon.Muted,
                                style = MaterialTheme.typography.bodyMedium)
                            ReadActivity(app.approvals.historyRefreshing)
                        }
                    }
                    item(key = "approvals-search", contentType = "search") {
                        Field(query, { query = it }, "상점 검색", maxLength = 100)
                    }
                    historyData("approvals", app.approvals.history, app.busy, "승인 기록을 불러오지 못했어요.",
                        { app.execute { app.approvals.loadHistory() } }) { rows ->
                        val found = rows.filter { it.displayName.contains(query, ignoreCase = true) }
                        if (found.isEmpty()) item(key = "approvals-empty", contentType = "empty") {
                            QuietEmptyState(WalletIcon.History,
                                if (query.isNotBlank()) "검색 결과가 없어요" else "아직 승인 기록이 없어요")
                        }
                        items(found, key = { "approval-${it.id}" }, contentType = { "approval" }) { value ->
                            ApprovalTile(value, highlight = app.receiptFocusId == value.id,
                                onVisible = { app.approvals.ensureThumbnail(value.id) }) { app.openApproval(value.id) }
                        }
                    }
                }
                HistoryTab.ORDERS -> {
                    item(key = "orders-description", contentType = "description") {
                        Text("주문별 배송 진행 상황을 확인하세요.", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    item(key = "orders-filters", contentType = "filters") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf("") + orderStatuses).forEach { status ->
                                HistoryFilter(app.account.orderStatus == status,
                                    if (status.isBlank()) "전체" else orderStatusLabel(status), app.busy) {
                                    app.account.orderStatus = status; app.execute { app.account.loadOrders() }
                                }
                            }
                        }
                    }
                    historyData("orders", app.account.orders, app.busy, "주문 기록을 불러오지 못했어요.",
                        { app.execute { app.account.loadOrders() } }) { page ->
                        if (page.items.isEmpty()) item(key = "orders-empty", contentType = "empty") {
                            QuietEmptyState(WalletIcon.Bag, "아직 주문 기록이 없어요")
                        }
                        items(page.items, key = { "order-${it.id}" }, contentType = { "order" }) { order ->
                            LaunchedEffect(order, app.account.orderImageRevision) { app.account.ensureOrderThumbnail(order.id) }
                            OrderRecordTile(order, !app.busy) {
                                app.execute { app.show(Screen.H02, order.id); app.account.openOrder(order.id) }
                            }
                        }
                        if (page.nextCursor != null) item(key = "orders-more", contentType = "more") {
                            Secondary("더 보기", loading = app.busy) { app.execute { app.account.loadOrders(more = true) } }
                        }
                    }
                }
            }
        }
    }
}

/** Keep the existing delayed loading and retry states without grouping loaded rows into one lazy item. */
internal fun <T> LazyListScope.historyData(key: String, value: T?, busy: Boolean, empty: String,
    retry: () -> Unit, content: LazyListScope.(T) -> Unit) {
    if (value == null) item(key = "$key-loading", contentType = "loading") {
        Data<T>(null, busy, empty, retry) { }
    } else content(value)
}

@Composable private fun OrderRecordTile(order: Order, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(onClick = onClick, enabled = enabled, color = Carbon.Surface, shape = RoundedCornerShape(16.dp),
        interactionSource = interaction, modifier = Modifier.pressMotion(interaction)
            .then(if (focused) Modifier.border(2.dp, Carbon.Blue, RoundedCornerShape(16.dp)) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductThumbnail(order.items.firstOrNull { !it.imageUrl.isNullOrBlank() }?.imageUrl, Modifier.size(72.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(order.title, style = MaterialTheme.typography.titleMedium)
                Text(order.createdAt, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                Text(orderStatusLabel(order.status), color = Carbon.Blue, style = MaterialTheme.typography.labelLarge)
                Text(approvalMoney(order.amountMinor, order.currency), style = MaterialTheme.typography.titleLarge)
            }
            Glyph(WalletIcon.ArrowRight, modifier = Modifier.padding(top = 4.dp).size(20.dp), tint = Carbon.Muted)
        }
    }
}

/** A read temporarily locks interaction without changing the filter's selection or visual identity. */
@Composable internal fun HistoryFilter(selected: Boolean, label: String, busy: Boolean, onClick: () -> Unit) {
    val fill = MaterialTheme.colorScheme.secondaryContainer
    FilterChip(selected, onClick, { Text(label) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
        leadingIcon = if (selected) {{ Glyph(WalletIcon.Check, modifier = Modifier.size(18.dp)) }} else null,
        colors = FilterChipDefaults.filterChipColors(containerColor = androidx.compose.ui.graphics.Color.Transparent,
            labelColor = Carbon.Muted, selectedContainerColor = fill, selectedLabelColor = Carbon.Blue,
            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent, disabledSelectedContainerColor = fill,
            disabledLabelColor = if (selected) Carbon.Blue else Carbon.Muted),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected))
}

@Composable fun OrderDetailScreen(app: AppModel) = Page {
    val id = LocalDisplayedRoute.current.id
    val value = app.account.order?.takeIf { it.id == id }
    Heading("주문 상세")
    Data(value, app.busy, "주문을 불러오지 못했어요.", { app.execute { app.refresh() } }) { order ->
        Tile {
            Glyph(WalletIcon.Bag, tint = Carbon.Blue)
            Text(order.title, style = MaterialTheme.typography.headlineSmall)
            Text(approvalMoney(order.amountMinor, order.currency), style = MaterialTheme.typography.headlineLarge)
            Text(orderStatusLabel(order.status), color = Carbon.Blue, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = Carbon.Line)
            DetailLine("주문 일시", order.createdAt)
        }
        if (order.items.isNotEmpty()) {
            Section("주문 상품")
            Tile {
                OrderItemRows(order.items, order.currency)
            }
        }
        if (order.paymentLabel.isNotBlank()) Tile {
            DetailLine("결제 정보", order.paymentLabel)
        }
        order.address?.let { address ->
            val lines = listOf(
                "받는 분" to address.recipient, "연락처" to address.phone,
                "우편번호" to address.postalCode,
                "배송지" to listOf(address.address, address.detail).filter { it.isNotBlank() }.joinToString("\n"),
                "배송 요청" to address.memo
            ).filter { it.second.isNotBlank() }
            if (lines.isNotEmpty()) {
                Section("배송 정보")
                Tile { lines.forEach { (label, value) -> DetailLine(label, value) } }
            }
        }
        TechnicalDetails("order-${order.id}", "주문 상세 정보") {
            DetailLine("주문 번호", order.id)
        }
    }
}

@Composable fun SettingsScreen(app: AppModel, activity: FragmentActivity) {
    var logout by remember { mutableStateOf(false) }
    Page {
        Heading("설정")
        Tile {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Carbon.Layer, shape = RoundedCornerShape(18.dp)) {
                    Glyph(WalletIcon.Profile, modifier = Modifier.padding(14.dp).size(28.dp), tint = Carbon.Blue)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(app.account.profile?.name ?: app.loginId, style = MaterialTheme.typography.headlineSmall)
                    app.account.profile?.let { Text(it.loginId, color = Carbon.Muted); Text(it.email, color = Carbon.Muted) }
                }
            }
            if (app.account.profile == null) TextButton({ app.execute { app.account.loadProfile() } }, enabled = !app.busy) { Text("프로필 다시 불러오기") }
        }
        Section("보안")
        PreferenceGroup {
            PreferenceRow(WalletIcon.Shield, "PIN 변경", enabled = !app.busy) { app.show(Screen.S02) }
            HorizontalDivider(Modifier.padding(start = 56.dp), color = Carbon.Line)
            val registered = app.biometricRegistered
            val available = app.biometrics.available(activity)
            PreferenceRow(if (registered) WalletIcon.Check else WalletIcon.Shield,
                if (registered) "생체 인증 끄기" else if (app.biometricServerRegistered) "생체 인증 다시 등록" else "생체 인증 등록",
                if (!registered && !available) "이 기기에서는 사용할 수 없어요" else null,
                enabled = !app.busy && (registered || available), loading = app.biometricRevoking) {
                if (registered) app.disableBiometric() else app.enroll(activity)
            }
            if (!registered && app.biometricServerRegistered) PreferenceRow(WalletIcon.Shield,
                "생체 인증 끄기", enabled = !app.busy, loading = app.biometricRevoking) { app.disableBiometric() }
        }
        app.biometricStatusError?.let { message ->
            Notice(message)
            TextButton({ app.execute { app.refreshBiometricStatus() } }, enabled = !app.busy) { Text("생체 인증 상태 다시 확인") }
        }
        Section("알림")
        PreferenceGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("승인 요청 알림", style = MaterialTheme.typography.titleMedium)
                Text(app.notificationError ?: if (app.notificationsRegistered) "알림 연결됨" else "알림 연결 대기 중",
                    color = if (app.notificationError != null) Carbon.Error else Carbon.Muted)
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Carbon.Line)
            PreferenceRow(WalletIcon.Refresh, "알림 다시 연결", enabled = !app.busy) { app.execute { app.registerNotifications() } }
            PreferenceRow(WalletIcon.Settings, "기기 알림 설정", enabled = !app.busy) {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
        }
        Section("앱")
        PreferenceGroup {
            PreferenceRow(WalletIcon.Link, "연결된 AI 서비스", enabled = !app.busy) { app.open(Screen.S03) }
        }
        TechnicalDetails("settings-advanced", "고급 설정") {
            PreferenceGroup {
                PreferenceRow(WalletIcon.Settings, "서버 설정", enabled = !app.busy) { app.show(Screen.X01) }
            }
        }
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandLogo(size = 40.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Copenhagen", style = MaterialTheme.typography.titleMedium)
                Text("버전 ${BuildConfig.VERSION_NAME}", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Secondary("로그아웃", !app.busy) { logout = true }
    }
    if (logout) Confirm("로그아웃할까요?", "로그아웃", { logout = false }) { logout = false; app.logout() }
}

@Composable fun ServerScreen(app: AppModel) {
    var core by remember { mutableStateOf(app.store.core) }
    var pay by remember { mutableStateOf(app.store.pay) }
    var pg by remember { mutableStateOf(app.store.pg) }
    var confirming by remember { mutableStateOf(false) }
    Page {
        Heading("서버 설정")
        Field(core, { core = it.trim() }, "서비스 서버", keyboard = KeyboardType.Uri, maxLength = 500, enabled = !app.busy)
        Field(pay, { pay = it.trim() }, "결제 서버", keyboard = KeyboardType.Uri, maxLength = 500, enabled = !app.busy)
        Field(pg, { pg = it.trim() }, "카드 등록 서버", keyboard = KeyboardType.Uri, maxLength = 500, enabled = !app.busy)
        if (app.signedIn) Notice("서버를 변경하면 로그아웃돼요.")
        val valid = runCatching {
            normalizeHost(core, BuildConfig.DEBUG); normalizeHost(pay, BuildConfig.DEBUG)
            if (pg.isNotBlank()) normalizeHost(pg, BuildConfig.DEBUG)
        }.isSuccess
        Action("저장", !app.busy && valid) { confirming = true }
    }
    if (confirming) Confirm("신뢰하는 서버 주소인가요?", "저장", { confirming = false }) {
        confirming = false; app.saveHosts(core, pay, pg)
    }
}

@Composable internal fun QuietEmptyState(icon: WalletIcon, title: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(24.dp), color = Carbon.Layer) {
            Glyph(icon, modifier = Modifier.padding(20.dp).size(36.dp), tint = Carbon.Muted)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        detail?.let { Text(it, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

@Composable internal fun DetailLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun PreferenceGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Carbon.Surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable internal fun PreferenceRow(icon: WalletIcon, label: String, detail: String? = null,
    enabled: Boolean = true, loading: Boolean? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(onClick = onClick, enabled = enabled, color = Carbon.Surface, interactionSource = interaction,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.pressMotion(interaction)
            .then(if (focused) Modifier.border(2.dp, Carbon.Blue, RoundedCornerShape(16.dp)) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Glyph(icon, tint = Carbon.Muted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled || loading == true) Carbon.Ink else Carbon.Muted)
                detail?.let { Text(it, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium) }
            }
            if (loading != null) ReadActivity(loading, activeDescription = "처리 중")
            Glyph(WalletIcon.ArrowRight, modifier = Modifier.size(20.dp), tint = Carbon.Muted)
        }
    }
}
