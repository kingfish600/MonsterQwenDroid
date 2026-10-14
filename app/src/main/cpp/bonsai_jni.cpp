// bonsai_jni.cpp — device telemetry + scheduling control for BonsaiDroid.
//
// Scope is deliberately narrow: everything the Kotlin layer cannot do portably
// or cheaply from Java.
//   * memory pressure (MemAvailable) — drives the RAM guard before -c commits
//   * thermal sampling (/sys/class/thermal) — ThermalGovernor input
//   * big-core detection + sched_setaffinity pinning for the llama-server child
//     (inherited affinity), so decode threads stay on prime/perf cores
//   * 16 KB-page / device sanity info surfaced in the UI
//
// The experimental in-process engine lives behind BONSAI_WITH_LLAMA_JNI and
// is intentionally not compiled by default (see CMakeLists.txt).

#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <sched.h>
#include <unistd.h>
#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "bonsai-native"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kMeminfoPath = "/proc/meminfo";

struct MemInfoKB {
    long total = -1;
    long available = -1;
};

MemInfoKB readMemInfo() {
    MemInfoKB mi;
    FILE* f = fopen(kMeminfoPath, "re");
    if (!f) return mi;
    char key[64];
    long val = -1;
    char unit[32];
    while (fscanf(f, "%63s %ld %31s", key, &val, unit) >= 2) {
        if (strcmp(key, "MemTotal:") == 0) mi.total = val;
        else if (strcmp(key, "MemAvailable:") == 0) { mi.available = val; break; }
    }
    fclose(f);
    return mi;
}

struct ThermalZone {
    std::string type;
    double tempC = -273.15;
};

bool readZone(const char* zoneDir, ThermalZone* out) {
    char path[256];
    snprintf(path, sizeof(path), "%s/type", zoneDir);
    FILE* ft = fopen(path, "re");
    if (!ft) return false;
    char buf[128] = {0};
    const bool okType = fgets(buf, sizeof(buf), ft) != nullptr;
    fclose(ft);
    if (!okType) return false;

    snprintf(path, sizeof(path), "%s/temp", zoneDir);
    FILE* fv = fopen(path, "re");
    if (!fv) return false;
    int milli = INT_MIN;
    const bool okVal = fscanf(fv, "%d", &milli) == 1;
    fclose(fv);
    if (!okVal || milli == INT_MIN) return false;

    out->type.assign(buf);
    // trim newline/space
    while (!out->type.empty() &&
           (out->type.back() == '\n' || out->type.back() == ' ' || out->type.back() == '\r')) {
        out->type.pop_back();
    }
    out->tempC = static_cast<double>(milli) / 1000.0;
    return true;
}

// CPU0..N on Snapdragon 8 Elite Leading:
//   cpu0-1  prime   (~4.4-4.47 GHz)
//   cpu2-7  performance (~3.3 GHz)
//   cpu8+   efficiency
// We detect clusters from cpufreq max_freq rather than hardcoding, with the
// above as fallback when sysfs denies reads.
std::vector<int> detectBigCoreIds() {
    std::vector<int> cores;
    long bestFreq = 0;
    std::vector<std::pair<int, long>> perCore;
    for (int c = 0; c < 32; ++c) {
        char path[160];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
        FILE* f = fopen(path, "re");
        if (!f) continue;
        long khz = -1;
        if (fscanf(f, "%ld", &khz) == 1 && khz > 0) {
            perCore.emplace_back(c, khz);
            if (khz > bestFreq) bestFreq = khz;
        }
        fclose(f);
    }
    if (bestFreq > 0) {
        // Threshold must INCLUDE mid-tier performance cores: on 8 Elite the
        // 6-core Oryon cluster runs ~74% of prime frequency and does real
        // work — excluding it pinned 8 threads onto 2 cores (~0.5 tok/s).
        // 2/3 keeps every performance tier, still excludes A5xx/A7xx LITTLE
        // cores (~55-64%) whose stragglers stall synchronous batch barriers.
        for (auto& [id, freq] : perCore) {
            if (freq * 3 >= bestFreq * 2) {
                cores.push_back(id);
            }
        }
    }
    if (cores.empty()) {  // sysfs unavailable — assume 8 Elite layout
        for (int c = 0; c <= 7; ++c) cores.push_back(c);
    }
    return cores;
}

}  // namespace

