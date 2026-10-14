package com.prismml.bonsaidroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.prismml.bonsaidroid.harness.BenchHarness
import com.prismml.bonsaidroid.harness.BenchProgress

@Composable
fun HarnessScreen(vm: AppViewModel) {
    val bench by vm.benchHarness.progress.collectAsState()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val appContextPath = appContext.filesDir.path
    var probeVariant by rememberSaveable { mutableStateOf(0) }
    val running by vm.isRunning.collectAsState()
    var evalJson by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Benchmark harness", style = MaterialTheme.typography.titleMedium)
        Text(
            "Runs against the loaded model. SPEC_AB restarts the runtime twice " +
                "(drafter off vs on) — expect ~a minute.",
            style = MaterialTheme.typography.bodySmall,
        )
        // Own row, above the scroll strip: the Kernel A/B button used to be the
        // fifth item in a horizontally scrolling row, so on a phone it was off-screen
        // unless the user happened to swipe sideways.
        val ptqLoaded = vm.loadedModelName?.contains("PTQ1_0", ignoreCase = true) == true
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "PTQ1_0 kernel battery",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Measures all ten NEON+DotProd kernels on this device and ranks " +
                        "them. Needs a PTQ1_0 model loaded.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    enabled = running && ptqLoaded,
                    onClick = { vm.launchUi { vm.benchHarness.runScenario(BenchHarness.SC_KERNEL_AB, vm.profile) } },
                ) { Text("Run kernel A/B (all 9, ~12 min)") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    enabled = running && ptqLoaded,
                    onClick = { vm.launchUi { vm.benchHarness.runScenario(BenchHarness.SC_KERNEL_QUICK, vm.profile) } },
                ) { Text("Run kernel A/B (quick, 4 variants, ~6 min)") }
                // When a sweep force-closes, the useful question is which variant did
                // it. This costs one reload instead of twelve.
                Spacer(Modifier.height(6.dp))
                Text(
                    "Test one variant (one reload, ~40 s)",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    listOf(0,1,2,3,4,5,6,8,9).forEach { n ->
                        FilterChip(
                            selected = probeVariant == n,
                            onClick = { probeVariant = n },
                            label = { Text("v$n") },
                        )
                    }
                }
                Button(
                    enabled = running && ptqLoaded,
                    onClick = {
                        vm.benchHarness.pendingVariant = probeVariant
                        vm.launchUi { vm.benchHarness.runScenario(BenchHarness.SC_KERNEL_ONE, vm.profile) }
                    },
                ) { Text("Test v$probeVariant") }
                Text(
                    "If this force-closes, the crash is in this variant. The red " +
                        "banner at the top of this tab then says which kind of fault.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = running && ptqLoaded,
                    onClick = { vm.launchUi { vm.benchHarness.runScenario(BenchHarness.SC_MTP_AB, vm.profile) } },
                ) { Text("MTP A/B (needs an MTP model, ~8 min)") }
                Text(
                    "Two passes with speculative decoding off vs on, alternating so " +
                        "heat cannot favour either arm. Results appear at the top of " +
                        "this tab.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "The quick sweep tests v0 (baseline), v5, v11 and v12 - the four " +
                        "instruction analysis says could plausibly win. Use it if the " +
                        "full sweep will not survive.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (running && !ptqLoaded) {
                    Text(
                        "Disabled: the loaded model is not PTQ1_0, so every variant " +
                            "would measure the same.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = running,
                onClick = { vm.launchUi { vm.benchHarness.runScenario(BenchHarness.SC_DECODE, vm.profile) } },
            ) { Text("Decode 256") }
            OutlinedButton(
                enabled = running,
                onClick = { vm.launchUi { vm.benchHarness.runScenario(BenchHarness.SC_PREFILL, vm.profile) } },
            ) { Text("Prefill 2K") }
            Button(
                enabled = running,
                onClick = { vm.launchUi { vm.benchHarness.runScenario(BenchHarness.SC_SPEC_AB, vm.profile) } },
            ) { Text("SPEC A/B (MTP)") }
            OutlinedButton(
                enabled = running,
                onClick = { vm.launchUi { vm.benchHarness.runScenario(BenchHarness.SC_LONG_CTX, vm.profile) } },
            ) { Text("Long ctx ~30K") }
        }

        // Machine-level primitives: answers whether the 128-bit path really beats
        // two 64-bit ops, and what the horizontal reduction costs. These are
        // machine questions no kernel variant can answer, and on host/emulator they
        // mislead (a software TLB walk over-weights loads).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("NEON primitives", style = MaterialTheme.typography.titleSmall)
                Text(
                    "ns per op, measured on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    enabled = !running,
                    onClick = {
                        vm.launchUi {
                            val v = runCatching {
                                com.prismml.bonsaidroid.nativeffi.BonsaiNative.neonProbeNs(200_000)
                            }.getOrNull()
                            if (v == null || v.size < 6) {
                                vm.setError("NEON probe unavailable on this device")
                            } else {
                                vm.setError(
                                    "dotprod128 %.2f | dotprod64 %.2f | add128 %.2f | " +
                                        "add64 %.2f | vaddvq %.2f | reduce %.2f  (ns/op)".format(
                                            v[0], v[1], v[2], v[3], v[4], v[5]
                                        )
                                )
                            }
                        }
                    },
                ) { Text("Run NEON primitive probe") }
            }
        }

        // Surface the previous native crash. Without this the only symptom is a
        // force close, which is what left the cause of the device crash ambiguous.
        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
                as com.prismml.bonsaidroid.BonsaiApp
        val crash = app.lastCrash
        if (crash.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Last native crash", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Text(
                        crash,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "SIGILL = the i8mm/SMMLA path. SIGSEGV = bad address, " +
                            "shown above. KOTLIN = an uncaught exception. " +
                            "No banner at all after a force close means the OOM " +
                            "killer: it kills from outside, so no handler runs.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Raw sweep rows, on screen. Reading these has meant digging through a file
        // manager; showing them here makes the measurement visible in the app, and
        // it is the ground truth if the results upload ever fails.
        val sweepFile = remember(appContextPath) {
            runCatching {
                java.io.File(
                    java.io.File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "bench"),
                    "kernel_sweep.txt",
                ).takeIf { it.exists() }?.readText()?.takeLast(1600)
            }.getOrNull()
        }
        if (!sweepFile.isNullOrBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Sweep file", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "files/bench/kernel_sweep.txt",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        sweepFile,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // Progress, at the top of the tab. It used to render below the fold, so a
        // running sweep looked like nothing was happening - the only clue was the
        // list twitching as it recomposed.
        val runningState = bench as? BenchProgress.Running
        var runStart by remember(runningState != null) { mutableStateOf(System.currentTimeMillis()) }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(runningState?.label) { runStart = System.currentTimeMillis() }
        LaunchedEffect(runningState != null) {
            while (runningState != null) { kotlinx.coroutines.delay(1000); tick++ }
        }
        if (runningState != null) {
            val elapsed = ((System.currentTimeMillis() - runStart) / 1000)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Running", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${runningState.label}  (step ${runningState.step} of ${runningState.steps})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val frac = if (runningState.steps > 0) {
                        runningState.step.toFloat() / runningState.steps
                    } else 0f
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text(
                        "${elapsed}s elapsed - a full sweep runs about 7 minutes",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // A 12-minute sweep had no way to abort: every button is disabled while it runs.
        if (running) {
            OutlinedButton(
                onClick = { vm.benchHarness.cancel() },
            ) { Text("Cancel running benchmark") }
        }

        when (val b = bench) {
            is BenchProgress.Running -> Text("… ${b.label} (${b.step}/${b.steps})")
            is BenchProgress.Failed -> Text(b.message, color = MaterialTheme.colorScheme.error)
            is BenchProgress.Finished -> if (b.results.isNotEmpty()) {
                val winner: Int? = b.results
                    .firstOrNull { it.scenario.startsWith("KERNEL/v") && (it.notes ?: "").contains("BEST") }
                    ?.scenario?.removePrefix("KERNEL/v")?.toIntOrNull()
                if (winner != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Fastest kernel: v$winner", style = MaterialTheme.typography.bodyMedium)
                            Button(
                                enabled = !running,
                                onClick = { vm.adoptWinningKernel(winner) },
                            ) { Text("Adopt as default") }
                            if (running) {
                                // The server reads the kernel choice once at startup, so the
                                // running engine still has the old one. Say so, rather than
                                // letting the change look applied when it is not.
                                Text(
                                    "Unload then Load to apply v$winner",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                b.results.forEach { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.label, style = MaterialTheme.typography.titleSmall)
                            MetricLine("TTFT", r.ttftMs?.let { "$it ms" } ?: "—")
                            MetricLine("Prefill", r.prefillTokPerSec?.let { "%.0f tok/s".format(it) } ?: "—")
                            MetricLine("Decode", r.decodeTokPerSec?.let { "%.2f tok/s".format(it) } ?: "—")
                            MetricLine("Draft", r.draftN?.let { "${r.draftNAccepted}/${it} accepted" } ?: "off")
                            MetricLine("RAM free", "${r.samples.firstOrNull()?.memAvailableMb ?: -1} MB")
                            MetricLine("Temp", r.samples.firstOrNull()?.maxTempC?.let { "%.1f °C".format(it) } ?: "—")
                            if (r.notes.isNotBlank()) MetricLine("Notes", r.notes)
                        }
                    }
                }
            }
            else -> Unit
        }

        Text("Eval harness (JSONL)", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = evalJson,
            onValueChange = { evalJson = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("{\"id\":\"m1\",\"messages\":[{\"role\":\"user\",\"content\":\"17*23?\"}],\"expectContains\":[\"391\"]}")
            },
            minLines = 3,
            maxLines = 8,
        )
        Button(
            enabled = running && evalJson.isNotBlank(),
            onClick = {
                vm.launchUi {
                    val cases = vm.evalHarness.parseCases(evalJson)
                    vm.evalHarness.run(cases, vm.profile)
                }
            },
        ) { Text("Run greedy eval") }

        val ep by vm.evalHarness.progress.collectAsState()
        when (val e = ep) {
            is com.prismml.bonsaidroid.harness.EvalHarness.Progress.Running ->
                Text("… ${e.id} (${e.index}/${e.total})")
            is com.prismml.bonsaidroid.harness.EvalHarness.Progress.Finished -> {
                Text("accuracy: %.0f%%".format((e.accuracy ?: Float.NaN) * 100))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        vm.launchUi { vm.evalHarness.export(e.outcomes) }
                    }) { Text("Export JSONL") }
                }
            }
            is com.prismml.bonsaidroid.harness.EvalHarness.Progress.Failed ->
                Text(e.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}
