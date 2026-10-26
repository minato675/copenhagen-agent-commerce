package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp

/** Original 24-unit line drawings. The control owns its name; ornamental glyphs stay silent. */
enum class WalletIcon { Home, Card, Pay, History, Profile, Back, Close, Add, Check, Clock, ArrowRight, Search, Bag, Location, Shield, Refresh, Link, ChevronDown, Minus, Info, Trash, Box, Settings, Eye, EyeOff }
private val glyphs = WalletIcon.entries.associateWith { icon ->
    ImageVector.Builder(name = "Copenhagen${icon.name}", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f, autoMirror = icon in listOf(WalletIcon.Back, WalletIcon.ArrowRight)).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (icon) {
                WalletIcon.Home -> { moveTo(3f,10f); lineTo(12f,3f); lineTo(21f,10f); moveTo(5f,9f); lineTo(5f,21f); lineTo(10f,21f); lineTo(10f,15f); lineTo(14f,15f); lineTo(14f,21f); lineTo(19f,21f); lineTo(19f,9f) }
                WalletIcon.Card -> { moveTo(5f,5f); lineTo(19f,5f); quadTo(22f,5f,22f,8f); lineTo(22f,17f); quadTo(22f,20f,19f,20f); lineTo(5f,20f); quadTo(2f,20f,2f,17f); lineTo(2f,8f); quadTo(2f,5f,5f,5f); close(); moveTo(2f,10f); lineTo(22f,10f); moveTo(6f,16f); lineTo(10f,16f) }
                WalletIcon.Pay -> { moveTo(4f,9f); lineTo(17f,9f); moveTo(13f,5f); lineTo(17f,9f); lineTo(13f,13f); moveTo(20f,15f); lineTo(7f,15f); moveTo(11f,11f); lineTo(7f,15f); lineTo(11f,19f) }
                WalletIcon.History -> { moveTo(6f,3f); lineTo(18f,3f); lineTo(18f,21f); lineTo(15f,19f); lineTo(12f,21f); lineTo(9f,19f); lineTo(6f,21f); close(); moveTo(9f,8f); lineTo(15f,8f); moveTo(9f,12f); lineTo(15f,12f); moveTo(9f,16f); lineTo(12f,16f) }
                WalletIcon.Profile -> { moveTo(16f,7f); curveTo(16f,12.3f,8f,12.3f,8f,7f); curveTo(8f,1.7f,16f,1.7f,16f,7f); moveTo(4f,21f); curveTo(4f,11.7f,20f,11.7f,20f,21f) }
                WalletIcon.Back -> { moveTo(14f,5f); lineTo(7f,12f); lineTo(14f,19f); moveTo(7f,12f); lineTo(21f,12f) }
                WalletIcon.ArrowRight -> { moveTo(4f,12f); lineTo(20f,12f); moveTo(14f,6f); lineTo(20f,12f); lineTo(14f,18f) }
                WalletIcon.Close -> { moveTo(6f,6f); lineTo(18f,18f); moveTo(18f,6f); lineTo(6f,18f) }
                WalletIcon.Add -> { moveTo(12f,5f); lineTo(12f,19f); moveTo(5f,12f); lineTo(19f,12f) }
                WalletIcon.Minus -> { moveTo(5f,12f); lineTo(19f,12f) }
                WalletIcon.Check -> { moveTo(5f,12f); lineTo(10f,17f); lineTo(20f,6f) }
                WalletIcon.Clock -> { moveTo(21f,12f); curveTo(21f,24f,3f,24f,3f,12f); curveTo(3f,0f,21f,0f,21f,12f); moveTo(12f,6f); lineTo(12f,12f); lineTo(16f,14f) }
                WalletIcon.Search -> { moveTo(17f,10f); curveTo(17f,19.3f,3f,19.3f,3f,10f); curveTo(3f,.7f,17f,.7f,17f,10f); moveTo(15f,15f); lineTo(21f,21f) }
                WalletIcon.Bag -> { moveTo(5f,8f); lineTo(19f,8f); lineTo(21f,21f); lineTo(3f,21f); close(); moveTo(8f,9f); lineTo(8f,6f); curveTo(8f,.7f,16f,.7f,16f,6f); lineTo(16f,9f) }
                WalletIcon.Location -> { moveTo(12f,22f); curveTo(7f,16f,4f,12f,4f,9f); curveTo(4f,-1f,20f,-1f,20f,9f); curveTo(20f,12f,17f,16f,12f,22f); moveTo(15f,9f); curveTo(15f,13f,9f,13f,9f,9f); curveTo(9f,5f,15f,5f,15f,9f) }
                WalletIcon.Shield -> { moveTo(12f,2f); lineTo(21f,6f); lineTo(20f,13f); curveTo(19f,18f,15f,21f,12f,22f); curveTo(9f,21f,5f,18f,4f,13f); lineTo(3f,6f); close(); moveTo(8f,12f); lineTo(11f,15f); lineTo(16f,9f) }
                WalletIcon.Refresh -> { moveTo(20f,8f); curveTo(16f,0f,3f,3f,3f,12f); curveTo(3f,21f,16f,24f,20f,16f); moveTo(20f,3f); lineTo(20f,8f); lineTo(15f,8f) }
                WalletIcon.Link -> { moveTo(9f,15f); lineTo(15f,9f); moveTo(7f,11f); lineTo(4f,14f); curveTo(-1f,19f,5f,25f,10f,20f); lineTo(13f,17f); moveTo(11f,7f); lineTo(14f,4f); curveTo(19f,-1f,25f,5f,20f,10f); lineTo(17f,13f) }
                WalletIcon.ChevronDown -> { moveTo(6f,9f); lineTo(12f,15f); lineTo(18f,9f) }
                WalletIcon.Info -> { moveTo(21f,12f); curveTo(21f,24f,3f,24f,3f,12f); curveTo(3f,0f,21f,0f,21f,12f); moveTo(12f,11f); lineTo(12f,17f); moveTo(12f,7f); lineTo(12f,7.1f) }
                WalletIcon.Trash -> { moveTo(4f,6f); lineTo(20f,6f); moveTo(9f,6f); lineTo(9f,3f); lineTo(15f,3f); lineTo(15f,6f); moveTo(6f,6f); lineTo(7f,21f); lineTo(17f,21f); lineTo(18f,6f); moveTo(10f,10f); lineTo(10f,17f); moveTo(14f,10f); lineTo(14f,17f) }
                WalletIcon.Box -> { moveTo(3f,7f); lineTo(12f,2f); lineTo(21f,7f); lineTo(21f,17f); lineTo(12f,22f); lineTo(3f,17f); close(); moveTo(3f,7f); lineTo(12f,12f); lineTo(21f,7f); moveTo(12f,12f); lineTo(12f,22f); moveTo(8f,4f); lineTo(17f,9f) }
                WalletIcon.Settings -> { moveTo(4f,6f); lineTo(20f,6f); moveTo(4f,12f); lineTo(20f,12f); moveTo(4f,18f); lineTo(20f,18f); moveTo(8f,3f); lineTo(8f,9f); moveTo(16f,9f); lineTo(16f,15f); moveTo(10f,15f); lineTo(10f,21f) }
                WalletIcon.Eye -> {
                    moveTo(2f,12f); curveTo(6f,3f,18f,3f,22f,12f); curveTo(18f,21f,6f,21f,2f,12f); close()
                    moveTo(15f,12f); curveTo(15f,16f,9f,16f,9f,12f); curveTo(9f,8f,15f,8f,15f,12f)
                }
                WalletIcon.EyeOff -> {
                    moveTo(3f,3f); lineTo(21f,21f)
                    moveTo(9f,5.7f); curveTo(14f,4.2f,19f,7f,22f,12f); curveTo(21f,14f,19.5f,15.5f,18f,16.5f)
                    moveTo(6f,7.5f); curveTo(4.4f,8.7f,3f,10.2f,2f,12f); curveTo(5.5f,18.5f,12f,20.2f,16f,18f)
                    moveTo(9f,11f); curveTo(8f,14f,11f,16f,13f,15f)
                }
            }
        }
    }.build()
}
@Composable fun Glyph(icon: WalletIcon, description: String? = null, modifier: Modifier = Modifier.size(24.dp), tint: Color = Carbon.Ink) {
    Icon(glyphs.getValue(icon), description, modifier, tint)
}
