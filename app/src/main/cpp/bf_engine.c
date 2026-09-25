#include "bf_engine.h"

#include <string.h>

#include "generated/bf_abi.h"

typedef struct {
    bf_engine *engine;
    int level;
} bf_level_ctx;

static int run_level(bf_engine *engine, int level, int program, const uint8_t *req, size_t req_len,
                     const uint8_t **out, size_t *out_len);

int bf_route(int opcode)
{
    if (opcode >= 0x01 && opcode <= 0x2F) {
        return BF_PROG_CORE;
    }
    switch (opcode) {
        case BF_OP_BOUNDARY_BEFORE:
        case BF_OP_BOUNDARY_AFTER:
        case BF_OP_SANITIZE_LIST:
            return BF_PROG_SANITIZE;
        case BF_OP_NESTED_SANITIZE:
        case BF_OP_NESTED_VISIT:
            return BF_PROG_NESTED;
        case BF_OP_PANDO_FILTER:
        case BF_OP_PANDO_VERDICT:
            return BF_PROG_PANDO;
        case BF_OP_DETECT:
            return BF_PROG_DETECT;
        case BF_OP_TEXT_MATCH:
            return BF_PROG_TEXT;
        default:
            return -1;
    }
}

void bf_engine_init(bf_engine *engine, const bf_host *host)
{
    memset(engine, 0, sizeof *engine);
    engine->host = *host;
}

void bf_engine_reset(bf_engine *engine)
{
    memset(engine->core_tape, 0, sizeof engine->core_tape);
}

/* Error response for a frame the runtime answers itself. */
static size_t error_frame(uint8_t *in, int opcode, int status)
{
    in[0] = BF_ABI_MAJOR;
    in[1] = BF_ABI_MINOR;
    in[2] = (uint8_t) (opcode | BF_RESPONSE_BIT);
    in[3] = (uint8_t) status;
    memset(in + 4, 0, 4);
    return BF_HEADER_SIZE;
}

/* Runs a CALL frame one level deeper; the callee's response becomes input. */
static int serve_call(bf_engine *engine, int level, const uint8_t *frame, size_t len, bf_io *io)
{
    bf_level *self = &engine->levels[level];
    if (len < BF_HEADER_SIZE + 4) {
        io->in_len = error_frame(self->in, BF_CAP_CALL, BF_ST_BAD_FRAME);
        return 1;
    }
    const uint8_t *p = frame + BF_HEADER_SIZE;
    int program = p[0];
    int subop = p[1];
    int fetch = p[2] & 1;
    size_t argc = p[3];
    if (BF_HEADER_SIZE + 4 + argc + (fetch ? 6u : 0u) > len || program >= bf_program_count
            || level + 1 >= BF_MAX_LEVELS || (program == BF_PROG_CORE)) {
        io->in_len = error_frame(self->in, BF_CAP_CALL, BF_ST_BAD_FRAME);
        return 1;
    }
    bf_level *callee = &engine->levels[level + 1];
    uint8_t request[BF_HEADER_SIZE + 255];
    request[0] = BF_ABI_MAJOR;
    request[1] = BF_ABI_MINOR;
    request[2] = (uint8_t) subop;
    request[3] = (uint8_t) (fetch ? BF_FLAG_STREAM : 0);
    request[4] = (uint8_t) argc;
    request[5] = 0;
    request[6] = 0;
    request[7] = 0;
    memcpy(request + BF_HEADER_SIZE, p + 4, argc);
    callee->streaming = 0;
    size_t request_len = BF_HEADER_SIZE + argc;
    const uint8_t *input = request;
    if (fetch) {
        const uint8_t *spec = p + 4 + argc;
        uint32_t handle = (uint32_t) spec[0] | ((uint32_t) spec[1] << 8)
                | ((uint32_t) spec[2] << 16) | ((uint32_t) spec[3] << 24);
        /* The request header and args go first; the fetched text follows. */
        memcpy(callee->in, request, request_len);
        engine->host_calls++;
        int more = 0;
        int n = engine->host.fetch(engine->host.ctx, level + 1, handle, spec[4], spec[5],
                                   callee->in + request_len, BF_LEVEL_IN_CAP - request_len, &more);
        if (n < 0) {
            io->in_len = error_frame(self->in, BF_CAP_CALL, BF_ST_HOST_ERROR);
            return 1;
        }
        callee->streaming = more;
        input = callee->in;
        request_len += (size_t) n;
    }
    const uint8_t *out;
    size_t out_len;
    int status = run_level(engine, level + 1, program, input, request_len, &out, &out_len);
    if (status != BF_OK) {
        return 0;
    }
    memcpy(self->in, out, out_len);
    io->in_len = out_len;
    return 1;
}

