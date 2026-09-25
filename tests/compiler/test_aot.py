"""Reference interpreter vs host-compiled AOT C for small generated programs."""

import json
import os
import random
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools"))

from bftool import gen, hostcc, replay  # noqa: E402

PROGRAM = """
persist counter
var x, f
in x
charset SP = " "
regex HIT = ANY* spaced("sponsored", SP) ANY*
match HIT -> f
inc counter
out counter
switch x {
  case 1 { outc 'a' }
  case 2, 3 { outc 'b' }
  default { outc 'z' }
}
out f
clear x, f
"""


class AotTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        try:
            hostcc.compiler()
        except hostcc.NoCompiler as e:
            raise unittest.SkipTest(str(e))
        cls.dir = tempfile.mkdtemp()
        src = os.path.join(cls.dir, "src")
        os.makedirs(src)
        with open(os.path.join(src, "demo.bfa"), "w") as f:
            f.write(PROGRAM)
        manifest = os.path.join(cls.dir, "programs.json")
        with open(manifest, "w") as f:
            json.dump({"programs": [{"id": 0, "name": "demo", "source": "src/demo.bfa",
                                     "tape": 256, "persist": 1}]}, f)
        files = gen.generate(manifest)
        cls.code = next(t for p, t in files.items() if p.endswith("demo.bf"))
        c_path = os.path.join(cls.dir, "gen.c")
        with open(c_path, "w") as f:
            f.write(files[gen.C_OUT].replace('"../bf_runtime.h"', '"bf_runtime.h"'))
        exe = os.path.join(cls.dir, "replay.exe" if os.name == "nt" else "replay")
        cls.binary = replay.build_harness(c_path, exe)

    def test_differential(self):
        rng = random.Random(9)
        cases = []
        for i in range(200):
            text = bytes(rng.choice(b"sponred x") for _ in range(rng.randint(0, 30)))
            cases.append((0, bytes([i % 256]), bytes([rng.randint(0, 4)]) + text + b"\0"))
        cases.append((0, b"", b"\x02sponsored\0"))
        aot = replay.run_aot(self.binary, cases)
        for case, got in zip(cases, aot):
            expected = replay.run_reference(self.code, 256, 1, case[1], case[2])
            self.assertEqual(expected, got, case)
        self.assertEqual(b"\x01b\x01", aot[-1][1])


if __name__ == "__main__":
    unittest.main()