extern "C" {

JNIEXPORT jdoubleArray JNICALL
Java_com_prismml_bonsaidroid_nativeffi_BonsaiNative_readMemInfoKb(JNIEnv* env, jobject /*thiz*/) {
    const MemInfoKB mi = readMemInfo();
    jdoubleArray arr = env->NewDoubleArray(2);
    const jdouble vals[2] = { static_cast<jdouble>(mi.total),
                              static_cast<jdouble>(mi.available) };
    env->SetDoubleArrayRegion(arr, 0, 2, vals);
    return arr;  // [totalKB, availableKB]; -1 on read failure
}

JNIEXPORT jobjectArray JNICALL
Java_com_prismml_bonsaidroid_nativeffi_BonsaiNative_readThermalZones(JNIEnv* env, jobject /*thiz*/) {
    static const char* kThermalRoot = "/sys/class/thermal";
    jclass stringCls = env->FindClass("java/lang/String");

    std::vector<ThermalZone> zones;
    DIR* d = opendir(kThermalRoot);
    if (d) {
        while (const dirent* e = readdir(d)) {
            if (e->d_name[0] != 't') continue;  // thermal_zone*
            char path[256];
            snprintf(path, sizeof(path), "%s/%s", kThermalRoot, e->d_name);
            ThermalZone z;
            if (readZone(path, &z)) zones.push_back(std::move(z));
        }
        closedir(d);
    }

    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(zones.size()) * 2, stringCls, nullptr);
    jsize idx = 0;
    for (const auto& z : zones) {
        env->SetObjectArrayElement(arr, idx++, env->NewStringUTF(z.type.c_str()));
        env->SetObjectArrayElement(arr, idx++,
                                   env->NewStringUTF(std::to_string(z.tempC).c_str()));
    }
    return arr;  // flat array: [type0, temp0, type1, temp1, ...]
}

JNIEXPORT jint JNICALL
Java_com_prismml_bonsaidroid_nativeffi_BonsaiNative_getBigCoreMask(JNIEnv* env, jobject /*thiz*/) {
    cpu_set_t set;
    CPU_ZERO(&set);
    const auto cores = detectBigCoreIds();
    for (const int c : cores) CPU_SET(c, &set);

    // Return mask bits as jint (enough for <=32 CPUs; 8 Elite has 8).
    jint mask = 0;
    for (const int c : cores) {
        if (c < 32) mask |= (1 << c);
    }
    return mask;
}

