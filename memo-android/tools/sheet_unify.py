# -*- coding: utf-8 -*-
"""H1/H2/M3 sheet 统一脚本（UI_AUDIT 2026-09-12），幂等可重跑。"""
import io, os, re

ROOT = "app/src/main/java/com/psyche/memo"
NL = chr(10)

def find_call_end(s, start):
    depth = 1  # start 已越过 ModalBottomSheet 的左括号
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
    def repl_cc(m):
        nonlocal changes
        changes += 1
        return "containerColor = %s.overlaySurfaceColor()," % m.group(1)
    new = re.sub(r"containerColor = ((?:cs|colorScheme|MaterialTheme\.colorScheme))\.surface\b\s*,",
                 repl_cc, block)
    if "containerColor" not in new:
        if True:
            i = skip_ws_comments(new, 0)
            rest = new[i:i + 80]
            if re.match(r"^\w+\s*=", rest):
                insert = ("containerColor = MaterialTheme.colorScheme.overlaySurfaceColor()," + NL
                          + "            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)," + NL)
                new = new[:i] + insert + new[i:]
                changes += 1
    def repl_shape(m):
        nonlocal changes
        old = m.group(0)
        normalized = re.sub(r"topStart = \d+\.dp, topEnd = \d+\.dp", "topStart = 16.dp, topEnd = 16.dp", old)
        if normalized != old:
            changes += 1
            return normalized
        return old
    new = re.sub(r"RoundedCornerShape\(topStart = \d+\.dp, topEnd = \d+\.dp\)", repl_shape, new)
    return new, changes

changed_files = []
for dirpath, _, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".kt"):
            continue
        p = os.path.join(dirpath, f)
        s = io.open(p, encoding="utf-8").read()
        if "ModalBottomSheet(" not in s:
            continue
        out = []
        last = 0
        total = 0
        for m in re.finditer(r"ModalBottomSheet\(", s):
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
        pkg = re.search(r"^package ([\w.]+)", new_s, re.M).group(1)
        if pkg != "com.psyche.memo.ui" and "import com.psyche.memo.ui.overlaySurfaceColor" not in new_s:
            m2 = re.search(r"^import ", new_s, re.M)
            new_s = new_s[:m2.start()] + "import com.psyche.memo.ui.overlaySurfaceColor" + NL + new_s[m2.start():]
        if "RoundedCornerShape(" in new_s and "import androidx.compose.foundation.shape.RoundedCornerShape" not in new_s:
            m2 = re.search(r"^import ", new_s, re.M)
            new_s = new_s[:m2.start()] + "import androidx.compose.foundation.shape.RoundedCornerShape" + NL + new_s[m2.start():]
        io.open(p, "w", encoding="utf-8", newline="\n").write(new_s)
        changed_files.append((p, total))

for p, n in changed_files:
    print(n, p)
print(len(changed_files), "files changed")
