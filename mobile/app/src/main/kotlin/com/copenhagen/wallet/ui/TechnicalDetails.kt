package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Reference numbers stay available for support without competing with the purchase itself. */
@Composable internal fun TechnicalDetails(
    stateKey: String,
    title: String = "상세 정보",
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "펼쳐짐" else "접힘" },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Text(title, Modifier.weight(1f), color = Carbon.Muted,
                style = MaterialTheme.typography.bodyMedium)
            Glyph(WalletIcon.ChevronDown,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f), tint = Carbon.Muted)
        }
        MotionReveal(expanded) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}
