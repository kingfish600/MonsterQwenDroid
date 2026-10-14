package com.prismml.bonsaidroid

import android.app.Application
import com.prismml.bonsaidroid.data.ChatStore
import com.prismml.bonsaidroid.data.ModelDownloader
import com.prismml.bonsaidroid.engine.LlamaServerEngine
import com.prismml.bonsaidroid.engine.ThermalGovernor
import com.prismml.bonsaidroid.nativeffi.BonsaiNative

class BonsaiApp : Application() {

    lateinit var engine: LlamaServerEngine private set
    lateinit var downloader: ModelDownloader private set
    lateinit var chats: ChatStore private set
    lateinit var governor: ThermalGovernor private set

    /**
     * Text of the most recent crash - native signal or uncaught exception -
     * captured on the way down and read back on the next launch. Empty when the
     * last run ended cleanly.
     */
    @Volatile
    var lastCrash: String = ""
        private set

    /**
     * The native handler only sees signals. An uncaught Kotlin exception would
     * leave no crash file at all, which would read as "the OOM killer" when it is
     * not - so record those in the same file, same banner.
     */
    private fun installCrashReporter(marker: java.io.File) {
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val kind = "KOTLIN ${throwable.javaClass.simpleName}"
                val detail = (throwable.message ?: "no message").take(120)
                val where = throwable.stackTrace.firstOrNull()?.let {
                    "${it.className}.${it.methodName}:${it.lineNumber}"
                } ?: "unknown"
                marker.writeText(
                    "bonsai-crash: $kind\n  thread: ${thread.name}\n" +
                        "  at: $where\n  msg: $detail\n"
                )
            }
            prevHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = LlamaServerEngine(this)
        downloader = ModelDownloader(this)
        chats = ChatStore(this)
        governor = ThermalGovernor(
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
            )
        ).also { it.observeForever(autoManage = true) }

        // Capture native crashes to a file, and report the previous one if there
        // was any. A SIGSEGV/SIGILL kills the process with nothing to read back, so
        // without this a device crash is only ever reported as "force close".
        runCatching {
            val dir = java.io.File(getExternalFilesDir(null) ?: filesDir, "crash").apply { mkdirs() }
            val marker = java.io.File(dir, "last_crash.txt")
            val prev = runCatching { if (marker.exists()) marker.readText().trim() else "" }.getOrNull()
            BonsaiNative.installCrashHandler(marker.absolutePath)
            installCrashReporter(marker)
            if (!prev.isNullOrBlank()) lastCrash = prev
        }

        // Sanity probe of the native lib at process start; surfaces packaging
        // problems immediately instead of mid-load.
        runCatching {
            check(BonsaiNative.nativeAbiProbe().isNotEmpty())
            check(BonsaiNative.getPageSizeBytes() in setOf(4096, 16384))
        }.onFailure {
            android.util.Log.e("BonsaiDroid", "native lib probe failed", it)
        }
    }
}
