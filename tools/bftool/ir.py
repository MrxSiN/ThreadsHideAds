"""Brainfuck -> optimized IR -> C.

IR operations use offsets relative to the current pointer `p`:

    ("add", off, n)         p[off] += n
    ("set", off, v)         p[off] = v
    ("mul", src, dst, k)    p[dst] += p[src] * k
    ("move", n)             p += n
    ("in", off)             p[off] = read()
    ("out", off)            write(p[off])
    ("loop", off, body)     while (p[off]) body      (body has net move 0)
    ("uloop", body)         while (*p) body          (body moves the pointer)
    ("if", off, body)       if (p[off]) body         (body leaves p[off] == 0)

Recognized idioms: operation runs, pointer-motion elimination inside
balanced code, clear loops, transfer/multiply-add loops and run-once loops.
Every rewrite preserves the reference semantics in ref.py; the optimizer tests
check that differentially.
"""

from .ref import match_brackets, strip


# ---------------------------------------------------------------- parsing

def parse(code):
    code = strip(code)
    match_brackets(code)
    ops, _, _, _ = _parse_seq(code, 0)
    # The final pointer position is unobservable, so a trailing move is dropped.
    return ops


def _parse_seq(code, i):
    """Parses until `]` or end. Returns (ops, next index, pending pointer
    delta, whether the pointer was materialized by a nested unbalanced loop)."""
    ops = []
    delta = 0
    moved = False
    n = len(code)
    while i < n:
        c = code[i]
        if c in "+-":
            ops.append(("add", delta, 1 if c == "+" else -1))
        elif c in "<>":
            delta += 1 if c == ">" else -1
        elif c == ".":
            ops.append(("out", delta))
        elif c == ",":
            ops.append(("in", delta))
        elif c == "[":
            inner, i, inner_delta, inner_moved = _parse_seq(code, i + 1)
            if inner_delta == 0 and not inner_moved:
                ops.append(("loop", delta, _shift(inner, delta)))
            else:
                if delta:
                    ops.append(("move", delta))
                    delta = 0
                if inner_delta:
                    inner = inner + [("move", inner_delta)]
                ops.append(("uloop", inner))
                moved = True
            continue
        elif c == "]":
            return ops, i + 1, delta, moved
        i += 1
    return ops, i, delta, moved


def _shift(ops, d):
    if d == 0:
        return ops
    out = []
    for op in ops:
        k = op[0]
        if k in ("add", "set"):
            out.append((k, op[1] + d, op[2]))
        elif k == "mul":
            out.append(("mul", op[1] + d, op[2] + d, op[3]))
        elif k in ("in", "out"):
            out.append((k, op[1] + d))
        elif k in ("loop", "if"):
            out.append((k, op[1] + d, _shift(op[2], d)))
        else:
            raise ValueError("cannot shift %r" % (op,))
    return out


# ---------------------------------------------------------------- optimizing

def optimize(ops):
    out = []
    for op in ops:
        k = op[0]
        if k == "loop":
            out.append(_optimize_loop(op[1], optimize(op[2])))
        elif k == "uloop":
            out.append(("uloop", optimize(op[1])))
        elif k == "if":
            out.append(("if", op[1], optimize(op[2])))
        else:
            out.append(op)
    return _peephole(out)


def _writes(ops, cell):
    for op in ops:
        k = op[0]
        if k in ("add", "set", "in") and op[1] == cell:
            return True
        if k == "mul" and op[2] == cell:
            return True
        if k in ("loop", "if") and _writes(op[2], cell):
            return True
        if k in ("uloop", "move"):
            return True
    return False


def _optimize_loop(off, body):
    # Clear, transfer and multiply-add loops: only adds, condition cell -1/+1.
    if body and all(op[0] == "add" for op in body):
        cond = sum(op[2] for op in body if op[1] == off) & 255
        if cond in (1, 255):
            if cond == 1:
                # Counting up from x reaches 0 after 256-x steps; only a pure
                # clear loop is rewritten in that direction.
                if all(op[1] == off for op in body):
                    return ("set", off, 0)
            else:
                others = {}
                for op in body:
                    if op[1] != off:
                        others[op[1]] = (others.get(op[1], 0) + op[2]) & 255
                result = [("mul", off, dst, k) for dst, k in sorted(others.items()) if k]
                result.append(("set", off, 0))
                return ("block", result)
    # Run-once loop: the body ends by clearing the condition cell and does not
    # otherwise write it, so the loop is an `if`.
    if body and body[-1] == ("set", off, 0) and not _writes(body[:-1], off):
        return ("if", off, body[:-1] + [("set", off, 0)])
    return ("loop", off, body)


def _peephole(ops):
    flat = []
    for op in ops:
        if op[0] == "block":
            flat.extend(op[1])
        else:
            flat.append(op)
    out = []
    for op in flat:
        if out:
            prev = out[-1]
            if op[0] == "add" and prev[0] == "add" and prev[1] == op[1]:
                n = (prev[2] + op[2]) & 255
                out[-1] = ("add", op[1], n)
                if n == 0:
                    out.pop()
                continue
            if op[0] == "add" and prev[0] == "set" and prev[1] == op[1]:
                out[-1] = ("set", op[1], (prev[2] + op[2]) & 255)
                continue
            if op[0] == "set" and prev[0] in ("add", "set") and prev[1] == op[1]:
                out[-1] = op
                continue
        if op[0] == "add":
            op = ("add", op[1], op[2] & 255)
            if op[2] == 0:
                continue
        out.append(op)
    return out


