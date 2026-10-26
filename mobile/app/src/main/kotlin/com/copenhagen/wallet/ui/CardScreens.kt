package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.*
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.*
import com.copenhagen.wallet.data.*

@Composable fun WalletScreen(app: AppModel) {
    val reduced = LocalReducedMotion.current
    val entrance = remember { Animatable(if (app.homeEntered || reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        app.homeEntered = true
        entrance.animateTo(1f, if (reduced) snap() else Motion.surface(.002f))
    }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("나의 지갑", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 720.dp && LocalDensity.current.fontScale < 1.6f
            val main: @Composable ColumnScope.() -> Unit = {
                Column(Modifier.graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * 12.dp.toPx() }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val next = app.approvals.inbox?.firstOrNull()
                    if (next != null) {
                        Column(Modifier.fillMaxWidth().sharedObject("approval-${next.id}").clip(RoundedCornerShape(28.dp)).background(Carbon.Surface).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("승인할 결제", style = MaterialTheme.typography.labelLarge, color = Carbon.Blue)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MerchantMark(next.displayName)
                                Text(next.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            }
                            Text(approvalMoney(next.amountMinor, next.currency), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 42.sp, lineHeight = 50.sp, fontFeatureSettings = "tnum"))
                            Action("결제 확인", loading = app.busy && !app.approvals.submitting) { app.openApproval(next.id) }
                        }
                    } else {
                        Tile {
                            Text(if (app.approvals.inbox == null) "결제 요청을 확인해 주세요" else "승인할 결제가 없어요", style = MaterialTheme.typography.headlineSmall)
                            Action("결제 요청", loading = app.busy) { app.selectTab(Screen.P01) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HomeShortcut(WalletIcon.Card, "카드", Modifier.weight(1f)) { app.open(Screen.W02) }
                        HomeShortcut(WalletIcon.Location, "배송지", Modifier.weight(1f)) { app.open(Screen.D01) }
                        HomeShortcut(WalletIcon.Search, "상품", Modifier.weight(1f)) { app.open(Screen.G01) }
                        HomeShortcut(WalletIcon.Bag, "장바구니", Modifier.weight(1f)) { app.open(Screen.T01) }
                    }
                }
                Column(Modifier.graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * 20.dp.toPx() }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Section("내 카드", "관리") { app.open(Screen.W02) }
                    Data(app.cards.items, app.busy, "카드를 불러오지 못했어요.", { app.execute { app.cards.load() } }) { cards ->
                        val card = cards.sortedByDescending { it.isDefault }.firstOrNull()
                        if (card != null) CardTile(card)
                        else Tile {
                            Glyph(WalletIcon.Card, tint = Carbon.Muted)
                            Text("등록된 카드가 없어요", style = MaterialTheme.typography.titleMedium)
                            if (BuildConfig.DEBUG) Secondary("카드 등록", loading = app.busy) { app.show(Screen.C01) }
                            else Notice("현재 앱에서는 새 카드를 등록할 수 없어요.")
                        }
                    }
                }
            }
            val activity: @Composable ColumnScope.() -> Unit = {
                Section("최근 기록", "전체 보기") { app.selectTab(Screen.H01) }
                Data(app.approvals.history, app.busy, "기록을 불러오지 못했어요.", { app.execute { app.approvals.loadHistory() } }) { rows ->
                    val recent = rows.filter { record -> app.approvals.inbox.orEmpty().none { it.id == record.id } }
                    if (recent.isEmpty()) EmptyPaymentState("아직 결제 기록이 없어요")
                    recent.take(3).forEach { value -> ApprovalTile(value, onVisible = { app.approvals.ensureThumbnail(value.id) }) { app.openApproval(value.id) } }
                }
                if (app.approvals.inbox.orEmpty().size > 1) {
                    Section("다른 승인 요청", "모두 보기") { app.selectTab(Screen.P01) }
                    app.approvals.inbox.orEmpty().drop(1).take(2).forEach { value -> ApprovalTile(value, onVisible = { app.approvals.ensureThumbnail(value.id) }) { app.openApproval(value.id) } }
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), content = main)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), content = activity)
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { main(); activity() }
        }
    }
}
@Composable private fun HomeShortcut(icon: WalletIcon, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(modifier.clip(RoundedCornerShape(20.dp)).then(if (focused) Modifier.border(2.dp, Carbon.Blue, RoundedCornerShape(20.dp)) else Modifier)
        .pressMotion(source).clickable(source, indication = null, role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(52.dp).background(Carbon.Surface, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Glyph(icon) }
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable fun CardManagerScreen(app: AppModel) {
    var deleting by remember { mutableStateOf<Card?>(null) }
    var pendingDefaultId by remember { mutableStateOf<String?>(null) }
    Page {
        Heading("카드 관리")
        Data(app.cards.items, app.busy, "카드를 불러오지 못했어요.", { app.execute { app.cards.load() } }) { cards ->
            if (cards.isEmpty()) Notice("등록된 카드가 없어요.")
            DefaultSelectionList(cards, { it.id }, { it.isDefault }, highlight = Color.White) { card, emphasis ->
                // Controls travel with their card and cover content passing underneath.
                Column(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = PaymentCardMaxWidth).background(Carbon.Canvas, RoundedCornerShape(24.dp))) {
                    CardTile(card, emphasis)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DefaultAction("기본 카드로 지정", card.active && !card.isDefault, app.busy, pendingDefaultId == card.id) {
                            app.execute {
                                pendingDefaultId = card.id
                                try { app.cards.makeDefault(card.id) } finally { pendingDefaultId = null }
                            }
                        }
                        TextButton({ deleting = card }, enabled = !app.busy) { Text("삭제", color = Carbon.Error) }
                    }
                }
            }
        }
        if (BuildConfig.DEBUG) Action("카드 추가", loading = app.busy) { app.show(Screen.C01) }
    }
    deleting?.let { card -> Confirm("${card.label}\n삭제할까요?", "삭제", { deleting = null }) {
        deleting = null; app.execute { app.cards.delete(card.id) }
    } }
}
private val termTitles = listOf("본인확인 개인정보 수집·이용 및 위탁", "본인확인 서비스 이용약관",
    "고유식별정보 처리 동의", "통신사 이용약관", "개인정보 제3자 제공 동의")

@Composable fun ConsentScreen(app: AppModel) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Int?>(null) }
    val terms = remember { termTitles.mapIndexed { index, _ ->
        runCatching { context.assets.open("terms/${index + 1}.txt").bufferedReader().use { it.readText() }.trim() }.getOrDefault("")
    } }
    // Mock 개발 흐름은 원래 약관 선택 UI만 유지한다. 법적 동의 증빙 API를 가정하지 않는다.
    val canReadAll = terms.all { it.isNotBlank() }
    val allowed = BuildConfig.DEBUG || canReadAll
    Page {
        Heading("약관 동의")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(app.cards.consents.size == 5, { checked -> app.cards.consents = if (checked) (0..4).toSet() else emptySet() }, enabled = !app.busy && allowed, modifier = Modifier.semantics { contentDescription = "전체 약관 동의" })
            Text("전체 동의", style = MaterialTheme.typography.titleMedium)
        }
        termTitles.forEachIndexed { index, title ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(index in app.cards.consents, { checked ->
                    app.cards.consents = if (checked) app.cards.consents + index else app.cards.consents - index
                }, enabled = !app.busy && allowed, modifier = Modifier.semantics { contentDescription = "$title 동의" })
                TextButton({ selected = index }, Modifier.weight(1f)) { Text("[필수] $title", Modifier.fillMaxWidth()) }
            }
            HorizontalDivider(color = Carbon.Line)
        }
        Action("동의하고 등록", !app.busy && allowed && app.cards.consents.size == 5, "E20") {
            app.execute { app.cards.register(); app.show(Screen.C03, replace = true) }
        }
    }
    selected?.let { index -> AlertDialog(onDismissRequest = { selected = null },
        title = { Text(termTitles[index]) }, text = { Text(terms[index].ifBlank { "약관을 불러올 수 없어요." }, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ selected = null }) { Text("닫기") } }) }
}
@Composable fun CardResultScreen(app: AppModel) = Page {
    Heading("카드 등록 완료")
    app.cards.registered?.let { CardTile(it) }
    Action("계속", !app.busy, "E21") { app.finishCard() }
}
