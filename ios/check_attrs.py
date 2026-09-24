#!/usr/bin/env python3
"""Find attributes duplicated on one declaration across intervening comments.

Swift allows comments between an attribute and the declaration it modifies,
which is how a stale `@discardableResult` left above a freshly written doc
comment survives a visual scan -- and it compiles as "Duplicate attribute",
a message that names no line in the editor's jump bar.

An attribute line that also carries a declaration keyword is self-contained
(`@State private var x = 1`) and closes the group; a bare attribute line
stays pending until a declaration arrives.
"""
import glob, re, sys

ATTR = re.compile(r'@[A-Za-z_][A-Za-z0-9_]*')
DECL = re.compile(r'\b(func|var|let|class|struct|enum|actor|init|subscript|extension|protocol|case|typealias)\b')

def check(path):
    errs, pending = [], []
    for i, raw in enumerate(open(path, encoding='utf-8'), 1):
        line = raw.split('//')[0]
        s = line.strip()
        if not s:
            continue
        starts_attr = s.startswith('@')
        has_decl = bool(DECL.search(line))

        if starts_attr:
            pending += [(a, i) for a in ATTR.findall(line)]
            if not has_decl:
                continue          # attribute alone -- keep waiting
        elif not has_decl:
            if s.startswith(('///', '/*', '*', '#if', '#else', '#endif')):
                continue          # comments and guards do not break the pairing
            pending = []
            continue

        seen = {}
        for name, ln in pending:
            if name in seen:
                errs.append(f"{path}:{ln}: duplicate {name} (first at line {seen[name]}) on the declaration at line {i}")
            else:
                seen[name] = ln
        pending = []
    return errs

roots = sys.argv[1:] or ['.']
files = []
for r in roots:
    files += glob.glob(f'{r}/**/*.swift', recursive=True)
bad = 0
for f in sorted(set(files)):
    for e in check(f):
        print(e); bad += 1
print(f"\n{len(set(files))} files checked, {bad} duplicate-attribute problem(s).")
sys.exit(1 if bad else 0)
