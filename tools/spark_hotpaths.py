#!/usr/bin/env python3
"""Summarise a spark .sparkprofile (sampler, uncompressed protobuf) without spark's viewer or protobuf libs.

Usage:
    python tools/spark_hotpaths.py FILE [--thread "Server thread"] [--top 30] [--grep tacz_sewv]
    python tools/spark_hotpaths.py BEFORE AFTER            # side-by-side busy-share diff of the top methods

Reports, for the chosen thread:
  * busy ms/tick: sampled time minus Unsafe.park (idle between ticks), per spark window and overall.
    Tick count comes from the profile metadata; windows are spark's 60 s buckets.
  * self time by mod, top self methods, top inclusive methods (recursion counted once),
  * sewv entry-point blame: every non-idle sample attributed to the OUTERMOST tacz_sewv frame above it,
    i.e. what our code costs including the vanilla/other-mod work it calls.
All shares are of BUSY time, so idle servers and busy servers compare fairly.

Field numbers were read off spark 1.10 profiles (SamplerData: 1=metadata, 2=threads, 3=class sources;
ThreadNode: 1=name, 3=children(nodes), 4=times, 5=root child refs; StackTraceNode: 3=class, 4=method,
8=packed per-window times, 9=packed child refs). If a spark update changes them this fails loudly.
"""
import argparse
import collections
import struct
import sys


def _varint(b, i):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        if not c & 0x80:
            return r, i
        s += 7


def _parse(b):
    i, out, n = 0, [], len(b)
    while i < n:
        k, i = _varint(b, i)
        f, w = k >> 3, k & 7
        if w == 0:
            v, i = _varint(b, i)
        elif w == 1:
            v, i = b[i:i + 8], i + 8
        elif w == 2:
            ln, i = _varint(b, i)
            v, i = b[i:i + ln], i + ln
        elif w == 5:
            v, i = b[i:i + 4], i + 4
        else:
            raise ValueError("unsupported wire type %d (not an uncompressed spark sampler profile?)" % w)
        out.append((f, w, v))
    return out


