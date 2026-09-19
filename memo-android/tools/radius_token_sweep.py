#!/usr/bin/env python3
"""把全站字面圆角收口到 MemoRadius token。

背景：commit fdbfbdf 建立了 MemoRadius（CARD 20 / INNER 16 / PILL 999）并扫过主要
面板，但仍有 ~355 处直接写数字。本脚本做三件事：

  1. MemoRadius.kt 从 app 的 `com.psyche.memo.ui` 搬到 core:ui 的
     `com.psyche.memo.ui.theme`（core:ui 里的 toast 卡要用 token），并补
     SMALL_DP = 10（Apple 参考系的 "tighter end"，原三档缺这一档）。
  2. 按值替换字面量：
       999 -> PILL_DP    20/14 -> CARD_DP    16 -> INNER_DP
       10/8/9/11/6 -> SMALL_DP
     sheet 顶角命名参数式：20/18/12 -> CARD_DP
     有意保留字面量（不硬塞三档）：0/2/3/4/50
  3. 给新增 token 引用的文件补 import。

用法：python3 tools/radius_token_sweep.py [--dry-run]
"""

import re
import subprocess
import sys
from pathlib import Path

DRY = "--dry-run" in sys.argv
ROOT = Path(__file__).resolve().parent.parent
TARGET_DIRS = [
    ROOT / "app" / "src" / "main" / "java",
    ROOT / "core" / "ui" / "src" / "main" / "java",
]

OLD_IMPORT = "import com.psyche.memo.ui.MemoRadius"
NEW_IMPORT = "import com.psyche.memo.ui.theme.MemoRadius"

# 位置参数式：RoundedCornerShape(<N>.dp)
POSITIONAL_MAP = {
    "999": "MemoRadius.PILL_DP",
    "20": "MemoRadius.CARD_DP",
    "14": "MemoRadius.CARD_DP",
    "16": "MemoRadius.INNER_DP",
    "10": "MemoRadius.SMALL_DP",
    "9": "MemoRadius.SMALL_DP",
    "11": "MemoRadius.SMALL_DP",
    "8": "MemoRadius.SMALL_DP",
    "6": "MemoRadius.SMALL_DP",
}
# 有意保留字面量的值（缩略图/代码块/直角/伪胶囊），脚本不碰，只在末尾报告。
KEEP_POSITIONAL = {"0", "2", "3", "4", "50"}

# 命名参数式：RoundedCornerShape(topStart = <N>.dp, topEnd = <N>.dp)
NAMED_VALUES = {"20", "18", "12"}
NAMED_TOKEN = "MemoRadius.CARD_DP"

POSITIONAL_RE = re.compile(r"RoundedCornerShape\((\d+(?:\.\d+)?)\.dp\)")
# 命名参数式（sheet 顶角）：整段匹配调用，再把里面每个 `= N.dp` 换掉 ——
# 只匹配首个参数会让 topStart 换了、topEnd 没换，两角不对称。
NAMED_CALL_RE = re.compile(r"RoundedCornerShape\([^()]*=[^()]*\)")
NAMED_ARG_RE = re.compile(r"=\s*(\d+(?:\.\d+)?)\.dp")
NAMED_CORNERS = ("topStart", "topEnd", "bottomStart", "bottomEnd")


def kotlin_files():
    out = []
    for d in TARGET_DIRS:
        if d.is_dir():
            out.extend(sorted(d.rglob("*.kt")))
    return out


def ensure_import(text: str) -> str:
    """文件引用了 MemoRadius 却没有 import 时，插进 import 块顶部。"""
    if "MemoRadius." not in text:
        return text
    if NEW_IMPORT in text or OLD_IMPORT in text:
        return text
    lines = text.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.startswith("import "):
            lines.insert(i, NEW_IMPORT + "\n")
            return "".join(lines)
    for i, line in enumerate(lines):
        if line.startswith("package "):
            lines.insert(i + 1, "\n" + NEW_IMPORT + "\n")
            return "".join(lines)
    return NEW_IMPORT + "\n" + text


