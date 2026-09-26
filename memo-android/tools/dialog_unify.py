# -*- coding: utf-8 -*-
"""H1/M1 AlertDialog 统一脚本（UI_AUDIT 2026-09-12），幂等可重跑。
只改 AlertDialog(...) 参数块：
  1. containerColor 缺省或非 overlaySurface → overlaySurfaceColor()
     （errorContainer 等特殊底色跳过）
  2. shape 缺省或非 16dp → RoundedCornerShape(16.dp)
"""
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

def skip_ws_comments(s, i):
    while i < len(s):
        if s[i] in " " + chr(9) + chr(10) + chr(13):
            i += 1
        elif s[i:i+2] == "//":
            j = s.find(NL, i)
            i = j + 1 if j >= 0 else len(s)
        else:
            break
    return i

def block_replacements(block):
    changes = 0
    cc = re.search(r"containerColor = ([^,\n]+),", block)
    if cc and "overlaySurfaceColor" in cc.group(1):
        pass
    elif cc and "errorContainer" in cc.group(1):
        pass
    elif cc:
        block = block.replace(cc.group(0), "containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),", 1)
        changes += 1
    else:
        i = skip_ws_comments(block, 0)
        rest = block[i:i + 80]
        if re.match(r"^\w+\s*=", rest):
            insert = ("containerColor = MaterialTheme.colorScheme.overlaySurfaceColor()," + NL
                      + "            shape = RoundedCornerShape(16.dp)," + NL)
            block = block[:i] + insert + block[i:]
            changes += 1
    if "shape = RoundedCornerShape(16.dp)" not in block:
        sh = re.search(r"shape = [^,\n]+,", block)
        if sh:
            block = block.replace(sh.group(0), "shape = RoundedCornerShape(16.dp),", 1)
            changes += 1
        else:
            i = skip_ws_comments(block, 0)
            rest = block[i:i + 80]
            if re.match(r"^\w+\s*=", rest):
                insert = "shape = RoundedCornerShape(16.dp)," + NL
                block = block[:i] + insert + block[i:]
                changes += 1
    return block, changes

changed_files = []
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
            new_block, n = block_replacements(block)
            out.append(new_block)
            total += n
            last = end
        out.append(s[last:])
        new_s = "".join(out)
        if total == 0:
            continue
        io.open(p, "w", encoding="utf-8", newline="\n").write(new_s)
        changed_files.append((p, total))

for p, n in changed_files:
    print(n, p)
print(len(changed_files), "files changed")
