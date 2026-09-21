"""把 Gradle 单测结果里的失败用例打成 GitHub annotation。

CI 的完整日志要鉴权才能下载，只靠 "exit 1" 没法定位是哪个用例挂了；这个脚本把
`build/test-results/**/*.xml` 里的 failure/error 逐条输出成 `::error::` 行，
GitHub 会把它们显示在 run 页面的 annotations 里，API 也能公开读到。
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def main(root_dir: str) -> int:
    pattern = os.path.join(root_dir, "**", "build", "test-results", "**", "*.xml")
    count = 0
    for path in glob.glob(pattern, recursive=True):
        try:
            tree = ET.parse(path).getroot()
        except Exception:
            continue
        suite = tree.get("name") or os.path.relpath(path, root_dir)
        for case in tree.iter("testcase"):
            for failure in list(case.findall("failure")) + list(case.findall("error")):
                # 断言消息常常不含根因，堆栈里才有；annotation 有长度上限，取前 1500 字。
                message = (failure.get("message") or "") + "\n" + (failure.text or "")
                message = " ".join(message.split())[:1500]
                print("::error::%s › %s: %s" % (suite, case.get("name"), message))
                count += 1
    if count == 0:
        print("no failed tests found in %s" % root_dir)
    else:
        print("annotated %d failed test(s)" % count)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
