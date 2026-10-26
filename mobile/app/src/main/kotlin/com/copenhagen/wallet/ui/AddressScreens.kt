package com.copenhagen.wallet.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.*

@Composable fun AddressesScreen(app: AppModel) {
    var pendingDefaultId by remember { mutableStateOf<String?>(null) }
    Page {
        Heading("배송지")
        Data(app.account.addresses, app.busy, "배송지를 불러오지 못했어요.", { app.execute { app.account.loadAddresses() } }) { rows ->
            if (rows.isEmpty()) QuietEmptyState(WalletIcon.Location, "등록된 배송지가 없어요")
            DefaultSelectionList(rows, { it.id }, { it.isDefault }) { address, emphasis -> Tile(modifier = emphasis) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(WalletIcon.Location, tint = Carbon.Blue)
                    Text(address.recipient, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    DefaultBadge(address.isDefault, "기본 배송지")
                }
                Text(address.phone, color = Carbon.Muted)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(address.address, style = MaterialTheme.typography.bodyLarge)
                    if (address.detail.isNotBlank()) Text(address.detail, style = MaterialTheme.typography.bodyLarge)
                    Text(address.postalCode, color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                if (address.memo.isNotBlank()) Text(address.memo, color = Carbon.Muted)
                HorizontalDivider(color = Carbon.Line)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ app.execute { app.account.editAddress(address.id); app.show(Screen.D02, address.id) } },
                        enabled = !app.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("수정") }
                    if (!address.isDefault) DefaultAction("기본 배송지로 지정", true, app.busy, pendingDefaultId == address.id,
                        modifier = Modifier.weight(1f)) {
                        app.execute {
                            pendingDefaultId = address.id
                            try { app.account.defaultAddress(address.id) } finally { pendingDefaultId = null }
                        }
                    }
                }
            } }
        }
        if (app.account.addresses.orEmpty().size >= 20) Notice("배송지는 20개까지 등록할 수 있어요.")
        Action("배송지 추가", !app.busy && app.account.addresses.orEmpty().size < 20, "E22") {
            app.account.newAddress(); app.show(Screen.D02)
        }
    }
}
@Composable fun AddressEditorScreen(app: AppModel) {
    var deleting by remember { mutableStateOf(false) }
    val account = app.account
    val form = account.form
    Page {
        Heading(if (account.addressId == null) "배송지 추가" else "배송지 수정")
        Section("받는 분")
        Field(form.recipient, { account.form = form.copy(recipient = it) }, "수령인", enabled = !app.busy, maxLength = 40)
        Field(form.phone, { account.form = form.copy(phone = it) }, "연락처", enabled = !app.busy, keyboard = KeyboardType.Phone, maxLength = 20)
        Section("받으실 주소")
        Secondary("주소 검색", !app.busy) { app.show(Screen.D03) }
        Field(form.postalCode, { account.form = form.copy(postalCode = it) }, "우편번호", enabled = !app.busy, keyboard = KeyboardType.Number, maxLength = 5)
        Field(form.address, { account.form = form.copy(address = it) }, "주소", enabled = !app.busy)
        Field(form.detail, { account.form = form.copy(detail = it) }, "상세주소", enabled = !app.busy)
        Field(form.memo, { account.form = form.copy(memo = it) }, "배송메모", enabled = !app.busy, maxLength = 100)
        Surface(color = Carbon.Surface, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(account.makeDefault, enabled = !app.busy, role = Role.Checkbox,
                onValueChange = { account.makeDefault = it }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(account.makeDefault, onCheckedChange = null, enabled = !app.busy)
                Text("기본 배송지로 지정", Modifier.weight(1f).padding(start = 12.dp))
            }
        }
        Action("저장", !app.busy && form.valid, "E27") {
            app.saveAddress()
        }
        if (account.addressId != null) Secondary("배송지 삭제", !app.busy) { deleting = true }
    }
    if (deleting) Confirm("배송지를 삭제할까요?", "삭제", { deleting = false }) {
        deleting = false
        app.execute { account.deleteAddress(); app.show(Screen.D01, replace = true) }
    }
}

/** 우편번호 검색은 공개 Kakao embed API만 사용한다. 앱 인증 토큰·네이티브 브리지는 노출하지 않는다. */
@SuppressLint("SetJavaScriptEnabled")
@Composable fun PostalSearchScreen(app: AppModel) {
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (failed) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Notice("주소 검색에 연결하지 못했어요.")
            Secondary("다시 검색") { failed = false; revision++ }
        }
        key(revision) {
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context -> WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (request.isForMainFrame && uri.scheme == "copenhagen-postcode" && uri.host == "selected") {
                            val zip = uri.getQueryParameter("zip").orEmpty()
                            val address = uri.getQueryParameter("address").orEmpty()
                            if (zip.matches(Regex("[0-9]{5}")) && address.isNotBlank() && address.length <= 200) {
                                app.account.form = app.account.form.copy(postalCode = zip, address = address)
                                app.back()
                            }
                            return true
                        }
                        // Kakao embed first creates an about:blank iframe, then its HTTPS search frame.
                        // Keep that bootstrap frame usable while blocking unrelated top-level navigation.
                        return request.isForMainFrame || (uri.scheme != "https" && uri.toString() != "about:blank")
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) failed = true
                    }
                }
                loadDataWithBaseURL("https://postcode.map.kakao.com/", postalHtml, "text/html", "UTF-8", null)
            } }, onReset = null, onRelease = { it.stopLoading(); it.destroy() })
        }
    }
}
private val postalHtml = """
<!doctype html><html lang="ko"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body,#postcode{margin:0;width:100%;height:100%}#error{padding:24px;font:16px sans-serif}</style>
<div id="postcode"></div><div id="error" hidden>주소 검색에 연결하지 못했어요. 뒤로 가서 다시 시도해 주세요.</div>
<script src="https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js"
onerror="document.getElementById('error').hidden=false"></script><script>
if(window.kakao && kakao.Postcode) new kakao.Postcode({width:'100%',height:'100%',oncomplete:function(data){
var address=data.userSelectedType==='R' ? data.roadAddress : data.jibunAddress;
if(data.userSelectedType==='R'){
 var extra=[]; if(data.bname && /[동로가]${'$'}/.test(data.bname)) extra.push(data.bname);
 if(data.buildingName && data.apartment==='Y') extra.push(data.buildingName);
 if(extra.length) address+=' ('+extra.join(', ')+')';
}
location.href='copenhagen-postcode://selected?zip='+encodeURIComponent(data.zonecode)+'&address='+encodeURIComponent(address);
}}).embed(document.getElementById('postcode'));
</script></html>
""".trimIndent()
