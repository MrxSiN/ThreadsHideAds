/*
 * Execution contract shared by every AOT-compiled Brainfuck program.
 *
 * A program is a C function over a tape of unsigned 8-bit cells. `,` reads
 * from io->in and asks the runtime for more when it runs out; 0 means end of
 * input. `.` appends to io->out. See docs/BRAINFUCK_ABI.md.
 */
#ifndef THREADSHIDEADS_BF_RUNTIME_H
#define THREADSHIDEADS_BF_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    BF_OK = 0,
    BF_ERR_TAPE = 1,
    BF_ERR_OUTPUT = 2,
    BF_ERR_HOST = 3,
    BF_ERR_NESTING = 4,
    BF_ERR_PROGRAM = 5
};

typedef struct bf_io bf_io;

struct bf_io {
    const uint8_t *in;
    size_t in_len;
    size_t in_pos;
    uint8_t *out;
    size_t out_len;
    size_t out_cap;
    size_t out_mark;          /* start of the frame currently being written */
    int status;
    int (*refill)(bf_io *io); /* returns 1 when io->in holds new bytes */
    void *ctx;
};

static inline uint8_t bf_refill(bf_io *io)
{
    if (io->refill != NULL && io->status == BF_OK && io->refill(io) && io->in_pos < io->in_len) {
        return io->in[io->in_pos++];
    }
    return 0;
}

#define BF_IN(io) ((io)->in_pos < (io)->in_len ? (io)->in[(io)->in_pos++] : bf_refill(io))

#define BF_OUT(io, v)                                        \
    do {                                                     \
        if ((io)->out_len < (io)->out_cap) {                 \
            (io)->out[(io)->out_len++] = (uint8_t) (v);      \
        } else {                                             \
            (io)->status = BF_ERR_OUTPUT;                    \
        }                                                    \
    } while (0)

#define BF_PROGRAM(name) int bf_prog_##name(uint8_t *t, bf_io *io)

typedef int (*bf_program_fn)(uint8_t *tape, bf_io *io);

typedef struct {
    const char *name;
    bf_program_fn run;
    uint16_t tape_size;
    uint16_t persist_size;
} bf_program_info;

/* Generated table, indexed by program id. */
extern const bf_program_info bf_programs[];
extern const int bf_program_count;

#ifdef __cplusplus
}
#endif

#endif
