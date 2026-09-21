"""四个生成器共用的上游输入根目录。

开发仓里 Memo 只是 kelivo Flutter 仓的一个子目录，生成器读的是同级的 `lib/` 与
`drift_schemas/`；单独发布的仓没有外层，所以上游输入以同样的目录结构放在仓内
`upstream/` 下。这里按"仓内有 upstream/ 就用它，否则回退到外层仓"选一个，两种布局
都能跑 `tools/quality_gate.sh` 的第 5 步。
"""

import os


def upstream_root(tools_dir=None):
    here = os.path.abspath(tools_dir or os.path.dirname(__file__))
    module = os.path.normpath(os.path.join(here, '..'))
    vendored = os.path.join(module, 'upstream')
    if os.path.isdir(vendored):
        return vendored
    return os.path.normpath(os.path.join(module, '..'))
