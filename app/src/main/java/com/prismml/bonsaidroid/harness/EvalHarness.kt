package com.prismml.bonsaidroid.harness

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.prismml.bonsaidroid.engine.BonsaiEngine
import com.prismml.bonsaidroid.engine.ChatMessage
import com.prismml.bonsaidroid.engine.CompletionEvent
import com.prismml.bonsaidroid.engine.EngineProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Greedy-decoding evaluation runner for small JSONL suites.
 *
 * Case format (one JSON object per line):
 * ```json
 * {"id":"qs-1","messages":[{"role":"user","content":"What is 17*23?"}],
 *  "expectContains":["391"],"maxTokens":256}
 * ```
 *
 * Scoring: case-insensitive substring match against `expectContains`
 * (empty list = informational run). Results export as JSONL into Downloads.
 */
class EvalHarness(private val context: Context, private val engine: BonsaiEngine) {

    @Serializable
    data class EvalCase(
        val id: String,
        val messages: List<Msg>,
        val expectContains: List<String> = emptyList(),
        val maxTokens: Int = 16_384, // Bonsai-2: thinking eats the budget; <16k -> empty
    ) {
        @Serializable
        data class Msg(val role: String, val content: String) {
            fun toChat() = ChatMessage(role, content)
        }
    }

    @Serializable
    data class EvalOutcome(
        val id: String,
        val passed: Boolean,
        val tokPerSec: Float,
        val ttftMs: Long?,
        val answerPreview: String,
    )

    sealed interface Progress {
        data object Idle : Progress
        data class Running(val index: Int, val total: Int, val id: String) : Progress
        data class Finished(val outcomes: List<EvalOutcome>, val accuracy: Float?) : Progress
        data class Failed(val message: String) : Progress
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    fun parseCases(text: String): List<EvalCase> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .map { json.decodeFromString(EvalCase.serializer(), it) }
            .toList()

    suspend fun run(cases: List<EvalCase>, profile: EngineProfile): Progress {
        if (cases.isEmpty()) {
            _progress.value = Progress.Failed("no cases")
            return _progress.value
        }
        val outcomes = mutableListOf<EvalOutcome>()
        try {
            cases.forEachIndexed { i, c ->
                _progress.value = Progress.Running(i + 1, cases.size, c.id)

                var ttftMs: Long? = null
                var done: CompletionEvent.Done? = null
                val t0 = System.currentTimeMillis()

                engine.streamChat(
                    messages = c.messages.map { it.toChat() },
                    profile = profile.copy(temperature = 0f), // greedy
                    maxTokens = c.maxTokens,
                ).collect { ev ->
                    when (ev) {
                        is CompletionEvent.Token ->
                            if (ttftMs == null) ttftMs = System.currentTimeMillis() - t0
                        is CompletionEvent.Done -> done = ev
                        is CompletionEvent.Error -> error(ev.message)
                        else -> Unit
                    }
                }

                val d = checkNotNull(done) { "stream ended without Done" }
                val wallS = (System.currentTimeMillis() - t0).coerceAtLeast(1) / 1000f
                val answer = d.fullText.trim()
                val serverTokPerSec = d.timings.predictedPerSecond
                val estTokPerSec = answer.split(WHITESPACE).size / wallS

                outcomes += EvalOutcome(
                    id = c.id,
                    passed = c.expectContains.isEmpty() ||
                        c.expectContains.any { answer.contains(it, ignoreCase = true) },
                    tokPerSec = if (serverTokPerSec > 0f) serverTokPerSec else estTokPerSec,
                    ttftMs = ttftMs,
                    answerPreview = answer.take(160),
                )
            }
            _progress.value = finished(outcomes)
        } catch (t: Throwable) {
            _progress.value = Progress.Failed(t.message ?: t.javaClass.simpleName)
        }
        return _progress.value
    }

    private fun finished(outcomes: List<EvalOutcome>): Progress =
        Progress.Finished(outcomes, outcomes.takeIf { it.isNotEmpty() }?.let { list ->
            list.count { it.passed }.toFloat() / list.size
        })

    /** Exports outcomes as JSONL into public Downloads. Returns display path. */
    fun export(outcomes: List<EvalOutcome>): String? {
        val body = outcomes.joinToString("\n") {
            json.encodeToString(EvalOutcome.serializer(), it)
        } + "\n"
        return runCatching {
            val resolver = context.contentResolver
            val name = "bonsaidroid-eval-${System.currentTimeMillis()}.jsonl"
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                },
            ) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            Environment.DIRECTORY_DOWNLOADS + "/" + name
        }.getOrNull()
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
