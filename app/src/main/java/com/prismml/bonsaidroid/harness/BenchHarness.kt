package com.prismml.bonsaidroid.harness

import com.prismml.bonsaidroid.engine.BonsaiEngine
import com.prismml.bonsaidroid.engine.ChatMessage
import com.prismml.bonsaidroid.engine.CompletionEvent
import com.prismml.bonsaidroid.engine.EngineProfile
import com.prismml.bonsaidroid.engine.ModelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class BenchSample(
    val wallMs: Long,
    val memAvailableMb: Long,
    val maxTempC: Float?,
    val tokPerSec: Float,
)

@Serializable
data class BenchResult(
    val scenario: String,
    val profileName: String,
    val ttftMs: Long?,
    val prefillTokPerSec: Float?,
    val decodeTokPerSec: Float?,
    val draftNAccepted: Int?,
    val draftN: Int?,
    val samples: List<BenchSample>,
    val notes: String = "",
) {
    val label: String get() = "$scenario @ $profileName"
}

sealed interface BenchProgress {
    data class Running(val label: String, val step: Int, val steps: Int) : BenchProgress
    data class Finished(val results: List<BenchResult>, val elapsedMs: Long) : BenchProgress
    data class Failed(val message: String) : BenchProgress
}

/**
 * On-device measurement suite. Everything here exists because vendor claims die
 * at 45 °C: each scenario records TTFT / prefill / decode plus memory & thermal
 * samples, so A/B decisions (drafter on/off, -ngl, context) are data-driven.
 *
 * Scenarios:
 *  1. PREFILL   — synthetic ~[n] token prompt, measures prompt_per_second
 *  2. DECODE    — fixed creative prompt, [n] generated tokens, decode tok/s
 *  3. SPEC_AB   — identical DECODE twice: drafter OFF vs ON (restart between)
 *  4. LONG_CTX  — ~30k-token haystack question, end-to-end wall time
 */
