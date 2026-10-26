package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.PayPush
import com.copenhagen.wallet.data.RequestTarget
import com.copenhagen.wallet.data.json
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Bounded, durable resend suppression. Stores fingerprints only, without payment/customer data. */
class PushDeliveryJournal(saved: String? = null) {
    @Serializable
    private data class Entry(val event: String, val approval: String?, val payment: String?, val terminal: Boolean)
    private val entries = saved?.let { runCatching { json.decodeFromString<List<Entry>>(it) }.getOrNull() }.orEmpty().takeLast(512).toMutableList()

    fun shouldPost(push: PayPush): Boolean {
        if (entries.any { it.event == hash(push.eventId) }) return false
        if (push.target.kind == RequestTarget.Kind.APPROVAL && entries.any { it.approval == hash(push.target.id) && it.payment != null }) return false
        if (push.target.kind == RequestTarget.Kind.PAYMENT_RESULT && entries.any { it.payment == hash(requireNotNull(push.target.paymentId)) && it.terminal }) return false
        return true
    }
    fun record(push: PayPush) {
        entries += Entry(hash(push.eventId), push.target.id.takeIf { push.target.kind != RequestTarget.Kind.PURCHASE }?.let(::hash),
            push.target.paymentId?.let(::hash), push.terminal)
        while (entries.size > 512) entries.removeAt(0)
    }
    fun snapshot(): String = json.encodeToString(entries)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
