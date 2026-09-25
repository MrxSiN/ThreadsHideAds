/*
 * Runtime around the AOT-compiled programs: one request in, one response out,
 * with capability frames routed to the host and CALL frames run natively on
 * a fresh tape one level deeper. See docs/BRAINFUCK_ABI.md.
 */
#ifndef THREADSHIDEADS_BF_ENGINE_H
#define THREADSHIDEADS_BF_ENGINE_H

#include <stddef.h>
#include <stdint.h>

#include "bf_runtime.h"
#include "generated/bf_abi.h"

#ifdef __cplusplus
extern "C" {
#endif

#define BF_MAX_LEVELS BF_RUNTIME_LEVELS
#define BF_TAPE_MAX BF_RUNTIME_TAPE_MAX
#define BF_LEVEL_IN_CAP BF_RUNTIME_LEVEL_IN_CAP
#define BF_LEVEL_OUT_CAP BF_RUNTIME_LEVEL_OUT_CAP

typedef struct bf_engine bf_engine;

/*
 * Host callbacks. Each writes at most `cap` bytes to `dst`, returns the number
 * written or -1 on failure, and sets *more when the answer continues.
 *
 * capability: answer the request frame `req` issued at `level`.
 * fetch: start streaming the text for (handle, source, transform) to the
 * program about to run at `level`; the stream ends with a 0 byte.
 * more: continue the pending answer or stream of `level`.
 */
typedef struct {
    int (*capability)(void *ctx, int level, const uint8_t *req, size_t req_len,
                      uint8_t *dst, size_t cap, int *more);
    int (*fetch)(void *ctx, int level, uint32_t handle, int source, int transform,
                 uint8_t *dst, size_t cap, int *more);
    int (*more)(void *ctx, int level, uint8_t *dst, size_t cap, int *more);
    void *ctx;
} bf_host;

typedef struct {
    uint8_t in[BF_LEVEL_IN_CAP];
    uint8_t out[BF_LEVEL_OUT_CAP];
    uint8_t tape[BF_TAPE_MAX];
    int streaming;
} bf_level;

struct bf_engine {
    bf_host host;
    int depth;
    uint32_t calls;          /* programs run, including nested ones */
    uint32_t host_calls;     /* capability, fetch and more callbacks */
    uint8_t core_tape[BF_TAPE_MAX];
    bf_level levels[BF_MAX_LEVELS];
};

void bf_engine_init(bf_engine *engine, const bf_host *host);

/* Clears persistent state (OP_RESET semantics at the runtime level). */
void bf_engine_reset(bf_engine *engine);

/*
 * Runs one top-level request. The response is copied to `out`.
 * Returns BF_OK or a BF_ERR_* code; *out_len is 0 on error.
 */
int bf_engine_request(bf_engine *engine, int program, const uint8_t *req, size_t req_len,
                      uint8_t *out, size_t out_cap, size_t *out_len);

/* The program that serves a top-level opcode, or -1. */
int bf_route(int opcode);

#ifdef __cplusplus
}
#endif

#endif
