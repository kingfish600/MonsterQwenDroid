package com.prismml.bonsaidroid.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class DownloadMeta(
    val assetId: String,
    val fileName: String,
    val sizeBytes: Long,
    val completedAtEpochMs: Long,
)

sealed interface DownloadState {
    data object Idle : DownloadState
    /** [bytesTotal] is the AUTHORITATIVE server size, not the catalog estimate. */
    data class Running(val assetId: String, val bytesDone: Long, val bytesTotal: Long) : DownloadState {
        val fraction: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
        val mbDone: Long get() = bytesDone / (1L shl 20)
    }
    data class Failed(val assetId: String, val message: String) : DownloadState
}

/**
 * Resumable downloader for Hugging Face weight files into app-scoped external
 * storage. No storage permissions required.
 *
 * Resume strategy: HTTP `Range` against a `.part` staging file; fsync +
 * atomic rename on completion; `.meta.json` sidecar records the result.
 *
 * LESSON LEARNED (v0.1): catalog sizes are marketing numbers. The real file
 * size is resolved via HEAD before any byte flows; treating nominal sizes as
 * authoritative caused HTTP 416 (range past EOF) and eternal 100%-bars.
 */
class ModelDownloader(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    /** One download at a time — these are multi-GB files on a phone. */
    private val mutex = Mutex()

    fun modelsDir(): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models").apply { mkdirs() }

    fun fileFor(asset: ModelAsset): File = File(modelsDir(), asset.fileName)

    fun metaFor(asset: ModelAsset): DownloadMeta? {
        val f = File(modelsDir(), asset.fileName + ".meta.json")
        return runCatching { json.decodeFromString<DownloadMeta>(f.readText()) }.getOrNull()
    }

    fun isDownloaded(asset: ModelAsset): Boolean = filesFor(asset).all { isOneDownloaded(it) }

    private fun isOneDownloaded(one: ModelAsset): Boolean {
        if (File(modelsDir(), one.fileName + ".meta.json").exists()) return fileFor(one).exists()
        val f = fileFor(one)
        // No sidecar yet (pre-fix download): accept within 3% of the catalog
        // number — close enough to avoid re-fetching gigabytes.
        return f.exists() &&
            (one.sizeBytes <= 0 || f.length() >= one.sizeBytes * 97 / 100)
    }

    /**
     * Every file an asset needs, as standalone single-file assets: the primary
     * GGUF plus any sibling shards. llama.cpp finds the shards by name, but only
     * if the downloader put them there.
     */
    fun filesFor(asset: ModelAsset): List<ModelAsset> {
        val dir = asset.url.substringBeforeLast('/')
        val primary = asset.copy(extraFiles = emptyList(), extraSizes = emptyList())
        val extras = asset.extraFiles.mapIndexed { i, name ->
            asset.copy(
                fileName = name,
                url = "$dir/$name",
                sizeBytes = asset.extraSizes.getOrElse(i) { 0L },
                extraFiles = emptyList(),
                extraSizes = emptyList(),
            )
        }
        return listOf(primary) + extras
    }

    fun downloadedTargets(): List<ModelAsset> =
        ModelCatalog.assets.filter { it.kind == AssetKind.TARGET_MODEL && isDownloaded(it) }

    /** Drafter available for speculative decoding? */
    fun availableDrafters(): List<ModelAsset> =
        ModelCatalog.assets.filter { it.kind == AssetKind.DRAFT_MODEL && isDownloaded(it) }

    fun freeBytes(): Long = StatFs(modelsDir().absolutePath).availableBytes

    /**
     * Downloads [asset], resuming any existing `.part`. The authoritative size
     * comes from a HEAD request; the catalog value is only a fallback.
     */
    suspend fun download(asset: ModelAsset): File = mutex.withLock {
        val all = filesFor(asset)
        val totalAll = all.sumOf { it.sizeBytes }
        var baseBytes = 0L
        for (one in all) {
            downloadOne(one, baseBytes, totalAll)
            baseBytes += one.sizeBytes
        }
        fileFor(asset)
    }

    /** Fetches one file of a (possibly multi-shard) asset. Caller holds the mutex. */
    private suspend fun downloadOne(
        asset: ModelAsset,
        baseBytes: Long,
        totalAll: Long,
    ): File =
        withContext(Dispatchers.IO) {
            val totalBytes = try {
                resolveTotalSize(asset.url)
            } catch (t: Throwable) {
                // Offline HEAD? Fall back to the nominal number; Range math may
                // then 416 near EOF, which we handle gracefully below.
                asset.sizeBytes
            }

            if (freeBytes() < totalBytes - stagedPartBytes()) {
                throw IOException(
                    "Not enough free space: need ~${totalBytes / GB} B, have ${freeBytes() / GB} B"
                )
            }

            val dest = fileFor(asset)
            if (isDownloaded(asset)) return@withContext dest

            val part = File(dest.absolutePath + ".part")
            // A stale .part bigger than the real file is unusable.
            var bytesDone = if (part.exists() && part.length() <= totalBytes) part.length() else {
                part.delete(); 0L
            }

            _state.value = DownloadState.Running(asset.id, baseBytes + bytesDone, totalAll)

            var consecutiveNoProgress = 0
            while (bytesDone < totalBytes && _state.value is DownloadState.Running) {
                val next = fetchChunk(asset, part, bytesDone, totalBytes, baseBytes, totalAll)
                if (next <= bytesDone) {
                    if (++consecutiveNoProgress >= 3) {
                        throw IOException("no download progress at $bytesDone/$totalBytes bytes")
                    }
                } else {
                    consecutiveNoProgress = 0
                }
                bytesDone = next
            }

            when (val s = _state.value) {
                is DownloadState.Failed ->
                    throw IOException("download failed at $bytesDone bytes: ${s.message}")
                else -> Unit
            }
            check(bytesDone >= totalBytes) { "incomplete download ($bytesDone/$totalBytes)" }

            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            File(dest.absolutePath + ".meta.json").writeText(
                json.encodeToString(
                    DownloadMeta.serializer(),
                    DownloadMeta(
                        assetId = asset.id,
                        fileName = asset.fileName,
                        sizeBytes = dest.length(),
                        completedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            )
            _state.value = DownloadState.Idle
            dest
        }

    fun cancel() {
        // Checked as the loop flag inside download(); mutex released by caller's scope.
        if (_state.value is DownloadState.Running) {
            _state.value = DownloadState.Idle
        }
    }

    fun delete(asset: ModelAsset) {
        for (one in filesFor(asset)) {
            val f = fileFor(one)
            f.delete()
            File(f.absolutePath + ".part").delete()
            File(f.absolutePath + ".meta.json").delete()
        }
    }

    // ------------------------------------------------------------------ //

    /** Authoritative Content-Length from the CDN edge (follows redirects). */
    private suspend fun resolveTotalSize(url: String): Long = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code} while resolving file size" }
            resp.header("Content-Length")?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: throw IOException("no Content-Length from server")
        }
    }

    /**
     * Streams one HTTP chunk into [part], returning the new absolute offset.
     *
     * Handles the two ways CDNs betray you:
     *  * **416** — our offset reached/passed EOF: if Content-Range says we hold
     *    the whole file already, treat it as done instead of failing.
     *  * **200 instead of 206** — server ignored Range: truncate and restart
     *    rather than corrupt-append a second full copy.
     */
    private suspend fun fetchChunk(
        asset: ModelAsset,
        part: File,
        fromByte: Long,
        totalBytes: Long,
        baseBytes: Long,
        totalAll: Long,
    ): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(asset.url).apply {
            if (fromByte > 0) header("Range", "bytes=$fromByte-")
        }.build()

        var done = fromByte
        http.newCall(req).execute().use { resp ->
            if (resp.code == 416) {
                val srvTotal = resp.header("Content-Range")
                    ?.substringAfter('/')?.toLongOrNull()
                if (srvTotal != null && fromByte >= srvTotal) {
                    return@withContext srvTotal // already complete
                }
                throw IOException("HTTP 416 (bad range) at offset $fromByte")
            }
            check(resp.isSuccessful || resp.code == 206) { "HTTP ${resp.code} for ${asset.fileName}" }
            val body = resp.body ?: throw IOException("empty body")

            FileOutputStream(part, /* append = */ resp.code == 206).use { out ->
                if (resp.code == 200 && fromByte > 0) done = 0 // truncated above
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 20) // 1 MiB
                    while (done < totalBytes) {
                        // Idle here means "cancel requested".
                        if (_state.value == DownloadState.Idle) {
                            throw CancellationException("download cancelled")
                        }
                        val n = input.read(buf)
                        if (n < 0) break // server closed at EOF; outer loop decides
                        out.write(buf, 0, n)
                        done += n
                        _state.value = DownloadState.Running(asset.id, baseBytes + done, totalAll)
                    }
                    out.fd.sync()
                }
            }
        }
        done
    }

    private fun stagedPartBytes(): Long =
        modelsDir().listFiles()?.filter { it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L

    companion object {
        const val GB: Long = 1L shl 30
    }
}
