package com.prismml.bonsaidroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable


import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Human labels for the PTQ1_0 ARM kernel battery ($GGML_PTQ1_ARM). */
private val KERNEL_NAMES = mapOf(
    0 to "v4 (default)",
    1 to "v4b",
    2 to "v5",
    3 to "v6",
    4 to "v7",
    5 to "v8",
    6 to "v9",
    7 to "v10 (nrc=2 tile; measures as v4 during decode)",
    8 to "v11",
    9 to "v12",
)

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val profile by vm.profileFlow.collectAsState()
    val running by vm.isRunning.collectAsState()
    val logs by vm.logTail.collectAsState()
    val thermal by vm.governor.reading.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Engine profile", style = MaterialTheme.typography.titleMedium)
        Text(
            "applies on next Load — Unload, then Load to switch live",
            style = MaterialTheme.typography.labelSmall,
        )
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            com.prismml.bonsaidroid.engine.EngineProfile.presets.forEach { p ->
                FilterChip(
                    selected = p.name == profile.name,
                    onClick = { vm.setProfile(p) },
                    label = { Text(p.name.replace('_', ' ')) },
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                KVLine(
                    "version",
                    "v${com.prismml.bonsaidroid.BuildConfig.VERSION_NAME} " +
                        "(build ${com.prismml.bonsaidroid.BuildConfig.VERSION_CODE})",
                )
                KVLine("context", "${profile.contextTokens} tokens")
                KVLine("threads", if (profile.threads == 0) "auto (8 big cores)" else "${profile.threads}")
                KVLine("gpu layers", "${profile.nGpuLayers}" + if (profile.nGpuLayers == 0) " (CPU)" else " (Vulkan)")
                KVLine("kv cache", "${profile.kvCacheTypeK}/${profile.kvCacheTypeV} + flash-attn ${if (profile.flashAttn) "on" else "off"}")
                KVLine("MTP drafter", if (profile.useDraftModel) "on, block=${profile.draftMax}" else "off")
                KVLine("PTQ1_0 kernel", KERNEL_NAMES.getOrElse(profile.kernelVariant) { "v${profile.kernelVariant}" })

                // Kernel battery: pick a variant, restart the engine, re-ask the same
                // prompt. Applied on restart because the server caches the choice once.
                Text(
                    "PTQ1_0 kernel variant (restart engine to apply)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    KERNEL_NAMES.keys.sorted().forEach { v ->
                        FilterChip(
                            selected = profile.kernelVariant == v,
                            onClick = { vm.setProfile(profile.copy(kernelVariant = v)) },
                            label = { Text("v$v") },
                        )
                    }
                }

                val selfTest by vm.selfTest.collectAsState()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { vm.runGpuSelfTest() }) { Text("Vulkan self-test") }
                    if (selfTest.isNotEmpty()) {
                        TextButton(onClick = { vm.copySelfTest() }) { Text("⧉ copy") }
                        TextButton(onClick = { vm.clearSelfTest() }) { Text("clear") }
                    }
                }
                if (selfTest.isNotEmpty()) {
                    SelectionContainer {
                        Column(
                            Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            selfTest.takeLast(30).forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }

        ToggleRow(
            label = "Expose on Wi-Fi",
            sub = "Serve the OpenAI-compatible API to your LAN (desktop clients can hit this phone)",
            checked = profile.exposeOnWifi,
            onChange = {
                vm.setProfile(profile.copy(exposeOnWifi = it))
            },
        )

        thermal?.let { t ->
            Text(
                "Thermal: ${t.level} " +
                    "(max zone ${t.maxTempC?.toInt() ?: 0} °C, suggested: ${t.recommendedProfile.name})",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Runtime log", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            var copiedTick by remember { mutableStateOf(0L) }
            androidx.compose.runtime.LaunchedEffect(copiedTick) {
                if (copiedTick > 0) {
                    kotlinx.coroutines.delay(1600)
                    copiedTick = 0
                }
            }
            if (copiedTick > 0) {
                Text("copied ✓", style = MaterialTheme.typography.labelSmall)
            }
            androidx.compose.material3.IconButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(
                    "MonsterQwenDroid full runtime log\n" + logs.joinToString("\n")
                ))
                copiedTick = System.currentTimeMillis()
            }) {
                androidx.compose.material3.Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy full log",
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            val tail = logs.takeLast(60).joinToString("\n")
            Text(
                tail.ifBlank { if (running) "waiting for output…" else "runtime idle" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
            )
        }

        Text(
            "Build note: the bundled llama-server comes from PrismML-Eng/llama.cpp " +
                "(branch prism), cross-built for arm64-v8a with NEON dotprod/i8mm and " +
                "16 KB page alignment. See docs/BUILD.md.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ToggleRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun KVLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 16.dp))
        Text(v, style = MaterialTheme.typography.labelMedium)
    }
}
