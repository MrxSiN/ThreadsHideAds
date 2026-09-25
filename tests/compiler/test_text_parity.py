"""Text matchers: legacy verdicts vs the AOT program, plus a reference sample.

Needs build/vectors/text.tsv from `gradlew :app:testDebugUnitTest`
(TextVectorExportTest) and a host C compiler.
"""

import os
import random
import struct
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools"))

from bftool import gen, hostcc, replay  # noqa: E402

VECTORS = os.path.join(gen.ROOT, "app", "build", "vectors", "text.tsv")
BUILD = os.path.join(gen.ROOT, "build", "bfhost")
PROG_TEXT = 1
OP_TEXT_MATCH = 0x38


def frame(matcher, stream, request_id=7):
    header = bytes([1, 0, OP_TEXT_MATCH, 1]) + struct.pack("<HH", 1, request_id)
    return header + bytes([matcher]) + stream + b"\0"


def load_vectors():
    out = []
    with open(VECTORS) as f:
        for line in f:
            matcher, expected, hexed = line.rstrip("\n").split("\t")
            out.append((int(matcher), int(expected), bytes.fromhex(hexed)))
    return out


class TextParityTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        if not os.path.exists(VECTORS):
            raise unittest.SkipTest("run gradlew :app:testDebugUnitTest first")
        try:
            hostcc.compiler()
        except hostcc.NoCompiler as e:
            raise unittest.SkipTest(str(e))
        cls.vectors = load_vectors()
        exe = os.path.join(BUILD, "replay.exe" if os.name == "nt" else "replay")
        cls.binary = replay.build_harness(gen.C_OUT, exe)
        with open(os.path.join(gen.BF_DIR, "text.bf")) as f:
            cls.code = f.read()

    def test_aot_matches_legacy(self):
        cases = [(PROG_TEXT, b"", frame(m, s)) for m, _, s in self.vectors]
        results = replay.run_aot(self.binary, cases)
        failures = []
        for (m, expected, s), (status, out, _) in zip(self.vectors, results):
            self.assertEqual(0, status)
            self.assertEqual(bytes([1, 0, OP_TEXT_MATCH | 0x80, 0, 1, 0, 7, 0]), out[:8])
            if out[8] != expected:
                failures.append((m, expected, s))
        self.assertEqual([], failures[:20], "%d mismatches" % len(failures))

    def test_reference_matches_aot_sample(self):
        rng = random.Random(5)
        sample = rng.sample(self.vectors, 60)
        cases = [(PROG_TEXT, b"", frame(m, s)) for m, _, s in sample]
        aot = replay.run_aot(self.binary, cases)
        for case, got in zip(cases, aot):
            expected = replay.run_reference(self.code, 256, 0, b"", case[2])
            self.assertEqual(expected, got)

    def test_unknown_matcher_and_version(self):
        cases = [(PROG_TEXT, b"", frame(99, b"x")),
                 (PROG_TEXT, b"", bytes([2, 0, OP_TEXT_MATCH, 1, 1, 0, 1, 0, 1]) + b"x\0")]
        (s1, out1, _), (s2, out2, _) = replay.run_aot(self.binary, cases)
        self.assertEqual(2, out1[3])   # ST_BAD_OPCODE
        self.assertEqual(1, out2[3])   # ST_BAD_VERSION


if __name__ == "__main__":
    unittest.main()
