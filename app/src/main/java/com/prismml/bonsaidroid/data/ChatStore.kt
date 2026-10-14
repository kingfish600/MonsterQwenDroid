package com.prismml.bonsaidroid.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ChatTurn(val role: String, val content: String, val reasoning: String = "")

@Serializable
data class ChatSession(
    val id: Long,
    val title: String,
    val modelFile: String,
    val createdAtEpochMs: Long,
    /** Per-chat reasoning_effort override; "" = engine profile default. */
    val reasoningOverride: String = "",
    val turns: MutableList<ChatTurn> = mutableListOf(),
)

/**
 * File-backed chat persistence (one JSON per session under app storage).
 * Deliberately not Room: sessions are few and small; the heavy state lives in
 * the native runtime, not the database.
 */
class ChatStore(context: Context) {

    private val dir = File(
        context.getExternalFilesDir(null) ?: context.filesDir, "chats"
    ).apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _sessions = MutableStateFlow(loadAll())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    fun newSession(modelFile: String): ChatSession {
        val s = ChatSession(
            id = System.currentTimeMillis(),
            title = "Chat ${_sessions.value.size + 1}",
            modelFile = modelFile,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        persist(s)
        _sessions.value = listOf(s) + _sessions.value
        return s
    }

    fun append(sessionId: Long, turn: ChatTurn) {
        update(sessionId) { s ->
            val next = s.copy(turns = (s.turns + turn).toMutableList())
            // Auto-title from the first user line; beats "Chat 3" in the list.
            if (s.title.startsWith("Chat ") && turn.role == "user") {
                next.copy(title = turn.content.lineSequence().firstOrNull()?.take(42)?.ifBlank { s.title } ?: s.title)
            } else next
        }
    }

    /** General-purpose mutation: transform one session and persist it atomically. */
    fun update(sessionId: Long, transform: (ChatSession) -> ChatSession) {
        _sessions.value = _sessions.value.map { s ->
            if (s.id == sessionId) transform(s).also { persist(it) } else s
        }
    }

    fun get(sessionId: Long): ChatSession? = _sessions.value.firstOrNull { it.id == sessionId }

    fun delete(sessionId: Long) {
        sessionFile(sessionId).delete()
        _sessions.value = _sessions.value.filterNot { it.id == sessionId }
    }

    private fun loadAll(): List<ChatSession> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    json.decodeFromString(ChatSession.serializer(), f.readText())
                }.getOrNull()
            }
            ?.sortedByDescending { it.createdAtEpochMs }
            .orEmpty()

    private fun persist(s: ChatSession) {
        sessionFile(s.id).writeText(json.encodeToString(ChatSession.serializer(), s))
    }

    private fun sessionFile(id: Long) = File(dir, "chat-$id.json")
}
