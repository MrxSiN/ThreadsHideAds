import os
import random
import re
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools"))

from bftool.regex_dfa import (  # noqa: E402
    Alt, Bytes, Cat, Lit, Star, any_byte, compile_regex, encode_text, plus)


def contains_any(words):
    return Cat([Star(any_byte()), Alt([Lit(w) for w in words]), Star(any_byte())])


class RegexDfaTest(unittest.TestCase):

    def test_exact_words(self):
        dfa = compile_regex(Alt([Lit(b"ad"), Lit(b"ad4ad"), Lit(b"ads")]))
        for text, expected in [(b"ad", True), (b"ad4ad", True), (b"ads", True),
                               (b"a", False), (b"ad4", False), (b"xad", False), (b"", False)]:
            self.assertEqual(expected, dfa.matches(text), text)
        self.assertEqual(0, dfa.step(dfa.start, ord("z")))

    def test_contains_matches_python_search(self):
        words = [b"is_sponsored=true", b"ad_id=", b"aab"]
        dfa = compile_regex(contains_any(words))
        self.assertIsNotNone(dfa.accept_sink())
        rng = random.Random(7)
        alphabet = b"a_bdist=ponre\"x"
        for _ in range(3000):
            text = bytes(rng.choice(alphabet) for _ in range(rng.randint(0, 24)))
            expected = any(w in text for w in words)
            self.assertEqual(expected, dfa.matches(text), text)

    def test_deterministic_numbering(self):
        a = compile_regex(contains_any([b"sponsored", b"directad"]))
        b = compile_regex(contains_any([b"sponsored", b"directad"]))
        self.assertEqual(a.trans, b.trans)
        self.assertEqual(a.start, b.start)

    def test_byte_sets_and_plus(self):
        digits = Bytes(range(ord("0"), ord("9") + 1))
        dfa = compile_regex(plus(digits))
        self.assertTrue(dfa.matches(b"0123"))
        self.assertFalse(dfa.matches(b""))
        self.assertFalse(dfa.matches(b"1a"))

    def test_modified_utf8(self):
        self.assertEqual(b"\xc0\x80", encode_text("\u0000"))
        self.assertEqual("реклама".encode(), encode_text("реклама"))


if __name__ == "__main__":
    unittest.main()
