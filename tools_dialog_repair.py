# -*- coding: utf-8 -*-
"""修复 dialog 统一脚本造成的重复参数 + 顶格参数行。幂等。"""
import io, os, re

ROOT = "app/src/main/java/com/psyche/memo"
NL = chr(10)

def find_call_end(s, start):
    depth = 1
    i = start
    in_str = False
    str_ch = ""
    while i < len(s):
        ch = s[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == str_ch:
                in_str = False
            i += 1
            continue
        if ch in ('"', "'"):
            in_str = True
            str_ch = ch
            i += 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1

def dedupe_block(block):
    lines = block.split(NL)
    seen_cc = False
    seen_shape = False
    out = []
    removed = 0
    for line in lines:
        stripped = line.strip()
        is_cc = stripped.startswith("containerColor =")
        is_shape = stripped.startswith("shape =")
        if is_cc:
            if seen_cc:
                removed += 1
                continue
            seen_cc = True
        if is_shape:
            if seen_shape:
                removed += 1
                continue
            seen_shape = True
        out.append(line)
    block = NL.join(out)
    # 顶格的命名参数行缩进 8 空格
    fixed = 0
    lines = block.split(NL)
    for i, line in enumerate(lines):
        if re.match(r"^\w+ =", line):
            lines[i] = "        " + line
            fixed += 1
    block = NL.join(lines)
    return block, removed, fixed

changed = 0
for dirpath, _, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".kt"):
            continue
        p = os.path.join(dirpath, f)
        s = io.open(p, encoding="utf-8").read()
        if "AlertDialog(" not in s:
            continue
        out = []
        last = 0
        total = 0
        for m in re.finditer(r"(?<![\w.])AlertDialog\(", s):
            start = m.end()
            end = find_call_end(s, start)
            if end < 0:
                continue
            out.append(s[last:start])
            block = s[start:end]
            new_block, removed, fixed_cnt = dedupe_block(block)
            total += removed + fixed_cnt
            out.append(new_block)
            last = end
        out.append(s[last:])
        new_s = "".join(out)
        if total:
            io.open(p, "w", encoding="utf-8", newline="\n").write(new_s)
            changed += 1
            print(p, removed if False else "", total)
print(changed, "files")
