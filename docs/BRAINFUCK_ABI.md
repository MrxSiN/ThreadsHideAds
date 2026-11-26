# Brainfuck ABI, version 1.0

This document is normative. `brainfuck/constants.txt` is the single source
of every number below; `tools/bftool/gen.py` exports it to
`bridge/BfAbi.java` and `cpp/generated/bf_abi.h`. Values are append-only.

## 1. Execution model

A **program** is canonical Brainfuck (`> < + - . , [ ]`) over unsigned 8-bit,
wrapping cells. Every backend (the AOT C in the APK, the host build used by
JVM tests, `tools/bftool/ref.py`, the IR executor) implements exactly this:

- the tape has a fixed size per program (`brainfuck/programs.json`); leaving
  it is an error. Generated programs keep every loop balanced, so all cell
  addresses are static and verified at generation time;
- `,` reads the next input byte; when the input is exhausted the runtime asks
  for more (section 5) and otherwise answers 0;
- `.` appends to the output. **The first byte of a new frame discards any
  unread input**, so a program may stop reading a reply early;
- at the start of every run the scratch region is zero. The persistent region
  (only `core` has one: cells 0..63) keeps its values between runs and is
  cleared by `OP_RESET`.

## 2. Frames

Every request, reply and capability exchange is a frame:

| Offset | Size | Request | Response |
| --- | --- | --- | --- |
| 0 | 1 | ABI major (1) | ABI major |
| 1 | 1 | ABI minor (0) | ABI minor |
| 2 | 1 | opcode | opcode \| 0x80 |
| 3 | 1 | flags (bit 0 `FLAG_STREAM`) | status |
| 4 | 2 | payload length, LE | payload length, LE |
| 6 | 2 | request id, LE | request id echoed |
| 8 | … | payload | payload |

A request whose major version is not 1 is answered `ST_BAD_VERSION`; an
opcode a program does not serve, `ST_BAD_OPCODE`. With `FLAG_STREAM` a
0-terminated text stream follows the fixed payload and the length field counts
only the fixed part.

Status: `ST_OK 0`, `ST_BAD_VERSION 1`, `ST_BAD_OPCODE 2`, `ST_BAD_FRAME 3`,
`ST_STALE_HANDLE 4`, `ST_LIMIT 5`, `ST_HOST_ERROR 6`, `ST_NOT_FOUND 7`.

Primitives: u8; u16/u32/i64 little-endian; booleans 0/1. A failed capability
answers a header with no payload; programs read zeros and fail open.

## 3. Handles

Java objects never enter Brainfuck memory. The host hands out u32 handles,
`generation << 16 | index`, with 0 meaning null.

- Handles live for one top-level request; the table is cleared afterwards and
  the generation advances, so an old handle answers `ST_STALE_HANDLE`.
- `CAP_RELEASE h` frees every handle allocated at or after `h` (stack
  discipline), which keeps long traversals within the table.
- Each handle remembers the slot it was read from (list index, array index,
  map entry, field), so `CAP_WRITE_BACK` can replace it in place.
- 16384 handles per engine; beyond that `put` returns 0 (treated as null).

## 4. Top-level requests (host → Brainfuck)

The runtime routes 0x01–0x2F to `core`; the rest by table (`bf_route`).

