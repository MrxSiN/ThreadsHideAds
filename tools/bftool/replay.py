"""Differential replay: the same recorded inputs through the reference
interpreter and through the host-compiled AOT programs."""

import os
import struct
import subprocess
import tempfile

from . import gen, hostcc, ref

ROOT = gen.ROOT
CPP = os.path.join(ROOT, "app", "src", "main", "cpp")
HARNESS = os.path.join(ROOT, "tests", "native", "bf_replay.c")


def build_harness(c_source_path, output):
    return hostcc.build([HARNESS, c_source_path], output, include_dirs=[CPP])


def encode_cases(cases):
    """cases: iterable of (program id, persist bytes, input bytes)."""
    blob = bytearray()
    for program, persist, data in cases:
        blob += struct.pack("<II", program, len(persist)) + persist
        blob += struct.pack("<I", len(data)) + data
    return bytes(blob)


def run_aot(binary, cases):
    with tempfile.TemporaryDirectory() as d:
        src, dst = os.path.join(d, "cases.bin"), os.path.join(d, "results.bin")
        with open(src, "wb") as f:
            f.write(encode_cases(cases))
        subprocess.run([binary, src, dst], check=True)
        with open(dst, "rb") as f:
            blob = f.read()
    results, i = [], 0
    while i < len(blob):
        status, n = struct.unpack_from("<II", blob, i)
        i += 8
        output = blob[i:i + n]
        i += n
        (p,) = struct.unpack_from("<I", blob, i)
        i += 4
        results.append((status, output, blob[i:i + p]))
        i += p
    return results


def run_reference(code, tape_size, persist_size, persist, data):
    tape = bytearray(tape_size)
    tape[:len(persist)] = persist
    output = ref.run(code, data, tape=tape)
    return 0, output, bytes(tape[:persist_size])
