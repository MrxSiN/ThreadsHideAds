# Brainfuck memory map

Each source declares its cells in `%cell NAME ADDRESS [SIZE]` comments at the
top of the file; the per-cell tables in
[`brainfuck/generated/MEMORY_MAP.md`](../brainfuck/generated/MEMORY_MAP.md) are
generated from those declarations. This page explains the layout they follow.

## Tapes

| Program | Tape | Persistent | Scratch high-water |
| --- | --- | --- | --- |
| core | 256 | 0..63 | 177 |
| text | 64 | — | 49 |
| detect | 256 | — | 163 |
| sanitize | 256 | — | 158 |
| nested | 256 | — | 160 |
| pando | 256 | — | 168 |

The checker rejects any source whose pointer can leave its tape.

## Regions

```text
0                    persist_size          ...                    tape
|  persistent (core)  |  request header, registers, program cells  |  w  |  temps  |
```

1. **Persistent** (core only, cells 0..63). Survives between requests;
   cleared by `OP_RESET`. Holds label/attach flags, `ini`, `inz`, the attempt
   counter, the primary/injector/permalink-ads/item-insert/Media-getter/native-ad match
   counters (`pc`, `ic`, `pa`, `ia` 31, `mg` 32, `na` 33) and the statistics totals with
   their rate-limit cycle counters.
2. **Request header**: major, minor, opcode, flags, length (2), id (2). In core
   these start at 64 (`maj`); in the other programs at 0.
3. **Capability registers** (detect, sanitize, nested, pando): `st` (reply
   status); the node descriptor `n_*` (25 cells) and the child record `c_*`
   (37 cells) laid out exactly as `CAP_NODE` / `CAP_CHILD` answer them, so a
   reply is read with consecutive `,`; unpacked class facts `f_*`.
4. **Program cells**: request arguments, results and loop state of that
   program.
5. **Core case cells**: core's switch cells (`o`, `f`/`t`/`e` per level),
   `dflt`, the emitter `w`, and the free blocks `L`, `X` (and `k*` for the card
   search) that each case uses for its own purposes.
6. **Emitter and temps** (non-core programs; `sanitize` uses `t:20`–`t:26` for the
   organic item memo around its detector call): `w` writes constant frame bytes
   and is zero between frames; `t` is a pool of temporaries used as branch
   flags and copy buffers, each zero whenever it is free.

## Invariants

- At the start of a run every non-persistent cell is zero (the runtime
  `memset`s the scratch region; `CAP_CALL` runs on a fresh tape).
- No Java object or pointer is ever stored; object references are u32 handles
  (see `BRAINFUCK_ABI.md`, "Handles").
- The pointer position is static: every loop body returns to the cell it
  started on, which the checker verifies at each `@` checkpoint and the
  generator checks again (`ir.is_static`).
