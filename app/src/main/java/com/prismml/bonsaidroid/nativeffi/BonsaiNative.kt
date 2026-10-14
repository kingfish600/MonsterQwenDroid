package com.prismml.bonsaidroid.nativeffi

/**
 * Thin JNI facade over `libbonsai_native.so` (see app/src/main/cpp/bonsai_jni.cpp).
 *
 * Everything here exists because the Java APIs either don't expose it
 * (sched_setaffinity inheritance by our llama-server child), or are too slow /
 * too filtered (/proc/meminfo, raw /sys/class/thermal).
 */
object BonsaiNative {

    init {
        System.loadLibrary("bonsai_native")
    }

    /** [totalKB, availableKB]; values are -1 when unreadable. */
    external fun readMemInfoKb(): DoubleArray

    /**
     * Times ARM NEON primitives on this device, in ns per op:
     * [dotprod128, dotprod64, add128, add64, vaddvq, reduceChain].
     *
     * These answer machine questions the kernel variants cannot: whether the
     * 128-bit path really beats two 64-bit ops, and what the horizontal
     * reduction costs. Measured on the host or an emulator they mislead - a
     * software TLB walk over-weights loads, which is how a "1.9x from repack"
     * number went wrong once already.
     */
    external fun neonProbeNs(iters: Int): DoubleArray

    /**
     * Install a SIGSEGV/SIGILL/SIGABRT/SIGBUS/SIGFPE handler that writes the
     * signal and faulting address to [path] before the process dies.
     *
     * A native fault otherwise kills the app with no trace, which is why a crash
     * on device arrived as a bare "force close". Note an OOM kill does NOT run
     * this handler, so a force close with no crash file means the kernel took it.
     */
    external fun installCrashHandler(path: String)

    /** Flat array [type0, tempC0, type1, tempC1, ...] over /sys/class/thermal. */
    external fun readThermalZones(): Array<String>

    /** Bitmask of prime+performance cores detected from cpufreq (8 Elite: cpu0..7). */
    external fun getBigCoreMask(): Int

    /**
     * Pins the CALLING THREAD to big cores. Spawn the llama-server child from a
     * coroutine dispatched onto this pinned thread so the whole child process
     * inherits the affinity mask.
     */
    external fun pinCurrentThreadToBigCores(): Boolean

    external fun clearThreadAffinity(): Boolean

    /** Runtime page size — sanity check for 16 KB-page devices. */
    external fun getPageSizeBytes(): Int

    /** Returns a constant string proving the .so loaded correctly. */
    external fun nativeAbiProbe(): String
}