| Op | Name | Payload | Response |
| --- | --- | --- | --- |
| 0x01 | `LOAD_PACKAGE` | package h, process h | in scope, install label hooks, install attach guard |
| 0x02 | `ATTACH_GUARD_FAILED` | — | 1 = request init from load-package loader |
| 0x03 | `INIT_REQUEST` | loader present, retry index | action, reason, attempt u32, delay ms u32, next retry |
| 0x04 | `INIT_FINISH` | success, retry index | as above |
| 0x05 | `INSTALL_STEP` | step, ok, aux, feed hooks u16, Pando hooks u16 | next step, success, detail |
| 0x06 | `PRIMARY_CANDIDATE` | static, abstract, in interface, params, p0 int, p1 int, p2 List, returns void | accept |
| 0x07 | `PRIMARY_DECISION` | — | decision, accepted u16 (resets the count) |
| 0x08 | `BROAD_CANDIDATE` | list boundary, returns boolean, method h, 13 marker flags | 0 skip, 1 list hook, 2 boolean hook |
| 0x09 | `FEED_OWNER_METHOD` | abstract, returns List, takes List | hook |
| 0x0A | `INJECTOR_METHOD` | abstract, returns boolean, params, p0 primitive, p1 int | shape |
| 0x0B | `INJECTOR_DECISION` | — | decision (install only for exactly one) |
| 0x0C | `PANDO_ACCESSOR` | abstract, native, returns List | hook |
| 0x0D | `CONST_STRING` | table, index | UTF-8 name, or `ST_NOT_FOUND` |
| 0x0E | `LIMITS` | — | field class depth, ancestor depth, list-container walk |
| 0x0F | `STATS` | kind, count u32 | log, total u32, event u32 |
| 0x10 | `UI_TEXT_EVENT` | 0 text / 1 description, view h | 1 hide, 2 restore if hidden |
| 0x11 | `UI_MARKER` | view h, is TextView | marker |
| 0x12 | `UI_CARD` | n, screen w u16, h u16, density 16.16 u32, n × (list container, root/decor, w u16, h u16) | chain index, 0xFE none |
| 0x13 | `UI_HIDE` | marker, card found, attempt, retry could not arm | 0 none, 1 card, 2 retry, 3 label; next attempt |
| 0x14 | `UI_RESTORE` | hidden by this label, still labelled | restore |
| 0x15 | `UI_SCAN` | root h, rescan | first labelled view h or 0 |
| 0x16 | `UI_VIEW_CLASS` | view h | list container, decor view |
| 0x17 | `UI_ADD_VIEW` | n, n list-container flags | scan the added view |
| 0x18 | `PERMALINK_ADS_METHOD` | static, returns Object, takes owner | shape (counted in `pa`) |
| 0x19 | `PERMALINK_ADS_DECISION` | — | decision (install only for exactly one) |
| 0x1A | `ITEM_INSERT_METHOD` | abstract, returns Integer, params, p0 reference, p1 int | shape (counted in `ia`) |
| 0x1B | `ITEM_INSERT_DECISION` | — | decision (install only for exactly one) |
| 0x1C | `ITEM_WRITE_METHOD` | static, private, returns Object, p0 is the insert's item type | hook |
| 0x1D | `MEDIA_GETTER_METHOD` | static, params, returns Media | shape (counted in `mg`) |
| 0x1E | `MEDIA_GETTER_DECISION` | — | decision (install only for exactly one) |
| 0x1F | `NATIVE_AD_METHOD` | static, params, returns boolean (host offers only Media methods calling the injected check) | shape (counted in `na`) |
| 0x20 | `NATIVE_AD_DECISION` | — | decision (install only for exactly one) |
| 0x21 | `ITEM_ACTION` | detector says ad, Threads' own check says ad | 1 keep the item out |
| 0x2E | `RESET` | — | clears persistent cells |
| 0x2F | `VERSION` | — | major, minor |
| 0x30 | `BOUNDARY_BEFORE` | argc | 0; removals via `CAP_REPORT` |
| 0x31 | `BOUNDARY_AFTER` | argc, threw | 0 |
| 0x32 | `SANITIZE_LIST` | h, declared {null/Object, interface, accepts ArrayList}, allow replacement, allow emptying | changed, result h, removed u32, emptying blocked |
| 0x33 | `NESTED_SANITIZE` | h | removed u32, replacement failures u32 |
| 0x34 | `NESTED_VISIT` | h, depth, budget u16, has replacer, slot facts ×3 | removed, failures, budget |
| 0x35 | `PANDO_FILTER` | h, declared facts ×3, declared type h | 0 none, 1 replaced, 2 blocked, 3 replace failed; result h; removed u32 |
| 0x36 | `PANDO_VERDICT` | h, class facts, cached verdict, flag memo | sponsored |
| 0x37 | `DETECT` | h, depth, budget u16, descriptor known, [descriptor] | found, budget u16 |
| 0x38 | `TEXT_MATCH` | matcher, stream | verdict |
| 0x39 | `CLASS_FACTS` | h | packed facts |

