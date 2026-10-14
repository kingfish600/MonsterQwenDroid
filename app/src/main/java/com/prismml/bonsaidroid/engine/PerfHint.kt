package com.prismml.bonsaidroid.engine

import android.content.Context
import android.os.Build
import java.lang.reflect.Method

/**
 * ADPF (Adaptive Dynamic Performance Framework) hints — android.perfhint, API 33+.
 *
 * While a generation runs we hold a load-cycle hint with a small target duration:
 * the same lever GameSpace pulls from the outside. It keeps EAS boost residency
 * and CPU freq clamps up — measured gap: ~1.9 t/s sustained vs 2.7-3.0 t/s when
 * boost-resident on the SD8 Elite. Advisory only (no root).
 *
 * Reflective on purpose: the compileSdk-34 stub jar in this SDK omits the
 * android.perfhint classes, but every Android 13+ runtime ships them (public API).
 * Any failure (missing class, OEM quirk, denial) degrades silently to no-op.
 */
object PerfHint {

    private var session: Any? = null
    private var releaseM: Method? = null

    @Synchronized
    fun acquire(context: Context) {
        if (session != null || Build.VERSION.SDK_INT < 33) return
        runCatching {
            val mgrCls = Class.forName("android.perfhint.PerformanceHintManager")
            val mgr = mgrCls.getMethod("getInstance", Context::class.java).invoke(null, context)
                ?: return
            val wlCls = Class.forName("android.perfhint.PerformanceHintManager\$Workload")
            // 8 ms target: a decode token costs ~400 ms, far beyond any frame
            // budget, so the tightest sustained hint = strongest "keep clocks high".
            val wl = wlCls.getMethod("createLoadCycleWorkload", java.lang.Long.TYPE)
                .invoke(null, 8_000_000L)
            val sessCls = Class.forName("android.perfhint.PerformanceHintManager\$Session")
            val sess = mgrCls.getMethod("createSession", String::class.java, List::class.java)
                .invoke(mgr, "bonsaidroid-decode", listOf(wl))
            sessCls.getMethod("acquire").invoke(sess)
            releaseM = sessCls.getMethod("release")
            session = sess
        }
    }

    @Synchronized
    fun release() {
        session?.let { s -> runCatching { releaseM?.invoke(s) } }
        session = null
    }
}
