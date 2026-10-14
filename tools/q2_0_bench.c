// Q2_0 dot-product microbenchmark: does breaking the single-accumulator FMA
// chain actually pay on Oryon?
//
// Both variants live in one binary, so the comparison is in-process and free of
// run-to-run variance, page-cache warmth, graph noise and thermal drift - the
// traps that produced two fake results earlier in this project.
//
//   ./q2_0_bench            # ~10s
//   ./q2_0_bench 200000     # more iterations
//
// Baseline: the current kernel (1 accumulator, vqtbl1q + variable shifts).
// Unroll4:  4 independent float accumulators, same unpacking and same maths.
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <math.h>
#include <sched.h>
#include <arm_neon.h>

#define QK2_0 64
typedef uint16_t ggml_half;

typedef struct { ggml_half d; uint8_t qs[QK2_0/4]; } block_q2_0;   // 18 bytes
typedef struct { ggml_half d; int8_t  qs[32];      } block_q8_0;   // 34 bytes

static inline float fp16_to_fp32(ggml_half h) {
    // enough for benchmarking: exact for the values we synthesise
    uint32_t sign = (uint32_t)(h >> 15) << 31;
    uint32_t exp  = (h >> 10) & 0x1F;
    uint32_t man  =  h & 0x3FF;
    uint32_t f;
    if (exp == 0) { f = sign; }
    else { f = sign | ((exp + 112) << 23) | (man << 13); }
    float r; memcpy(&r, &f, sizeof r); return r;
}

// ---- baseline: one accumulator, tbl + variable shifts -------------------
static float dot_base(const block_q2_0 * x, const block_q8_0 * y, int nb) {
    static const uint8_t tbl_idx_lo[16] = {0,0,0,0, 1,1,1,1, 2,2,2,2, 3,3,3,3};
    static const uint8_t tbl_idx_hi[16] = {4,4,4,4, 5,5,5,5, 6,6,6,6, 7,7,7,7};
    static const int8_t  shift_vals[16] = {0,-2,-4,-6, 0,-2,-4,-6, 0,-2,-4,-6, 0,-2,-4,-6};
    const uint8x16_t idx_lo = vld1q_u8(tbl_idx_lo);
    const uint8x16_t idx_hi = vld1q_u8(tbl_idx_hi);
    const int8x16_t  shifts = vld1q_s8(shift_vals);
    const uint8x16_t mask2  = vdupq_n_u8(0x03);
    const int8x16_t  one    = vdupq_n_s8(1);
    float32x4_t sumv = vdupq_n_f32(0.0f);

    for (int i = 0; i < nb; i++) {
        const float d0 = fp16_to_fp32(x[i].d);
        for (int k = 0; k < 2; k++) {
            const block_q8_0 * yb = &y[i*2 + k];
            const float d1 = fp16_to_fp32(yb->d);
            const uint8x8_t raw = vld1_u8(&x[i].qs[k*8]);
            const uint8x16_t raw16 = vcombine_u8(raw, raw);
            uint8x16_t bytes0 = vqtbl1q_u8(raw16, idx_lo);
            int8x16_t qv0 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshlq_u8(bytes0, shifts), mask2)), one);
            uint8x16_t bytes1 = vqtbl1q_u8(raw16, idx_hi);
            int8x16_t qv1 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshlq_u8(bytes1, shifts), mask2)), one);
            const int8x16_t y0 = vld1q_s8(yb->qs);
            const int8x16_t y1 = vld1q_s8(yb->qs + 16);
            int32x4_t p0 = vdotq_s32(vdupq_n_s32(0), qv0, y0);
            int32x4_t p1 = vdotq_s32(p0, qv1, y1);
            sumv = vmlaq_n_f32(sumv, vcvtq_f32_s32(p1), d0*d1);
        }
    }
    return vaddvq_f32(sumv);
}

// ---- unrolled variants: N independent accumulators ----------------------
#define INNER(b) do { \
    const block_q2_0 * xb = &x[i + (b)]; \
    const float d0 = fp16_to_fp32(xb->d); \
    for (int k = 0; k < 2; k++) { \
        const block_q8_0 * yb = &y[(i + (b))*2 + k]; \
        const float d1 = fp16_to_fp32(yb->d); \
        const uint8x8_t raw = vld1_u8(&xb->qs[k*8]); \
        const uint8x16_t raw16 = vcombine_u8(raw, raw); \
        uint8x16_t bytes0 = vqtbl1q_u8(raw16, idx_lo); \
        int8x16_t qv0 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshlq_u8(bytes0, shifts), mask2)), one); \
        uint8x16_t bytes1 = vqtbl1q_u8(raw16, idx_hi); \
        int8x16_t qv1 = vsubq_s8(vreinterpretq_s8_u8(vandq_u8(vshlq_u8(bytes1, shifts), mask2)), one); \
        const int8x16_t y0 = vld1q_s8(yb->qs); \
        const int8x16_t y1 = vld1q_s8(yb->qs + 16); \
        int32x4_t p0 = vdotq_s32(vdupq_n_s32(0), qv0, y0); \
        int32x4_t p1 = vdotq_s32(p0, qv1, y1); \
        acc[(b)] = vmlaq_n_f32(acc[(b)], vcvtq_f32_s32(p1), d0*d1); \
    } } while (0)