`0x34`, `0x36`, `0x37` and `0x39` are normally reached through `CAP_CALL`.

Reason and action codes are listed in the comment above each case of
`core.bf` and mirrored in `bridge/Policy.java`.

## 5. Capabilities (Brainfuck → host)

A program writes one capability frame, then reads. The runtime passes the frame
to the host and feeds the reply back as input. `CAP_CALL` is served by the
runtime itself.

| Op | Name | Payload | Reply |
| --- | --- | --- | --- |
| 0x40 | `NODE` | h | descriptor |
| 0x41 | `CHILD` | h, index u32, mode | child record |
| 0x42 | `VISIT` | set, mode (0 add, 1 clear, 2 clear then add), h | 1 when newly added |
| 0x43 | `CLASS_INFO` | h | class overrides toString |
| 0x44 | `MEMO_GET` | h, slot, 0, index u16 | value (0 = unknown) |
| 0x45 | `MEMO_PUT` | h, slot, value, index u16 | 0 |
| 0x46 | `JSON_LENGTH` | h, flag pair | JSON length u32, 0xFFFFFFFF when absent |
| 0x47 | `MARK` | list h, index u32 (0xFFFFFFFF starts fresh marks) | — |
| 0x48 | `REMOVE_MARKED` | list h | removed in place u32 (backwards, stops at first failure) |
| 0x49 | `FILTERED_COPY` | list h | new ArrayList of unmarked entries |
| 0x4A | `WRITE_BACK` | child h, replacement h | ok |
| 0x4B | `COPY_OF` | kept h, type h, 0 declared / 1 original class | list h or 0 |
| 0x4C | `RELEASE` | h | — |
| 0x4D | `SUPERCLASS` | h, k | k-th class of the runtime class chain, or 0 |
| 0x4E | `FIND_ACCESSOR` | h, length, name | `name(boolean, boolean)` found (first becomes the JSON accessor) |
| 0x4F | `SCAN_NEXT` | root h, max children, reset | next view breadth-first, is TextView |
| 0x50 | `ARG` | index (255 = result) | h, is List, declared facts ×3, runtime-class facts ×3 |
| 0x51 | `SET_ARG` | index, h | — |
| 0x52 | `TREE_VERDICT_GET` | h | 0 unknown, 1 organic, 2 sponsored |
| 0x53 | `TREE_VERDICT_PUT` | h, verdict | — |
| 0x54 | `REPORT` | site, index, count u32 | — |
| 0x55 | `CHILDREN` | h, start u32, count u16, mode | consecutive child records |
| 0x60 | `CALL` | program, sub-op, flags (bit 0 fetch), argc, args, [h, source, transform] | callee's response |

**Descriptor** (25 bytes): is CharSequence, Enum, Number, Boolean, Character,
Class, Map, Collection, List, array; class facts memo; size u32 (text length,
container size or instance-field count); value i64 (`Number.longValue()`,
Boolean 1/0); cached Pando verdict (0 not looked up, 1 organic, 2 sponsored,
3 not cached); Pando flag memo.

**Child record** (37 bytes): handle, descriptor, declared slot type facts ×3,
field-name memo, key handle (map key; field index for `MODE_FIELD_NEXT`).
Modes: 0 list get, 1 iteration, 2 map entry, 3 array, 4 field, 5 next non-null
field.