static int level_refill(bf_io *io)
{
    bf_level_ctx *ctx = (bf_level_ctx *) io->ctx;
    bf_engine *engine = ctx->engine;
    int level = ctx->level;
    bf_level *self = &engine->levels[level];
    size_t frame_len = io->out_len - io->out_mark;

    /* A new request abandons whatever remains of the previous answer. */
    if (frame_len == 0 && self->streaming) {
        engine->host_calls++;
        int more = 0;
        int n = engine->host.more(engine->host.ctx, level, self->in, BF_LEVEL_IN_CAP, &more);
        self->streaming = n > 0 && more;
        if (n < 0) {
            io->status = BF_ERR_HOST;
            return 0;
        }
        io->in = self->in;
        io->in_len = (size_t) n;
        io->in_pos = 0;
        return n > 0;
    }
    self->streaming = 0;
    if (frame_len < BF_HEADER_SIZE) {
        return 0; /* nothing requested: end of input */
    }
    const uint8_t *frame = io->out + io->out_mark;
    int opcode = frame[2];
    int ok = 1;
    io->in_pos = 0;
    if (opcode == BF_CAP_CALL) {
        /* serve_call writes the callee's response into self->in. */
        ok = serve_call(engine, level, frame, frame_len, io);
    } else {
        engine->host_calls++;
        int more = 0;
        int n = engine->host.capability(engine->host.ctx, level, frame, frame_len,
                                        self->in, BF_LEVEL_IN_CAP, &more);
        if (n < 0) {
            io->in_len = error_frame(self->in, opcode, BF_ST_HOST_ERROR);
        } else {
            io->in_len = (size_t) n;
            self->streaming = more;
        }
    }
    io->in = self->in;
    /* The request has been consumed; the next frame starts at the mark. */
    io->out_len = io->out_mark;
    if (!ok) {
        io->status = BF_ERR_HOST;
        return 0;
    }
    return io->in_len > 0;
}

static int run_level(bf_engine *engine, int level, int program, const uint8_t *req, size_t req_len,
                     const uint8_t **out, size_t *out_len)
{
    if (program < 0 || program >= bf_program_count || level >= BF_MAX_LEVELS) {
        return BF_ERR_PROGRAM;
    }
    const bf_program_info *info = &bf_programs[program];
    bf_level *self = &engine->levels[level];
    uint8_t *tape;
    if (program == BF_PROG_CORE) {
        /* Persistent cells survive; the scratch region starts zeroed. */
        tape = engine->core_tape;
        memset(tape + info->persist_size, 0, info->tape_size - info->persist_size);
    } else {
        tape = self->tape;
        memset(tape, 0, info->tape_size);
    }
    bf_level_ctx ctx = {engine, level};
    bf_io io;
    memset(&io, 0, sizeof io);
    io.in = req;
    io.in_len = req_len;
    io.out = self->out;
    io.out_cap = BF_LEVEL_OUT_CAP;
    io.refill = level_refill;
    io.ctx = &ctx;
    engine->calls++;
    int status = info->run(tape, &io);
    self->streaming = 0;
    if (status == BF_OK) {
        status = io.status;
    }
    *out = self->out;
    *out_len = io.out_len;
    return status;
}

int bf_engine_request(bf_engine *engine, int program, const uint8_t *req, size_t req_len,
                      uint8_t *out, size_t out_cap, size_t *out_len)
{
    *out_len = 0;
    if (engine->depth != 0) {
        return BF_ERR_NESTING; /* a hook fired while a request was running */
    }
    engine->depth = 1;
    const uint8_t *response;
    size_t response_len;
    int status = run_level(engine, 0, program, req, req_len, &response, &response_len);
    engine->depth = 0;
    if (status != BF_OK) {
        return status;
    }
    if (response_len > out_cap) {
        return BF_ERR_OUTPUT;
    }
    memcpy(out, response, response_len);
    *out_len = response_len;
    return BF_OK;
}
