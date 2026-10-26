package com.copenhagen.wallet

import android.app.Application
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.copenhagen.wallet.data.*
import com.copenhagen.wallet.features.*
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

// 기존 와이어프레임 ID를 그대로 검색할 수 있게 유지한다.
enum class Screen(val title: String, val focused: Boolean = true) {
    A01("로그인"), A02("로그인"), A03("회원가입"), A04("보안 확인"), A08("본인 확인"),
    A05("PIN 등록"), A06("PIN 확인"), A07("생체 인증"),
    W01("나의 지갑", false), W02("카드 관리", false), C01("카드 등록"), C02("약관 동의"), C03("카드 등록"),
    T01("장바구니"),
    G01("상품 탐색"), G02("상품 상세"),
    D01("배송지", false), D02("배송지 편집"), D03("주소 검색"),
    U01("배송지 선택"),
    N01("승인 요청"), P01("승인 요청", false), P02("승인 요청"), P03("PIN 인증"), P04("생체 인증"),
    P05("승인 결과"), P06("인증 잠김"), P07("처리 확인"),
    H01("기록", false), H02("주문 상세", false), H03("구매 상세", false), S01("설정", false), S02("PIN 변경"), S03("연결된 AI 서비스"), X01("서버 설정");
    companion object { val tabs = listOf(W01, P01, H01) }
}
data class Route(val screen: Screen, val id: String? = null)

/** Late responses and exiting presentation callbacks must stay attached to their actual request. */
internal fun Route.isApprovalPresentation(approvalId: String) = id == approvalId &&
    screen in setOf(Screen.P02, Screen.P03, Screen.P04, Screen.P05, Screen.P07)

/** 앱 수명·탭·로그인만 소유한다. 카드/승인/Core 상태는 각각 features/에 있다. */
class AppModel(application: Application) : AndroidViewModel(application) {
    val store = Store(application)
    val api = Api(store)
    val productImages = ProductImages(viewModelScope, api.decorations::approval, api.decorations::core)
    val cards = Cards(api)
    val approvals = Approvals(api, store.unresolvedApprovalId, productImages) { store.unresolvedApprovalId = it }
    val paymentOutcomes = PaymentOutcomes { path -> api.core("GET", path) }
    val purchaseHistory = PurchaseHistory({ path -> api.core("GET", path) }, { path -> api.pay("GET", path) }, productImages)
    val account = Account(api, productImages)
    val purchases = Purchases(PurchaseTransport { method, path, body, key ->
        if (key == null) api.core(method, path, body) else api.coreCommand(method, path, body, key)
    }, store.unresolvedPurchase, productImages) { store.unresolvedPurchase = it }
    val connections = Connections { method, path -> api.core(method, path) }
    val cart = Cart(api::core)
    val catalog = Catalog(api::core)
    val products = ProductDetails(api::core)
    val biometrics = Biometrics()
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var signedIn by mutableStateOf(store.tokens != null); private set
    var notificationsRegistered by mutableStateOf(false); private set
    var notificationError by mutableStateOf<String?>(null); private set
    var loginId by mutableStateOf(store.loginId)
    var pin by mutableStateOf<PinStatus?>(null); private set
    var biometricRegistered by mutableStateOf(store.credentialId != null); private set
    var biometricServerRegistered by mutableStateOf(false); private set
    var biometricStatusError by mutableStateOf<String?>(null); private set
    var biometricRevoking by mutableStateOf(false); private set
    var approvalAuthenticationNotice by mutableStateOf<String?>(null); private set
    var secretRevision by mutableIntStateOf(0); private set
    var tab by mutableStateOf(Screen.W01); private set
    var historyTab by mutableStateOf(HistoryTab.PURCHASES)
    var receiptFocusId by mutableStateOf<String?>(null); private set
    var homeEntered by mutableStateOf(false)
    var navigationBack by mutableStateOf(false); private set
    private var reviewedApproval: Approval? = null
    private val foregroundHosts = mutableSetOf<Any>()
    private var foreground = true
    // Presentation leases prevent a late read/POST from routing an already-dismissed host.
    // Financial work remains owned by this process-scoped model until it resolves.
    private val presentationLease = ThreadLocal<Long?>()
    private var presentationGeneration = 0L
    private val presentationCurrent get() = presentationLease.get()?.let { it == presentationGeneration } ?: true
    private val notificationEntry = NotificationEntrySession()
    var notificationTarget by mutableStateOf<RequestTarget?>(null); private set
    var paymentResultTarget by mutableStateOf<RequestTarget?>(null); private set
    val notificationEntryId get() = notificationTarget?.id
    var notificationSessionId by mutableStateOf<Long?>(null); private set
    private data class NavigationSnapshot(
        val routes: List<Route>, val tab: Screen,
        val historyTab: HistoryTab, val historyStatus: String, val receiptFocusId: String?, val signedIn: Boolean
    )
    private var notificationOrigin: NavigationSnapshot? = null
    private var refreshOnReturn = false
    private var routes by mutableStateOf(listOf(Route(if (signedIn) Screen.A04 else Screen.A01)))
    private var firstPin = ""
    private var pinKey = ""
    private var incomingRequest: RequestTarget? = null
    private var addingPurchaseAddress: String? = null
    val route get() = routes.last()
    val canBack get() = routes.size > 1 || (!route.screen.focused && tab != Screen.W01)