JNIEXPORT jboolean JNICALL
Java_com_prismml_bonsaidroid_nativeffi_BonsaiNative_pinCurrentThreadToBigCores(JNIEnv* env,
                                                                               jobject /*thiz*/) {
    cpu_set_t set;
    CPU_ZERO(&set);
    const auto cores = detectBigCoreIds();
    for (const int c : cores) CPU_SET(c, &set);
    // Applies to the CALLING THREAD only. The llama-server child process is
    // spawned from a thread pinned here first, so it inherits the affinity.
    const int rc = sched_setaffinity(0, sizeof(set), &set);
    if (rc != 0) {
        ALOGW("sched_setaffinity failed errno=%d", errno);
        return JNI_FALSE;
    }
    ALOGI("pinned to %zu big cores", cores.size());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_prismml_bonsaidroid_nativeffi_BonsaiNative_clearThreadAffinity(JNIEnv* /*env*/,
                                                                        jobject /*thiz*/) {
    cpu_set_t set;
    CPU_ZERO(&set);
    const int n = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    for (int c = 0; c < n && c < CPU_SETSIZE; ++c) CPU_SET(c, &set);
    return sched_setaffinity(0, sizeof(set), &set) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_prismml_bonsaidroid_nativeffi_BonsaiNative_getPageSizeBytes(JNIEnv* env, jobject /*thiz*/) {
    return static_cast<jint>(sysconf(_SC_PAGESIZE));
}

JNIEXPORT jstring JNICALL
Java_com_prismml_bonsaidroid_nativeffi_BonsaiNative_nativeAbiProbe(JNIEnv* env, jobject /*thiz*/) {
    // Sanity string proving the .so loaded on this device/page-size combo.
    return env->NewStringUTF("bonsai-native ok");
}


// ---------------------------------------------------------------------------
// NEON primitive probes.
//
// The kernel battery ranks whole vec_dot variants, but the design questions that
// actually mattered - is vdot the bottleneck, does the 128-bit path beat two
// 64-bit ops, what does the horizontal reduction cost - are questions about the
// machine, not about any one kernel. Answering them on the host (or under an
// emulator, whose software TLB walk over-weights loads) gave the wrong answer
// once already, so measure them on the device.
//
// Returns ns-per-op for each probe, in the order: dotprod_128, dotprod_64,
// add_128, add_64, addvq (horizontal reduce), vdotq_lane (full reduce chain).
// ---------------------------------------------------------------------------
#if defined(__aarch64__)
#include <arm_neon.h>
#include <time.h>

// The operand arrays are loop-invariant, so without this clang hoists the loads
// out and the "per-op" figure loses the load cost entirely - which is most of what
// the PTQ1_0 kernel actually spends time on. Forcing a reload each iteration is
// what makes these numbers comparable with the real decode loop.
#define BONSAI_BARRIER() __asm__ __volatile__("" ::: "memory")

static inline uint64_t bonsai_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * 1000000000ull + (uint64_t) ts.tv_nsec;
}

// The app's own target is not built with -march=...+dotprod (that flag is scoped
// to the llama.cpp subdirectory), so the sdot intrinsics need the feature enabled
// per function rather than per translation unit.
__attribute__((target("+dotprod")))
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_prismml_bonsaidroid_nativeffi_BonsaiNative_neonProbeNs(JNIEnv *env, jclass, jint iters) {
    const int n = iters > 0 ? iters : 200000;

    // Representative operands. Values are chosen so nothing can be optimised
    // away: the results feed a volatile sink at the end of each probe.
    int8_t  a[16] = {1,-1,2,-2,3,-3,4,-4,5,-5,6,-6,7,-7,8,-8};
    int8_t  b[16] = {85,-86,86,-86,1,-1,64,-64,17,-17,90,-90,33,-33,7,-7};
    volatile int32_t sink = 0;

    // warm up: first-touch page faults and cold caches would skew the first probe
    { int32x4_t t = vdotq_s32(vdupq_n_s32(0), vld1q_s8(a), vld1q_s8(b));
      sink += vgetq_lane_s32(t, 0); }

    // 1) 128-bit s8 dot-product
    uint64_t t0 = bonsai_now_ns();
    int32x4_t acc128 = vdupq_n_s32(0);
    for (int i = 0; i < n; i++) {
        BONSAI_BARRIER();
        acc128 = vdotq_s32(acc128, vld1q_s8(a), vld1q_s8(b));
    }
    uint64_t t1 = bonsai_now_ns();
    sink += vaddvq_s32(acc128);

    // 2) 64-bit s8 dot-product (half the width, two ops to match)
    // Two 64-bit dots per iteration so this covers the same 16 MACs as probe 1
    // and the two numbers are directly comparable.
    uint64_t t2 = bonsai_now_ns();
    int32x2_t acc64 = vdup_n_s32(0);
    for (int i = 0; i < n; i++) {
        BONSAI_BARRIER();
        acc64 = vdot_s32(acc64, vld1_s8(a),     vld1_s8(b));
        acc64 = vdot_s32(acc64, vld1_s8(a + 8), vld1_s8(b + 8));
    }
    uint64_t t3 = bonsai_now_ns();
    sink += vget_lane_s32(acc64, 0) + vget_lane_s32(acc64, 1);

    // 3) 128-bit integer add
    uint8x16_t x = vdupq_n_u8(3), y = vdupq_n_u8(5);
    uint64_t t4 = bonsai_now_ns();
    uint8x16_t z = x;
    for (int i = 0; i < n; i++) { BONSAI_BARRIER(); z = vaddq_u8(z, y); }
    uint64_t t5 = bonsai_now_ns();
    sink += vaddvq_u8(z);

    // 4) 64-bit integer add, twice, to do the same 16 bytes of work
    uint8x8_t x8 = vdup_n_u8(3), y8 = vdup_n_u8(5);
    uint64_t t6 = bonsai_now_ns();
    uint8x8_t z8 = x8;
    for (int i = 0; i < n; i++) { BONSAI_BARRIER(); z8 = vadd_u8(z8, y8); z8 = vadd_u8(z8, y8); }
    uint64_t t7 = bonsai_now_ns();
    sink += vget_lane_u8(z8, 0) + vget_lane_u8(z8, 1);

    // 5) horizontal reduce alone: vaddvq over a fixed vector
    uint64_t t8 = bonsai_now_ns();
    int32x4_t hv = vdupq_n_s32(7);
    int32_t h = 0;
    for (int i = 0; i < n; i++) h += vaddvq_s32(hv);
    uint64_t t9 = bonsai_now_ns();
    sink += h;

    // 6) the reduction the PTQ1_0 kernel actually ends with
    uint64_t ta = bonsai_now_ns();
    const int32x4_t fv = acc128;
    int32_t red = 0;
    for (int i = 0; i < n; i++) red += vaddvq_s32(fv);
    uint64_t tb = bonsai_now_ns();
    sink += red;

    const jdouble vals[6] = {
        (jdouble)(t1 - t0) / n,   // dotprod 128
        (jdouble)(t3 - t2) / n,   // dotprod 64 (two ops = 16 MACs, same work)
        (jdouble)(t5 - t4) / n,   // add 128
        (jdouble)(t7 - t6) / n,   // add 64 (two ops = one 128-bit op's work)
        (jdouble)(t9 - t8) / n,   // vaddvq
        (jdouble)(tb - ta) / n,   // full reduce chain
    };
    jdoubleArray out = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(out, 0, 6, vals);
    return out;
}
#endif  // __aarch64__


// ---------------------------------------------------------------------------
// Crash capture.
//
// A native fault (SIGSEGV / SIGILL / SIGABRT / SIGBUS) kills the process before
// anything can report it, which is why device crashes here arrived as a bare
// "force close" with no evidence. A signal handler can still write the signal and
// faulting address with write(2) - async-signal-safe - to a small file, and the
// app surfaces it on the next launch. That turns "guess the cause from symptoms"
// into "read the answer".
//
// An OOM kill does not run this handler (the process is killed from outside); the
// absence of a crash.txt after a force close is itself the signal for that case.
// ---------------------------------------------------------------------------
#include <fcntl.h>
#include <signal.h>
#include <unistd.h>
#include <string.h>

static char g_crash_path[512] = {0};

static void bonsai_crash_handler(int sig, siginfo_t *info, void *ctx) {
    if (g_crash_path[0]) {
        char buf[256];
        const char *name = "UNKNOWN";
        switch (sig) {
            case SIGSEGV: name = "SIGSEGV"; break;   // bad address
            case SIGILL:  name = "SIGILL";  break;   // illegal instruction (e.g. an ISA the core lacks)
            case SIGABRT: name = "SIGABRT"; break;   // abort()/assert
            case SIGBUS:  name = "SIGBUS";  break;
            case SIGFPE:  name = "SIGFPE";  break;
            default: break;
        }
        // Format without malloc/printf: not async-signal-safe.
        char num[32];
        int i = 0;
        unsigned long a = (unsigned long)(uintptr_t)info->si_addr;
        num[i++] = '0'; if (a >= 10) num[i++] = '0'; if (a >= 100) num[i++] = '0';
        if (a >= 1000) num[i++] = '0'; if (a >= 10000) num[i++] = '0';
        if (a >= 100000) num[i++] = '0'; if (a >= 1000000) num[i++] = '0';
        if (a >= 10000000) num[i++] = '0'; if (a >= 100000000) num[i++] = '0';
        num[i] = 0;

        size_t n = 0;
        const char *hdr = "bonsai-crash: ";
        while (hdr[n] && n < sizeof(buf)) { buf[n] = hdr[n]; n++; }
        for (const char *p2 = name; *p2 && n < sizeof(buf) - 2; p2++) buf[n++] = *p2;
        if (n < sizeof(buf) - 1) buf[n++] = ' ';
        if (n < sizeof(buf) - 1) buf[n++] = 'a';
        if (n < sizeof(buf) - 1) buf[n++] = 'd';
        if (n < sizeof(buf) - 1) buf[n++] = 'd';
        if (n < sizeof(buf) - 1) buf[n++] = 'r';
        if (n < sizeof(buf) - 2) buf[n++] = '=';
        for (int k = 0; num[k] && n < sizeof(buf); k++) buf[n++] = num[k];
        if (n < sizeof(buf) - 1) buf[n++] = '\n';
        int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) { ssize_t r = write(fd, buf, n); (void) r; close(fd); }
    }
    // Re-raise with the default handler so the process still dies normally and
    // the platform records the crash as usual.
    signal(sig, SIG_DFL);
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_com_prismml_bonsaidroid_nativeffi_BonsaiNative_installCrashHandler(
        JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        size_t n = strlen(p);
        if (n < sizeof(g_crash_path)) { memcpy(g_crash_path, p, n + 1); }
        env->ReleaseStringUTFChars(path, p);
    }
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = bonsai_crash_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    for (int sig : { SIGSEGV, SIGILL, SIGABRT, SIGBUS, SIGFPE }) {
        sigaction(sig, &sa, nullptr);
    }
}

}  // extern "C"
