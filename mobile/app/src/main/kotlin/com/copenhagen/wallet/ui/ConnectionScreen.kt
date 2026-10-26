package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.AppModel
import com.copenhagen.wallet.data.connectionScopeLabel
import com.copenhagen.wallet.features.AgentConnection

@Composable fun ConnectionsScreen(app: AppModel) {
    var disconnect by remember { mutableStateOf<AgentConnection?>(null) }
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AI 서비스", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            IconButton({ app.execute { app.connections.load() } }, enabled = !app.busy) { Glyph(WalletIcon.Refresh, "새로고침") }
        }
        Data(app.connections.items, app.busy, "연결 정보를 불러오지 못했어요.",
            { app.execute { app.connections.load() } }) { items ->
            if (items.isEmpty()) QuietEmptyState(WalletIcon.Link, "연결된 AI 서비스가 없어요")
            items.forEach { connection -> key(connection.connectionId) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Tile {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RemoteImage(connection.logoUrl, "${connection.clientName} 로고", Modifier.size(48.dp))
                        Text(connection.clientName, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Glyph(WalletIcon.Link, tint = Carbon.Muted)
                    }
                    TextButton({ expanded = !expanded }) {
                        Text(if (expanded) "연결 정보 접기" else "연결 정보")
                        Spacer(Modifier.width(8.dp))
                        Glyph(WalletIcon.ChevronDown, modifier = Modifier.size(20.dp), tint = Carbon.Blue)
                    }
                    MotionReveal(expanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            DetailLine("허용된 권한", connection.scopes.map(::connectionScopeLabel).joinToString(" · ").ifBlank { "권한 없음" })
                            DetailLine("연결 일시", connection.connectedAt)
                            DetailLine("최근 사용", connection.lastUsedAt ?: "기록 없음")
                        }
                    }
                    Secondary("연결 해제", !app.busy) { disconnect = connection }
                }
            } }
        }
    }
    disconnect?.let { connection ->
        Confirm("${connection.clientName} 연결 해제", "해제", { disconnect = null }) {
            disconnect = null
            app.execute { app.connections.disconnect(connection.connectionId) }
        }
    }
}