def _doubles(b):
    return list(struct.unpack('<%dd' % (len(b) // 8), b))


def _varints(b):
    out, i = [], 0
    while i < len(b):
        v, i = _varint(b, i)
        out.append(v)
    return out


class Profile:
    def __init__(self, path, thread_name):
        data = open(path, 'rb').read()
        top = _parse(data)
        self.ticks = None
        for f, w, v in top:
            if f == 1:
                for ff, ww, vv in _parse(v):
                    if ff == 12 and ww == 0:
                        self.ticks = vv
        self.src = {}
        for f, w, v in top:
            if f == 3 and w == 2:
                d = {ff: vv for ff, ww, vv in _parse(v)}
                self.src[d.get(1, b'').decode()] = d.get(2, b'').decode()
        threads = [v for f, w, v in top if f == 2]
        chosen = None
        for t in threads:
            p = _parse(t)
            name = next((v.decode() for f, w, v in p if f == 1), '')
            if name == thread_name:
                chosen = p
        if chosen is None:
            names = [next((v.decode() for f, w, v in _parse(t) if f == 1), '?') for t in threads]
            sys.exit("thread %r not in profile; threads: %s" % (thread_name, names))
        raw = [v for f, w, v in chosen if f == 3]
        self.window_totals = _doubles(next(v for f, w, v in chosen if f == 4))
        self.roots = _varints(next(v for f, w, v in chosen if f == 5))
        n = len(raw)
        W = len(self.window_totals)
        self.cls, self.meth = [''] * n, [''] * n
        self.tw, self.kids = [[0.0] * W for _ in range(n)], [[] for _ in range(n)]
        for i, node in enumerate(raw):
            for f, w, v in _parse(node):
                if f == 3:
                    self.cls[i] = v.decode()
                elif f == 4:
                    self.meth[i] = v.decode()
                elif f == 8:
                    self.tw[i] = _doubles(v)
                elif f == 9:
                    self.kids[i] = _varints(v)
        self.tot = [sum(x) for x in self.tw]
        self.selft = [max(0.0, self.tot[i] - sum(self.tot[k] for k in self.kids[i])) for i in range(n)]
        park = [0.0] * W
        for i in range(n):
            if self.cls[i] == 'jdk.internal.misc.Unsafe' and self.meth[i] == 'park':
                for j in range(W):
                    park[j] += self.tw[i][j] - sum(self.tw[k][j] for k in self.kids[i])
        self.busy_w = [self.window_totals[j] - park[j] for j in range(W)]
        self.busy = sum(self.busy_w)
        self._aggregate()

    def mod(self, c):
        if c in self.src:
            return self.src[c]
        for pre, name in (('com.neoalive.tacz_sewv', 'tacz_sewv'), ('net.nekoyuni', 'simpleenemymod'),
                          ('com.atsuishio', 'superbwarfare'), ('com.tacz', 'tacz'),
                          ('net.minecraft', 'minecraft/forge'), ('java.', 'jvm'), ('jdk.', 'jvm'), ('sun.', 'jvm')):
            if c.startswith(pre):
                return name
        return 'other:' + '.'.join(c.split('.')[:2])

    def _aggregate(self):
        sys.setrecursionlimit(1_000_000)
        self.incl = collections.Counter()
        self.selfm = collections.Counter()
        self.selfmod = collections.Counter()
        self.entry = collections.Counter()
        stack = [(r, frozenset(), None) for r in self.roots]
        while stack:
            i, seen, outer = stack.pop()
            key = self.cls[i] + '.' + self.meth[i]
            if key not in seen:
                self.incl[key] += self.tot[i]
            idle = self.cls[i] == 'jdk.internal.misc.Unsafe' and self.meth[i] == 'park'
            if not idle:
                self.selfm[key] += self.selft[i]
                self.selfmod[self.mod(self.cls[i])] += self.selft[i]
            if outer is None and 'neoalive.tacz_sewv' in self.cls[i]:
                outer = key
            if outer is not None and not idle:
                self.entry[outer] += self.selft[i]
            s2 = seen | {key}
            for k in self.kids[i]:
                stack.append((k, s2, outer))

    def pct(self, x):
        return 100.0 * x / self.busy if self.busy else 0.0


def report(p, top, grep):
    print("windows: %d   busy ms per window: %s" % (len(p.busy_w), [round(x) for x in p.busy_w]))
    for j, (t, b) in enumerate(zip(p.window_totals, p.busy_w)):
        print("  window %d: sampled %.0f ms, busy %.0f ms (%.0f%%)" % (j, t, b, 100 * b / t if t else 0))
    if p.ticks:
        print("ticks: %d   mean busy ms/tick: %.2f   (1%% of busy = %.3f ms/tick)"
              % (p.ticks, p.busy / p.ticks, p.busy / p.ticks / 100))

    def section(title, counter, filt=lambda k: True):
        print("\n== %s ==" % title)
        n = 0
        for k, v in counter.most_common():
            if not filt(k):
                continue
            print("%6.2f%%  %9.0f ms  %s" % (p.pct(v), v, k))
            n += 1
            if n >= top:
                break

    section("self time by mod", p.selfmod)
    section("top self methods (idle excluded)", p.selfm)
    g = grep or 'neoalive.tacz_sewv'
    section("top inclusive methods matching %r" % g, p.incl, lambda k: g in k)
    if not grep:
        section("tacz_sewv entry points (outermost sewv frame, incl. vanilla it calls)", p.entry)


def diff(a, b, top, grep):
    print("busy ms/tick: before %.2f  after %.2f" % (a.busy / (a.ticks or 1), b.busy / (b.ticks or 1)))
    keys = [k for k, _ in a.incl.most_common() if (grep or 'neoalive.tacz_sewv') in k][:top]
    print("\n%-8s %-8s %-8s  %s" % ("before", "after", "delta", "inclusive % of busy"))
    for k in keys:
        x, y = a.pct(a.incl[k]), b.pct(b.incl.get(k, 0.0))
        print("%6.2f%%  %6.2f%%  %+6.2f   %s" % (x, y, y - x, k))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('files', nargs='+')
    ap.add_argument('--thread', default='Server thread')
    ap.add_argument('--top', type=int, default=30)
    ap.add_argument('--grep', default=None, help='substring filter for the inclusive table')
    args = ap.parse_args()
    if len(args.files) == 1:
        report(Profile(args.files[0], args.thread), args.top, args.grep)
    elif len(args.files) == 2:
        diff(Profile(args.files[0], args.thread), Profile(args.files[1], args.thread), args.top, args.grep)
    else:
        sys.exit("give one profile, or two to diff")


if __name__ == '__main__':
    main()
