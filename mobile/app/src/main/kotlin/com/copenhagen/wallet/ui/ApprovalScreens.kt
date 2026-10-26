package com.copenhagen.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.delay
import java.time.Instant

@Composable fun InboxScreen(app: AppModel) = Page {
    Heading("결제")
    PurchaseInbox(app)
    Text("승인할 요청", color = Carbon.Muted, style = MaterialTheme.typography.bodyLarge)
    Data(app.approvals.inbox, app.busy, "승인 요청을 불러오지 못했어요.", { app.execute { app.approvals.loadInbox() } }) { rows ->
        if (rows.isEmpty()) EmptyPaymentState("기다리는 요청이 없어요")
        rows.forEach { value -> key(value.id) { ApprovalTile(value, onVisible = { app.approvals.ensureThumbnail(value.id) }) { app.openApproval(value.id) } } }
    }
}

@Composable fun EmptyPaymentState(title: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).background(Carbon.Layer, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) { Glyph(WalletIcon.Pay, modifier = Modifier.size(32.dp), tint = Carbon.Muted) }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/** One purchase, total and surface survive review → authorization → processing → receipt. */
@Composable fun PaymentFlowScreen(app: AppModel, activity: FragmentActivity, screen: Screen) {
    // Outgoing presentation keeps its last native object until the route transition ends.
    // This is memory-only view state, excluded from semantics once the route is no longer active.
    val displayedId = LocalDisplayedRoute.current.id
    var snapshot by remember(displayedId) { mutableStateOf(app.approvals.current?.takeIf { it.id == displayedId }) }
    app.approvals.current?.takeIf { it.id == LocalDisplayedRoute.current.id }?.let { snapshot = it }
    val approval = snapshot
    val reduced = LocalReducedMotion.current
    val onReturn = LocalNotificationReturn.current
    val resultTarget = app.paymentResultTarget?.takeIf { it.id == LocalDisplayedRoute.current.id }
    val resultOnly = resultTarget != null
    if (approval == null) {
        if (resultTarget != null) {
            Page {
                Heading("결제 결과")
                Data<Approval>(null, app.busy, "결제 결과를 불러오지 못했어요.",
                    { app.execute { app.refreshApprovalResult() } }) { }
                Secondary(if (onReturn != null) "돌아가기" else "지갑으로") {
                    if (onReturn != null) onReturn() else app.closeApproval()
                }
            }
            return
        }
        Page {
            Heading(if (app.approvals.uncertain) "처리 상태 확인" else "결제 요청")
            Notice(if (app.approvals.uncertain) "이전 요청의 결과를 확인해 주세요." else "결제 정보를 불러오지 못했어요.")
            Action("상태 확인", !app.busy) { app.execute { app.approvals.reconcile() } }
            Secondary(if (onReturn != null) "닫고 돌아가기" else "지갑으로", !app.busy || app.approvals.submitting) {
                if (onReturn != null) onReturn() else app.closeApproval()
            }
            app.approvals.pendingId?.let { id ->
                TechnicalDetails("pending-approval-$id", "요청 상세 정보") {
                    DetailLine("요청 번호", id)
                }
            }
        }
        return
    }
    val resultPhase = screen == Screen.P05 || screen == Screen.P07
    val isCurrent = app.approvals.current?.id == approval.id
    val approvalStatus = if (isCurrent) app.approvals.result ?: approval.status else approval.status
    val uncertain = isCurrent && app.approvals.uncertain
    val submitting = isCurrent && app.approvals.submitting
    val status = if (approvalStatus == "APPROVED" && !uncertain && !submitting)
        app.paymentOutcomes.statusFor(approval.id) ?: approvalStatus else approvalStatus
    val rejecting = isCurrent && app.approvals.rejecting
    val completed = resultPhase && status == "COMPLETED" && !uncertain
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(approval.id, resultTarget, focused, status, screen, submitting, uncertain) {
        if (!resultPhase || !focused || submitting || uncertain || !paymentResultPending(status) ||
            (!resultOnly && approvalStatus != "APPROVED")) return@LaunchedEffect
        // Visible, bounded GETs only. Leaving the screen stops polling; no financial command is replayed.
        repeat(10) { attempt ->
            delay(if (attempt == 0) 150 else 1500)
            if (app.paymentResultTarget != resultTarget || !app.route.isApprovalPresentation(approval.id) ||
                app.route.screen !in setOf(Screen.P05, Screen.P07) || !app.signedIn) return@LaunchedEffect
            if (!app.busy) app.execute { app.refreshApprovalResult() }
        }
    }
    val radius = animateDpAsState(if (resultPhase) 28.dp else 24.dp,
        if (reduced) snap() else Motion.surface(.1.dp), label = "receipt-corners")
    val anchor: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().sharedObject("approval-${approval.id}")
            .animateContentSize(if (reduced) snap() else Motion.surface())
            .graphicsLayer { shape = RoundedCornerShape(radius.value); clip = true }
            .background(Carbon.Surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (LocalDensity.current.fontScale >= 1.6f) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MerchantMark(approval.displayName)
                    Column(Modifier.fillMaxWidth()) {
                        Text(approval.displayName, style = MaterialTheme.typography.titleLarge)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MerchantMark(approval.displayName)
                    Column(Modifier.weight(1f)) {
                        Text(approval.displayName, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            val exactAmount = approvalMoney(approval.amountMinor, approval.currency)
            if (approval.merchants.size > 1) Text("구매 요청 총액", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
            // Native wrapping respects comma groups. Accessibility receives the untouched exact value.
            Text(amountWithGroupBreaks(exactAmount),
                modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(exactAmount) },
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
            if (screen == Screen.P02 && approval.displaySummary.isNotEmpty()) {
                OrderItemRows(approval.displaySummary)
            }
            if (resultPhase) {
                HorizontalDivider(color = Carbon.Line)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatusSeal(completed, submitting, if (isCurrent) app.approvals.operationStartedAt else null,
                        failed = status in setOf("INVALIDATED", "FAILED", "PARTIALLY_COMPLETED"))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (resultOnly) paymentResultTitle(status) else paymentTitle(status, submitting, uncertain, rejecting),
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (resultOnly) ReadActivity(app.busy)
                    }
                }
            }
        }
    }
    val controls: @Composable () -> Unit = {
        AnimatedContent(screen,
            // Processing and its authoritative result are one live receipt, not two fading copies.
            contentKey = { phase -> if (phase == Screen.P05 || phase == Screen.P07) "receipt" else phase.name },
            transitionSpec = {
            val transform = if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(160)) + slideInVertically(Motion.surface()) { it / 20 }) togetherWith fadeOut(tween(90))
            transform.using(if (reduced) null else SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> Motion.surface() }))
        }, label = "commitment-surface") { phase ->
            Column(Modifier.fillMaxWidth()
                .then(if (phase != screen) Modifier.clearAndSetSemantics { } else Modifier), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (phase) {
                    Screen.P02 -> ApprovalReview(app, activity, approval)
                    Screen.P03, Screen.P04 -> ApprovalAuthorization(app, activity, phase, approval)
                    else -> ApprovalReceipt(app, approval, status, submitting, uncertain,
                        if (isCurrent) app.approvals.operationStartedAt else null, isCurrent, resultTarget)
                }
            }
        }
    }
    Page {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp && LocalDensity.current.fontScale < 1.6f) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.weight(1f)) { anchor() }
                    Box(Modifier.weight(1f)) { controls() }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { anchor(); controls() }
        }

    }
}

