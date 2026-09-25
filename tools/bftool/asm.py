"""`.bfa` macro assembler: structured source -> canonical Brainfuck.

Every cell has a static address and every loop returns to the cell it started
on, so the pointer position is known at assembly time. See
docs/BRAINFUCK_ABI.md ("BFA language") for the full reference.
"""

import os
import re

from . import regex_dfa as rx


class AsmError(Exception):
    pass


def _display(path):
    try:
        return os.path.relpath(path).replace(os.sep, "/")
    except ValueError:
        return path.replace(os.sep, "/")


# ---------------------------------------------------------------- lexing

_TOKEN = re.compile(r"""
    (?P<ws>[ \t\r]+) | (?P<comment>\#[^\n]*) | (?P<nl>\n) |
    (?P<str>"(?:[^"\\]|\\.)*") | (?P<char>'(?:[^'\\]|\\.)') |
    (?P<num>0x[0-9A-Fa-f]+|\d+) | (?P<cp>[uU]\+[0-9A-Fa-f]{4}) |
    (?P<id>[A-Za-z_][A-Za-z0-9_]*) |
    (?P<op>->|\.\.|<<|>>|[{}()\[\],;=+\-*/%|&~:?])
""", re.X)


class Tok:
    def __init__(self, kind, value, where):
        self.kind, self.value, self.where = kind, value, where

    def __repr__(self):
        return "%s:%r" % (self.kind, self.value)


_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "0": "\0", "\\": "\\", '"': '"', "'": "'"}


def _unescape(s):
    def repl(m):
        e = m.group(1)
        if e[0] in "ux":
            return chr(int(e[1:], 16))
        return _ESCAPES[e]
    return re.sub(r"\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)", repl, s)


def lex(text, path):
    toks, line, i = [], 1, 0
    while i < len(text):
        m = _TOKEN.match(text, i)
        if not m:
            raise AsmError("%s:%d: bad character %r" % (path, line, text[i]))
        kind = m.lastgroup
        value = m.group()
        where = "%s:%d" % (path, line)
        if kind == "nl":
            toks.append(Tok("nl", None, where))
            line += 1
        elif kind == "str":
            toks.append(Tok("str", _unescape(value[1:-1]), where))
        elif kind == "char":
            toks.append(Tok("num", ord(_unescape(value[1:-1])), where))
        elif kind == "num":
            toks.append(Tok("num", int(value, 0), where))
        elif kind == "cp":
            toks.append(Tok("cp", int(value[2:], 16), where))
        elif kind in ("id", "op"):
            toks.append(Tok(kind, value, where))
        i = m.end()
    toks.append(Tok("nl", None, path))
    toks.append(Tok("eof", None, path))
    return toks


# ---------------------------------------------------------------- parsing

class Stmt:
    def __init__(self, op, args, blocks, where, extra=None):
        self.op, self.args, self.blocks, self.where, self.extra = op, args, blocks, where, extra


