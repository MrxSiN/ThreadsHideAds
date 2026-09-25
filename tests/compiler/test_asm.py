import os
import random
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools"))

from bftool import asm, ir, ref  # noqa: E402


def build(source, extra=None):
    d = tempfile.mkdtemp()
    for name, text in (extra or {}).items():
        with open(os.path.join(d, name), "w", encoding="utf-8") as f:
            f.write(text)
    path = os.path.join(d, "main.bfa")
    with open(path, "w", encoding="utf-8") as f:
        f.write(source)
    program, _ = asm.assemble(path, tape_size=512)
    return program.code


def run(code, data=b""):
    expected = ref.run(code, data, tape_size=512)
    actual = ir.execute(ir.compile_bf(code), data, tape_size=512)
    assert expected == actual
    assert expected == ir.execute(ir.compile_bf(code, zero_from=0), data, tape_size=512)
    return expected


class AsmTest(unittest.TestCase):

    def test_if_else_and_ifeq(self):
        code = build("""
var x, y
in x
if x { outc 1 } else { outc 2 }
ifeq x 7 { outc 3 } else { outc 4 }
ifz x { outc 5 }
ifne x 7 { outc 6 }
""")
        self.assertEqual(b"\x02\x04\x05\x06", run(code, b"\x00"))
        self.assertEqual(b"\x01\x03", run(code, b"\x07"))
        self.assertEqual(b"\x01\x04\x06", run(code, b"\x09"))

    def test_switch(self):
        code = build("""
var x
in x
switch x {
  case 1 { outc 10 }
  case 2, 3 { outc 20 }
  default { outc 99 }
}
""")
        for value, out in [(1, 10), (2, 20), (3, 20), (0, 99), (200, 99)]:
            self.assertEqual(bytes([out]), run(code, bytes([value])))

    def test_multibyte_arithmetic_and_compare(self):
        code = build("""
var a[4], b[4], f
in a, b
addv a, b
out a
gt a, 70000 -> f
out f
lt a, b -> f
out f
dec a
out a
""")
        rng = random.Random(3)
        for _ in range(60):
            x, y = rng.randrange(1 << 20), rng.randrange(1 << 18)
            data = x.to_bytes(4, "little") + y.to_bytes(4, "little")
            out = run(code, data)
            s = (x + y) & 0xFFFFFFFF
            self.assertEqual(s.to_bytes(4, "little"), out[:4])
            self.assertEqual(int(s > 70000), out[4])
            self.assertEqual(int(s < y), out[5])
            self.assertEqual(((s - 1) & 0xFFFFFFFF).to_bytes(4, "little"), out[6:10])

    def test_regex_match_with_early_exit(self):
        code = build("""
charset SP = " "
regex HIT = ANY* spaced("ad_id", SP) ANY* | "exact"
var f
match HIT -> f
out f
""")
        cases = [(b"x ad_ id=1", 1), (b"exact", 1), (b"exactly", 0), (b"nothing", 0)]
        for text, expected in cases:
            self.assertEqual(bytes([expected]), run(code, text + b"\0"))
        # Early exit: after the match the rest of the input is left unread.
        early = build("""
charset SP = " "
regex HIT = ANY* spaced("ad_id", SP) ANY*
var f, next
match HIT -> f
in next
out f, next
""")
        self.assertEqual(b"\x01\x07", run(early, b"ad_id\x07\0"))

    def test_macros_includes_and_scoping(self):
        code = build("""
include "lib.bfa"
var x
in x
twice x
out x
""", {"lib.bfa": """
const TWO = 1 + 1
macro twice(c) {
  var t
  copy c -> t
  addv c, t
}
"""})
        self.assertEqual(b"\x0a", run(code, b"\x05"))

    def test_scratch_is_clean_after_program(self):
        code = build("""
var x, f
in x
ifeq x 3 { outc 1 }
gt x, 2 -> f
out f
clear x, f
""")
        tape = bytearray(512)
        ref.run(code, b"\x05", tape=tape)
        self.assertEqual(bytes(512), bytes(tape))

    def test_strings_and_bits(self):
        code = build("""
var x, a, b, c, y
in x
outs "Hé"
unpack x -> a, b, c
out a, b, c
pack c, b, a -> y
out y
""")
        self.assertEqual("Hé".encode() + b"\x01\x00\x01\x05", run(code, b"\x05"))
        self.assertEqual("Hé".encode() + b"\x00\x01\x01\x03", run(code, b"\x06"))

    def test_errors(self):
        with self.assertRaises(asm.AsmError):
            build("var x\nbogus x\n")
        with self.assertRaises(asm.AsmError):
            build("var x\nvar y\npersist z\n")


if __name__ == "__main__":
    unittest.main()
