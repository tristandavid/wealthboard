#!/usr/bin/env python3
"""Two mechanical Swift errors this project has actually shipped.

Both compile-fail with messages that do not name the construct, which is why
they are worth a script rather than a note:

  * a key path to a tuple element (`map(\\.1)`) -- "Cannot infer key path type
    from context", which reads like a type-inference problem anywhere in the
    expression;
  * a call to a member the type does not declare, checked by diffing every
    `repository.x` call site against PortfolioRepository's declarations --
    this project shipped fifteen of those at once, and each one surfaces as its
    own "has no member" error far from the cause.
"""
import glob, re, sys

def tuple_keypaths():
    out = []
    for p in sorted(glob.glob('WealthBoard/**/*.swift', recursive=True)):
        for i, raw in enumerate(open(p, encoding='utf-8'), 1):
            if re.search(r'\\\.\d', raw.split('//')[0]):
                out.append(f"{p}:{i}: key path to a tuple element -- use {{ $0.N }}")
    return out

def repository_members():
    src = {p: open(p, encoding='utf-8').read()
           for p in glob.glob('WealthBoard/**/*.swift', recursive=True)}
    repo = 'WealthBoard/Data/PortfolioRepository.swift'
    if repo not in src:
        return []
    # Modifiers matter here: `@Published private(set) var accounts` is a
    # declaration, and a pattern that misses `private(set)` reports every
    # reader of it as unresolved -- forty false positives that bury the one
    # real finding.
    mod = (r'(?:@\w+(?:\([^)]*\))?\s+|private\(set\)\s+|public\(set\)\s+'
           r'|private\s+|public\s+|internal\s+|fileprivate\s+|static\s+'
           r'|nonisolated\s+|final\s+|lazy\s+|weak\s+|override\s+)*')
    declared = set(re.findall(rf'^\s*{mod}(?:func|var|let)\s+([A-Za-z_]\w*)',
                              src[repo], re.M))
    out = []
    for p, text in src.items():
        for m in re.finditer(r'repository\.([A-Za-z_]\w*)', text):
            if m.group(1) not in declared:
                line = text[:m.start()].count('\n') + 1
                out.append(f"{p}:{line}: PortfolioRepository has no member '{m.group(1)}'")
    return sorted(set(out))

errs = tuple_keypaths() + repository_members()
for e in errs:
    print(e)
print(f"\n{len(errs)} problem(s).")
sys.exit(1 if errs else 0)
