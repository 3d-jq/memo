"""把 Gradle 输出里的异常行打成 GitHub annotation。

为什么单独一个脚本：单测 XML 只记录"这条用例失败"和断言消息，协程泄漏出来的
**原始异常**只出现在 Gradle 的标准输出里，而完整日志要鉴权才能下载。CI 把输出 tee 到
文件后由这个脚本挑出关键行，公开的 annotations API 就能读到。
"""

import re
import sys

KEYWORDS = re.compile(r"(Uncaught|uncaught exception|Caused by|Exception|Error:|FAILED)")

# 这些是"失败提示"本身，不是根因，先滤掉减少噪音
NOISE = re.compile(r"(There were uncaught exceptions before the test started|"
                   r"UncaughtExceptionsBeforeTest|Please avoid this)")


def main(path: str, limit: int = 25) -> int:
    try:
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    except OSError as e:
        print("::warning::cannot read %s: %s" % (path, e))
        return 0

    seen = set()
    emitted = 0
    for raw in lines:
        line = raw.strip()
        if not line or not KEYWORDS.search(line) or NOISE.search(line):
            continue
        if line in seen:
            continue
        seen.add(line)
        print("::error::%s" % line[:900])
        emitted += 1
        if emitted >= limit:
            print("::warning::stopped after %d lines" % limit)
            break
    if emitted == 0:
        print("no exception lines found in %s" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/unit-tests.log"))