def move_token_file():
    src = ROOT / "app" / "src" / "main" / "java" / "com" / "psyche" / "memo" / "ui" / "MemoRadius.kt"
    dst_dir = ROOT / "core" / "ui" / "src" / "main" / "java" / "com" / "psyche" / "memo" / "ui" / "theme"
    dst = dst_dir / "MemoRadius.kt"
    if not src.exists():
        print(f"skip move (missing): {src}")
        return
    text = src.read_text(encoding="utf-8")
    text = text.replace("package com.psyche.memo.ui\n", "package com.psyche.memo.ui.theme\n", 1)
    if "SMALL_DP" not in text:
        text = text.replace(
            "    /** 胶囊（按钮 / 标签 / chips）。 */",
            "    /** 小控件圆角（Apple 参考系的 tighter end：小标签、内嵌 chip、紧凑输入）。\n"
            "     *  三档收档时漏了它，导致全站 r6/r8/r9/r10/r11 无处归。 */\n"
            "    const val SMALL_DP = 10\n\n"
            "    /** 胶囊（按钮 / 标签 / chips）。 */",
            1,
        )
    dst_dir.mkdir(parents=True, exist_ok=True)
    if DRY:
        print(f"[dry] move {src.relative_to(ROOT)} -> {dst.relative_to(ROOT)} (+SMALL_DP)")
    else:
        subprocess.run(["git", "mv", str(src.relative_to(ROOT)), str(dst.relative_to(ROOT))],
                       cwd=ROOT, check=True)
        dst.write_text(text, encoding="utf-8")
    print(f"moved MemoRadius.kt -> core/ui/.../ui/theme/ (+ SMALL_DP = 10)")


def sweep_files():
    pos_hits = {}
    named_hits = 0
    keep_hits = []
    changed = 0

    for path in kotlin_files():
        original = path.read_text(encoding="utf-8")
        text = original

        if OLD_IMPORT in text:
            text = text.replace(OLD_IMPORT, NEW_IMPORT)

        def sub_positional(m):
            val = m.group(1)
            token = POSITIONAL_MAP.get(val)
            if token is None:
                if val in KEEP_POSITIONAL:
                    keep_hits.append(f"{path.relative_to(ROOT)}:{val}")
                return m.group(0)
            pos_hits[val] = pos_hits.get(val, 0) + 1
            return f"RoundedCornerShape({token}.dp)"

        text = POSITIONAL_RE.sub(sub_positional, text)

        def sub_named_call(m):
            nonlocal named_hits
            call = m.group(0)
            if not any(c in call for c in NAMED_CORNERS):
                return call

            def repl(mm):
                nonlocal named_hits
                val = mm.group(1)
                if val not in NAMED_VALUES:
                    return mm.group(0)
                named_hits += 1
                return f"= {NAMED_TOKEN}.dp"

            return NAMED_ARG_RE.sub(repl, call)

        text = NAMED_CALL_RE.sub(sub_named_call, text)
        text = ensure_import(text)

        if text != original:
            changed += 1
            if not DRY:
                path.write_text(text, encoding="utf-8")

    print(f"\nfiles changed: {changed}")
    print("positional replacements (value -> count):")
    for val, n in sorted(pos_hits.items(), key=lambda kv: -kv[1]):
        note = "零变化" if val in ("999", "20", "16", "10") else ("归位 20" if val == "14" else "收到 10")
        print(f"  r{val:<4} -> {POSITIONAL_MAP[val]:<22} x{n:<4} {note}")
    print(f"named-arg (sheet top corners) replacements: {named_hits} 角")
    print(f"\nkept literal on purpose ({len(keep_hits)}):")
    for k in keep_hits:
        print(f"  {k}")


if __name__ == "__main__":
    if DRY:
        print("=== DRY RUN (no writes) ===")
    move_token_file()
    sweep_files()
