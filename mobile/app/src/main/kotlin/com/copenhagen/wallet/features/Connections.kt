package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AgentConnection(
    val connectionId: String,
    val clientName: String,
    val logoUrl: String? = null,
    val scopes: List<String>,
    val connectedAt: String,
    val lastUsedAt: String? = null
)

/** AppModel.execute가 요청을 직렬화하며 세션 종료 시 clear한다. */
class Connections(private val request: suspend (String, String) -> JsonElement) {
    var items by mutableStateOf<List<AgentConnection>?>(null); private set

    suspend fun load() {
        items = null
        items = request("GET", "/users/me/agent-connections").read<List<AgentConnection>>()
    }

    suspend fun disconnect(id: String) {
        require(validId(id)) { "연결 정보를 다시 불러와 주세요." }
        request("DELETE", "/users/me/agent-connections/$id")
        load()
    }

    fun clear() { items = null }
}
