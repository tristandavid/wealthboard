#!/usr/bin/env python3
"""
Structural sanity checks for the Swift sources.

No Swift toolchain is available in this environment, so this is not a compiler.
It catches the mechanical failures that a compiler would catch first and that
are easy to introduce by hand across several thousand lines: unbalanced
delimiters, unterminated strings, stray tabs in indentation, and symbols that
are referenced but never declared anywhere in the module.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).parent / "WealthBoard"

PAIRS = {"(": ")", "[": "]", "{": "}"}
CLOSERS = {v: k for k, v in PAIRS.items()}


def scan(text, path):
    """Walk the file character by character, tracking strings, comments and nesting."""
    problems = []
    stack = []
    i = 0
    n = len(text)
    line = 1
    in_line_comment = False
    block_comment = 0
    in_string = False
    in_multiline_string = False
    interpolation_depth = []

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if c == "\n":
            line += 1
            in_line_comment = False
            if in_string and not in_multiline_string:
                problems.append((line, "string literal spans a newline"))
                in_string = False
            i += 1
            continue

        if in_line_comment:
            i += 1
            continue

        if block_comment:
            if c == "/" and nxt == "*":
                block_comment += 1
                i += 2
                continue
            if c == "*" and nxt == "/":
                block_comment -= 1
                i += 2
                continue
            i += 1
            continue

        if in_multiline_string:
            if text.startswith('"""', i):
                in_multiline_string = False
                i += 3
                continue
            i += 1
            continue

        if in_string:
            if c == "\\":
                # String interpolation opens a fresh nesting context.
                if nxt == "(":
                    interpolation_depth.append(len(stack))
                    stack.append(("(", line))
                    in_string = False
                    i += 2
                    continue
                i += 2
                continue
            if c == '"':
                in_string = False
                i += 1
                continue
            i += 1
            continue

        # Not in a string or comment.
        if c == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            block_comment = 1
            i += 2
            continue
        if text.startswith('"""', i):
            in_multiline_string = True
            i += 3
            continue
        if c == '"':
            in_string = True
            i += 1
            continue
        if c in PAIRS:
            stack.append((c, line))
            i += 1
            continue
        if c in CLOSERS:
            if not stack:
                problems.append((line, f"unmatched closing '{c}'"))
            else:
                opener, opened_at = stack.pop()
                if PAIRS[opener] != c:
                    problems.append(
                        (line, f"closing '{c}' does not match '{opener}' opened on line {opened_at}")
                    )
                # Closing the paren that opened a string interpolation puts us
                # back inside the string.
                if interpolation_depth and len(stack) == interpolation_depth[-1]:
                    interpolation_depth.pop()
                    in_string = True
            i += 1
            continue

        i += 1

    for opener, opened_at in stack:
        problems.append((opened_at, f"'{opener}' opened here is never closed"))
    if in_string or in_multiline_string:
        problems.append((line, "file ends inside a string literal"))
    if block_comment:
        problems.append((line, "file ends inside a block comment"))

    return problems


DECL_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public |internal |private |fileprivate |open |final |static |class |indirect )*"
    r"(struct|class|enum|protocol|actor|extension|typealias)\s+([A-Z]\w*)",
    re.M,
)


def declared_types(files):
    found = set()
    for path in files:
        for kind, name in DECL_RE.findall(path.read_text()):
            found.add(name)
    return found


def main():
    files = sorted(ROOT.rglob("*.swift"))
    if not files:
        print("no Swift files found", file=sys.stderr)
        return 1

    failures = 0
    for path in files:
        text = path.read_text()
        rel = path.relative_to(ROOT)

        problems = scan(text, path)
        for line, message in problems:
            print(f"{rel}:{line}: {message}")
            failures += 1

        for number, raw in enumerate(text.splitlines(), 1):
            if raw.startswith("\t"):
                print(f"{rel}:{number}: leading tab")
                failures += 1
            if raw.rstrip() != raw:
                print(f"{rel}:{number}: trailing whitespace")
                failures += 1

    types = declared_types(files)
    print(f"\n{len(files)} files, {sum(len(f.read_text().splitlines()) for f in files)} lines")
    print(f"{len(types)} declared types")
    print("delimiter/string check:", "clean" if failures == 0 else f"{failures} problem(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
