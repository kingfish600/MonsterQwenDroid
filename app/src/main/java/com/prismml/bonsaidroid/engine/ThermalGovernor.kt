package com.prismml.bonsaidroid.engine

import com.prismml.bonsaidroid.nativeffi.BonsaiNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thermal watchdog for sustained decode on the 8 Elite.
 *
 * Polls /sys/class/thermal (via JNI) while generation is plausible, classifies
 * the device state and — when auto-manage is on — recommends the next profile
 * step down/up. Thread-count changes require a server restart, so the governor
 * acts *between* messages; the draft toggle is likewise applied at restart.
 */
class ThermalGovernor(private val scope: CoroutineScope) {

    enum class Level { COOL, WARM, HOT }

    data class Reading(
        val maxTempC: Float?,
        val level: Level,
        val recommendedProfile: EngineProfile,
    )

    private val _reading = MutableStateFlow<Reading?>(null)
    val reading: StateFlow<Reading?> = _reading.asStateFlow()

    @Volatile private var baseline: EngineProfile = EngineProfile.BALANCED

    fun observeForever(autoManage: Boolean) {
        scope.launch {
            var hotStreak = 0
            var coolStreak = 0
            var displayTemp: Float? = null
            while (isActive) {
                val maxTemp = readMaxRelevantTemp()
                // Per-core junction sensors spike momentarily; smooth the
                // displayed value so a single 103C blip doesn't alarm anyone.
                if (maxTemp != null) {
                    displayTemp = displayTemp?.let { it * 0.6f + maxTemp * 0.4f } ?: maxTemp
                }
                val level = classify(maxTemp)
                // Hysteresis: 3 consecutive samples (~6 s) before flipping state.
                when (level) {
                    Level.HOT -> { hotStreak++; coolStreak = 0 }
                    else -> { coolStreak++; if (level == Level.COOL) hotStreak = 0 }
                }
                val profile = if (autoManage && hotStreak >= 3) {
                    downgrade(baseline)
                } else if (autoManage && coolStreak >= 10) {
                    baseline
                } else {
                    _reading.value?.recommendedProfile ?: baseline
                }
                _reading.value = Reading(displayTemp, level, profile)
                delay(POLL_MS)
            }
        }
    }

    fun setBaseline(profile: EngineProfile) { baseline = profile }

    /** Flat array [type0, temp0, ...] from JNI to max cpu/gpu/soc zone temp. */
    fun readMaxRelevantTemp(): Float? {
        val flat = runCatching { BonsaiNative.readThermalZones() }.getOrNull() ?: return null
        var best: Float? = null
        var i = 0
        while (i + 1 < flat.size) {
            val type = flat[i].lowercase()
            val temp = flat[i + 1].toFloatOrNull()
            if (temp != null && (type.contains("cpu") || type.contains("gpu") || type.contains("soc"))) {
                if (best == null || temp > best) best = temp
            }
            i += 2
        }
        return best
    }

    private fun classify(temp: Float?): Level = when {
        temp == null -> Level.COOL
        temp >= HOT_C -> Level.HOT
        temp >= WARM_C -> Level.WARM
        else -> Level.COOL
    }

    private fun downgrade(p: EngineProfile): EngineProfile = p.copy(
        threads = if (p.name == "THERMAL_SAVER") 4 else 6,
        useDraftModel = false, // drafter adds its own pass; shed it first
        name = "THERMAL_SAVER",
    )

    companion object {
        private const val POLL_MS = 2000L
        private const val WARM_C = 70f
        private const val HOT_C = 85f
    }
}
