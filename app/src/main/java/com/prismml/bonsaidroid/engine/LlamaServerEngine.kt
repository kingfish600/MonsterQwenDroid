package com.prismml.bonsaidroid.engine

import android.content.Context
import android.util.Log
import com.prismml.bonsaidroid.nativeffi.BonsaiNative
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ships the PrismML fork's `llama-server` as an executable inside the APK
 * (`nativeLibraryDir/libllamaserver.so`) and drives it over loopback HTTP.
 *
 * Why subprocess instead of JNI-in-process (v1 decision):
 *   * unlocks the fork-only DSpark drafter path verbatim (`--spec-type
 *     draft-dspark`), which is where this model's MTP acceleration lives;
 *   * isolates a 27B-scale native runtime from app lifecycle crashes;
 *   * localhost HTTP adds <1 ms — negligible next to 200+ ms/token decode.
 *
 * The child is spawned from a big-core-pinned thread so its decode workers
 * inherit `sched_setaffinity` (see bonsai_jni.cpp).
 */
class LlamaServerEngine(private val context: Context) : BonsaiEngine {

    /**
     * Supervised, and with a handler: an exception escaping a coroutine in a scope
     * without one reaches the default handler and kills the process. A logging or
     * housekeeping coroutine must never be able to take the app down - that is
     * exactly what drainLogs did, once per engine restart, and it is why the
     * Kernel A/B force-closed.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t ->
                android.util.Log.w(TAG, "suppressed coroutine failure", t)
            }
    )
    private val json = Json { ignoreUnknownKeys = true }

    // Loopback-only client: NO_PROXY defeats any device-level proxy/VPN that
    // would otherwise hijack 127.0.0.1 traffic, and the explicit CLEARTEXT
    // spec keeps plain-HTTP negotiation possible for our local IPC.
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE streams must not time out
        .proxy(java.net.Proxy.NO_PROXY)
        .connectionSpecs(listOf(
            okhttp3.ConnectionSpec.RESTRICTED_TLS,
            okhttp3.ConnectionSpec.CLEARTEXT,
        ))
        .build()

    /** Latest health-poll failure reason; surfaced live while STARTING. */
    @Volatile private var healthNote: String? = null

    private var process: Process? = null
    private var port: Int = DEFAULT_PORT
    private var logJob: Job? = null

