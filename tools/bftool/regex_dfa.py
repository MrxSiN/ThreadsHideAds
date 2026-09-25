"""Byte-level regular expressions compiled to minimal DFAs.

The `.bfa` assembler uses this module to lower `regex` declarations into
Brainfuck state machines. Everything here is deterministic: the same regex
always yields the same numbered DFA, which keeps generated `.bf` reproducible.

The alphabet is bytes 1..255. Byte 0 is reserved as the stream terminator of
the text ABI and never appears inside a modified-UTF-8 string.
"""

ALPHABET = range(1, 256)


# ---------------------------------------------------------------- regex AST

class Node:
    pass


class Lit(Node):
    def __init__(self, data):
        self.data = bytes(data)


class Bytes(Node):
    """One byte out of a set."""

    def __init__(self, values):
        self.values = frozenset(v for v in values if 1 <= v <= 255)


class Cat(Node):
    def __init__(self, parts):
        self.parts = list(parts)


class Alt(Node):
    def __init__(self, options):
        self.options = list(options)


class Star(Node):
    def __init__(self, inner):
        self.inner = inner


def plus(node):
    return Cat([node, Star(node)])


def opt(node):
    return Alt([node, Cat([])])


def any_byte():
    return Bytes(ALPHABET)


def encode_code_point(cp):
    """Java modified UTF-8 of one BMP code point (U+0000 is C0 80)."""
    if cp == 0:
        return b"\xc0\x80"
    if cp > 0xFFFF:
        raise ValueError("supplementary code points are not supported: %x" % cp)
    return chr(cp).encode("utf-8", "surrogatepass")


def encode_text(text):
    return b"".join(encode_code_point(ord(c)) for c in text)


# ---------------------------------------------------------------- NFA

class _Nfa:
    def __init__(self):
        self.eps = []
        self.edges = []  # list of dict byte -> set(state)

    def state(self):
        self.eps.append(set())
        self.edges.append({})
        return len(self.eps) - 1

    def build(self, node):
        """Returns (start, end) for node."""
        if isinstance(node, Lit):
            start = self.state()
            cur = start
            for b in node.data:
                nxt = self.state()
                self.edges[cur].setdefault(b, set()).add(nxt)
                cur = nxt
            return start, cur
        if isinstance(node, Bytes):
            start, end = self.state(), self.state()
            for b in node.values:
                self.edges[start].setdefault(b, set()).add(end)
            return start, end
        if isinstance(node, Cat):
            start = self.state()
            cur = start
            for part in node.parts:
                s, e = self.build(part)
                self.eps[cur].add(s)
                cur = e
            return start, cur
        if isinstance(node, Alt):
            start, end = self.state(), self.state()
            for option in node.options:
                s, e = self.build(option)
                self.eps[start].add(s)
                self.eps[e].add(end)
            return start, end
        if isinstance(node, Star):
            start, end = self.state(), self.state()
            s, e = self.build(node.inner)
            self.eps[start].update((s, end))
            self.eps[e].update((s, end))
            return start, end
        raise TypeError(node)

    def closure(self, states):
        stack = list(states)
        seen = set(states)
        while stack:
            s = stack.pop()
            for t in self.eps[s]:
                if t not in seen:
                    seen.add(t)
                    stack.append(t)
        return frozenset(seen)


# ---------------------------------------------------------------- DFA

class Dfa:
    """Minimal DFA with canonical numbering.

    State 0 is always the non-accepting dead sink. State 1 is the accepting
    sink when one exists. `start` is the initial state.
    """

    def __init__(self, trans, accepting, start):
        self.trans = trans          # list of dict byte -> target (missing = 0)
        self.accepting = accepting  # set of states
        self.start = start

    @property
    def size(self):
        return len(self.trans)

    def step(self, state, byte):
        return self.trans[state].get(byte, 0)

    def accept_sink(self):
        return 1 if self.size > 1 and self._is_accept_sink(1) else None

    def _is_accept_sink(self, s):
        return s in self.accepting and all(
            self.trans[s].get(b, 0) == s for b in ALPHABET)

    def matches(self, data):
        s = self.start
        for b in data:
            s = self.step(s, b)
        return s in self.accepting


def compile_regex(node):
    nfa = _Nfa()
    start, end = nfa.build(node)

    # Subset construction.
    first = nfa.closure({start})
    index = {first: 0}
    order = [first]
    raw = []
    i = 0
    while i < len(order):
        subset = order[i]
        moves = {}
        for s in subset:
            for b, targets in nfa.edges[s].items():
                moves.setdefault(b, set()).update(targets)
        row = {}
        for b in sorted(moves):
            target = nfa.closure(moves[b])
            if target not in index:
                index[target] = len(order)
                order.append(target)
            row[b] = index[target]
        raw.append(row)
        i += 1
    accepting = {i for i, subset in enumerate(order) if end in subset}
    return _minimize(raw, accepting, 0)


def _minimize(raw, accepting, start):
    # Add an explicit dead state so every transition is total.
    dead = len(raw)
    rows = [dict(r) for r in raw] + [{}]
    n = len(rows)

    def target(s, b):
        return rows[s].get(b, dead)

    # Moore partition refinement.
    block = [1 if s in accepting else 0 for s in range(n)]
    while True:
        signatures = {}
        new_block = []
        for s in range(n):
            sig = (block[s], tuple(block[target(s, b)] for b in ALPHABET))
            new_block.append(signatures.setdefault(sig, len(signatures)))
        if len(signatures) == len(set(block)):
            block = new_block
            break
        block = new_block

    # Canonical numbering: dead block 0, accepting sink 1, then BFS from start.
    dead_block = block[dead]
    blocks = {}
    for s in range(n):
        blocks.setdefault(block[s], s)

    def is_accept_sink(bk):
        rep = blocks[bk]
        return rep in accepting and all(block[target(rep, b)] == bk for b in ALPHABET)

    numbering = {dead_block: 0}
    sink = [bk for bk in sorted(blocks) if is_accept_sink(bk)]
    if sink:
        numbering[sink[0]] = 1
    queue = [block[start]]
    seen = set(queue)
    while queue:
        bk = queue.pop(0)
        if bk not in numbering:
            numbering[bk] = len(numbering)
        rep = blocks[bk]
        for b in ALPHABET:
            t = block[target(rep, b)]
            if t not in seen:
                seen.add(t)
                queue.append(t)

    size = len(numbering)
    trans = [dict() for _ in range(size)]
    acc = set()
    for bk, num in numbering.items():
        rep = blocks[bk]
        if rep in accepting:
            acc.add(num)
        for b in ALPHABET:
            t = numbering.get(block[target(rep, b)], 0)
            if t != 0:
                trans[num][b] = t
    return Dfa(trans, acc, numbering[block[start]])
