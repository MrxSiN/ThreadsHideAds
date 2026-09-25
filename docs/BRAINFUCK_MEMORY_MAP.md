# Brainfuck memory map

The per-cell tables are generated from the assembler's symbol table:
[`brainfuck/generated/MEMORY_MAP.md`](../brainfuck/generated/MEMORY_MAP.md).
This page explains the layout they follow.

## Tapes

| Program | Tape | Persistent | Scratch high-water |
| --- | --- | --- | --- |
| core | 256 | 0..63 | 209 |
| text | 64 | — | 37 |
| detect | 256 | — | 164 |
| sanitize | 256 | — | 155 |
| nested | 256 | — | 158 |
| pando | 256 | — | 148 |

The generator rejects any program that addresses a cell outside its tape.

## Regions

```text
0                    persist_size          ...                    tape
|  persistent (core)  |  program globals  |  block-scoped vars and temps  |
```

1. **Persistent** (`persist`, core only, 64 cells). Survives between requests;
   cleared by `OP_RESET`. Holds label/attach flags, `initialized`,
   `initializing`, the attempt counter, the primary/injector match counters and
   the statistics totals with their rate-limit cycle counters.
2. **Request header** (`frame.bfa`): `rq_major`, `rq_minor`, `rq_op`,
   `rq_flags`, `rq_len[2]`, `rq_id[2]`.
3. **Capability registers** (`lib.bfa`): `r_status`; the node descriptor
   `n_*` (25 cells) and the child record `c_*` (37 cells); unpacked class facts
   `f_*`.
4. **Program globals**: request arguments and results of that program.
5. **Scoped cells**: every `var` inside a block, macro expansion or builtin
   temp. Allocation is a stack; on scope exit the cells are cleared, so the
   invariant "free scratch cells are zero" holds at every point, which the
   optimizer relies on for value propagation.

## Invariants

- At the start of a run every non-persistent cell is zero (the runtime
  `memset`s the scratch region; `CAP_CALL` runs on a fresh tape).
- No Java object or pointer is ever stored; object references are u32 handles
  (see `BRAINFUCK_ABI.md`, "Handles").
- The pointer position is static: every loop body returns to the cell it
  started on, which the generator checks (`ir.is_static`).