class Parser:
    def __init__(self, toks):
        self.toks, self.i = toks, 0

    def peek(self, k=0):
        return self.toks[self.i + k]

    def take(self, kind=None, value=None):
        t = self.toks[self.i]
        if (kind and t.kind != kind) or (value is not None and t.value != value):
            raise AsmError("%s: expected %s %s, got %r" % (t.where, kind or "", value or "", t))
        self.i += 1
        return t

    def skip_nl(self):
        while self.peek().kind == "nl" or (self.peek().kind == "op" and self.peek().value == ";"):
            self.i += 1

    def block(self):
        self.skip_nl()
        self.take("op", "{")
        body = []
        while True:
            self.skip_nl()
            if self.peek().kind == "op" and self.peek().value == "}":
                self.i += 1
                return body
            body.append(self.stmt())

    def program(self):
        items = []
        while True:
            self.skip_nl()
            if self.peek().kind == "eof":
                return items
            items.append(self.stmt())

    def stmt(self):
        t = self.take("id")
        op, where = t.value, t.where
        if op == "macro":
            name = self.take("id").value
            self.take("op", "(")
            params = []
            while not (self.peek().kind == "op" and self.peek().value == ")"):
                params.append(self.take("id").value)
                if self.peek().value == ",":
                    self.i += 1
            self.take("op", ")")
            return Stmt("macro", [name, params], [self.block()], where)
        if op in ("regex", "charset"):
            name = self.take("id").value
            self.take("op", "=")
            return Stmt(op, [name], [], where, self.regex_alt())
        if op == "switch":
            subject = self.expr()
            self.skip_nl()
            self.take("op", "{")
            cases = []
            while True:
                self.skip_nl()
                t = self.peek()
                if t.kind == "op" and t.value == "}":
                    self.i += 1
                    break
                label = self.take("id").value
                if label == "case":
                    values = [self.expr()]
                    while self.peek().value == ",":
                        self.i += 1
                        values.append(self.expr())
                    cases.append((values, self.block()))
                elif label == "default":
                    cases.append((None, self.block()))
                else:
                    raise AsmError("%s: expected case/default" % t.where)
            return Stmt("switch", [subject], [], where, cases)
        # Generic statement: operands up to newline or `{`.
        args = []
        while True:
            t = self.peek()
            if t.kind in ("nl", "eof") or (t.kind == "op" and t.value in "{};"):
                break
            if t.kind == "op" and t.value in (",", "->", "="):
                self.i += 1
                continue
            args.append(self.expr())
        blocks = []
        if self.peek().kind == "op" and self.peek().value == "{":
            blocks.append(self.block())
            save = self.i
            self.skip_nl()
            if self.peek().kind == "id" and self.peek().value == "else":
                self.i += 1
                if self.peek().kind == "id" and self.peek().value in ("if", "ifz", "ifeq", "ifne"):
                    blocks.append([self.stmt()])
                else:
                    blocks.append(self.block())
            else:
                self.i = save
        return Stmt(op, args, blocks, where)

    # Expressions: ("num", v) ("name", n) ("idx", n, expr) ("str", s) ("bin", op, a, b)
    # ("neg", a) ("not", a) ("call", n, args)
    _PREC = [("|",), ("&",), ("<<", ">>"), ("+", "-"), ("*", "/", "%")]

    def expr(self, level=0):
        if level == len(self._PREC):
            return self.unary()
        left = self.expr(level + 1)
        while self.peek().kind == "op" and self.peek().value in self._PREC[level]:
            op = self.take().value
            left = ("bin", op, left, self.expr(level + 1))
        return left

    def unary(self):
        t = self.take()
        if t.kind == "num":
            return ("num", t.value)
        if t.kind == "str":
            return ("str", t.value)
        if t.kind == "op" and t.value == "-":
            return ("neg", self.unary())
        if t.kind == "op" and t.value == "~":
            return ("not", self.unary())
        if t.kind == "op" and t.value == "(":
            e = self.expr()
            self.take("op", ")")
            return e
        if t.kind == "id":
            if self.peek().kind == "op" and self.peek().value == "[":
                self.i += 1
                idx = self.expr()
                self.take("op", "]")
                return ("idx", t.value, idx)
            if self.peek().kind == "op" and self.peek().value == "(":
                self.i += 1
                args = []
                while not (self.peek().kind == "op" and self.peek().value == ")"):
                    args.append(self.expr())
                    if self.peek().value == ",":
                        self.i += 1
                self.take("op", ")")
                return ("call", t.value, args)
            return ("name", t.value)
        raise AsmError("%s: unexpected %r" % (t.where, t))

    # Regex grammar: alt := cat ('|' cat)* ; cat := post+ ; post := atom ('*'|'+'|'?')*
    def regex_alt(self):
        options = [self.regex_cat()]
        while True:
            # A line starting with `|` continues the alternation.
            k = 0
            while self.peek(k).kind == "nl":
                k += 1
            if not (self.peek(k).kind == "op" and self.peek(k).value == "|"):
                break
            self.i += k + 1
            options.append(self.regex_cat())
        return options[0] if len(options) == 1 else ("alt", options)

    def regex_cat(self):
        parts = []
        while True:
            t = self.peek()
            if t.kind == "nl":
                # Newlines are allowed inside parentheses only.
                if self._depth > 0:
                    self.i += 1
                    continue
                break
            if t.kind == "eof" or (t.kind == "op" and t.value in "|),"):
                break
            parts.append(self.regex_post())
        return ("cat", parts)

    _depth = 0

    def regex_post(self):
        atom = self.regex_atom()
        while self.peek().kind == "op" and self.peek().value in "*+?":
            atom = ({"*": "star", "+": "plus", "?": "opt"}[self.take().value], atom)
        return atom

    def regex_atom(self):
        t = self.take()
        if t.kind == "str":
            return ("text", t.value)
        if t.kind in ("num", "cp"):
            lo = t.value
            if self.peek().kind == "op" and self.peek().value == "..":
                self.i += 1
                hi = self.take().value
                return ("range", t.kind, lo, hi)
            return ("range", t.kind, lo, lo)
        if t.kind == "op" and t.value == "(":
            self._depth += 1
            inner = self.regex_alt()
            self._depth -= 1
            self.take("op", ")")
            return inner
        if t.kind == "id":
            if t.value in ("spaced", "word") and self.peek().kind == "op" and self.peek().value == "(":
                self.i += 1
                self._depth += 1
                args = [self.regex_alt()]
                while self.peek().kind == "op" and self.peek().value == ",":
                    self.i += 1
                    args.append(self.regex_alt())
                self._depth -= 1
                self.take("op", ")")
                return ("fn", t.value, args)
            return ("ref", t.value)
        raise AsmError("%s: bad regex token %r" % (t.where, t))


# ---------------------------------------------------------------- emission