/** Break only the presentation string at thousands separators; never change the financial value. */
private fun amountWithGroupBreaks(exactAmount: String): String = buildString {
    exactAmount.forEachIndexed { index, character ->
        append(character)
        val next = exactAmount.getOrNull(index + 1)
        if (character == ',') append('\u200B')
        else if (character.isDigit() && (next?.isDigit() == true || next == ',' || next == '원')) append('\u2060')
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ApprovalReview(app: AppModel, activity: FragmentActivity, approval: Approval) {
    var selecting by remember { mutableStateOf(false) }
    // Card correction stays in this review; Back collapses it without leaving the request.
    BackHandler(enabled = selecting && app.route.screen == Screen.P02 && app.route.id == approval.id) { selecting = false }
    var rejecting by remember { mutableStateOf(false) }
    var items by rememberSaveable(approval.id) { mutableStateOf(false) }
    val now by produceState(Instant.now(), approval.id) { while (true) { value = Instant.now(); delay(1000) } }
    val active = approval.actionable && !approval.expired(now) && !app.approvals.uncertain
    val chosen = app.cards.items.orEmpty().find { it.id == approval.selectedPaymentMethodId }
    Tile {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (chosen != null) CardThumbnail(chosen.id) else Glyph(WalletIcon.Card, tint = Carbon.Muted)
            Column(Modifier.weight(1f)) {
                Text("결제 카드", style = MaterialTheme.typography.bodyMedium, color = Carbon.Muted)
                Text(chosen?.label ?: "카드를 선택해 주세요", style = MaterialTheme.typography.titleMedium)
            }
            if (active) TextButton({ selecting = true }, enabled = !app.busy) { Text("변경") }
        }
        HorizontalDivider(color = Carbon.Line)
        ReviewLine("요청 총액", approvalMoney(approval.amountMinor, approval.currency))
        ReviewLine("결제 통화", approval.currency)
        if (active) {
            val seconds = (Instant.parse(approval.expiresAt).epochSecond - now.epochSecond).coerceAtLeast(0)
            ReviewLine("남은 시간", "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
        }
    }
    MotionReveal(selecting) {
        Tile {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("결제 카드", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton({ selecting = false }) { Glyph(WalletIcon.Close, "닫기") }
            }
            val cards = app.cards.items.orEmpty().filter { it.active }
            if (cards.isEmpty()) Notice("등록된 카드가 없어요.")
            cards.forEach { card ->
                OutlinedButton({ selecting = false; app.execute { app.approvals.selectCard(card.id) } },
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { selected = chosen?.id == card.id }, enabled = !app.busy,
                    shape = RoundedCornerShape(20.dp), border = BorderStroke(if (chosen?.id == card.id) 2.dp else 1.dp, if (chosen?.id == card.id) Carbon.Blue else Carbon.Boundary)) {
                    CardThumbnail(card.id); Spacer(Modifier.width(12.dp))
                    Text(card.label, Modifier.weight(1f), color = Carbon.Ink)
                    if (chosen?.id == card.id) Glyph(WalletIcon.Check, "선택됨", tint = Carbon.Blue)
                }
            }
            if (BuildConfig.DEBUG) Secondary("새 카드 등록", !app.busy) { selecting = false; app.show(Screen.C01) }
        }
    }
    if (approval.displaySummary.isNotEmpty()) {
        TextButton({ items = !items }, Modifier.fillMaxWidth()) {
            Text("주문 상품 ${approval.displaySummary.sumOf { it.quantity }}개", Modifier.weight(1f), color = Carbon.Ink)
            Glyph(WalletIcon.ChevronDown, tint = Carbon.Muted)
        }
        MotionReveal(items) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (approval.merchants.isNotEmpty()) approval.merchants.forEach { merchant ->
                Tile {
                    Text(merchant.merchantName, style = MaterialTheme.typography.titleMedium)
                    OrderItemRows(merchant.summary, approval.currency)
                    HorizontalDivider(color = Carbon.Line)
                    ReviewLine("판매처 결제 금액", approvalMoney(merchant.subtotalAmountMinor, approval.currency))
                }
            } else OrderItemRows(approval.displaySummary, approval.currency)
        } }
    }
    if (active) {
        if (approval.currency != "KRW") Notice("이 통화는 앱에서 승인할 수 없어요.")
        else if (chosen?.active != true) Notice("사용할 카드를 선택해 주세요.")
        Action("${approvalMoney(approval.amountMinor, approval.currency)} 승인", !app.busy && chosen?.active == true && approval.currency == "KRW", "E30") { app.beginApproval(activity) }
        TextButton({ rejecting = true }, Modifier.fillMaxWidth(), enabled = !app.busy) { Text("요청 거절", color = Carbon.Muted) }
    } else {
        Notice(if (app.approvals.uncertain) "처리 중인 요청의 상태를 먼저 확인해 주세요." else if (approval.actionable) "승인 요청이 만료되었어요." else "이미 처리된 요청이에요.")
        Action("처리 상태 확인", !app.busy) { app.execute { app.approvals.reconcile(); app.show(Screen.P05, approval.id, replace = true) } }
    }
    TechnicalDetails("approval-review-${approval.id}", "결제 상세 정보") {
        DetailLine("요청 번호", approval.id)
        ApprovalCheckoutReferences(approval)
    }
    if (rejecting) Confirm("승인 요청을 거절할까요?", "거절", { rejecting = false }) {
        rejecting = false
        app.rejectApproval()
    }
}
@Composable private fun ApprovalCheckoutReferences(approval: Approval) {
    if (approval.merchants.isNotEmpty()) approval.merchants.forEach { merchant ->
        ReviewLine("${merchant.merchantName} 주문", merchant.checkoutId)
    } else if (approval.checkoutId.isNotBlank()) ReviewLine("주문 번호", approval.checkoutId)
}
@Composable private fun ReviewLine(label: String, value: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun ApprovalAuthorization(app: AppModel, activity: FragmentActivity, phase: Screen, approval: Approval) {
    var pin by remember(app.secretRevision, approval.id, phase) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Glyph(WalletIcon.Shield, tint = Carbon.Blue)
        Text(if (phase == Screen.P03) "PIN으로 결제하기" else "생체 인증", style = MaterialTheme.typography.titleLarge)
    }
    val card = app.cards.items.orEmpty().find { it.id == approval.selectedPaymentMethodId }
    card?.let { Text(it.label, color = Carbon.Muted) }
    if (phase == Screen.P03) {
        app.approvalAuthenticationNotice?.let { Notice(it) }
        PinEntry(pin, { pin = it }, enabled = !app.busy, sessionKey = Triple(app.secretRevision, approval.id, phase))
        Action("${approvalMoney(approval.amountMinor, approval.currency)} 승인", !app.busy && validPin(pin), "E34") {
            val entered = pin; pin = ""; app.approvePin(entered)
        }
    } else {
        Text("기기의 생체인식 창에서 인증해 주세요.", color = Carbon.Muted)
        if (!app.busy) {
            Action("생체인식 다시 시도", true, "E35") { app.approveBiometric(activity) }
            Secondary("PIN으로 결제하기") { app.usePinForApproval() }
        }
    }
    TextButton({ app.back() }, enabled = !app.busy, modifier = Modifier.fillMaxWidth()) { Text("결제 정보 수정") }
}
@Composable private fun ApprovalReceipt(app: AppModel, approval: Approval, status: String,
    submitting: Boolean, uncertain: Boolean, startedAt: Long?, isCurrent: Boolean, resultTarget: RequestTarget?) {
    val onReturn = LocalNotificationReturn.current
    if (!submitting && !uncertain && app.paymentOutcomes.approvalId == approval.id) {
        app.paymentOutcomes.error?.let { Notice(it) }
        app.paymentOutcomes.purchase?.merchantCheckouts?.filter { it.approvalId == approval.id }?.let { checkouts ->
            if (checkouts.size > 1) Tile {
                Text("판매처별 결제 결과", style = MaterialTheme.typography.titleMedium)
                checkouts.forEachIndexed { index, checkout ->
                    val name = approval.merchants.find { it.merchantId == checkout.merchantId }?.merchantName ?: "판매처 ${index + 1}"
                    ReviewLine(name, "${approvalMoney(checkout.totalAmount, checkout.currency)} · ${purchaseStatusLabel(checkout.status)}")
                }
            }
        }
    }
    if (resultTarget != null) {
        if (paymentResultPending(status)) Secondary("상태 확인", isCurrent && !app.busy) {
            app.execute { app.refreshApprovalResult() }
        }
        Action(if (onReturn != null) "돌아가기" else "지갑으로") {
            if (onReturn != null) onReturn() else app.closeApproval()
        }
        TechnicalDetails("payment-result-${approval.id}", "결제 상세 정보") {
            DetailLine("요청 번호", approval.id)
            ApprovalCheckoutReferences(approval)
            DetailLine("결제 번호", requireNotNull(resultTarget.paymentId))
        }
        return
    }
    val elapsed by produceState(0L, startedAt, submitting) {
        while (submitting) { value = System.currentTimeMillis() - (startedAt ?: System.currentTimeMillis()); delay(250) }
    }
    if (submitting && elapsed >= 3000) Notice(if (elapsed >= 8000) "닫아도 요청은 계속 처리돼요." else "응답이 늦어지고 있어요.")
    if (!submitting) {
        Tile {
            ReviewLine("결제 카드", app.cards.items.orEmpty().find { it.id == approval.selectedPaymentMethodId }?.label ?: "정보 확인 필요")
        }
        if (onReturn != null) {
            if (uncertain || status in setOf("PENDING", "PAYMENT_METHOD_REQUIRED")) {
                Action("상태 확인", isCurrent && !app.busy, "E40") { app.execute { app.approvals.reconcile() } }
                if (isCurrent && app.approvals.canRetry) Secondary("승인 요청 재전송", !app.busy) { app.retryApproval() }
                else if (!uncertain && approval.actionable) Secondary("결제 정보로", isCurrent && !app.busy) { app.show(Screen.P02, approval.id, replace = true) }
            }
            Action("돌아가기", !app.busy) { onReturn() }
            if (status == "APPROVED" && !uncertain) Secondary("상태 확인", isCurrent && !app.busy) { app.execute { app.refreshApprovalResult() } }
            if (uncertain) Text("닫아도 요청은 취소되지 않아요.", color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
        } else if (status == "COMPLETED" && !uncertain) {
            Action("기록에서 보기", isCurrent && !app.busy, "E40") { app.closeApproval(toHistory = true) }
            Secondary("지갑으로", isCurrent && !app.busy) { app.closeApproval() }
        } else {
            Action("상태 확인", isCurrent && !app.busy, "E40") { app.execute { app.refreshApprovalResult() } }
            if (isCurrent && app.approvals.canRetry) Secondary("승인 요청 재전송", !app.busy) {
                app.retryApproval()
            }
            else if (!uncertain && approval.actionable) Secondary("결제 정보로", isCurrent && !app.busy) { app.show(Screen.P02, approval.id, replace = true) }
            Secondary("기록 보기", isCurrent && !app.busy) { app.closeApproval(toHistory = true) }
            TextButton({ app.closeApproval() }, Modifier.fillMaxWidth(), enabled = isCurrent && !app.busy) { Text("지갑으로") }
        }
        TechnicalDetails("approval-receipt-${approval.id}", "결제 상세 정보") {
            DetailLine("요청 번호", approval.id)
            ApprovalCheckoutReferences(approval)
        }
    } else {
        // This is presentation-only navigation: the AppModel operation remains alive.
        Secondary(if (onReturn != null) "닫고 돌아가기" else "지갑에서 기다리기") {
            if (onReturn != null) onReturn() else app.closeApproval()
        }
    }
}
private fun paymentTitle(status: String, submitting: Boolean, uncertain: Boolean, rejecting: Boolean): String = when {
    rejecting -> "거절 전달 중"
    submitting -> "승인 전달 중"
    uncertain -> "처리 확인 필요"
    status == "COMPLETED" -> "결제가 완료되었어요"
    status in setOf("PARTIALLY_COMPLETED", "FAILED", "CANCELLED") -> paymentResultTitle(status)
    status == "APPROVED" -> "승인을 전달했어요"
    status == "REJECTED" -> "요청을 거절했어요"
    status == "EXPIRED" -> "요청이 만료되었어요"
    status == "INVALIDATED" -> "요청이 취소되었어요"
    status in listOf("PENDING", "PAYMENT_METHOD_REQUIRED") -> "승인 대기 중"
    else -> "처리 상태 확인 필요"
}
@Composable private fun StatusSeal(completed: Boolean, submitting: Boolean, startedAt: Long?, failed: Boolean = false) {
    val reduce = LocalReducedMotion.current
    val focused = LocalWindowInfo.current.isWindowFocused
    val age by produceState(0L, submitting, startedAt) { while (submitting && value < 8100) { value = System.currentTimeMillis() - (startedAt ?: System.currentTimeMillis()); delay(250) } }
    val draw = animateFloatAsState(if (completed) 1f else 0f, tween(if (reduce) 0 else 220, easing = Motion.Reveal), label = "confirmed-check")
    val rotation = if (submitting && age in 800..8000 && !reduce && focused) {
        val transition = rememberInfiniteTransition(label = "request-activity")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "request-arc")
    } else null
    Spacer(Modifier.size(56.dp).clearAndSetSemantics { }.drawWithCache {
        val stroke = 2.dp.toPx(); val inset = stroke * 2
        val tint = if (completed) Carbon.Positive else if (failed) Carbon.Error else Carbon.Warning
        val path = Path().apply { moveTo(size.width * .27f, size.height * .52f); lineTo(size.width * .43f, size.height * .67f); lineTo(size.width * .74f, size.height * .34f) }
        val measure = PathMeasure().apply { setPath(path, false) }
        val segment = Path()
        onDrawBehind {
            drawCircle(tint.copy(alpha = .08f))
            if (completed) {
                segment.reset()
                measure.getSegment(0f, measure.length * draw.value, segment)
                drawPath(segment, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else if (failed) {
                drawLine(tint, Offset(size.width * .34f, size.height * .34f), Offset(size.width * .66f, size.height * .66f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(size.width * .66f, size.height * .34f), Offset(size.width * .34f, size.height * .66f), stroke, StrokeCap.Round)
            } else {
                drawArc(tint, (rotation?.value ?: 0f) - 90f, if (submitting && age in 800..8000 && !reduce) 240f else 360f, false,
                    Offset(inset * 2, inset * 2), Size(size.width - inset * 4, size.height - inset * 4), style = Stroke(stroke, cap = StrokeCap.Round))
                drawLine(tint, center, Offset(center.x, center.y - size.height * .16f), stroke, StrokeCap.Round)
                drawLine(tint, center, Offset(center.x + size.width * .12f, center.y + size.height * .08f), stroke, StrokeCap.Round)
            }
        }
    })
}