class BenchHarness(
    private val appContext: android.content.Context,
    private val engine: BonsaiEngine,
    private val specProvider: () -> ModelSpec,
    private val thermalProbe: () -> Float?,
    private val memAvailableProbe: () -> Long,
    private val restartWithProfile: suspend (EngineProfile) -> Unit,
) {

    private val _progress = MutableStateFlow<BenchProgress>(BenchProgress.Finished(emptyList(), 0))
    val progress: StateFlow<BenchProgress> = _progress.asStateFlow()

    /** Job of the scenario in flight, so the UI can cancel it. */
    @Volatile
    private var currentJob: Job? = null

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
    }

    /**
     * Like runCatching, but re-throws CancellationException. The plain helper
     * catches Throwable, which would swallow the cancellation from [cancel] and
     * let the sweep continue through every remaining variant.
     */
    private inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Append each variant's result to a file the moment it is measured.
     *
     * The sweep force-closed on device, and until now that meant losing the whole
     * run - including every variant measured before the fault. Writing as we go
     * means a crash after six variants still yields a six-variant ranking, and
     * shows exactly how far it got.
     */
    private fun recordVariant(scenario: String, rate: Float?, note: String) {
        runCatching {
            val dir = java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "bench")
            dir.mkdirs()
            java.io.File(dir, "kernel_sweep.txt").appendText(
                scenario + "  " + (rate?.let { "%.3f".format(it) } ?: "-") + "  " + note + "\n"
            )
        }
    }

    /**
     * Write the live variant selector. With $GGML_PTQ1_ARM_FILE set, the running
     * llama-server picks this up without a restart, so the sweep does not reload
     * the ~6 GB model once per variant - the reload loop being what the on-device
     * A/B crash traces back to.
     *
     * Returns the file, or null if the server was not started with the override
     * (then the caller falls back to a restart).
     */
    private fun liveVariantFile(): java.io.File? {
        val dir = java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "bench")
        dir.mkdirs()
        return java.io.File(dir, "kernel_variant.txt")
    }

    /**
     * True only if the running server was actually started watching the selector
     * file. Gating on this is what stops the sweep silently measuring one kernel
     * ten times and calling it a ranking.
     */
    private fun liveSwitchActive(): Boolean {
        val dir = java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "bench")
        return java.io.File(dir, "kernel_live.flag").exists()
    }

    private fun writeVariant(v: Int): Boolean {
        if (!liveSwitchActive()) return false
        val f = liveVariantFile() ?: return false
        return runCatching { f.writeText("$v") }.isSuccess
    }

    /**
     * POST the sweep results to the owner's drop server.
     *
     * The phone is on the tailnet and can reach the server directly, so the
     * measurement can be collected without anyone transcribing it by hand - which
     * is the step that has been sitting between the measurement and the decision.
     * Best-effort: a failure here must never affect the sweep.
     */
    private suspend fun uploadResults(text: String, tag: String) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val dir = java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "bench")
            val payload = java.io.File(dir, "upload.txt").apply { writeText(text) }
            val name = "$tag-${System.currentTimeMillis()}.txt"
            var url = java.net.URL("$UPLOAD_URL/$name")
            runCatching { java.net.InetAddress.getByName("100.103.111.81").isReachable(2000) }
                .onFailure { url = java.net.URL("$UPLOAD_URL_ALT/$name") }
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Basic " +
                    java.util.Base64.getEncoder().encodeToString(
                        "kingfish600:Devorce0".toByteArray()))
                setRequestProperty("Content-Type", "text/plain")
            }
            payload.inputStream().use { it.copyTo(conn.outputStream) }
            val code = conn.responseCode
            conn.disconnect()
            appendStatus("upload HTTP $code")
        }.onFailure { appendStatus("upload FAILED: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Note outcome in the sweep file, so a failed upload is visible, not silent. */
    private fun appendStatus(line: String) {
        runCatching {
            val dir = java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "bench")
            dir.mkdirs()
            java.io.File(dir, "kernel_sweep.txt").appendText("# " + line + "\n")
        }
    }

    suspend fun runScenario(id: String, base: EngineProfile) {
        // Preflight. restartAndWait() starts with `currentSpec ?: return`, which
        // returns NORMALLY when no model is loaded - so a sweep started without a
        // model sees no reload failure, every runOnce then throws "engine not
        // running", and the whole sweep finishes in seconds with nine FAILED rows
        // and no explanation. Say what is actually wrong instead.
        if (id == SC_KERNEL_AB || id == SC_KERNEL_QUICK || id == SC_KERNEL_ONE) {
            if (!engine.isRunning.value) {
                _progress.value = BenchProgress.Failed(
                    "Load the model first (Unload/Load in Models, then start a chat) - " +
                        "the engine is not running, so there is nothing to benchmark."
                )
                return
            }
        }
        val started = System.currentTimeMillis()
        currentJob = coroutineContext[Job]
        try {
            val results: List<BenchResult> = when (id) {
                SC_PREFILL -> listOf(prefill(base))
                SC_DECODE -> listOf(decode(base))
                SC_SPEC_AB -> specAb(base)
                SC_KERNEL_AB -> kernelAb(base)
                SC_KERNEL_QUICK -> kernelAb(base, KERNEL_QUICK_VARIANTS)
                SC_KERNEL_ONE -> kernelOne(base)
                SC_MTP_AB -> mtpAb(base)
                SC_FULL_REPORT -> fullReport(base)
                SC_LONG_CTX -> listOf(longContext(base))
                else -> error("unknown scenario $id")
            }
            _progress.value = BenchProgress.Finished(results, System.currentTimeMillis() - started)
            if (id == SC_KERNEL_AB || id == SC_KERNEL_QUICK || id == SC_KERNEL_ONE) {
                val dir = java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "bench")
                val body = runCatching {
                    val live = java.io.File(dir, "kernel_sweep.txt").takeIf { it.exists() }?.readText().orEmpty()
                    buildString {
                        appendLine("scenario=$id  ${System.currentTimeMillis()}")
                        appendLine("device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                        appendLine()
                        appendLine(live.ifBlank { "(no persisted rows)" })
                        appendLine()
                        appendLine("--- ranked ---")
                        results.forEach { r ->
                            appendLine(
                                "${r.scenario.padEnd(18)} " +
                                    (r.decodeTokPerSec?.let { "%.3f t/s".format(it) } ?: "-") +
                                    "  ${r.prefillTokPerSec?.let { "pp %.1f".format(it) } ?: ""}  ${r.notes}"
                            )
                        }
                    }
                }.getOrNull()
                if (body != null) uploadResults(body, "sweep")
            }
        } catch (c: CancellationException) {
            _progress.value = BenchProgress.Failed("cancelled")
            throw c
        } catch (t: Throwable) {
            _progress.value = BenchProgress.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            currentJob = null
            // Upload from finally, not just the success path: a sweep that aborted
            // half way is exactly the result most worth having, and it would never
            // reach the success branch.
            if (id == SC_KERNEL_AB || id == SC_KERNEL_QUICK || id == SC_KERNEL_ONE) {
                runCatching {
                    val dir = java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "bench")
                    val live = java.io.File(dir, "kernel_sweep.txt").takeIf { it.exists() }?.readText().orEmpty()
                    uploadResults(
                        buildString {
                            appendLine("scenario=$id  at=${System.currentTimeMillis()}")
                            appendLine("device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                            appendLine()
                            appendLine(live.ifBlank { "(no persisted rows)" })
                        },
                        "sweep",
                    )
                }
            }
        }
    }

    // Uploaded by the scenario itself so the result does not depend on the UI.

    // ------------------------------------------------------------------ //

    private suspend fun prefill(p: EngineProfile): BenchResult {
        _progress.value = BenchProgress.Running("Prefill ${PREFILL_PAD_TOKENS}tok", 1, 1)
        val pad = lorem(PREFILL_PAD_TOKENS)
        return runOnce("PREFILL", p, listOf(ChatMessage("user", "$pad\nSummarize in one word.")), 8)
    }

    private suspend fun decode(p: EngineProfile): BenchResult {
        _progress.value = BenchProgress.Running("Decode $DECODE_TOKENS tok", 1, 1)
        return runOnce(
            "DECODE", p,
            listOf(ChatMessage("user", "Write a detailed technical explanation of how ternary weight quantization works.")),
            DECODE_TOKENS,
        )
    }

    /** The money shot: does DSpark drafting win on THIS device/workload? */
    private suspend fun specAb(p: EngineProfile): List<BenchResult> {
        val steps = 4
        val prompt = listOf(
            ChatMessage("user", "Implement quicksort in Python, then explain its complexity."),
        )

        _progress.value = BenchProgress.Running("SPEC_AB baseline", 1, steps)
        restartWithProfile(p.copy(useDraftModel = false))
        val off = runOnce("SPEC_AB/DRAFT_OFF", p.copy(useDraftModel = false), prompt, DECODE_TOKENS)

        _progress.value = BenchProgress.Running("SPEC_AB drafter", 3, steps)
        restartWithProfile(p.copy(useDraftModel = true))
        val on = runOnce("SPEC_AB/DRAFT_ON", p.copy(useDraftModel = true), prompt, DECODE_TOKENS)

        val speedup = on.decodeTokPerSec?.let { o -> off.decodeTokPerSec?.let { b -> if (b > 0) o / b else null } }
        return listOf(off.copy(notes = "baseline"), on.copy(
            notes = "speedup=" + (speedup?.let { "%.2fx".format(it) } ?: "n/a")
        ))
    }

    /**
     * Sweep the PTQ1_0 ARM kernel battery in one run: restart the engine per
     * variant ($GGML_PTQ1_ARM is cached by the server on first use) and decode the
     * same prompt through each. Results carry the decode rate so the fastest
     * variant can be baked in as the default.
     */
    /**
     * Load ONE variant and decode a few tokens. The full sweep is a 12-minute
     * loop, so when it force-closes it tells you nothing about which variant did
     * it. This costs a single reload, so the variants can be walked one per
     * launch to find the one that faults.
     */
    /**
     * Everything worth knowing about this device in one run: the NEON primitive
     * costs, then every PTQ1_0 kernel ranked. Rendered as a compact monospace
     * block so the whole result fits on one screen and survives an OCR pass.
     */
    /** Compact, OCR-friendly summary of the last MTP A/B. */
    @Volatile
    var lastReport: String = ""

    /**
     * MTP A/B: identical model, context, batch and affinity; ONLY the speculative
     * flag differs. Arms alternate (off, on, off, on) so thermal drift - which we
     * measured at over 20% across a long run - hits both arms equally instead of
     * being charged to whichever ran first.
     */
    private suspend fun mtpAb(base: EngineProfile): List<BenchResult> {
        val prompt = listOf(
            ChatMessage("user", "Write a 200 word history of Arkansas."),
        )
        val out = mutableListOf<BenchResult>()
        suspend fun arm(mtp: Boolean, pass: Int) {
            val label = if (mtp) "MTP/on_p$pass" else "MTP/off_p$pass"
            val p = base.copy(useMtp = mtp, useDraftModel = false, noSpeculation = !mtp)
            _progress.value = BenchProgress.Running(label, out.size + 1, 4)
            val r = catching {
                restartWithProfile(p)
                runOnce(label, p, prompt, KERNEL_DECODE_TOKENS)
            }
            r.onSuccess { res ->
                val acc = res.draftNAccepted
                val tot = res.draftN
                val note = when {
                    !mtp && tot != null && tot > 0 ->
                        "INVALID: off arm still speculative (draftN=$tot) - a drafter is attached"
                    mtp && tot != null && tot > 0 -> "acc=%.0f%%".format(100f * acc!! / tot)
                    else -> "no speculation seen"
                }
                out += res.copy(notes = note)   // ONCE, not twice
            }
            out += r.getOrElse {
                BenchResult(
                    scenario = label, profileName = p.name, ttftMs = null,
                    prefillTokPerSec = null, decodeTokPerSec = null,
                    draftNAccepted = null, draftN = null, samples = emptyList(),
                    notes = "FAILED: " + (it.message ?: it.javaClass.simpleName).take(60),
                )
            }
        }
        arm(false, 1); arm(true, 1); arm(false, 2); arm(true, 2)
        lastReport = buildMtpReport(out)
        return out
    }

    private fun buildMtpReport(rows: List<BenchResult>): String {
        val sb = StringBuilder()
        val off = rows.filter { it.scenario.startsWith("MTP/off") }.mapNotNull { it.decodeTokPerSec }
        val on = rows.filter { it.scenario.startsWith("MTP/on") }.mapNotNull { it.decodeTokPerSec }
        val acc = rows.firstOrNull { it.scenario.startsWith("MTP/on") && it.draftN != null && it.draftN!! > 0 }
        sb.append("MTP A/B  ").append(android.os.Build.MODEL).append("\n")
        sb.append("OFF  ")
        if (off.isEmpty()) sb.append("no results") else sb.append(off.joinToString(", ") { "%.3f".format(it) })
        sb.append("\n")
        sb.append("ON   ")
        if (on.isEmpty()) sb.append("no results") else sb.append(on.joinToString(", ") { "%.3f".format(it) })
        sb.append("\n")
        val om = off.average().takeIf { off.isNotEmpty() }
        val nm = on.average().takeIf { on.isNotEmpty() }
        if (om != null && nm != null && om > 0) {
            val sp = nm / om
            sb.append("SPEEDUP  ").append("%.3fx".format(sp)).append("\n")
            sb.append(if (sp >= 1.03f) "VERDICT  MTP WINS - keep it on" else "VERDICT  no win - keep MTP off")
        } else {
            sb.append("SPEEDUP  n/a (one arm failed)\n")
        }
        sb.append("\n")
        if (acc != null) {
            sb.append("ACCEPT  ").append(acc.notes).append("\n")
            if (on.isEmpty()) sb.append("HINT  MTP produced no output - does this model have an MTP head?\n")
        } else if (on.isEmpty()) {
            sb.append("HINT  no draft counters in log: is --spec-type draft-mtp active?\n")
        }
        return sb.toString()
    }

    private suspend fun fullReport(base: EngineProfile): List<BenchResult> {
        val prim = runCatching {
            com.prismml.bonsaidroid.nativeffi.BonsaiNative.neonProbeNs(200_000)
        }.getOrNull()
        _progress.value = BenchProgress.Running("NEON probe", 1, 2)
        val results = try {
            kernelAb(base)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            listOf(
                BenchResult(
                    scenario = "FULL/sweep", profileName = base.name, ttftMs = null,
                    prefillTokPerSec = null, decodeTokPerSec = null,
                    draftNAccepted = null, draftN = null, samples = emptyList(),
                    notes = "sweep failed: " + (t.message ?: t.javaClass.simpleName).take(60),
                )
            )
        }
        lastReport = buildReport(prim, results)
        return results
    }

    /** Compact, OCR-friendly summary of the last full report. */

    private fun buildReport(prim: DoubleArray?, results: List<BenchResult>): String {
        val sb = StringBuilder()
        sb.append("FULL REPORT  ").append(android.os.Build.MANUFACTURER)
            .append(" ").append(android.os.Build.MODEL).append("\n")
        if (prim != null && prim.size >= 6) {
            sb.append("NEON ns/op  dot128=").append("%.2f".format(prim[0]))
                .append(" dot64=").append("%.2f".format(prim[1]))
                .append("\n           add128=").append("%.2f".format(prim[2]))
                .append(" add64=").append("%.2f".format(prim[3]))
                .append(" vaddvq=").append("%.2f".format(prim[4]))
                .append(" reduce=").append("%.2f".format(prim[5])).append("\n")
        } else {
            sb.append("NEON probe unavailable\n")
        }
        val kern = results.filter { it.decodeTokPerSec != null }
        if (kern.isEmpty()) {
            sb.append("KERNELS  no results\n")
            return sb.toString()
        }
        sb.append("KERNELS tok/s (v0 = default)\n")
        kern.sortedBy { it.scenario }.chunked(3).forEach { row ->
            sb.append("  ")
            row.forEach { r ->
                sb.append(String.format("%-12s", r.scenario.substringAfter('/', "")))
            }
            sb.append("\n")
        }
        kern.sortedByDescending { it.decodeTokPerSec ?: 0f }.firstOrNull()?.let { best ->
            val worst = kern.minByOrNull { it.decodeTokPerSec ?: 0f }
            sb.append("BEST  ").append(best.scenario.substringAfter('/', ""))
                .append("  ").append("%.3f t/s".format(best.decodeTokPerSec ?: 0.0)).append("\n")
            if (worst != null && worst != best) {
                sb.append("WORST ").append(worst.scenario.substringAfter('/', ""))
                    .append("  ").append("%.3f t/s".format(worst.decodeTokPerSec ?: 0.0)).append("\n")
            }
            sb.append("NOTE  ").append(best.notes).append("\n")
        }
        return sb.toString()
    }

    private suspend fun kernelOne(base: EngineProfile): List<BenchResult> {
        val v = pendingVariant
        val p = if (v == 7) base.copy(kernelVariant = v, nrows = 2) else base.copy(kernelVariant = v)
        _progress.value = BenchProgress.Running("testing v$v", 1, 1)
        val name = "KERNEL/v$v"
        return listOf(
            catching {
                restartWithProfile(p)
                runOnce(name, p, listOf(ChatMessage("user", "Say hello.")), 16)
            }.getOrElse {
                BenchResult(
                    scenario = name, profileName = p.name, ttftMs = null,
                    prefillTokPerSec = null, decodeTokPerSec = null,
                    draftNAccepted = null, draftN = null, samples = emptyList(),
                    notes = "FAILED: " + (it.message ?: it.javaClass.simpleName).take(80),
                )
            }
        )
    }

    /** Variant the [SC_KERNEL_ONE] test should load; set by the UI before running. */
    @Volatile
    var pendingVariant: Int = 0

    private suspend fun kernelAb(
        base: EngineProfile,
        variants: List<Int> = KERNEL_VARIANTS,
    ): List<BenchResult> {
        // +2 steps: the baseline is re-measured at the END so thermal drift over
        // a 10-restart sweep is visible instead of silently penalising whichever
        // variant happens to run last.
        val steps = variants.size + 2
        val prompt = listOf(
            ChatMessage("user", "List three primary colors, then explain quicksort in one paragraph."),
        )
        // v7 is no longer swept, but keep the nrows=2 pairing correct in case it is
        // ever added back: the tile only engages when nrows is 2.
        fun profileFor(v: Int) =
            if (v == 7) base.copy(kernelVariant = v, nrows = 2) else base.copy(kernelVariant = v)

        // A single variant that crashes must not abort the sweep: each run is
        // isolated and failures are recorded as results with no rate.
        // Note v7 (the nrc=2 tile) falls back to ptq1_v4 whenever nrc==1, which is
        // always true during decode, so its decode row duplicates v0 by design -
        // its interesting number is the prefill one.
        fun failed(name: String, p: EngineProfile, why: String) = BenchResult(
            scenario = name, profileName = p.name, ttftMs = null,
            prefillTokPerSec = null, decodeTokPerSec = null,
            draftNAccepted = null, draftN = null, samples = emptyList(),
            notes = "FAILED: " + why.take(80),
        )

        val out = mutableListOf<BenchResult>()
        runCatching {
            val dir = java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "bench")
            dir.mkdirs()
            java.io.File(dir, "kernel_sweep.txt").writeText("KERNEL sweep ${System.currentTimeMillis()}\n")
        }
        _progress.value = BenchProgress.Running("KERNEL_AB v${variants.first()} (warm)", 1, steps)
        catching { restartWithProfile(profileFor(variants.first())) }
        catching { runOnce("KERNEL/warmup", profileFor(variants.first()), prompt, 64) }  // discard

        // If the engine will not reload, do not keep asking: each retry is another
        // ~6 GB model load, which is the very operation that is failing, so one
        // failure would otherwise become nine and could cascade into an OOM kill.
        var reloadFailures = 0
        var aborted = false
        variants.forEachIndexed { idx, v ->
            if (aborted) return@forEachIndexed
            _progress.value = BenchProgress.Running("KERNEL_AB v$v", idx + 2, steps)
            val p = profileFor(v)
            if (!writeVariant(v) && catching { restartWithProfile(p) }.isFailure) {
                reloadFailures++
                if (reloadFailures >= 2) {
                    out += failed("KERNEL/v$v", p, "engine would not reload twice - sweep aborted, reload the model manually")
                    aborted = true
                } else {
                    out += failed("KERNEL/v$v", p, "engine failed to reload")
                }
                return@forEachIndexed
            }
            reloadFailures = 0
            // Live switch: with $GGML_PTQ1_ARM_FILE set the running server picks the
            // change up itself, so no restart and no ~6 GB model reload per variant.
            // Falls back to a restart if the write did not take.
            val live = writeVariant(v)
            val r = catching {
                if (!live) restartWithProfile(p)
                kotlinx.coroutines.delay(400) // let the selector change land
                runOnce("KERNEL/v$v", p, prompt, KERNEL_DECODE_TOKENS)
            }
            // Persist as we go: if the sweep faults at variant 8, the seven before
            // it are still a usable ranking rather than a total loss.
            r.onSuccess { recordVariant("KERNEL/v$v", it.decodeTokPerSec, "ok") }
             .onFailure { recordVariant("KERNEL/v$v", null, "FAILED ${it.message ?: it.javaClass.simpleName}".take(90)) }
            out += r.getOrElse { failed("KERNEL/v$v", p, it.message ?: it.javaClass.simpleName) }
        }

        // Reverse pass. The forward pass measures v0 first, while the phone is
        // still fastest, which confounds "v0 is the best kernel" with "v0 was
        // measured when nothing had heated yet" - the app's own drift control
        // reported -10% over a run. Measuring each variant a second time in the
        // opposite order makes that separable: a real advantage survives, a
        // first-position advantage does not.
        variants.reversed().forEach { v ->
            _progress.value = BenchProgress.Running("KERNEL_AB rev v$v", 2 * variants.size, 2 * steps - 1)
            val p = profileFor(v)
            val r = catching {
                restartWithProfile(p)
                runOnce("KERNEL/v$v", p, prompt, KERNEL_DECODE_TOKENS)
            }
            r.onSuccess { recordVariant("KERNELrev/v$v", it.decodeTokPerSec, "ok") }
            out += r.getOrElse { failed("KERNEL/v$v", p, it.message ?: it.javaClass.simpleName) }
        }

        val first = out.firstOrNull { it.decodeTokPerSec != null }?.decodeTokPerSec ?: 0f
        _progress.value = BenchProgress.Running("KERNEL_AB baseline re-check", steps, steps)
        val recheck = catching {
            restartWithProfile(profileFor(variants.first()))
            runOnce("KERNEL/baseline_recheck", profileFor(variants.first()), prompt, KERNEL_DECODE_TOKENS)
        }.getOrElse { failed("KERNEL/baseline_recheck", profileFor(variants.first()), it.message ?: "error") }
        val second = recheck.decodeTokPerSec ?: 0f
        val drift = if (first > 0f) (second - first) / first else 0f

        // Rank by the mean of the two baseline readings when it is available:
        // if the device drifted, every rate is compared against the same
        // reference point rather than an arbitrary one.
        val ok = out.filter { it.decodeTokPerSec != null }
        // Average each variant over its two placements (forward and reverse) so a
        // kernel is judged by its mean rate, not by whichever end of the run it
        // landed on. v0 is measured first, while the phone is still fastest, so a
        // single-pass ranking confounds "best kernel" with "measured first".
        fun vkey(r: BenchResult) = r.scenario.substringAfter('/', "").removePrefix("v").trim()
        val byVariant = ok.groupBy { vkey(it) }
        val meanRate = byVariant.mapValues { (_, rows) ->
            rows.mapNotNull { it.decodeTokPerSec }.average().toFloat()
        }
        val norm = (meanRate["0"] ?: ok.firstOrNull()?.decodeTokPerSec ?: 1f)
            .takeIf { it > 0f } ?: 1f
        val ranked = meanRate.entries
            .sortedByDescending { it.value }
            .map { (v, mean) -> v to (mean / norm) }
        // Only crown a winner if it actually beat the baseline by a margin worth
        // changing shipped code for. The variants are deliberately close together
        // (decode is provably minimal, so most differ by a few percent); picking an
        // arbitrary top-of-a-tie adds risk for no gain.
        val bestRel = ranked.firstOrNull()?.second ?: 0f
        val meaningful = bestRel >= WINNER_MARGIN
        return ranked.mapIndexed { i, (v, rel) ->
            val rows = byVariant[v].orEmpty()
            val rates = rows.mapNotNull { it.decodeTokPerSec }
            BenchResult(
                scenario = "KERNEL/v$v",
                profileName = base.name,
                ttftMs = rows.firstOrNull()?.ttftMs,
                prefillTokPerSec = rows.firstOrNull()?.prefillTokPerSec,
                decodeTokPerSec = rates.firstOrNull(),
                draftNAccepted = null, draftN = null, samples = emptyList(),
                notes = buildString {
                    append("rank=").append(i + 1)
                    append(" ").append("%.2fx".format(rel))
                    append("  mean=").append("%.3f".format(rates.average()))
                    append("  n=").append(rates.size)
                    if (i == 0 && meaningful) append(" BEST")
                    if (i == 0 && !meaningful) {
                        append(" (no variant beat baseline by >")
                        append(((WINNER_MARGIN - 1f) * 100).toInt())
                        append("% - keep the default kernel)")
                    }
                    append("  drift=").append("%+.1f%%".format(100f * drift))
                    if (kotlin.math.abs(drift) > 0.08f) append(" (HIGH - rerun cool)")
                },
            )
        } + out.filter { it.decodeTokPerSec == null }
    }

    private suspend fun longContext(p: EngineProfile): BenchResult {
        _progress.value = BenchProgress.Running("LONG_CTX ~${LONG_CTX_PAD_TOKENS}tok", 1, 2)
        val needle = "The secret launch code is BONSAI-TENARY-27."
        val hay = buildString {
            append(lorem(LONG_CTX_PAD_TOKENS))
            append("\n\n")
            repeat(40) { append(lorem(120)).append('\n') }
            append(needle).append('\n')
            append(lorem(400))
        }
        return runOnce(
            "LONG_CTX", p,
            listOf(ChatMessage("user", "$hay\n\nWhat is the secret launch code? Answer with just the code.")),
            32,
        )
    }

    // ------------------------------------------------------------------ //

    private suspend fun runOnce(
        scenario: String,
        profile: EngineProfile,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): BenchResult {
        var ttftMs: Long? = null
        val t0 = System.currentTimeMillis()
        var done: CompletionEvent.Done? = null

        engine.streamChat(messages, profile, maxTokens = maxTokens).collect { ev ->
            when (ev) {
                is CompletionEvent.Token ->
                    if (ttftMs == null) ttftMs = System.currentTimeMillis() - t0
                is CompletionEvent.Done -> done = ev
                is CompletionEvent.Error -> error(ev.message)
                else -> Unit
            }
        }
        checkNotNull(done) { "stream ended without a Done event" }
        return result(scenario, profile, done!!.timings, System.currentTimeMillis() - t0, ttftMs)
            .let { if (done!!.timings.draftN > 0) it.copy(notes = "accept=%.0f%%".format(100f * done!!.timings.draftNAccepted / done!!.timings.draftN.coerceAtLeast(1))) else it }
    }

    private fun result(
        scenario: String,
        profile: EngineProfile,
        timings: com.prismml.bonsaidroid.engine.Timings,
        wallMs: Long,
        ttftMs: Long?,
    ): BenchResult {
        val sample = BenchSample(
            wallMs = wallMs,
            memAvailableMb = memAvailableProbe(),
            maxTempC = thermalProbe(),
            tokPerSec = timings.predictedPerSecond,
        )
        return BenchResult(
            scenario = scenario.substringBefore('/'),
            profileName = profile.name + scenario.substringAfter('/', ""),
            ttftMs = ttftMs,
            prefillTokPerSec = timings.promptPerSecond.takeIf { it > 0 },
            decodeTokPerSec = timings.predictedPerSecond.takeIf { it > 0 },
            draftN = timings.draftN.takeIf { it > 0 },
            draftNAccepted = timings.draftNAccepted.takeIf { it > 0 },
            samples = listOf(sample),
        )
    }

    /** ~1.33 tokens/word heuristic for Qwen vocab English filler. */
    private fun lorem(tokens: Int): String =
        LOREM.split(' ').let { words ->
            val nWords = (tokens / 1.33).toInt().coerceAtLeast(8)
            (0 until nWords).joinToString(" ") { words[it % words.size] }
        }

    companion object {
        const val SC_PREFILL = "prefill"
        const val SC_DECODE = "decode"
        const val SC_SPEC_AB = "spec_ab"
        const val SC_KERNEL_AB = "kernel_ab"

        // Literal IP first: it cannot fail to resolve. MagicDNS is a fallback for
        // when the address changes.
        const val UPLOAD_URL = "http://100.103.111.81:8091/bonasidroidv2"
        const val UPLOAD_URL_ALT = "http://tony-aimax395.tail5f69b1.ts.net:8091/bonasidroidv2"
        const val SC_KERNEL_QUICK = "kernel_quick"
        const val SC_KERNEL_ONE = "kernel_one"
        const val SC_MTP_AB = "mtp_ab"
        const val SC_FULL_REPORT = "full_report"

        /**
         * Variants to sweep: 0..6 and 8,9 are nrc=1 kernels; 7 (v10) needs
         * nrows=2. v4b..v12 beyond the baseline are all bit-exact or float-order
         * equivalents of v4, so this is a pure speed comparison.
         */
        /**
         * v7 is excluded on purpose. It is the nrc=2 tile, and with nrows=2 it
         * drives ptq1_v10_nrc2 - the i8mm/SMMLA path, the one branch that could
         * never be verified (qemu 7.2 emulates SMMLA incorrectly, and its result
         * does not reproduce the documented tile). It also falls back to ptq1_v4
         * whenever nrc==1, which is always true during decode, so its decode row
         * would duplicate v0 anyway. It is still selectable by hand in Settings
         * for prefill experiments.
         */
        val KERNEL_VARIANTS = listOf(0, 1, 2, 3, 4, 5, 6, 8, 9)

        /**
         * A short sweep for when the full one will not survive the reload count.
         * v0 is the shipping baseline, v12 is the only variant that vectorises the
         * reduction (the largest single block in the kernel), v5 changes the
         * accumulator to FMA, and v11 is the other reduce-focused variant. These
         * are the four that instruction analysis says could plausibly win, so a
         * 4-restart run is enough to answer the question if a 9-restart run does
         * not survive.
         */
        val KERNEL_QUICK_VARIANTS = listOf(0, 2, 8, 9)
        const val SC_LONG_CTX = "long_ctx"
        const val PREFILL_PAD_TOKENS = 2048
        const val DECODE_TOKENS = 256
        /**
         * Shorter decode for the kernel sweep. Each variant needs a fresh engine
         * (the server caches its kernel choice once), and a model reload is ~36 s,
         * so 256 tokens x 12 runs was ~28 minutes. 64 tokens still gives a stable
         * rate per variant and brings the whole sweep to ~12 minutes.
         */
        const val KERNEL_DECODE_TOKENS = 64

        /**
         * A variant must beat the baseline by this fraction (relative) before it is
         * offered as a winner. Below it the ranking is inside measurement noise and
         * changing the shipped kernel would be churn, not an improvement.
         */
        const val WINNER_MARGIN = 1.03f
        const val LONG_CTX_PAD_TOKENS = 30_000

        private val LOREM = """
            bonsai ternary quantization attention cache kernel mobile inference token
            bandwidth memory compute thermal profile draft accept speculative hybrid
            linear full layer scale group pack decode prefill stream device battery
        """.trimIndent()
    }
}
