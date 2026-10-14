package com.prismml.bonsaidroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(vm: AppViewModel) {
    val turns by vm.turns.collectAsState()
    val generating by vm.generating.collectAsState()
    val running by vm.isRunning.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val timings by vm.lastTimings.collectAsState()
    val error by vm.error.collectAsState()
    val thermal by vm.governor.reading.collectAsState()
    val sessions by vm.chats.sessions.collectAsState()
    val reasoning by vm.reasoning.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size, turns.lastOrNull()?.content?.length) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }
    val canRegenerate = !generating && running && turns.lastOrNull()?.role == "assistant"

    Column(Modifier.fillMaxSize().padding(12.dp).imePadding()) {

        // Session controls: history + new chat.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { showHistory = true }) { Text("chats (${sessions.size})") }
            TextButton(onClick = { vm.newChat() }) { Text("new") }
            Spacer(Modifier.weight(1f))
            // Per-chat thinking level — the wall-clock lever. "off" skips the
            // ~1400-token think block entirely; "deep" = xhigh for hard asks.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("think:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                listOf("" to "auto", "medium" to "med", "xhigh" to "deep", "none" to "off")
                    .forEach { (code, label) ->
                        FilterChip(
                            selected = reasoning == code,
                            onClick = { vm.setReasoning(code) },
                            label = { Text(label) },
                        )
                    }
            }
        }

        // Status chips row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        when {
                            loaded == null -> "no model"
                            !running -> "loading…"
                            else -> loaded!!.target.fileName.removeSuffix(".gguf").removePrefix("Ternary-")
                        }
                    )
                },
            )
            timings?.let { t ->
                // >5000 t/s on CPU/Vulkan phone = division-by-near-zero garbage
                // (instant-EOS run), not a real measurement.
                if (t.predictedPerSecond in 0.01f..5000f) {
                    AssistChip(onClick = {}, label = { Text("%.1f tok/s".format(t.predictedPerSecond)) })
                }
                if (t.draftN > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("MTP %d/%d".format(t.draftNAccepted, t.draftN)) },
                    )
                }
            }
            thermal?.let {
                if (it.level != com.prismml.bonsaidroid.engine.ThermalGovernor.Level.COOL) {
                    AssistChip(
                        onClick = {},
                        label = { Text("${it.level} ${it.maxTempC?.toInt() ?: 0}°C") },
                    )
                }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    Text(
                        "Qwen3.8-Flash-Next (176B MoE) runs entirely on this device.\n" +
                            "Load a pack in the Models tab, then talk.\n\n" +
                            "Long prompts are processed on CPU; tap \"thinking\" on an " +
                            "answer to expand or collapse the reasoning trace.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            }
            items(turns) { turn -> Bubble(turn) }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (running) "Message" else "Load a model first…") },
                enabled = running && !generating,
                maxLines = 5,
            )
            if (generating) {
                IconButton(onClick = { vm.stopGenerating() }) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                if (canRegenerate) {
                    IconButton(onClick = { vm.regenerate() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Regenerate")
                    }
                }
                IconButton(
                    onClick = {
                        vm.send(input); input = ""
                    },
                    enabled = running && input.isNotBlank(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            confirmButton = {
                TextButton(onClick = { showHistory = false }) { Text("close") }
            },
            title = { Text("Chat history") },
            text = {
                if (sessions.isEmpty()) {
                    Text("No stored chats yet.")
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(sessions) { s ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .padding(vertical = 6.dp)
                                        .clickable {
                                            vm.openChat(s.id)
                                            showHistory = false
                                        },
                                ) {
                                    Text(s.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "%d turns · %s".format(
                                            s.turns.size,
                                            java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
                                                .format(java.util.Date(s.createdAtEpochMs)),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { vm.deleteChat(s.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/** Tiny clickable wrapper — using foundation.clickable directly above. */

@OptIn(ExperimentalTextApi::class)
@Composable
private fun Bubble(turn: com.prismml.bonsaidroid.data.ChatTurn) {
    val isUser = turn.role == "user"
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (turn.reasoning.isNotBlank()) {
                // Qwen thinking blocks: collapsed by default.
                var expanded by rememberSaveable { mutableStateOf(false) }
                Text(
                    (if (expanded) "▾ thinking" else "▸ thinking") + " (${turn.reasoning.length} chars)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (expanded) {
                    Text(turn.reasoning, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (isUser) {
                Text(turn.content, style = MaterialTheme.typography.bodyMedium)
            } else {
                RichAnswer(turn.content.ifBlank { "" })
            }
            if (turn.content.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(turn.content)) },
                        modifier = Modifier.padding(0.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Minimal markdown subset tuned for model output: fenced code blocks (copyable,
 * horizontally scrollable), `inline code`, **bold**. Anything else renders as
 * plain text - deliberately no networked markdown dependency.
 */
@Composable
private fun RichAnswer(text: String) {
    if (text.isBlank()) {
        Text("…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val blocks = remember(text) { splitFenced(text) }
    Column {
        blocks.forEach { (isCode, body) ->
            if (isCode) CodeBlock(body.trimEnd('\n'))
            else if (body.isNotBlank()) RichInline(body.trimEnd('\n'))
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun CodeBlock(code: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.widthIn(min = 0.dp).padding(8.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Text("copy code", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RichInline(text: String) {
    val segs = remember(text) { splitInline(text) }
    androidx.compose.foundation.text.BasicText(
        text = androidx.compose.ui.text.buildAnnotatedString {
            segs.forEach { seg ->
                when (seg.kind) {
                    SegKind.CODE -> withStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) { append(seg.text) }
                    SegKind.BOLD -> withStyle(
                        androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold),
                    ) { append(seg.text) }
                    SegKind.PLAIN -> append(seg.text)
                }
            }
        },
        // BasicText ignores the Material theme - explicit color or answer text renders BLACK on dark bg (owner QoL 10-07)
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    )
}

private enum class SegKind { PLAIN, CODE, BOLD }
private data class Seg(val kind: SegKind, val text: String)

private fun splitInline(text: String): List<Seg> {
    val out = mutableListOf<Seg>()
    var i = 0
    val sb = StringBuilder()
    while (i < text.length) {
        val c = text[i]
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                if (sb.isNotEmpty()) { out.add(Seg(SegKind.PLAIN, sb.toString())); sb.setLength(0) }
                out.add(Seg(SegKind.CODE, text.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }
        if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end > i + 1) {
                if (sb.isNotEmpty()) { out.add(Seg(SegKind.PLAIN, sb.toString())); sb.setLength(0) }
                out.add(Seg(SegKind.BOLD, text.substring(i + 2, end)))
                i = end + 2
                continue
            }
        }
        sb.append(c)
        i++
    }
    if (sb.isNotEmpty()) out.add(Seg(SegKind.PLAIN, sb.toString()))
    return out
}

@Composable
private fun MarkdownInlineImpl(text: String) {
    val segs = remember(text) { splitInline(text) }
    androidx.compose.foundation.text.BasicText(
        text = androidx.compose.ui.text.buildAnnotatedString {
            segs.forEach { seg ->
                when (seg.kind) {
                    SegKind.CODE -> withStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) { append(seg.text) }
                    SegKind.BOLD -> withStyle(
                        androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold),
                    ) { append(seg.text) }
                    SegKind.PLAIN -> append(seg.text)
                }
            }
        },
        // BasicText ignores the Material theme - explicit color or answer text renders BLACK on dark bg (owner QoL 10-07)
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    )
}

/** Split text into (isCode, chunk) on ``` fence lines. */
private fun splitFenced(text: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    val sb = StringBuilder()
    var inCode = false
    for (line in text.lineSequence()) {
        if (line.trimStart().startsWith("```")) {
            out.add(inCode to sb.toString())
            sb.setLength(0)
            inCode = !inCode
        } else {
            sb.append(line).append('\n')
        }
    }
    out.add(inCode to sb.toString())
    return out.filter { it.second.isNotBlank() }
}
