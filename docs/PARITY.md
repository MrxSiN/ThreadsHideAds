# Parity

The frozen v1.2.1 policy (`app/src/sharedTest/java/.../legacy/`) is the oracle.
Every suite below runs the **production** stack — `Policy` → JNI →
runtime → AOT C → Brainfuck programs → `Host` capabilities — against it. On
the host the same C is built by MSVC or gcc (`tools/bftool/hostlib.py`); on
device by the NDK.

## Suites

| Suite | What | Size | Result |
| --- | --- | --- | --- |
| `TextParityTest` (JVM) | 14 matchers vs `AdFieldSignals`, `AdLabelVocabulary`, `LegacyRules` | 8 675 vectors | pass |
| `test_text_parity.py` | same vectors through the host AOT binary; reference interpreter on a sample | 8 675 + 60 | pass |
| `FeedModelParityTest.detectorMatchesLegacy` | `ReflectiveAdDetector` on seeded random graphs (cycles, deep chains, Pando trees, maps, arrays, strong fields) | 1 500 graphs | pass |
| `…listSanitizerMatchesLegacy` | `ListSanitizer`: mutable, `Arrays.asList`, unmodifiable, copyOf lists × 8 declared types × replacement/emptying flags; compares verdicts, counts and dumps of both the result and the mutated input | 1 500 | pass |
| `…nestedSanitizerMatchesLegacy` | `NestedCollectionSanitizer`: removals, replacement failures, write-back through fields/lists/maps/arrays | 1 500 | pass |
| `…boundaryFlowMatchesLegacy` | full before/after hook flow incl. late insertion and nested results | 600 | pass |
| `CoreParityTest` | scope, retry state machine (400 random scripts), install order (64 scenarios), discovery predicates on real methods, names/limits, Pando filter (800 lists), statistics rate limits, card search (3 000 chains), hide/restore, label scan (400 trees), view classes, addView | — | pass |
| `AbiRobustnessTest` | bad version/opcode, 4 000 fuzzed frames, stale handles, nesting bound, concurrency, 100 KB chunked streams | — | pass |
| `test_ir.py`, `test_asm.py`, `test_regex_dfa.py`, `test_aot.py` | optimizer vs reference on 1 500 random programs (with and without value propagation), assembler constructs, DFA vs Python search, AOT vs reference | — | pass |
| `DeviceCoreTest` (androidTest, arm64) | detector and sanitizer vs legacy with the NDK-built library | 500 + 500 | pass |

## Reference vs AOT

`tools/bftool/ref.py` interprets the canonical `.bf` one command at a time.
`test_aot.py` and `test_text_parity.py` build the generated C with the host
compiler and compare output bytes and persistent cells with the interpreter.

## Deriving the label regex

`AdLabelVocabulary.normalize` trims (≤ U+0020), collapses whitespace runs to
one space, strips separators and whitespace from both ends, and trims again.
The set of strings it maps to a vocabulary word is regular:

```text
TRIM* (SEP|WS)* (CTRL|WS)*  word-with-inner-spaces-as-WS+  (CTRL|WS)* (SEP|WS)* TRIM*
```

where TRIM = chars ≤ U+0020, WS = `Character.isWhitespace`, CTRL = TRIM \ WS,
SEP = `· • - – — | , :`. The corpus includes the cases that separate the
orderings (for example `ad·\u0001·` is *not* a label while `ad\u0001·` is);
all agree with the legacy function.

## Device

Pixel 8 Pro, Android SDK 37, Threads 448.0.0.54.85, LSPosed:

- initialization completes on every cold start (6/6) with 38 model hooks and
  28 Pando hooks, primary boundary `X.4At.Dyg(Integer, Integer, List)`;
- 10–12 advertisements removed per cold launch + 25 scroll passes (legacy on
  the same device: 10–12);
- no `Ad`/`Sponsored` label in any `uiautomator` dump; no crash-buffer entries.

## Adding a vector

Text: add strings to `TextVectors` (the expected value is computed by the
legacy function). Graph/list behavior: extend `Graphs.Builder`. Then run
`./gradlew :app:testDebugUnitTest` and `python -m unittest discover -s tests/compiler`.
