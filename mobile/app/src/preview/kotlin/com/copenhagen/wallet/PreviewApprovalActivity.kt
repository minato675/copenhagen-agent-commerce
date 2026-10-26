package com.copenhagen.wallet

import androidx.compose.runtime.Composable

/** Same notification host and shared model; only the isolated fixture window is recordable. */
class PreviewApprovalActivity : ApprovalActivity() {
    override val secureWindow = false

    @Composable
    override fun RenderContent(content: @Composable () -> Unit) { FixtureFrame(content) }
}
