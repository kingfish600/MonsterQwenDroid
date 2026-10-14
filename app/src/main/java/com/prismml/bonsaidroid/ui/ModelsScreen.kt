package com.prismml.bonsaidroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.prismml.bonsaidroid.data.AssetKind
import com.prismml.bonsaidroid.data.DownloadState
import com.prismml.bonsaidroid.data.ModelAsset
import com.prismml.bonsaidroid.data.ModelCatalog

/** Above this fraction the bytes are in; show "finalizing..." not Cancel. */
private const val FINALIZING = 0.999f
private const val MB: Long = 1L shl 20

/** One-tap shareable diagnostic bundle — paste into chat instead of OCR'ing screenshots. */
private fun diagnosticBundle(
    state: com.prismml.bonsaidroid.engine.EngineStatus,
    loaded: AppViewModel.Loaded?,
    error: String?,
    fallback: String?,
    logs: List<String>,
): String = buildString {
    appendLine("== MonsterQwenDroid diagnostics ==")
    appendLine(
        "build: v${com.prismml.bonsaidroid.BuildConfig.VERSION_NAME} " +
            "(${com.prismml.bonsaidroid.BuildConfig.VERSION_CODE})"
    )
    appendLine("engine_state: ${state.state}  elapsed=${state.elapsedSec}s")
    appendLine("detail: ${state.detail ?: "-"}")
    appendLine("loaded: ${loaded?.target?.fileName ?: "-"}" +
        (loaded?.draft?.let { " + ${it.fileName}" } ?: ""))
    if (fallback != null) appendLine("note: $fallback")
    if (error != null) appendLine("error: $error")
    appendLine("--- full runtime log (${logs.size} lines) ---")
    logs.forEach { appendLine(it) }
}.trimEnd()
@Composable
fun ModelsScreen(vm: AppViewModel) {
    val downloads by vm.downloadState.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val engineStatus by vm.engineStatus.collectAsState()
    val logs by vm.logTail.collectAsState()
    val fallbackNote by vm.fallbackNote.collectAsState()

    val clipboard = LocalClipboardManager.current
    var copiedTick by remember { mutableStateOf(0L) }
    LaunchedEffect(copiedTick) {
        if (copiedTick > 0) { kotlinx.coroutines.delay(1500); copiedTick = 0 }
    }

    var selectedTarget by remember {
        // Was .first { it.id == "bonsai2-ptq1_0" } - a hardcoded id that threw
        // NoSuchElementException (force close) when the catalog changed. Resolve
        // through the catalog's own default and degrade to the first entry rather
        // than crashing if it is ever missing.
        mutableStateOf(
            ModelCatalog.assets.firstOrNull { it.id == ModelCatalog.DEFAULT_TARGET_ID }
                ?: ModelCatalog.assets.first()
        )
    }
    // Drafter must have been trained against the selected target (v1 drafter
    // is architecturally incompatible with Bonsai-2).
    val drafter = ModelCatalog.assets.firstOrNull {
        it.kind == AssetKind.DRAFT_MODEL && it.compatibleWith == selectedTarget.id
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Runtime", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        if (copiedTick > 0) {
                            Text("copied \u2713", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(diagnosticBundle(
                                state = engineStatus, loaded = loaded, error = error,
                                fallback = fallbackNote, logs = logs,
                            )))
                            copiedTick = System.currentTimeMillis()
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy diagnostics")
                        }
                    }
                    val statusLine = when (engineStatus.state) {
                        com.prismml.bonsaidroid.engine.EngineState.STARTING -> {
                            val health = engineStatus.detail
                                ?.substringAfter(" \u2014 health: ", "")
                                ?.takeIf { it.isNotBlank() }
                            "loading model\u2026 ${engineStatus.elapsedSec}s elapsed" +
                                (health?.let { " \u00b7 health: $it" } ?: "") +
                                " \u2014 first load pages ~7 GB off flash, be patient"
                        }
                        com.prismml.bonsaidroid.engine.EngineState.LOADED ->
                            "loaded: ${loaded?.target?.fileName ?: engineStatus.detail}" +
                                "" // drafter is off by default (measured neutral)
                        com.prismml.bonsaidroid.engine.EngineState.FAILED ->
                            "load failed \u2014 see details below"
                        else ->
                            if (busy) "working\u2026" else "idle \u2014 pick a pack below and load"
                    }
                    Text(statusLine, style = MaterialTheme.typography.bodySmall)
                    fallbackNote?.let {
                        Text(it, color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (logs.isNotEmpty()) {
                        Text(
                            logs.takeLast(3).joinToString("\n") { l -> l.take(120) },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled = !busy && loaded == null &&
                                vm.downloader.isDownloaded(selectedTarget),
                            onClick = {
                                val d = drafter?.takeIf { vm.downloader.isDownloaded(it) }
                                vm.loadModel(selectedTarget, d)
                            },
                        ) {
                            // The engine is started with the model only. Speculative
                            // decoding measured neutral here (64% acceptance, decode
                            // unchanged) and its head costs 2.8 GB of page cache, so the
                            // drafter is deliberately not passed.
                            Text("Load")
                        }
                        OutlinedButton(enabled = loaded != null && !busy, onClick = { vm.unload() }) {
                            Text("Unload")
                        }
                    }
                }
            }
        }

        items(ModelCatalog.assets) { asset ->
            AssetCard(
                asset = asset,
                selected = asset.id == selectedTarget.id && asset.kind == AssetKind.TARGET_MODEL,
                downloaded = vm.downloader.isDownloaded(asset),
                downloadState = downloads.takeIf { it is DownloadState.Running || it is DownloadState.Failed },
                onSelect = { if (asset.kind == AssetKind.TARGET_MODEL) selectedTarget = asset },
                onDownload = { vm.launchUi { vm.downloader.download(asset) } },
                onCancel = { vm.downloader.cancel() },
                onDelete = { vm.downloader.delete(asset) },
            )
        }
    }
}

@Composable
private fun AssetCard(
    asset: ModelAsset,
    selected: Boolean,
    downloaded: Boolean,
    downloadState: DownloadState?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = downloadState is DownloadState.Running && downloadState.assetId == asset.id
    Card(onClick = onSelect, enabled = asset.kind == AssetKind.TARGET_MODEL) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(asset.fileName, style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Text("%.1f GB".format(asset.sizeBytes / 1e9),
                    style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${asset.summary} ${if (selected) "• selected" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                running -> {
                    val st = downloadState as DownloadState.Running
                    LinearProgressIndicator(
                        progress = { st.fraction },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                    Text(
                        "${st.mbDone} / ${st.bytesTotal / MB} MB (${(st.fraction * 100).toInt()}%)" +
                            if (st.fraction >= FINALIZING) " — finalizing…" else "",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                downloaded -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("on device ✓", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.CenterVertically))
                        androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete") }
                    }
                }
                else -> {
                    OutlinedButton(onClick = onDownload, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Download")
                    }
                }
            }
            val failedHere = downloadState is DownloadState.Failed && downloadState.assetId == asset.id
            val stillStreaming = running &&
                (downloadState as DownloadState.Running).fraction < FINALIZING
            if (stillStreaming || failedHere) {
                Row {
                    if (stillStreaming) {
                        androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") }
                    } else {
                        Text((downloadState as DownloadState.Failed).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
