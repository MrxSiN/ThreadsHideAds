/*
 * Replays recorded program invocations against the AOT-compiled programs.
 *
 * Input file, repeated:  u32 program, u32 persist_len, persist bytes,
 *                        u32 input_len, input bytes
 * Output file, repeated: u32 status, u32 output_len, output bytes,
 *                        u32 persist_len, persist bytes
 * All integers little-endian. Each case starts from a zeroed tape with the
 * given persistent prefix, and reads 0 once its input is exhausted.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "bf_runtime.h"

static int read_u32(FILE *f, uint32_t *v)
{
    uint8_t b[4];
    if (fread(b, 1, 4, f) != 4) {
        return 0;
    }
    *v = (uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16) | ((uint32_t) b[3] << 24);
    return 1;
}

static void write_u32(FILE *f, uint32_t v)
{
    uint8_t b[4] = {(uint8_t) v, (uint8_t) (v >> 8), (uint8_t) (v >> 16), (uint8_t) (v >> 24)};
    fwrite(b, 1, 4, f);
}

int main(int argc, char **argv)
{
    if (argc != 3) {
        fprintf(stderr, "usage: bf_replay <cases> <results>\n");
        return 2;
    }
    FILE *in = fopen(argv[1], "rb");
    FILE *out = fopen(argv[2], "wb");
    if (in == NULL || out == NULL) {
        return 2;
    }
    size_t out_cap = 1u << 20;
    uint8_t *output = malloc(out_cap);
    uint32_t program;
    while (read_u32(in, &program)) {
        uint32_t persist_len, input_len;
        if (program >= (uint32_t) bf_program_count || !read_u32(in, &persist_len)) {
            return 3;
        }
        const bf_program_info *info = &bf_programs[program];
        uint8_t *tape = calloc(info->tape_size, 1);
        if (persist_len > info->tape_size || fread(tape, 1, persist_len, in) != persist_len
                || !read_u32(in, &input_len)) {
            return 3;
        }
        uint8_t *input = malloc(input_len ? input_len : 1);
        if (fread(input, 1, input_len, in) != input_len) {
            return 3;
        }
        bf_io io;
        memset(&io, 0, sizeof io);
        io.in = input;
        io.in_len = input_len;
        io.out = output;
        io.out_cap = out_cap;
        int status = info->run(tape, &io);
        if (status == BF_OK) {
            status = io.status;
        }
        write_u32(out, (uint32_t) status);
        write_u32(out, (uint32_t) io.out_len);
        fwrite(output, 1, io.out_len, out);
        write_u32(out, info->persist_size);
        fwrite(tape, 1, info->persist_size, out);
        free(input);
        free(tape);
    }
    fclose(out);
    fclose(in);
    free(output);
    return 0;
}