**Class facts** (bits, LSB first): known, app-model class, strong ad token,
Pando tree, structured toString. Memo slots: 0 facts, 1 Pando flag pair + 1,
2 field-name facts (by index), 3 view-class facts.

**`CAP_CALL`** runs the program one level deeper on a zeroed tape. The core
program cannot be called (its state belongs to top-level requests). With the
fetch flag, the host streams a text source after the arguments. Sources:
0 `String.valueOf`, 1 enum name, 2 runtime class name, 3 field name of the
handle's slot, 4 Pando JSON (flag pair in transform bits 1–2), 5 generic
method signature, 6 `Class.method`, 7 TextView text, 8 content description,
9 `Class.getName()` of a Class handle. Transform bit 0: `toLowerCase(Locale.ROOT)`.

## 6. Text streams

Text is Java modified UTF-8 (U+0000 as C0 80) ending in a 0 byte, streamed in
16 KB chunks. Everything except case mapping — trimming, whitespace classes,
separators, every word — is in `brainfuck/src/text.bf`. A matcher stops
reading when its machine reaches a sink; the unread rest is discarded.

## 7. Limits

| Limit | Value | Source |
| --- | --- | --- |
| detector depth / budget / entries | 8 / 1200 / 32 | `detect.bf` |
| nested depth / budget / entries | 4 / 192 / 48 | `nested.bf` |
| field class depth | 6 | `core.bf` OP_LIMITS (host applies it) |
| inspected text length | 262 144 | `detect.bf` |
| Pando JSON / item JSON / list size | 524 288 / 65 536 / 256 | `pando.bf` |
| primary boundaries accepted | 1..8 | `core.bf` |
| retry delays | 250, 1000, 3000 ms | `core.bf` |
| ancestor depth / list-container walk / layout retries | 14 / 9 / 4 | `core.bf` |
| label scan / rescan / children per group | 320 / 160 / 64 | `core.bf` |
| runtime levels / input / output per level | 24 / 16 KB / 8 KB | `constants.txt` |
| handles per engine | 16 384 | `HandleTable` |
| nested requests per thread | 2 | `Policy` |

## 8. Lifecycle

`core` persistent cells: label hooks installed, attach guard requested,
initialized, initializing, attempt counter, discovery counters, statistics.
States: pre-attach (loaded, guard installed) → initializing (attempt started)
→ initialized (committed only when a hook installed) or retry pending
(250/1000/3000 ms) → retries exhausted (Pando and label layers stay active).
A second trigger while an attempt runs is ignored. `OP_RESET` returns to the
initial state.

## 9. Sources

Every program is a hand-written `.bf` file in `brainfuck/src/`; there is no
assembler or macro layer. `tools/bftool/lint.py` checks a source and never
changes it:

- `#` starts a comment that runs to the end of the line. A comment may not
  contain any of the eight command characters, so prose can never become code;
- outside comments a line holds only commands, whitespace and checkpoints;
- `%cell NAME ADDRESS [SIZE]` in a comment names tape cells; the memory map is
  built from these declarations;
- `@NAME`, `@NAME:K` (byte K of NAME) or `@ADDRESS` asserts where the data
  pointer is at that point;
- `~N` asserts that the run of one command just before it (spaces allowed
  inside the run) is exactly N long;
- every loop leaves the pointer where it found it, so each checkpoint is
  verified statically and the pointer never leaves the tape.

Shapes the sources use throughout, which the optimizer recognizes:

- **equality test**: copy the subject into `f` through a temporary, subtract
  the case value, set `e`; `f[ e=0 … f=0 ]` runs the else body and
  `e[ … e=0 ]` the case body. Runs of these become C `switch` statements;
- **run-once `if`**: a loop whose body ends by clearing its condition cell;
- **multi-byte numbers** are little endian: sums carry one unit at a time,
  comparisons go from the high byte down;
- **frames** are written byte by byte from a single emitter cell that
  returns to zero after each frame.