def compile_bf(code):
    return optimize(parse(code))


def is_static(ops):
    """True when the pointer never moves (every loop balanced)."""
    for op in ops:
        if op[0] in ("move", "uloop"):
            return False
        if op[0] in ("loop", "if") and not is_static(op[2]):
            return False
    return True


def offset_range(ops, lo=0, hi=0):
    for op in ops:
        k = op[0]
        if k in ("add", "set", "in", "out"):
            lo, hi = min(lo, op[1]), max(hi, op[1])
        elif k == "mul":
            lo, hi = min(lo, op[1], op[2]), max(hi, op[1], op[2])
        elif k in ("loop", "if"):
            lo, hi = min(lo, op[1]), max(hi, op[1])
            lo, hi = offset_range(op[2], lo, hi)
    return lo, hi


def count_ops(ops):
    total = 0
    for op in ops:
        total += 1
        if op[0] in ("loop", "if"):
            total += count_ops(op[2])
        elif op[0] == "uloop":
            total += count_ops(op[1])
    return total


# ---------------------------------------------------------------- IR execution (tests)

class _Io:
    def __init__(self, data, reader):
        self.data = bytearray(data)
        self.pos = 0
        self.out = bytearray()
        self.reader = reader

    def read(self):
        if self.pos >= len(self.data) and self.reader is not None:
            more = self.reader(bytes(self.out))
            if more:
                self.data += more
        if self.pos < len(self.data):
            self.pos += 1
            return self.data[self.pos - 1]
        return 0


def execute(ops, data=b"", tape=None, tape_size=4096, reader=None):
    if tape is None:
        tape = bytearray(tape_size)
    io = _Io(data, reader)
    _exec(ops, tape, 0, io)
    return bytes(io.out)


def _exec(ops, t, p, io):
    for op in ops:
        k = op[0]
        if k == "add":
            t[p + op[1]] = (t[p + op[1]] + op[2]) & 255
        elif k == "set":
            t[p + op[1]] = op[2]
        elif k == "mul":
            t[p + op[2]] = (t[p + op[2]] + t[p + op[1]] * op[3]) & 255
        elif k == "move":
            p += op[1]
            if p < 0 or p >= len(t):
                raise IndexError("pointer out of tape")
        elif k == "in":
            t[p + op[1]] = io.read()
        elif k == "out":
            io.out.append(t[p + op[1]])
        elif k == "loop":
            while t[p + op[1]]:
                p = _exec(op[2], t, p, io)
        elif k == "if":
            if t[p + op[1]]:
                p = _exec(op[2], t, p, io)
        elif k == "uloop":
            while t[p]:
                p = _exec(op[1], t, p, io)
    return p


# ---------------------------------------------------------------- C generation

def emit_c(ops, name, tape_size):
    static = is_static(ops)
    lo, hi = offset_range(ops)
    if static and (lo < 0 or hi >= tape_size):
        raise ValueError("%s addresses cells %d..%d outside tape %d" % (name, lo, hi, tape_size))
    lines = [
        "/* Generated from %s.bf by tools/bftool. Do not edit. */" % name,
        "BF_PROGRAM(%s)" % name,
        "{",
        "    uint8_t *p = t;",
    ]
    _emit_block(ops, lines, 1, static, tape_size)
    lines += ["    return BF_OK;", "}", ""]
    return "\n".join(lines)


def _emit_block(ops, lines, depth, static, tape_size):
    pad = "    " * depth
    for op in ops:
        k = op[0]
        if k == "add":
            lines.append("%sp[%d] += %d;" % (pad, op[1], op[2]))
        elif k == "set":
            lines.append("%sp[%d] = %d;" % (pad, op[1], op[2]))
        elif k == "mul":
            if op[3] == 1:
                lines.append("%sp[%d] += p[%d];" % (pad, op[2], op[1]))
            else:
                lines.append("%sp[%d] += (uint8_t)(p[%d] * %d);" % (pad, op[2], op[1], op[3]))
        elif k == "move":
            lines.append("%sp += %d;" % (pad, op[1]))
            lines.append("%sif (p < t || p >= t + %d) return BF_ERR_TAPE;" % (pad, tape_size))
        elif k == "in":
            lines.append("%sp[%d] = BF_IN(io);" % (pad, op[1]))
        elif k == "out":
            lines.append("%sBF_OUT(io, p[%d]);" % (pad, op[1]))
        elif k in ("loop", "if"):
            keyword = "while" if k == "loop" else "if"
            lines.append("%s%s (p[%d]) {" % (pad, keyword, op[1]))
            _emit_block(op[2], lines, depth + 1, static, tape_size)
            lines.append(pad + "}")
        elif k == "uloop":
            lines.append("%swhile (*p) {" % pad)
            _emit_block(op[1], lines, depth + 1, static, tape_size)
            lines.append(pad + "}")
        else:
            raise ValueError(op)