    private val _activeSlot = MutableStateFlow<String?>(null)

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus(EngineState.IDLE))
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _lastTimings = MutableStateFlow<Timings?>(null)
    override val lastTimings: StateFlow<Timings?> = _lastTimings.asStateFlow()

    private val _logTail = MutableStateFlow<List<String>>(emptyList())
    override val logTail: StateFlow<List<String>> = _logTail.asStateFlow()

    /** Single-thread executor used to spawn the child with pinned affinity. */
    private val spawner = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bonsai-server-spawner").apply { isDaemon = false }
    }
    private val spawnerDispatcher = spawner.asCoroutineDispatcher()

    // ------------------------------------------------------------------ //
    // Lifecycle
    // ------------------------------------------------------------------ //

    override suspend fun start(spec: ModelSpec, profile: EngineProfile) {
        check(!_isRunning.value) { "engine already running" }
        _status.value = EngineStatus(EngineState.STARTING, detail = spec.target.name)
        val startedAt = System.currentTimeMillis()
        val ticker = scope.launch {
            while (true) {
                delay(1000)
                // CAS update: never resurrect a stale STARTING over LOADED/FAILED.
                var changed = false
                _status.update { cur ->
                    if (cur.state == EngineState.STARTING) {
                        changed = true
                        cur.copy(
                            elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt(),
                            detail = buildString {
                                append(spec.target.name)
                                append(" · port=").append(port)
                                healthNote?.let { append(" — health: ").append(it) }
                            },
                        )
                    } else {
                        cur
                    }
                }
                if (!changed) break
            }
        }
        try {
            startInternal(spec, profile)
            // Marker: the harness must only skip a restart if the server really is
            // watching the selector file. Without this check the sweep would happily
            // measure the same kernel ten times and report a meaningless ranking.
            if (profile.liveKernelSwitch) {
                runCatching {
                    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "bench")
                    dir.mkdirs()
                    File(dir, "kernel_live.flag")
                        .writeText(profile.kernelVariant.toString())
                }
            } else {
                runCatching {
                    File(File(context.getExternalFilesDir(null) ?: context.filesDir, "bench"),
                        "kernel_live.flag").delete()
                }
            }
            _isRunning.value = true
            _status.value = EngineStatus(EngineState.LOADED, detail = spec.target.name)
        } catch (t: Throwable) {
            val detail = t.message ?: t.javaClass.simpleName
            _status.value = EngineStatus(EngineState.FAILED, detail = detail)
            logJob?.cancel()
            process?.let { runCatching { it.destroyForcibly() } }
            process = null
            throw t
        } finally {
            ticker.cancel()
        }
    }

    private suspend fun startInternal(spec: ModelSpec, profile: EngineProfile) {
        assertRamHeadroom(spec, profile)

        val exe = File(context.applicationInfo.nativeLibraryDir, SERVER_LIB_NAME)
        check(exe.exists()) {
            "${SERVER_LIB_NAME} missing from APK native libs — rebuild with " +
                "third_party/llama.cpp checked out (docs/BUILD.md)"
        }

        port = findFreePort()
        val argv = buildArgv(exe, spec, profile)

        // Spawn on the pinned thread so the child inherits big-core affinity.
        val proc = withContext(spawnerDispatcher) {
            BonsaiNative.pinCurrentThreadToBigCores()
            try {
                ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] =
                            context.applicationInfo.nativeLibraryDir
                        // Backend workaround knobs (e.g. GGML_VK_DISABLE_SUBGROUPS).
                        profile.vulkanEnv.forEach { (k, v) -> environment()[k] = v }
                        // Opt-in expert cap; off (0) unless a preset asks for it.
                        if (profile.moeTopK > 0) {
                            environment()["BONSAI_MOE_TOPK"] = profile.moeTopK.toString()
                        }
                        // PTQ1_0 kernel battery selector (see EngineProfile.kernelVariant).
                        environment()["GGML_PTQ1_ARM"] = profile.kernelVariant.toString()
                        // Benchmark affordance: lets the running server pick up a
                        // kernel change from a file instead of being restarted, so
                        // the on-device A/B does not reload the model per variant.
                        if (profile.liveKernelSwitch) {
                            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "bench")
                            dir.mkdirs()
                            environment()["GGML_PTQ1_ARM_FILE"] =
                                File(dir, "kernel_variant.txt").absolutePath
                        }
                        environment()["GGML_PTQ1_NROWS"] = profile.nrows.toString()
                    }
                    .start()
            } finally {
                BonsaiNative.clearThreadAffinity()
            }
        }
        process = proc
        _logTail.value = emptyList()
        runCatching {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "runtime.log").delete()
        }
        logJob?.cancel()
        logJob = scope.launch { drainLogs(proc) }
        Log.i(TAG, "llama-server spawned on port=$port argv=${argv.joinToString(" ")}")

        awaitHealthy(proc)
    }

    override suspend fun stop() {
        _isRunning.value = false
        try {
            process?.let { p ->
                runCatching { p.destroy() }
                withContext(Dispatchers.IO) {
                    if (!p.waitFor(3, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                        p.waitFor(2, TimeUnit.SECONDS)
                    }
                }
            }
        } catch (t: Throwable) {
            // Teardown is best-effort: the child dies with our process group
            // anyway; never let a stop-path exception escape to Main.
            Log.w(TAG, "stop(): ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            process = null
            logJob?.cancel()
            scope.coroutineContext[Job]?.children?.forEach { runCatching { it.cancel() } }
            _status.value = EngineStatus(EngineState.IDLE)
        }
    }

    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
        spawnerDispatcher.close()
        spawner.shutdownNow()
    }

    // ------------------------------------------------------------------ //
    // Chat streaming (OpenAI-compatible /v1/chat/completions)
    // ------------------------------------------------------------------ //

    override fun streamChat(
        messages: List<ChatMessage>,
        profile: EngineProfile,
        maxTokens: Int,
        temperature: Float,
        stop: List<String>,
    ): Flow<CompletionEvent> = flow {
        // Timings are per-request. Without clearing, a response carrying no
        // "timings" field reuses the PREVIOUS request's - so an unspeculative run
        // could inherit draft counters from an earlier speculative one and report
        // speculation that was not happening. That is what made the MTP A/B's
        // baseline arm look speculative when it was not.
        _lastTimings.value = null
        check(_isRunning.value) { "engine not running" }
        // Bonsai-2: thinking counts against the output limit; a small cap
        // truncates mid-thought and the answer comes back empty.
        val budget = if (maxTokens > 0) maxTokens else profile.outputBudget
        val body = buildJsonObject {
            put("messages", JsonArray(messages.map {
                buildJsonObject {
                    put("role", it.role)
                    put("content", it.content)
                }
            }))
            put("stream", true)
            put("max_tokens", budget)
            put("temperature", temperature.toDouble())
            put("top_p", profile.topP.toDouble())
            put("top_k", profile.topK)
            // Bonsai-2: penalties are NOT in the GGUF; must be sent per request.
            put("min_p", profile.minP.toDouble())
            if (profile.presencePenalty != 0.0f)
                put("presence_penalty", profile.presencePenalty.toDouble())
            if (profile.repetitionPenalty != 1.0f)
                put("repetition_penalty", profile.repetitionPenalty.toDouble())
            // Valid: low|medium|xhigh|none. NEVER "high" (HTTP 500 upstream bug).
            if (profile.reasoningEffort.isNotEmpty())
                put("reasoning_effort", profile.reasoningEffort)
            if (profile.seed >= 0) put("seed", profile.seed)
            if (stop.isNotEmpty()) put("stop", JsonArray(stop.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("http://127.0.0.1:$port/v1/chat/completions")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        // Aborts the blocking SSE read when the collecting coroutine is cancelled
        // (UI stop button / lifecycle teardown).
        val call = http.newCall(req)
        currentCoroutineContext().job.invokeOnCompletion {
            runCatching { if (!call.isCanceled()) call.cancel() }
        }

        var slotId: String? = null
        val sb = StringBuilder()
        val rb = StringBuilder()

        call.execute().use { resp ->
            check(resp.isSuccessful) { "chat request failed: HTTP ${resp.code}" }
            slotId = resp.header("X-Slot-Id") ?: slotId
            _activeSlot.value = slotId

            val source = resp.body?.source() ?: error("empty SSE body")
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8LineStrict() // throws on EOF/cancel
                when {
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }
                            .getOrNull() ?: continue

                        obj["error"]?.let { err ->
                            emit(CompletionEvent.Error(err.jsonObject.toString()))
                            return@flow
                        }

                        val delta = obj["choices"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta") as? JsonObject ?: continue

                        // NB: JsonNull IS a JsonPrimitive with content=="null";
                        // must be excluded or every null delta leaks "null" text.
                        val c = delta["content"]
                        if (c is kotlinx.serialization.json.JsonPrimitive &&
                            c !is kotlinx.serialization.json.JsonNull && c.content.isNotEmpty()
                        ) {
                            sb.append(c.content); emit(CompletionEvent.Token(c.content))
                        }
                        val r = delta["reasoning_content"]
                        if (r is kotlinx.serialization.json.JsonPrimitive &&
                            r !is kotlinx.serialization.json.JsonNull && r.content.isNotEmpty()
                        ) {
                            rb.append(r.content); emit(CompletionEvent.Reasoning(r.content))
                        }

                        obj["timings"]?.let { t ->
                            parseTimings(t).let { _lastTimings.value = it }
                        }
                    }
                    // SSE comments/blank lines ignored
                }
            }
        }
        _activeSlot.value = null
        emit(
            CompletionEvent.Done(
                fullText = sb.toString(),
                reasoningText = rb.toString(),
                timings = _lastTimings.value ?: Timings(),
            )
        )
    }.flowOn(Dispatchers.IO)

    /** Cancels the in-flight generation, if any. */
    suspend fun cancelCurrent() {
        _activeSlot.value?.let { cancelSlot(it) }
    }

    /** Best-effort slot teardown when the UI cancels mid-stream. */
    suspend fun cancelSlot(slotId: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$port/slots/$slotId?action=close")
                        .delete()
                        .build()
                ).execute().close()
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Internals
    // ------------------------------------------------------------------ //

    private fun buildArgv(exe: File, spec: ModelSpec, p: EngineProfile): List<String> {
        val args = mutableListOf(
            exe.absolutePath,
            "-m", spec.target.absolutePath,
            "--host", if (p.exposeOnWifi) "0.0.0.0" else "127.0.0.1",
            "--port", port.toString(),
            "-c", p.contextTokens.toString(),
            // Checkpoints copy the whole KV (~150 MB at 8k q4_0) mid-decode;
            // useless for linear chats and a visible stutter source.
            "-ctxcp", "0",
            "-np", "1",
            "-fa", if (p.flashAttn) "on" else "off",
            "--cache-type-k", p.kvCacheTypeK,
            "--cache-type-v", p.kvCacheTypeV,
            "-ngl", p.nGpuLayers.toString(),
            "--jinja", // honor the Qwen3.x chat template incl. thinking blocks
        )
        // Single-user prompt cache: without this the server splits slots and
        // long chats re-process the whole prompt every turn (Bonsai-demo #183).
        if (p.cacheRamMb > 0) args += listOf("--cache-ram", p.cacheRamMb.toString())
        // UMA auto-distribution ignores -ngl 0 and silently spreads tensors on
        // the Adreno device, which runs PTQ1_0 on a slow fallback path. Pin it.
        // Only name a device when we actually offload; "-device none" on a
        // CPU-only engine produced a spurious "no usable GPU found" warning.
        if (p.nGpuLayers > 0) args += listOf("--device", "Vulkan0")
        if (p.threads > 0) args += listOf("-t", p.threads.toString())
        if (p.threadsBatch > 0) args += listOf("-tb", p.threadsBatch.toString())
        // Keep every worker inside one L2 cluster. Measured +26% decode versus
        // unpinned (3.5 -> 4.4 t/s) with identical output; only applied when the
        // device actually has the 6-core perf cluster.
        if (p.pinPerfCluster && Runtime.getRuntime().availableProcessors() >= 6) {
            args += listOf("--cpu-mask", "0x3f", "--cpu-strict", "1")
        }
        if (p.pollPercent >= 0) args += listOf("--poll", p.pollPercent.toString())
        // --mlock was replaced upstream by --load-mode. Note mlock is wrong for
        // this model anyway: locking 66 GB of weights against 23 GB of RAM cannot
        // succeed, so it is off by default.
        if (p.mlockWeights) args += listOf("--load-mode", "mlock")
        if (p.noWarmup) args += "--no-warmup"

        // MTP: the head is grafted into the target GGUF, so the fork builds the
        // draft context against the target model itself - no -md and no extra RAM.
        if (p.noSpeculation) {
            // A/B baseline arm: suppress BOTH paths, so a drafter left over from an
            // earlier model choice cannot make the "off" arm speculative too.
        } else if (p.useMtp && spec.hasMtp && spec.draft == null) {
            args += listOf("--spec-type", "draft-mtp", "--spec-draft-n-max", p.draftMax.toString())
        }
        spec.draft?.let { d ->
            // An MTP head is drafted by the model itself and verified in one pass;
            // a DSpark drafter is a separate small model. Same -md plumbing, very
            // different --spec-type, and passing the wrong one silently wastes it.
            val specType = spec.draftSpecType.ifBlank { "draft-dspark" }
            args += listOf(
                "-md", d.absolutePath,
                "--spec-type", specType,
                // Must equal the drafter block size; smaller crashes (SPECULATIVE.md).
                "--spec-draft-n-max", p.draftMax.toString(),
            )
        }
        return args
    }

    /**
     * Readiness via a RAW TCP socket — deliberately NOT OkHttp.
     * Kernel-level connect+GET is exempt from cleartext policies, proxy
     * selectors and TLS machinery. We try IPv4 loopback first, then IPv6
     * (::1): an address-family bind mismatch otherwise looks exactly like
     * "server listening but nobody home".
     */
    private fun rawHealthProbe(): Boolean {
        var lastFail = ""
        for (t in listOf("127.0.0.1" to "v4", "::1" to "v6")) {
            try {
                val addr = java.net.InetAddress.getByName(t.first)
                java.net.Socket().use { s ->
                    s.tcpNoDelay = true
                    s.connect(java.net.InetSocketAddress(addr, port), 1500)
                    s.soTimeout = 1500
                    val out = s.getOutputStream()
                    out.write("GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                    out.flush()
                    val line = s.getInputStream().bufferedReader().readLine() ?: ""
                    if (line.contains(" 200")) {
                        healthNote = null
                        clientLog("health OK via ${t.second}:${port}")
                        return true
                    }
                    lastFail = "${t.second}:$port answered '${line.take(40)}'"
                }
            } catch (e: Exception) {
                val kind = e.javaClass.simpleName
                lastFail = "${t.second}:$port $kind (${e.message?.take(70) ?: kind})"
            }
        }
        healthNote = lastFail
        clientLog("health probe failed — $lastFail")
        return false
    }

    /** Mirror client-side probe failures into the shared log tail (on change only). */
    @Volatile private var lastClientNote: String? = null
    private fun clientLog(msg: String) {
        if (msg == lastClientNote) return // not every 500ms tick — just transitions
        lastClientNote = msg
        _logTail.value = (_logTail.value + "[client] $msg").takeLast(LOG_CAP)
    }

    private suspend fun awaitHealthy(proc: Process) {
        val deadline = System.nanoTime() + LOAD_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive) error(
                "llama-server exited (code=${proc.exitValue()}): " + recentLogSummary()
            )
            // Socket I/O must leave the main thread — Android forbids network
            // on the UI thread (NetworkOnMainThreadException otherwise).
            val ok = withContext(Dispatchers.IO) { rawHealthProbe() }
            if (ok) {
                healthNote = null
                return
            }
            delay(500)
        }
        error(
            "model still not ready after ${LOAD_TIMEOUT_MS / 1000}s (${healthNote ?: "no response"}). " +
                "Last runtime output: " + recentLogSummary()
        )
    }

    /** Last few meaningful log lines for failure messages (skips blank spam). */
    private fun recentLogSummary(): String =
        _logTail.value.filter { it.isNotBlank() }.takeLast(5)
            .joinToString(" ⏎ ") { it.trim().take(160) }
            .ifBlank { "<none>" }

    /**
     * Tail the child's output to the UI ring and to runtime.log.
     *
     * Must not throw. When the engine is stopped, the child's streams are closed
     * from this thread while drainLogs may be blocked in read(), which raises
     * InterruptedIOException ("read interrupted by close() on another thread")
     * rather than returning EOF. That escapes the logJob coroutine, and since the
     * scope has no exception handler it takes the whole app down - which is what
     * the Kernel A/B was doing, once per restart.
     */
    private suspend fun drainLogs(proc: Process) = withContext(Dispatchers.IO) {
        try {
        // Full transcript also lands on disk: the ring rotates too fast to
        // hold GGML_VULKAN_DEBUG firehoses, and adb pull can fetch this file
        // after a crash for complete post-mortems.
        val logFile = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "runtime.log",
        )
        var written: Long = 0
        BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
            val ring = ArrayDeque<String>(LOG_CAP)
            for (line in lines) {
                if (ring.size >= LOG_CAP) ring.removeFirst()
                ring.addLast(line)
                _logTail.value = ring.toList()
                if (written < MAX_LOG_FILE_BYTES) {
                    runCatching {
                        logFile.appendText(line + "\n")
                        written += line.length + 1
                    }
                }
            }
        }
        } catch (t: Throwable) {
            // Expected on stop(): the child was destroyed mid-read, so the stream
            // raises InterruptedIOException rather than returning EOF. Anything
            // else here is equally non-fatal - tailing a log is best-effort and
            // must never take the app down.
            android.util.Log.d(TAG, "log drain ended", t)
        }
    }

    /**
     * RAM guard before we commit `-c`: weights are mmap'd (page-cache backed),
     * but the KV cache is anonymous memory we should not gamble on.
     * Full-attention KV lives on 16 of 64 layers; 4 kv-heads x 256 head-dim.
     */
    private fun assertRamHeadroom(spec: ModelSpec, profile: EngineProfile) {
        val mem = BonsaiNative.readMemInfoKb()
        val totalKb = mem.getOrNull(0)?.toLong() ?: return
        val kvBits = 4 // q4_0 cache
        val fullAttnLayers = 16
        val kvHeads = 4
        val headDim = 256
        val kvBytes = profile.contextTokens.toLong() *
            fullAttnLayers * 2 /*K,V*/ * kvHeads * headDim * kvBits / 8
        // Weights are mmap'd and demand-paged, so they do NOT have to be
        // resident - and for this model they cannot be (66 GB against ~23 GB).
        // Requiring the whole file to fit made the app unable to load the very
        // model it exists for. Only the anonymous KV cache plus working
        // headroom must actually fit; the rest is the page cache's problem.
        val availKb = mem.getOrNull(1)?.toLong() ?: totalKb
        val overhead = 2_000_000_000L
        val needBytes = kvBytes + overhead
        val budget = minOf(availKb, totalKb * 85 / 100) * 1024
        check(needBytes <= budget) {
            "KV cache + headroom needs ~%.1f GB but only %.1f GB is free — lower the context."
                .format(needBytes / 1e9, budget / 1e9)
        }
        val weightsBytes = spec.target.length() + (spec.draft?.length() ?: 0L)
        if (weightsBytes > budget) {
            Log.i(
                "MonsterQwenDroid",
                "weights %.1f GB exceed free RAM - mmap demand-paging owns the difference"
                    .format(weightsBytes / 1e9)
            )
        }
    }

    private fun parseTimings(el: JsonElement): Timings? {
        val o = el as? JsonObject ?: return null
        fun num(key: String): Float = o[key]?.jsonPrimitive?.floatOrNull ?: 0f
        fun int(key: String): Int = o[key]?.jsonPrimitive?.intOrNull ?: 0
        fun long(key: String): Long = o[key]?.jsonPrimitive?.longOrNull ?: 0L
        return Timings(
            promptN = int("prompt_n"),
            promptMs = long("prompt_ms"),
            promptPerSecond = num("prompt_per_second"),
            predictedN = int("predicted_n"),
            predictedMs = long("predicted_ms"),
            predictedPerSecond = num("predicted_per_second"),
            draftN = int("draft_n"),
            draftNAccepted = int("draft_n_accepted"),
        )
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val TAG = "BonsaiDroid"
        const val SERVER_LIB_NAME = "libllamaserver.so"
        const val DEFAULT_PORT = 8081
        private const val LOG_CAP = 400
        private const val MAX_LOG_FILE_BYTES = 8L * 1024 * 1024
        // Cold first load: 7 GB mmap page-in + graph warmup on phone UFS is slow.
        private const val LOAD_TIMEOUT_MS = 600_000L
    }
}