    init { if (signedIn) execute { registerNotifications(); checkPin() } }

    fun execute(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch(presentationLease.asContextElement(presentationGeneration)) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SessionExpired) { resetSession(); error = "다시 로그인해 주세요." }
            catch (failure: Exception) {
                if (presentationCurrent) error = failure.userMessage()
                if (presentationCurrent && failure is ApiError && failure.status == 429 && failure.code.contains("PIN") &&
                    route.screen in setOf(Screen.A02, Screen.A04, Screen.A05, Screen.A06, Screen.A08,
                        Screen.P02, Screen.P03, Screen.P04, Screen.P05, Screen.P07, Screen.S02)) show(Screen.P06)
            } finally {
                busy = false
                if (!foreground) forgetSecrets()
                val incoming = incomingRequest
                if (signedIn && pin?.status == "ACTIVE" && incoming != null) {
                    incomingRequest = null; acceptRequestLink(incoming)
                } else if (refreshOnReturn) {
                    refreshOnReturn = false
                    if (signedIn) execute {
                        if (pin?.status == "ACTIVE" && route.screen.name.startsWith("A")) enterHome()
                        else refresh()
                    }
                } else if (notificationEntryId != null && signedIn && !presentationCurrent) {
                    // A notification may have arrived while the startup PIN read was suspended.
                    execute {
                        when (pin?.status) {
                            "NOT_REGISTERED" -> show(Screen.A05, replace = true)
                            "LOCKED" -> show(Screen.P06, replace = true)
                            "REVOKED" -> show(Screen.A08, replace = true)
                        }
                    }
                }
            }
        }
    }
    fun show(screen: Screen, id: String? = null, replace: Boolean = false) {
        if (!presentationCurrent) return
        error = null
        navigationBack = false
        val next = Route(screen, id)
        val previous = if (replace) routes.dropLast(1) else routes
        routes = if (previous.lastOrNull() == next) previous else previous + next
    }
    private fun root(screen: Screen = Screen.W01) {
        if (!presentationCurrent) return
        tab = screen; routes = listOf(Route(screen))
    }
    fun back() {
        if (busy && !approvals.submitting && !purchases.submitting) return
        navigationBack = true
        error = null; secretRevision++
        if (route.screen in listOf(Screen.P05, Screen.P07)) {
            val recordIndex = routes.indexOfLast { it.screen == Screen.H03 }
            if (recordIndex >= 0) {
                paymentResultTarget = null; approvals.close(); reviewedApproval = null
                routes = routes.take(recordIndex + 1)
                if (!busy) execute { refresh() }
            } else closeApproval()
            return
        }
        if (route.screen == Screen.C02 && cards.awaitingPay) {
            cards.clearDraft(); root(); show(Screen.W02); execute { cards.load() }; return
        }
        when (route.screen) {
            Screen.C01, Screen.C02 -> cards.clearDraft()
            Screen.A06 -> firstPin = ""
            Screen.P02 -> approvals.close()
            Screen.P03, Screen.P04, Screen.P07 -> { approvals.forgetAuthentication(); reviewedApproval = null }
            Screen.G02 -> products.clear()
            Screen.U01 -> purchases.close()
            Screen.D02 -> addingPurchaseAddress = null
            else -> Unit
        }
        if (route.screen == Screen.A06) { show(Screen.A05, replace = true); return }
        if (route.screen == Screen.C02) { show(Screen.C01, replace = true); return }
        if (routes.size > 1) routes = routes.dropLast(1)
        else if (tab != Screen.W01 && !route.screen.focused) { selectTab(Screen.W01); return }
        if (route.screen in setOf(Screen.P02, Screen.H01)) execute { refresh() }
    }
    fun selectTab(screen: Screen) {
        if (busy && !approvals.submitting && !purchases.submitting) return
        receiptFocusId = null
        navigationBack = screen == tab && route.screen != screen
        // Tabs open their root destination; the UI retains scroll positions and filters separately.
        root(screen)
        execute { refresh() }
    }
    suspend fun refresh() {
        when (route.screen) {
            Screen.W01 -> { cards.load(); approvals.loadInbox(); approvals.loadHistory() }
            Screen.W02 -> cards.load()
            Screen.P01 -> { approvals.loadInbox(); purchases.loadInbox() }
            Screen.U01 -> purchases.open(requireNotNull(route.id))
            Screen.P02 -> approvals.open(requireNotNull(route.id ?: approvals.pendingId))
            Screen.P05, Screen.P07 -> {
                refreshApprovalResult()
            }
            Screen.D01 -> account.loadAddresses()
            Screen.H01 -> when (historyTab) {
                HistoryTab.PURCHASES -> purchaseHistory.load()
                HistoryTab.APPROVALS -> approvals.loadHistory()
                HistoryTab.ORDERS -> account.loadOrders()
            }
            Screen.H02 -> account.openOrder(requireNotNull(route.id))
            Screen.H03 -> purchaseHistory.open(requireNotNull(route.id))
            Screen.S01 -> { pin = api.pay("GET", "/users/me/payment-pin").read(); refreshBiometricStatus(); account.loadProfile() }
            Screen.S03 -> connections.load()
            Screen.T01 -> cart.load()
            Screen.G01 -> catalog.search(catalog.page ?: 1)
            Screen.G02 -> products.reload()
            Screen.A04, Screen.P06 -> checkPin()
            else -> Unit
        }
    }
    fun open(screen: Screen) { if (!busy) { receiptFocusId = null; show(screen); execute { refresh() } } }
    fun openProduct(productId: String, merchantId: String) = execute {
        show(Screen.G02)
        products.load(ProductSelection(productId, merchantId))
    }
    fun login(password: String, reauthenticate: Boolean = false) = execute {
        require(password.isNotBlank()) { "비밀번호를 입력해 주세요." }
        if (signedIn && loginId != store.loginId) {
            val result = closeSession()
            if (result.warning != null) { error = result.warning; return@execute }
        }
        if (loginId != store.loginId) { biometrics.clear(); store.clearBiometricCredential(); biometricRegistered = false }
        api.login(loginId, password)
        signedIn = true
        show(Screen.A04, replace = true)
        registerNotifications()
        checkPin(freshLogin = reauthenticate)
    }
    suspend fun registerNotifications() {
        if (!signedIn) return
        notificationsRegistered = false; notificationError = null
        try {
            check(FirebaseApp.getApps(getApplication()).isNotEmpty())
            val fid = withTimeoutOrNull(10_000) {
                FirebaseMessaging.getInstance().register().await()
                FirebaseInstallations.getInstance().id.await()
            } ?: error("Firebase registration timed out")
            api.pay("PUT", "/notification-installations", buildJsonObject { put("fid", fid) })
            notificationsRegistered = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (expired: SessionExpired) { throw expired }
        catch (_: Exception) { notificationError = "알림 연결에 실패했어요. 다시 연결해 주세요." }
    }
    suspend fun checkPin(freshLogin: Boolean = false) {
        val status = api.pay("GET", "/users/me/payment-pin").read<PinStatus>()
        pin = status
        refreshBiometricStatus()
        when (status.status) {
            "ACTIVE" -> enterHome()
            "NOT_REGISTERED" -> show(Screen.A05, replace = true)
            "LOCKED" -> show(Screen.P06, replace = true)
            "REVOKED" -> show(if (freshLogin) Screen.A05 else Screen.A08, replace = true)
            else -> throw IllegalStateException("PIN 상태를 확인할 수 없어요.")
        }
    }
    fun setFirstPin(value: String) {
        if (!validPin(value)) { error = "PIN 6자리를 입력해 주세요."; return }
        firstPin = value; pinKey = newKey(); show(Screen.A06)
    }
    fun confirmPin(value: String) = execute {
        require(validPin(value) && value == firstPin) { "PIN이 일치하지 않아요." }
        api.pay("POST", "/users/me/payment-pin", buildJsonObject { put("pin", value) }, pinKey)
        firstPin = ""; pinKey = ""; pin = PinStatus(true, "ACTIVE")
        show(Screen.A07, replace = true)
    }
    fun changePin(current: String, next: String, key: String) = execute {
        require(validPin(current) && validPin(next) && current != next) { "현재 PIN과 다른 6자리 PIN을 입력해 주세요." }
        api.pay("PUT", "/users/me/payment-pin", buildJsonObject { put("currentPin", current); put("newPin", next) }, key)
        secretRevision++; root(Screen.S01)
    }
    fun disableBiometric() = execute { revokeBiometric() }
    suspend fun refreshBiometricStatus() {
        try {
            val remote = biometrics.status(api)
            biometricServerRegistered = remote.registered
            biometricRegistered = remote.matches(store.credentialId, biometrics.hasKey())
            biometricStatusError = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (expired: SessionExpired) { throw expired }
        catch (failure: Exception) { biometricStatusError = failure.userMessage() }
    }
    private suspend fun revokeBiometric() {
        biometricRevoking = true
        try {
            revokeBiometricCredential(
                pendingKey = store.biometricRevocationKey,
                persistKey = { store.biometricRevocationKey = it },
                revoke = { key ->
                    api.pay("DELETE", "/users/me/biometric-credentials", key = key, deviceHeader = true)
                },
                clearLocal = {
                    biometrics.clear()
                    store.clearBiometricCredential()
                    biometricRegistered = false
                    biometricServerRegistered = false
                    biometricStatusError = null
                }
            )
        } finally { biometricRevoking = false }
    }
    fun enroll(activity: FragmentActivity) = execute {
        val setup = route.screen == Screen.A07
        biometrics.enroll(activity, api, store, ::revokeBiometric)
        biometricRegistered = true
        biometricServerRegistered = true
        biometricStatusError = null
        if (setup) enterHome()
    }
    suspend fun enterHome() {
        if (!presentationCurrent) return
        root()
        val incoming = incomingRequest
        if (incoming?.kind == RequestTarget.Kind.PAYMENT_RESULT) {
            incomingRequest = null
            paymentResultTarget = incoming
            show(Screen.P05, incoming.id)
            approvals.open(incoming.id)
            return
        }
        if (incoming?.kind == RequestTarget.Kind.PURCHASE) {
            incomingRequest = null
            val target = purchases.pendingId ?: incoming.id
            show(Screen.U01, target)
            purchases.open(target)
            return
        }
        val pending = approvals.pendingId
        if (pending != null) {
            approvals.open(pending); cards.load()
            show(if (approvals.uncertain) Screen.P07 else if (approvals.current?.actionable == true) Screen.P02 else Screen.P05, pending)
        }
        else refresh()
    }
    fun finishCard() = execute {
        cards.load()
        val pending = approvals.pendingId
        if (pending != null) {
            val card = requireNotNull(cards.registered)
            approvals.open(pending)
            val request = requireNotNull(approvals.current)
            if (request.actionable && !request.expired() && approvals.canAuthorize) approvals.selectCard(card.id)
            root()
            show(if (approvals.uncertain) Screen.P07 else if (approvals.current?.actionable == true) Screen.P02 else Screen.P05, pending)
        } else root()
    }
    fun openApproval(id: String) {
        receiptFocusId = null
        if (approvals.submitting) { show(Screen.P07, approvals.pendingId); return }
        execute {
            paymentResultTarget = null
            approvals.open(id); cards.load()
            show(if (approvals.uncertain || !requireNotNull(approvals.current).actionable) Screen.P05 else Screen.P02, id)
        }
    }
    fun openPurchase(id: String) {
        if (!validId(id)) return
        val target = purchases.pendingId ?: id
        execute {
            show(Screen.U01, target)
            purchases.open(target)
        }
    }
    fun openPurchaseRecord(id: String) = execute {
        show(Screen.H03, id)
        purchaseHistory.open(id)
    }
    fun openRecordedApproval(id: String) {
        val record = purchaseHistory.detail?.takeIf { route == Route(Screen.H03, it.id) } ?: return
        if (id !in record.approvalIds) return
        paymentOutcomes.remember(record.purchase)
        openApproval(id)
    }
    fun confirmPurchaseAddress() = execute {
        check(route.screen == Screen.U01 && route.id == purchases.current?.purchaseRequestId)
        purchases.confirm()
    }
    fun retryPurchaseAddress() = execute {
        check(route.screen == Screen.U01 && route.id == purchases.pendingId) { "구매 요청을 다시 확인해 주세요." }
        purchases.retry()
    }
    fun openPurchaseApproval(id: String) {
        if (id !in purchases.current?.approvalIds.orEmpty()) return
        purchases.current?.let(paymentOutcomes::remember)
        acceptApprovalLink(id)
    }
    fun addPurchaseAddress() = execute {
        check(purchases.operation == null) { "이전 선택 결과를 먼저 확인해 주세요." }
        account.loadAddresses()
        if (!presentationCurrent) return@execute
        account.newAddress()
        addingPurchaseAddress = purchases.current?.purchaseRequestId
        show(Screen.D02)
    }
    fun saveAddress() = execute {
        account.saveAddress()
        if (!presentationCurrent) return@execute
        val purchaseId = addingPurchaseAddress
        if (purchaseId == null) show(Screen.D01, replace = true)
        else {
            addingPurchaseAddress = null
            purchases.open(purchaseId)
            if (!presentationCurrent) return@execute
            account.addressId?.takeIf { id -> purchases.addresses.orEmpty().any { it.id == id } }?.let(purchases::select)
            routes = routes.takeWhile { it.screen != Screen.D02 }.let { previous ->
                if (previous.lastOrNull() == Route(Screen.U01, purchaseId)) previous else previous + Route(Screen.U01, purchaseId)
            }
            navigationBack = true
        }
    }
    fun acceptRequestLink(target: RequestTarget) {
        if (target.kind == RequestTarget.Kind.APPROVAL) { acceptApprovalLink(target.id); return }
        if (target.kind == RequestTarget.Kind.PAYMENT_RESULT) {
            val unresolved = approvals.unresolvedId
            if (unresolved != null && unresolved != target.id) { acceptApprovalLink(unresolved); return }
            if (busy || !signedIn || pin?.status != "ACTIVE") incomingRequest = target
            else openPaymentResult(target)
        } else if (busy || !signedIn || pin?.status != "ACTIVE") incomingRequest = target
        else openPurchase(purchases.pendingId ?: target.id)
    }
    private fun openPaymentResult(target: RequestTarget) = execute {
        paymentResultTarget = target
        reviewedApproval = null; approvals.forgetAuthentication(); secretRevision++
        // A result push can never reopen consent or submit/retry a payment.
        show(Screen.P05, target.id, replace = route.isApprovalPresentation(target.id))
        approvals.open(target.id)
    }
    suspend fun refreshApprovalResult() {
        val id = route.id ?: approvals.pendingId ?: return
        if (approvals.pendingId != id) approvals.open(id) else approvals.reconcile()
        refreshPaymentOutcome(id)
    }
    suspend fun refreshPaymentOutcome(id: String) {
        val approval = approvals.current?.takeIf { it.id == id && it.status == "APPROVED" } ?: return
        if (!approvals.uncertain && !approvals.submitting) paymentOutcomes.load(approval)
    }
    fun reloadNotificationEntry() { notificationTarget?.let(::acceptRequestLink) }
    fun resumeApproval() {
        if (!signedIn || pin?.status != "ACTIVE") return
        val id = approvals.unresolvedId ?: approvals.pendingId ?: return
        show(Screen.P07, id)
        if (!busy) execute { approvals.reconcile() }
    }
    fun beginApproval(activity: FragmentActivity) = execute {
        approvalAuthenticationNotice = null
        val request = requireNotNull(approvals.current)
        check(route.screen == Screen.P02 && route.isApprovalPresentation(request.id)) { "결제 정보를 다시 확인해 주세요." }
        approvals.open(request.id)
        val latest = requireNotNull(approvals.current)
        require(latest.actionable && !latest.expired()) { "승인 요청 상태를 다시 확인해 주세요." }
        require(request.sameReviewAs(latest)) { "결제 정보가 변경되었어요. 금액과 카드를 다시 확인해 주세요." }
        require(latest.currency == "KRW" && approvals.canAuthorize) { "처리 상태를 먼저 확인해 주세요." }
        require(!latest.selectedPaymentMethodId.isNullOrBlank()) { "결제 카드를 선택해 주세요." }
        check(presentationCurrent) { "결제 정보를 다시 확인해 주세요." }
        reviewedApproval = latest
        val useBiometric = latest.requiredAction == "BIOMETRIC" && latest.biometricChallenge != null &&
            biometricRegistered && biometrics.hasKey() && biometrics.available(activity)
        show(if (useBiometric) Screen.P04 else Screen.P03, latest.id)
        if (useBiometric) authenticateApprovalBiometric(activity)
    }
    fun approvePin(value: String) = execute {
        check(route.screen == Screen.P03 && reviewedApproval?.let { route.isApprovalPresentation(it.id) } == true && approvals.canAuthorize) {
            "결제 정보를 다시 확인해 주세요."
        }
        submitApproval(pinApprovalBody(value))
    }
    fun approveBiometric(activity: FragmentActivity) = execute { authenticateApprovalBiometric(activity) }
    fun usePinForApproval() {
        val reviewed = reviewedApproval ?: return
        if (busy || route.screen != Screen.P04 || !route.isApprovalPresentation(reviewed.id) || !approvals.canAuthorize) return
        approvalAuthenticationNotice = null
        show(Screen.P03, reviewed.id, replace = true)
    }
    private suspend fun authenticateApprovalBiometric(activity: FragmentActivity) {
        try {
            val reviewed = requireNotNull(reviewedApproval)
            check(route.screen == Screen.P04 && route.isApprovalPresentation(reviewed.id) && approvals.canAuthorize) {
                "결제 정보를 다시 확인해 주세요."
            }
            approvals.open(reviewed.id)
            val latest = requireNotNull(approvals.current)
            if (!reviewed.sameReviewAs(latest) || !latest.actionable || latest.expired()) {
                show(Screen.P02, latest.id, replace = true)
                error("결제 정보가 변경되었어요. 다시 확인해 주세요.")
            }
            val challenge = requireNotNull(latest.biometricChallenge)
            check(presentationCurrent) { "결제 정보를 다시 확인해 주세요." }
            val signature = biometrics.sign(activity, challenge)
            submitApproval(biometricApprovalBody(challenge.challengeId, signature), revalidate = false)
        } catch (failure: BiometricPromptFailure) {
            val id = reviewedApproval?.id
            if (id != null && presentationCurrent && route.screen == Screen.P04 && route.isApprovalPresentation(id) && approvals.canAuthorize) {
                show(Screen.P03, id, replace = true)
                approvalAuthenticationNotice = if (failure.cancelled) "PIN으로 결제를 이어갈 수 있어요."
                    else "생체인식을 사용할 수 없어요. PIN으로 결제해 주세요."
                secretRevision++
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // Already inside execute: await revocation directly instead of being dropped by its busy guard.
            if (failure is android.security.keystore.KeyPermanentlyInvalidatedException) revokeBiometric()
            val id = approvals.pendingId
            if (!approvals.uncertain && route.screen == Screen.P04 && id != null && route.isApprovalPresentation(id)) {
                show(Screen.P03, id, replace = true)
            }
            throw failure
        }
    }
    private suspend fun submitApproval(body: JsonObject, revalidate: Boolean = true) {
        val reviewed = requireNotNull(reviewedApproval) { "결제 정보를 다시 확인해 주세요." }
        try {
            // Review again after authorization: a changed checkout must never inherit prior consent.
            if (revalidate) approvals.open(reviewed.id)
            val latest = requireNotNull(approvals.current)
            if (!reviewed.sameReviewAs(latest) || !latest.actionable || latest.expired()) {
                show(Screen.P02, latest.id, replace = true)
                error("결제 정보가 변경되었어요. 다시 확인해 주세요.")
            }
            check(presentationCurrent) { "결제 정보를 다시 확인해 주세요." }
            show(Screen.P07, reviewed.id, replace = true)
            approvals.approve(body)
            if (route.screen == Screen.P07 && route.isApprovalPresentation(reviewed.id)) show(Screen.P05, reviewed.id, replace = true)
        } catch (failure: Exception) {
            if (route.isApprovalPresentation(reviewed.id)) {
                if (approvals.uncertain) show(Screen.P07, reviewed.id, replace = true)
                else if (route.screen == Screen.P07) show(Screen.P03, reviewed.id, replace = true)
            }
            throw failure
        } finally {
            secretRevision++
            refreshAfterLeavingApproval(reviewed.id)
        }
    }
    fun rejectApproval() = execute {
        val approval = requireNotNull(approvals.current)
        check(route.screen == Screen.P02 && route.isApprovalPresentation(approval.id)) { "결제 정보를 다시 확인해 주세요." }
        try {
            show(Screen.P07, approval.id, replace = true)
            approvals.reject()
            if (route.screen == Screen.P07 && route.isApprovalPresentation(approval.id)) show(Screen.P05, approval.id, replace = true)
        } catch (failure: Exception) {
            if (route.isApprovalPresentation(approval.id)) {
                show(if (approvals.uncertain) Screen.P07 else Screen.P02, approval.id, replace = true)
            }
            throw failure
        } finally {
            refreshAfterLeavingApproval(approval.id)
        }
    }
    fun retryApproval() = execute {
        val id = requireNotNull(approvals.unresolvedId)
        check(route.isApprovalPresentation(id) && approvals.canRetry) { "처리 상태를 먼저 확인해 주세요." }
        try {
            approvals.retry() // Reconciles first, then may reuse the retained body and key.
            if (route.isApprovalPresentation(id)) show(Screen.P05, id, replace = true)
        } finally {
            refreshAfterLeavingApproval(id)
        }
    }
    /** A result refreshes the destination the user chose, without forcing a return to its receipt. */
    private suspend fun refreshAfterLeavingApproval(id: String) {
        if (!presentationCurrent || route.isApprovalPresentation(id) || approvals.uncertain || approvals.result == null || !signedIn) return
        if (route.screen !in setOf(Screen.W01, Screen.P01, Screen.H01, Screen.H03)) return
        if (route.screen == Screen.W01 || (route.screen == Screen.H01 && historyTab == HistoryTab.APPROVALS && receiptFocusId == id)) {
            approvals.result?.takeIf { it in approvalStatuses }?.let { approvals.historyStatus = it }
        }
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (expired: SessionExpired) { throw expired }
        catch (failure: Exception) { error = failure.userMessage() }
    }
    /** Leaving changes presentation only. An accepted or unresolved operation is never canceled. */
    fun closeApproval(toHistory: Boolean = false) {
        paymentResultTarget = null
        val purchaseId = if (toHistory && !approvals.uncertain && !approvals.submitting &&
            paymentOutcomes.approvalId == approvals.current?.id) paymentOutcomes.purchase?.purchaseRequestId else null
        if (toHistory) {
            receiptFocusId = approvals.prepareReceiptHistory()?.id
            historyTab = if (purchaseId != null) HistoryTab.PURCHASES else HistoryTab.APPROVALS
        } else receiptFocusId = null
        approvals.close()
        reviewedApproval = null
        root(if (toHistory) Screen.H01 else Screen.W01)
        if (purchaseId != null) show(Screen.H03, purchaseId)
        if (!busy) execute { refresh() }
    }
    fun saveHosts(core: String, pay: String, pg: String) = execute {
        val coreUrl = normalizeHost(core, BuildConfig.DEBUG)
        val payUrl = normalizeHost(pay, BuildConfig.DEBUG)
        val pgUrl = if (pg.isBlank()) "" else normalizeHost(pg, BuildConfig.DEBUG)
        val result = if (signedIn) closeSession() else { resetSession(); null }
        store.saveHosts(coreUrl, payUrl, pgUrl)
        error = result?.warning
    }
    fun logout() = execute { error = closeSession().warning }
    private suspend fun closeSession() = endSession(
        disconnect = {
            check(FirebaseApp.getApps(getApplication()).isNotEmpty())
            val fid = FirebaseInstallations.getInstance().id.await()
            require(fid.isNotBlank())
            api.pay("DELETE", "/notification-installations/$fid")
        },
        revoke = { api.logout() },
        clear = ::resetSession
    )
    private fun resetSession() {
        paymentResultTarget = null
        notificationOrigin = null // Never restore routes belonging to a previous authentication session.
        productImages.clear()
        store.clearSession(); biometrics.clear(); cards.clear(); approvals.clear(); paymentOutcomes.clear(); purchaseHistory.clear(); account.clear(); purchases.clear()
        historyTab = HistoryTab.PURCHASES
        connections.clear()
        cart.clear()
        catalog.clear()
        products.clear()
        signedIn = false; pin = null; biometricRegistered = false; biometricServerRegistered = false
        biometricStatusError = null; approvalAuthenticationNotice = null; firstPin = ""; pinKey = ""
        notificationsRegistered = false; notificationError = null
        incomingRequest = notificationTarget; addingPurchaseAddress = null; homeEntered = false; receiptFocusId = null; reviewedApproval = null; routes = listOf(Route(Screen.A01)); secretRevision++
    }
    fun acceptApprovalLink(id: String) {
        if (!validId(id)) return
        val unresolved = approvals.unresolvedId
        if (unresolved != null && unresolved != id) {
            // A deep link must not replace the durable reference of a possibly accepted operation.
            if (signedIn && pin?.status == "ACTIVE") resumeApproval()
            else approvals.pendingId = unresolved
            error = "진행 중인 결제 결과를 먼저 확인해 주세요."
            return
        }
        if (busy) incomingRequest = RequestTarget(RequestTarget.Kind.APPROVAL, id)
        else if (signedIn && pin?.status == "ACTIVE") openApproval(id)
        else approvals.pendingId = id
    }
    /** Notification contents provide identity only; this still performs server reads and normal authentication. */
    fun beginNotificationEntry(id: String) {
        if (!validId(id)) return
        beginNotificationEntry(RequestTarget(RequestTarget.Kind.APPROVAL, id))
    }
    fun beginNotificationEntry(request: RequestTarget) {
        val active = notificationTarget
        if (active != null) {
            if (request.kind == RequestTarget.Kind.PAYMENT_RESULT &&
                (active.kind != RequestTarget.Kind.PURCHASE && active.id == request.id || route.isApprovalPresentation(request.id))) {
                notificationTarget = request
                acceptRequestLink(request)
                return
            }
            // A user tap may continue this purchase into its server-linked approval, never an unrelated request.
            if (active.kind == RequestTarget.Kind.PURCHASE && request.kind == RequestTarget.Kind.APPROVAL &&
                route.screen == Screen.U01 && !busy && request.id in purchases.current?.approvalIds.orEmpty()) {
                openPurchaseApproval(request.id)
            } else if (active != request && route.id != request.id) error = "현재 요청을 먼저 확인해 주세요."
            return // Duplicate taps must preserve review, PIN entry, and the logical operation.
        }
        notificationOrigin = NavigationSnapshot(routes, tab, historyTab, approvals.historyStatus, receiptFocusId, signedIn)
        presentationGeneration++
        val target = approvals.unresolvedId?.let {
            if (request.kind == RequestTarget.Kind.PAYMENT_RESULT && request.id == it) request
            else RequestTarget(RequestTarget.Kind.APPROVAL, it)
        }
            ?: purchases.pendingId?.let { RequestTarget(RequestTarget.Kind.PURCHASE, it) } ?: request
        notificationEntry.start(target.identity)
        notificationTarget = target
        notificationSessionId = notificationEntry.sessionId
        reviewedApproval = null
        secretRevision++
        // Do not expose the previous wallet screen or another request while this read is pending.
        routes = listOf(Route(when {
            !signedIn -> Screen.A01
            pin?.status == "ACTIVE" -> Screen.N01
            pin?.status == "LOCKED" -> Screen.P06
            pin?.status == "NOT_REGISTERED" -> Screen.A05
            pin?.status == "REVOKED" -> Screen.A08
            else -> Screen.A04
        }, target.id))
        acceptRequestLink(target)
        if (target != request) error = "진행 중인 요청 결과를 먼저 확인해 주세요."
    }
    /** Close only the notification presentation; never cancel a dispatched payment or clear its durable reference. */
    fun endNotificationEntry() {
        if (notificationEntryId == null) return
        presentationGeneration++
        notificationEntry.close()
        notificationTarget = null
        paymentResultTarget = null
        notificationSessionId = null
        incomingRequest = null
        addingPurchaseAddress = null
        reviewedApproval = null
        secretRevision++
        approvals.close()
        purchases.close()
        val origin = notificationOrigin
        notificationOrigin = null
        error = null
        if (origin != null && origin.signedIn == signedIn &&
            !(pin?.status == "ACTIVE" && origin.routes.last().screen in setOf(Screen.A01, Screen.A02, Screen.A03, Screen.A04, Screen.A05, Screen.A06, Screen.A07, Screen.A08, Screen.P06))) {
            fun safeRoutes(saved: List<Route>) = saved.map {
                val unresolved = approvals.unresolvedId
                when {
                    unresolved != null && it.id != unresolved && it.screen in setOf(Screen.P02, Screen.P03, Screen.P04, Screen.P05, Screen.P07) -> Route(Screen.P07, unresolved)
                    it.screen in setOf(Screen.P03, Screen.P04) -> Route(Screen.P02, it.id)
                    else -> it
                }
            }
            tab = origin.tab
            historyTab = origin.historyTab
            approvals.historyStatus = origin.historyStatus
            receiptFocusId = origin.receiptFocusId
            // Authentication cannot be restored from a presentation snapshot.
            routes = safeRoutes(origin.routes)
        } else if (signedIn && pin?.status == "ACTIVE") root()
        else { routes = listOf(Route(if (signedIn) Screen.A04 else Screen.A01)) }
        navigationBack = true
        if (busy) refreshOnReturn = true
        else if (signedIn) execute { refresh() }
    }
    /** Opening the full wallet takes presentation ownership from a parked notification task. */
    fun enterWallet() { if (notificationEntryId != null) endNotificationEntry() }
    fun foregrounded(host: Any) { foregroundHosts.add(host); foreground = true }
    fun backgrounded(host: Any, changingConfigurations: Boolean = false) {
        foregroundHosts.remove(host)
        foreground = foregroundHosts.isNotEmpty()
        if (!foreground && !changingConfigurations) forgetSecrets()
    }
    private fun forgetSecrets() {
        secretRevision++; firstPin = ""
        if (!busy) {
            cards.clearDraft(); approvals.forgetAuthentication()
            when (route.screen) {
                Screen.C02 -> show(Screen.C01, replace = true)
                Screen.A06 -> show(Screen.A05, replace = true)
                Screen.P03, Screen.P04 -> { reviewedApproval = null; show(Screen.P07, approvals.pendingId, replace = true) }
                else -> Unit
            }
        }
    }
    override fun onCleared() { cards.clearDraft(); approvals.forgetAuthentication(); firstPin = "" }
}