class Cell:
    def __init__(self, name, addr, size):
        self.name, self.addr, self.size = name, addr, size

    def byte(self, i):
        if not 0 <= i < self.size:
            raise AsmError("byte %d out of range for %s" % (i, self.name))
        return Cell("%s[%d]" % (self.name, i), self.addr + i, 1)

    def bytes(self):
        return [self.byte(i) for i in range(self.size)]

    def __repr__(self):
        return "Cell(%s@%d:%d)" % (self.name, self.addr, self.size)


class Program:
    """Result of assembling one entry file."""

    def __init__(self, name, code, symbols, persist_size, tape_size, dfas):
        self.name, self.code, self.symbols = name, code, symbols
        self.persist_size, self.tape_size, self.dfas = persist_size, tape_size, dfas


class StrConst(str):
    """A string literal bound to a macro parameter."""


class Assembler:
    def __init__(self, tape_size=4096, include_dirs=(), persist_capacity=None):
        """persist_capacity: size of the persistent region at the start of the
        tape. When None (tests), the region ends at the first scratch cell."""
        self.tape_size = tape_size
        self.persist_capacity = persist_capacity
        self.include_dirs = list(include_dirs)
        self.out = []
        self.pos = 0
        self.consts = {}
        self.macros = {}
        self.regexes = {}
        self.dfas = {}
        self.globals = {}
        self.persist_next = 0
        self.persist_frozen = False
        self.scratch_next = persist_capacity
        self.included = set()
        self.symbols = []
        self.max_used = 0

    # --- low level ------------------------------------------------------

    def goto(self, cell):
        a = cell.addr if isinstance(cell, Cell) else cell
        d = a - self.pos
        self.out.append(">" * d if d > 0 else "<" * -d)
        self.pos = a

    def raw(self, cell, text):
        self.goto(cell)
        self.out.append(text)

    def add(self, cell, n):
        n &= 255
        if n:
            self.raw(cell, "+" * n if n <= 128 else "-" * (256 - n))

    def clear(self, cell):
        for b in cell.bytes():
            self.raw(b, "[-]")

    def setc(self, cell, value):
        for i, b in enumerate(cell.bytes()):
            self.clear(b)
            self.add(b, (value >> (8 * i)) & 255)

    def loop(self, cell, body):
        self.goto(cell)
        start = self.pos
        self.out.append("[")
        body()
        self.goto(start)
        self.out.append("]")

    # --- allocation -----------------------------------------------------

    def alloc(self, name, size):
        if self.scratch_next is None:
            self.persist_frozen = True
            self.scratch_next = self.persist_next
        addr = self.scratch_next
        self.scratch_next += size
        if self.scratch_next > self.tape_size:
            raise AsmError("tape exhausted allocating %s" % name)
        self.max_used = max(self.max_used, self.scratch_next)
        return Cell(name, addr, size)

    def temp(self, size=1):
        return self.alloc("_t", size)

    class _Scope:
        def __init__(self, asm, clear_on_exit):
            self.asm, self.clear_on_exit, self.cells = asm, clear_on_exit, []

        def __enter__(self):
            if self.asm.scratch_next is None:
                self.asm.alloc("_init", 0)
            self.mark = self.asm.scratch_next
            return self

        def __exit__(self, *exc):
            if exc[0] is None:
                for c in self.cells:
                    self.asm.clear(c)
            self.asm.scratch_next = self.mark

    def scope(self, cells_to_clear=True):
        return Assembler._Scope(self, cells_to_clear)

    # --- primitives built from BF ----------------------------------------

    def move(self, src, targets):
        """src -> each (cell, factor); src ends at 0."""
        def body():
            self.add(src, -1)
            for cell, k in targets:
                self.add(cell, k)
        self.loop(src, body)

    def copy(self, src, dst):
        if src.size != dst.size:
            raise AsmError("copy size mismatch %s -> %s" % (src, dst))
        for s, d in zip(src.bytes(), dst.bytes()):
            if s.addr == d.addr:
                continue
            self.clear(d)
            with self.scope():
                t = self.temp()
                self.move(s, [(d, 1), (t, 1)])
                self.move(t, [(s, 1)])

    def flag_nonzero(self, cell, flag):
        """flag := (cell != 0), cell preserved. flag must be zero."""
        with self.scope():
            t = self.temp()
            for b in cell.bytes():
                self.copy(b, t)
                self.loop(t, lambda: (self.setc(flag, 1), self.clear(t)))

    def if_flag(self, flag, then, other=None):
        """Consumes a 0/1 flag cell (left at 0)."""
        if other is None:
            self.loop(flag, lambda: (then(), self.clear(flag)))
            return
        with self.scope():
            e = self.temp()
            self.add(e, 1)
            self.loop(flag, lambda: (self.clear(e), then(), self.clear(flag)))
            self.loop(e, lambda: (other(), self.clear(e)))

    def if_nonzero(self, cell, then, other=None):
        with self.scope():
            f = self.temp()
            if cell.size == 1:
                self.copy(cell, f)
            else:
                self.flag_nonzero(cell, f)
            self.if_flag(f, then, other)

    def diff_flag(self, cell, value, flag):
        """flag := (cell != value)."""
        with self.scope():
            t = self.temp()
            for i, b in enumerate(cell.bytes()):
                self.copy(b, t)
                self.add(t, -((value >> (8 * i)) & 255))
                self.loop(t, lambda: (self.setc(flag, 1), self.clear(t)))

    def if_eq(self, cell, value, then, other=None):
        with self.scope():
            f = self.temp()
            if cell.size == 1:
                self.copy(cell, f)
                self.add(f, -(value & 255))
            else:
                self.diff_flag(cell, value, f)
            # f != 0 means "not equal".
            self.if_flag(f, other or (lambda: None), then) if other else \
                self._if_zero_flag(f, then)

    def _if_zero_flag(self, f, then):
        with self.scope():
            e = self.temp()
            self.add(e, 1)
            self.loop(f, lambda: (self.clear(e), self.clear(f)))
            self.loop(e, lambda: (then(), self.clear(e)))

    def inc(self, cell):
        b = cell.bytes()
        self.add(b[0], 1)
        if len(b) > 1:
            rest = Cell(cell.name, cell.addr + 1, cell.size - 1)
            self.if_eq(b[0], 0, lambda: self.inc(rest))

    def dec(self, cell):
        b = cell.bytes()
        if len(b) > 1:
            rest = Cell(cell.name, cell.addr + 1, cell.size - 1)
            self.if_eq(b[0], 0, lambda: self.dec(rest))
        self.add(b[0], -1)

    def add_var(self, dst, src, sign=1):
        """dst += src (or -=), multi-byte with carry; src preserved."""
        for i, s in enumerate(src.bytes()):
            if i >= dst.size:
                break
            part = Cell(dst.name, dst.addr + i, dst.size - i)
            with self.scope():
                t = self.temp()
                self.copy(s, t)
                self.loop(t, lambda: (self.add(t, -1),
                                      self.inc(part) if sign > 0 else self.dec(part)))

    def compare(self, a, b, gt, lt):
        """gt := a > b, lt := a < b (unsigned, little-endian, equal sizes)."""
        with self.scope():
            decided = self.temp()
            for x, y in reversed(list(zip(a.bytes(), b.bytes()))):
                def byte_compare(x=x, y=y):
                    with self.scope():
                        ta, tb = self.temp(), self.temp()
                        self.copy(x, ta)
                        self.copy(y, tb)

                        def step():
                            self.if_nonzero(tb, lambda: (self.add(tb, -1), self.add(ta, -1)),
                                            lambda: (self.setc(gt, 1), self.setc(decided, 1),
                                                     self.clear(ta)))
                        self.loop(ta, step)
                        self.if_nonzero(tb, lambda: (self.setc(lt, 1), self.setc(decided, 1),
                                                     self.clear(tb)))
                self.if_eq(decided, 0, byte_compare)
            self.clear(decided)

    # --- DFA lowering ----------------------------------------------------

    def run_dfas(self, dfas, flag):
        """Reads a 0-terminated byte stream through every DFA in parallel;
        flag := any of them accepted.

        Bytes are first mapped to equivalence classes (bytes every state treats
        alike), and each state is kept as two nibble cells so a step costs two
        short switches instead of one switch over every state. Reading stops
        early once a DFA reaches its accepting sink, or every DFA is dead."""
        with self.scope():
            states = [(self.temp(), self.temp()) for _ in dfas]
            more, c, cls = self.temp(), self.temp(), self.temp()
            for dfa, (hi, lo) in zip(dfas, states):
                self._set_state(hi, lo, dfa.start)
            self.add(more, 1)

            def step():
                for dfa, (hi, lo) in zip(dfas, states):
                    self._dfa_step(dfa, _byte_classes(dfa), hi, lo, c, cls)
                self.clear(more)
                for dfa, (hi, lo) in zip(dfas, states):
                    self._switch_state(hi, lo, {s: (lambda: self.setc(more, 1))
                                                for s in range(1, dfa.size)})
                for dfa, (hi, lo) in zip(dfas, states):
                    if dfa.accept_sink():
                        self._switch_state(hi, lo, {dfa.accept_sink(): lambda: self.clear(more)})

            def body():
                self.raw(c, ",")
                self.if_nonzero(c, step, lambda: self.clear(more))
            self.loop(more, body)
            self.clear(c)
            for dfa, (hi, lo) in zip(dfas, states):
                self._switch_state(hi, lo, {s: (lambda: self.setc(flag, 1)) for s in dfa.accepting})
                self.clear(hi)
                self.clear(lo)

    def _set_state(self, hi, lo, state):
        self.setc(hi, state >> 4)
        self.setc(lo, state & 15)

    def _switch_state(self, hi, lo, bodies):
        by_hi = {}
        for s, body in bodies.items():
            by_hi.setdefault(s >> 4, {})[s & 15] = body
        self.switch(hi, [([h], lambda inner=inner: self.switch(
            lo, [([l], b) for l, b in sorted(inner.items())], None))
            for h, inner in sorted(by_hi.items())], None)

    def _dfa_step(self, dfa, classes, hi, lo, c, cls):
        byte_class, members, default_class = classes
        self.setc(cls, default_class)
        self.switch(c, [(members[k], lambda k=k: self.setc(cls, k))
                        for k in range(len(members)) if k != default_class], None)
        sink = dfa.accept_sink()

        def row(state):
            return [dfa.trans[state].get(bytes_[0], 0) for bytes_ in members]

        # States of a contains-automaton mostly fall back to the start state's
        # transitions. A state may therefore list only the classes where it
        # differs from the start state and delegate the rest to one shared
        # dispatch, when that is shorter than listing its own default.
        root = row(dfa.start)
        with self.scope():
            handled = self.temp()
            bodies = {}
            for s in range(1, dfa.size):
                if s == sink:
                    continue
                targets = {}
                for k, t in enumerate(row(s)):
                    targets.setdefault(t, []).append(k)
                default = max(targets, key=lambda t: (len(targets[t]), -t))
                own_cost = sum(len(ks) for t, ks in targets.items() if t != default)
                differs = {}
                for k, t in enumerate(row(s)):
                    if t != root[k]:
                        differs.setdefault(t, []).append(k)
                delegate_cost = sum(len(ks) for ks in differs.values())
                if s != dfa.start and delegate_cost < own_cost:
                    bodies[s] = lambda differs=differs: self._delegating_transitions(
                        hi, lo, cls, differs, handled)
                else:
                    bodies[s] = lambda targets=targets: (
                        self._class_transitions(hi, lo, cls, targets), self.setc(handled, 1))
            self._switch_state(hi, lo, bodies)
            root_targets = {}
            for k, t in enumerate(root):
                root_targets.setdefault(t, []).append(k)
            # Dead and accepting-sink states never move.
            self._switch_state(hi, lo, {q: (lambda: self.setc(handled, 1))
                                        for q in ([0] + ([sink] if sink else []))})
            self.if_eq(handled, 0, lambda: self._class_transitions(hi, lo, cls, root_targets))
            self.clear(handled)
        self.clear(cls)

    def _delegating_transitions(self, hi, lo, cls, differs, handled):
        cases = [(ks, lambda t=t: (self._set_state(hi, lo, t), self.setc(handled, 1)))
                 for t, ks in sorted(differs.items())]
        if cases:
            self.switch(cls, cases, None)

    def _class_transitions(self, hi, lo, cls, targets):
        # The target reached by the most classes becomes the default.
        default = max(targets, key=lambda t: (len(targets[t]), -t))
        cases = [(ks, lambda t=t: self._set_state(hi, lo, t))
                 for t, ks in sorted(targets.items()) if t != default]
        if cases:
            self.switch(cls, cases, lambda: self._set_state(hi, lo, default))
        else:
            self._set_state(hi, lo, default)

    # --- switch -----------------------------------------------------------

    def switch(self, subject, cases, default):
        """Exclusive multi-way branch on a snapshot of `subject`."""
        with self.scope():
            snap = self.temp(subject.size)
            self.copy(subject, snap)
            d = None
            if default is not None:
                d = self.temp()
                self.add(d, 1)
            for values, body in cases:
                if values is None:
                    continue
                def run(body=body):
                    if d is not None:
                        self.clear(d)
                    body()
                if len(values) == 1:
                    self.if_eq(snap, values[0], run)
                    continue
                with self.scope():
                    hit = self.temp()
                    for v in values:
                        self.if_eq(snap, v, lambda: self.setc(hit, 1))
                    self.if_flag(hit, run)
            if d is not None:
                self.loop(d, lambda: (default(), self.clear(d)))
            self.clear(snap)

    # --- source level -----------------------------------------------------

    def const_value(self, e, env):
        k = e[0]
        if k == "num":
            return e[1]
        if k == "name":
            v = self.lookup(e[1], env)
            if isinstance(v, int):
                return v
            raise AsmError("%s is not a constant" % e[1])
        if k == "neg":
            return -self.const_value(e[1], env)
        if k == "not":
            return ~self.const_value(e[1], env)
        if k == "bin":
            a, b = self.const_value(e[2], env), self.const_value(e[3], env)
            return {"+": a + b, "-": a - b, "*": a * b, "/": a // b, "%": a % b,
                    "<<": a << b, ">>": a >> b, "|": a | b, "&": a & b}[e[1]]
        if k == "call" and e[1] == "sizeof":
            return self.cell(e[2][0], env).size
        if k == "call" and e[1] == "len":
            return len(rx.encode_text(self.string_value(e[2][0], env)))
        raise AsmError("not a constant expression: %r" % (e,))

    def string_value(self, e, env):
        if e[0] == "str":
            return e[1]
        if e[0] == "name":
            v = self.lookup(e[1], env)
            if isinstance(v, StrConst):
                return v
        raise AsmError("not a string: %r" % (e,))

    def lookup(self, name, env):
        for scope in reversed(env):
            if name in scope:
                return scope[name]
        if name in self.consts:
            return self.consts[name]
        if name in self.globals:
            return self.globals[name]
        raise AsmError("unknown name %s" % name)

    def cell(self, e, env):
        if e[0] == "name":
            v = self.lookup(e[1], env)
            if isinstance(v, Cell):
                return v
        elif e[0] == "idx":
            v = self.lookup(e[1], env)
            if isinstance(v, Cell):
                return v.byte(self.const_value(e[2], env))
        raise AsmError("not a cell: %r" % (e,))

    def is_cell(self, e, env):
        try:
            self.cell(e, env)
            return True
        except AsmError:
            return False

    def declare(self, stmt, env, region):
        cells = []
        args = stmt.args
        i = 0
        while i < len(args):
            name = args[i][1]
            size = 1
            if args[i][0] == "idx":
                size = self.const_value(args[i][2], env)
            if region == "persist":
                if self.persist_capacity is None and self.persist_frozen:
                    raise AsmError("%s: persist after first var" % stmt.where)
                cell = Cell(name, self.persist_next, size)
                self.persist_next += size
                if self.persist_capacity is not None and self.persist_next > self.persist_capacity:
                    raise AsmError("persistent region exceeds %d cells" % self.persist_capacity)
                self.max_used = max(self.max_used, self.persist_next)
            else:
                cell = self.alloc(name, size)
            self.symbols.append((region, name, cell.addr, size))
            cells.append(cell)
            env[-1][name] = cell
            i += 1
        return cells

    def assemble_file(self, path):
        path = os.path.abspath(path)
        with open(path, encoding="utf-8") as f:
            items = Parser(lex(f.read(), _display(path))).program()
        env = [self.globals]
        self.base_dir = os.path.dirname(path)
        self.statements(items, env, top=True, base=os.path.dirname(path))

    def statements(self, items, env, top=False, base=None, scope=None):
        for s in items:
            self.statement(s, env, top, base, scope)

    def statement(self, s, env, top, base, scope):
        op = s.op
        try:
            if op == "include":
                target = os.path.normpath(os.path.join(base, s.args[0][1]))
                if target in self.included:
                    return
                self.included.add(target)
                with open(target, encoding="utf-8") as f:
                    items = Parser(lex(f.read(), _display(target))).program()
                self.statements(items, env, top, os.path.dirname(target), scope)
            elif op == "const":
                name = s.args[0][1]
                if name in self.consts:
                    raise AsmError("duplicate const %s" % name)
                self.consts[name] = self.const_value(s.args[1], env)
            elif op == "persist":
                self.declare(s, env, "persist")
            elif op == "var":
                cells = self.declare(s, env, "var")
                if scope is not None:
                    scope.cells.extend(cells)
            elif op == "macro":
                self.macros[s.args[0]] = (s.args[1], s.blocks[0])
            elif op == "regex":
                self.regexes[s.args[0]] = s.extra
            elif op == "charset":
                self.regexes[s.args[0]] = s.extra
            elif op == "static_assert":
                if not self.const_value(s.args[0], env):
                    raise AsmError("static_assert failed: %s" % (s.args[1][1] if len(s.args) > 1 else ""))
            else:
                self.instruction(s, env, base)
        except AsmError as e:
            if str(e).startswith(s.where):
                raise
            raise AsmError("%s: %s" % (s.where, e)) from None

    def block(self, body, env, base):
        def run():
            with self.scope() as sc:
                self.statements(body, env + [{}], base=base, scope=sc)
        return run

    def instruction(self, s, env, base):
        op, a = s.op, s.args
        C = lambda i: self.cell(a[i], env)          # noqa: E731
        K = lambda i: self.const_value(a[i], env)   # noqa: E731
        then = self.block(s.blocks[0], env, base) if s.blocks else None
        other = self.block(s.blocks[1], env, base) if len(s.blocks) > 1 else None
        if op == "clear":
            for i in range(len(a)):
                self.clear(C(i))
        elif op == "set":
            self.setc(C(0), K(1))
        elif op == "add":
            self.add(C(0), K(1))
        elif op == "sub":
            self.add(C(0), -K(1))
        elif op == "inc":
            self.inc(C(0))
        elif op == "dec":
            self.dec(C(0))
        elif op == "move":
            src = C(0)
            self.move(src, [(C(i), 1) for i in range(1, len(a))])
        elif op == "copy":
            self.copy(C(0), C(1))
        elif op == "addv":
            self.add_var(C(0), C(1), 1)
        elif op == "subv":
            self.add_var(C(0), C(1), -1)
        elif op == "in":
            for i in range(len(a)):
                for b in C(i).bytes():
                    self.raw(b, ",")
        elif op == "out":
            for i in range(len(a)):
                if self.is_cell(a[i], env):
                    for b in C(i).bytes():
                        self.raw(b, ".")
                else:
                    self.emit_const(K(i), 1)
        elif op in ("outc", "outc16", "outc32"):
            width = {"outc": 1, "outc16": 2, "outc32": 4}[op]
            for i in range(len(a)):
                self.emit_const(K(i), width)
        elif op in ("cap", "call"):
            # cap OP, args...           : capability request frame
            # call PROGRAM, OP, args... : CAP_CALL frame running PROGRAM
            # Lengths are computed from the operands: a cell sends its bytes,
            # a constant one byte.
            first = 1 if op == "cap" else 2
            sizes = [C(i).size if self.is_cell(a[i], env) else 1 for i in range(first, len(a))]
            args = sum(sizes)
            header_op = K(0) if op == "cap" else self.consts["CAP_CALL"]
            payload = args if op == "cap" else 4 + args
            if op == "call" and args > 255:
                raise AsmError("call arguments exceed 255 bytes")
            self.emit_const(self.consts["ABI_MAJOR"], 1)
            self.emit_const(self.consts["ABI_MINOR"], 1)
            self.emit_const(header_op, 1)
            self.emit_const(0, 1)
            self.emit_const(payload, 2)
            self.emit_const(0, 2)
            if op == "call":
                self.emit_const(K(0), 1)
                self.emit_const(K(1), 1)
                self.emit_const(0, 1)
                self.emit_const(args, 1)
            for i in range(first, len(a)):
                if self.is_cell(a[i], env):
                    for b in C(i).bytes():
                        self.raw(b, ".")
                else:
                    self.emit_const(K(i), 1)
        elif op == "outs":
            # outs "text": the modified UTF-8 bytes, no terminator.
            self.emit_bytes(rx.encode_text(self.string_value(a[0], env)))
        elif op == "unpack":
            # unpack x -> b0, b1, ... : bk := bit k of x (x preserved, x < 2**n).
            x = C(0)
            bits = [C(i) for i in range(1, len(a))]
            for b in bits:
                self.clear(b)

            def setter(value):
                return lambda: [self.add(b, 1) for k, b in enumerate(bits) if value >> k & 1]
            self.switch(x, [([v], setter(v)) for v in range(1, 1 << len(bits))], None)
        elif op == "pack":
            # pack b0, b1, ... -> x : x := sum of (bk != 0) << k.
            x = C(len(a) - 1)
            self.clear(x)
            for k in range(len(a) - 1):
                self.if_nonzero(C(k), lambda k=k: self.add(x, 1 << k))
        elif op == "drop":
            with self.scope():
                t = self.temp()
                for _ in range(K(0)):
                    self.raw(t, ",")
                self.clear(t)
        elif op == "if":
            self.if_nonzero(C(0), then, other)
        elif op == "ifz":
            self.if_nonzero(C(0), other or (lambda: None), then) if other else \
                self.if_eq(C(0), 0, then)
        elif op == "ifeq":
            self.if_eq(C(0), K(1), then, other)
        elif op == "ifne":
            self.if_eq(C(0), K(1), other or (lambda: None), then) if other else \
                self.if_eq(C(0), K(1), lambda: None, then)
        elif op == "while":
            cell = C(0)
            if cell.size != 1:
                raise AsmError("while needs a single-byte cell")
            self.loop(cell, then)
        elif op == "repeat":
            with self.scope():
                t = self.temp()
                self.setc(t, K(0))
                self.loop(t, lambda: (self.add(t, -1), then()))
        elif op in ("gt", "lt", "ge", "le", "eq", "ne"):
            self.compare_op(op, a, env)
        elif op == "not":
            src, dst = C(0), C(1)
            self.clear(dst)
            self.if_eq(src, 0, lambda: self.add(dst, 1))
        elif op == "match":
            # match A[, B...] -> flag : flag := any regex accepted the stream.
            self.run_dfas([self.dfa(e[1]) for e in a[:-1]], C(len(a) - 1))
        elif op == "switch":
            subject = self.cell(s.args[0], env)
            cases = []
            default = None
            for values, body in s.extra:
                fn = self.block(body, env, base)
                if values is None:
                    default = fn
                else:
                    cases.append(([self.const_value(v, env) for v in values], fn))
            self.switch(subject, cases, default)
        elif op in self.macros:
            params, body = self.macros[op]
            if len(params) != len(a):
                raise AsmError("macro %s expects %d arguments" % (op, len(params)))
            bound = {}
            for p, e in zip(params, a):
                if e[0] == "str":
                    bound[p] = StrConst(e[1])
                elif self.is_cell(e, env):
                    bound[p] = self.cell(e, env)
                elif e[0] == "name" and isinstance(self.lookup(e[1], env), StrConst):
                    bound[p] = self.lookup(e[1], env)
                else:
                    bound[p] = self.const_value(e, env)
            with self.scope() as sc:
                self.statements(body, [self.globals, bound, {}], base=base, scope=sc)
        else:
            raise AsmError("unknown instruction %s" % op)

    def emit_bytes(self, data):
        # Each byte is reached from the previous one by the shorter direction.
        with self.scope():
            t = self.temp()
            previous = 0
            for byte in data:
                self.add(t, byte - previous)
                self.raw(t, ".")
                previous = byte
            self.clear(t)

    def emit_const(self, value, width):
        with self.scope():
            t = self.temp()
            for i in range(width):
                self.setc(t, (value >> (8 * i)) & 255)
                self.raw(t, ".")
            self.clear(t)

    def compare_op(self, op, a, env):
        # `gt x, y -> flag` where y is a cell or a constant; flag := x op y.
        x = self.cell(a[0], env)
        flag = self.cell(a[2], env)
        with self.scope():
            if self.is_cell(a[1], env):
                y = self.cell(a[1], env)
            else:
                y = self.temp(x.size)
                self.setc(y, self.const_value(a[1], env))
            if y.size != x.size:
                raise AsmError("compare size mismatch")
            gt, lt = self.temp(), self.temp()
            self.compare(x, y, gt, lt)
            self.clear(flag)
            if op == "gt":
                self.move(gt, [(flag, 1)])
            elif op == "lt":
                self.move(lt, [(flag, 1)])
            elif op == "ge":
                self.add(flag, 1)
                self.move(lt, [(flag, -1)])
            elif op == "le":
                self.add(flag, 1)
                self.move(gt, [(flag, -1)])
            elif op == "eq":
                self.add(flag, 1)
                self.move(gt, [(flag, -1)])
                self.move(lt, [(flag, -1)])
            elif op == "ne":
                self.move(gt, [(flag, 1)])
                self.move(lt, [(flag, 1)])
            self.clear(gt)
            self.clear(lt)
            if not self.is_cell(a[1], env):
                self.clear(y)

    # --- regex resolution ---------------------------------------------------

    def dfa(self, name):
        if name not in self.dfas:
            self.dfas[name] = rx.compile_regex(self.regex_node(("ref", name)))
        return self.dfas[name]

    def regex_node(self, e):
        k = e[0]
        if k == "ref":
            if e[1] == "ANY":
                return rx.any_byte()
            if e[1] not in self.regexes:
                raise AsmError("unknown regex %s" % e[1])
            return self.regex_node(self.regexes[e[1]])
        if k == "text":
            return rx.Lit(rx.encode_text(e[1]))
        if k == "range":
            _, kind, lo, hi = e
            if kind == "num":
                return rx.Bytes(range(lo, hi + 1))
            return rx.Alt([rx.Lit(rx.encode_code_point(cp)) for cp in range(lo, hi + 1)])
        if k == "cat":
            return rx.Cat([self.regex_node(p) for p in e[1]])
        if k == "alt":
            return rx.Alt([self.regex_node(p) for p in e[1]])
        if k == "star":
            return rx.Star(self.regex_node(e[1]))
        if k == "plus":
            return rx.plus(self.regex_node(e[1]))
        if k == "opt":
            return rx.opt(self.regex_node(e[1]))
        if k == "fn":
            return self.regex_fn(e[1], e[2])
        raise AsmError("bad regex node %r" % (e,))

    def regex_fn(self, name, args):
        def text_of(arg):
            if arg[0] == "cat" and len(arg[1]) == 1 and arg[1][0][0] == "text":
                return arg[1][0][1]
            raise AsmError("%s expects a string literal first" % name)

        if name == "spaced":
            # spaced("abc", R): R* between every pair of characters.
            text, sep = text_of(args[0]), rx.Star(self.regex_node(args[1]))
            parts = []
            for i, ch in enumerate(text):
                if i:
                    parts.append(sep)
                parts.append(rx.Lit(rx.encode_text(ch)))
            return rx.Cat(parts)
        if name == "word":
            # word("a b", R): every space becomes one or more R.
            text, sep = text_of(args[0]), rx.plus(self.regex_node(args[1]))
            parts = []
            for i, chunk in enumerate(text.split(" ")):
                if i:
                    parts.append(sep)
                parts.append(rx.Lit(rx.encode_text(chunk)))
            return rx.Cat(parts)
        raise AsmError("unknown regex function %s" % name)


def _byte_classes(dfa):
    """Groups bytes 1..255 that every state maps identically.
    Returns (class of each byte, member bytes per class, largest class)."""
    ids = {}
    members = []
    byte_class = {}
    for b in range(1, 256):
        signature = tuple(dfa.trans[s].get(b, 0) for s in range(dfa.size))
        if signature not in ids:
            ids[signature] = len(members)
            members.append([])
        members[ids[signature]].append(b)
        byte_class[b] = ids[signature]
    default = max(range(len(members)), key=lambda k: (len(members[k]), -k))
    return byte_class, members, default


def assemble(path, tape_size=4096, persist_capacity=None):
    asm = Assembler(tape_size, persist_capacity=persist_capacity)
    asm.assemble_file(path)
    code = "".join(asm.out)
    name = os.path.splitext(os.path.basename(path))[0]
    return Program(name, code, asm.symbols, asm.persist_next, tape_size,
                   {k: v.size for k, v in asm.dfas.items()}), asm