static const uint8_t TBL_LO[16] = {0,0,0,0, 1,1,1,1, 2,2,2,2, 3,3,3,3};
static const uint8_t TBL_HI[16] = {4,4,4,4, 5,5,5,5, 6,6,6,6, 7,7,7,7};
static const int8_t  SHIFT[16]  = {0,-2,-4,-6, 0,-2,-4,-6, 0,-2,-4,-6, 0,-2,-4,-6};

#define DEF_UNROLL(N) \
static float dot_unroll##N(const block_q2_0 * x, const block_q8_0 * y, int nb) { \
    const uint8x16_t idx_lo = vld1q_u8(TBL_LO), idx_hi = vld1q_u8(TBL_HI); \
    const int8x16_t shifts = vld1q_s8(SHIFT); \
    const uint8x16_t mask2 = vdupq_n_u8(0x03); \
    const int8x16_t one = vdupq_n_s8(1); \
    float32x4_t acc[N]; for (int j = 0; j < N; j++) acc[j] = vdupq_n_f32(0.0f); \
    int i = 0; \
    for (; i + (N) - 1 < nb; i += (N)) { for (int b = 0; b < (N); b++) { INNER(b); } } \
    for (; i < nb; i++) { INNER(0); } \
    float32x4_t t = acc[0]; \
    for (int j = 1; j < N; j++) t = vaddq_f32(t, acc[j]); \
    return vaddvq_f32(t); \
}
DEF_UNROLL(2)
DEF_UNROLL(4)
DEF_UNROLL(8)

static double now_s(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec*1e-9;
}

int main(int argc, char ** argv) {
    const int n_rows = 640;                 // rows per expert slice
    const int nb     = 2560/QK2_0;          // 40 blocks per row (n = 2560)
    const long iters = argc > 1 ? atol(argv[1]) : 20000;

    // pin to the prime cluster if we can: less scheduler noise
    cpu_set_t set; CPU_ZERO(&set); CPU_SET(6, &set); CPU_SET(7, &set);
    sched_setaffinity(0, sizeof set, &set);

    // posix_memalign, not aligned_alloc: the latter requires the size to be a
    // multiple of the alignment and 720 bytes is not.
    block_q2_0 * x = NULL;
    block_q8_0 * y = NULL;
    if (posix_memalign((void **)&x, 64, (size_t)nb*sizeof(block_q2_0)) != 0) return 1;
    if (posix_memalign((void **)&y, 64, (size_t)nb*2*sizeof(block_q8_0)) != 0) return 1;
    srand(1234);
    for (int i = 0; i < nb; i++) {
        x[i].d = (ggml_half)((rand()%64) | 0x2000);
        for (int b = 0; b < QK2_0/4; b++) x[i].qs[b] = (uint8_t)rand();
    }
    for (int i = 0; i < nb*2; i++) {
        y[i].d = (ggml_half)((rand()%64) | 0x2000);
        for (int j = 0; j < 32; j++) y[i].qs[j] = (int8_t)(rand()%255 - 127);
    }

    // correctness: the two must agree closely (same maths, different order)
    double maxrel2 = 0, maxrel4 = 0, maxrel8 = 0;
    for (int r = 0; r < n_rows; r++) {
        double a  = dot_base(x, y, nb);
        double b2 = dot_unroll2(x, y, nb), b4 = dot_unroll4(x, y, nb), b8 = dot_unroll8(x, y, nb);
        double e2 = fabs(a-b2)/(fabs(a)>1e-30?fabs(a):1e-30);
        double e4 = fabs(a-b4)/(fabs(a)>1e-30?fabs(a):1e-30);
        double e8 = fabs(a-b8)/(fabs(a)>1e-30?fabs(a):1e-30);
        if (e2>maxrel2) maxrel2=e2; if (e4>maxrel4) maxrel4=e4; if (e8>maxrel8) maxrel8=e8;
    }
    printf("correctness vs baseline: rel diff unroll2 %.2e  unroll4 %.2e  unroll8 %.2e\n",
           maxrel2, maxrel4, maxrel8);

    // timing: n_rows dots per iteration, so this matches one expert slice
    volatile float sink = 0;
    double rows = (double)(iters/100)*n_rows;
    double mb = (double)nb*sizeof(block_q2_0) / 1e6;
    double t[5];
    t[0] = now_s();
    for (long it = 0; it < iters/100; it++) for (int r = 0; r < n_rows; r++) sink += dot_base(x, y, nb);
    t[1] = now_s();
    for (long it = 0; it < iters/100; it++) for (int r = 0; r < n_rows; r++) sink += dot_unroll2(x, y, nb);
    t[2] = now_s();
    for (long it = 0; it < iters/100; it++) for (int r = 0; r < n_rows; r++) sink += dot_unroll4(x, y, nb);
    t[3] = now_s();
    for (long it = 0; it < iters/100; it++) for (int r = 0; r < n_rows; r++) sink += dot_unroll8(x, y, nb);
    t[4] = now_s();
    const char * nm[4] = {"baseline","unroll2 ","unroll4 ","unroll8 "};
    double base_ms = (t[1]-t[0])/(iters/100)*1e3;
    for (int v = 0; v < 4; v++) {
        double dt = t[v+1]-t[v];
        printf("%s: %5.1f Mrow/s  %5.2f GB/s  %.2f ms/slice  speedup %.3fx\n",
               nm[v], rows/dt/1e6, rows*mb/dt/1e3, dt/(iters/100)*1e3,
               (v==0)?1.0:base_ms/(dt/(iters/100)*1e3));
    }
    (void)sink;
    return 0;
}
