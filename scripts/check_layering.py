"""分层方向检查（兼容入口）：判定已并入 scripts/check_architecture.py，这里只做参数转发。

保留本文件是为了让既有习惯与文档里的命令继续可用；规则与阈值现在只写在
architecture-policy.json，改口径请改那里，不要在本文件里另写一套判定。

用法：
    python3 scripts/check_layering.py                # 只跑分层规则
    python3 scripts/check_layering.py --baseline     # 列出当前分层违规明细
    python3 scripts/check_layering.py --root <repo>

判据（与 architecture-policy.json 的 layering 段一致）：
1. core/** 不许 import com.aicode.feature.*。
2. feature/A 与 feature/B 同时存在 A→B 与 B→A 的依赖 → 循环依赖。
单向的 feature→feature 不报：拆分它需要先定谁在上层，属重构决策。
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import check_architecture


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=os.path.expanduser("~/workspace/AiCode"))
    parser.add_argument("--baseline", action="store_true", help="列出当前分层违规明细")
    args = parser.parse_args()

    argv = ["--root", args.root, "--only", "layering"]
    if args.baseline:
        argv.append("-v")
    return check_architecture.main(argv)


if __name__ == "__main__":
    sys.exit(main())
