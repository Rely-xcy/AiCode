"""发版前一键串跑所有静态门禁：任一环节失败就停，退出码非 0。

用法：
    python3 scripts/release_preflight.py              # 全部环节
    python3 scripts/release_preflight.py --fast       # 跳过自测（只跑结论性检查）
    python3 scripts/release_preflight.py --list       # 只列出要跑的环节

环节按「失败代价从低到高」排列，先跑的便宜、后跑的贵；任一步退出码非 0 立刻中止，
不继续后面的步骤，避免在已知不达标的分支上浪费时间。

架构门禁用 ratchet 模式：除了拦新增违规，还要求 .architecture-baseline.json 已经跟着
收缩，否则「修好了但没更新基线」会在 CI 里被当成失败——这是有意的，基线不收缩就等于
悄悄放水。

技能 frontmatter 检查依赖工作区里的运维脚本（~/workspace/scripts/），不在本仓库内，
仓库里跑不到就跳过并提示，不算失败。
"""
import argparse
import os
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKILL_CHECK = os.path.expanduser("~/workspace/scripts/check_skill_frontmatter.py")


def steps(fast):
    items = [
        ("迁移版本号对账", [sys.executable, "scripts/check_migrations.py"]),
        ("镜像同步脚本自测", [sys.executable, "scripts/sync-container-images.py", "--self-test"]),
        ("架构门禁（棘轮）", [sys.executable, "scripts/check_architecture.py", "--mode", "ratchet"]),
    ]
    if not fast:
        items.append(("架构门禁自测", [sys.executable, "scripts/test_check_architecture.py"]))
    if os.path.isfile(SKILL_CHECK):
        items.append(("技能 frontmatter", [sys.executable, SKILL_CHECK, "--strict"]))
    else:
        items.append(("技能 frontmatter", None))
    return items


def main(argv=None):
    parser = argparse.ArgumentParser(description="发版前串跑静态门禁")
    parser.add_argument("--fast", action="store_true", help="跳过架构门禁自测")
    parser.add_argument("--list", action="store_true", help="只列出环节，不执行")
    args = parser.parse_args(argv)

    plan = steps(args.fast)
    if args.list:
        for index, (title, command) in enumerate(plan, 1):
            print("%d. %s%s" % (index, title, "" if command else "（本机不可用，将跳过）"))
        return 0

    failed = []
    for index, (title, command) in enumerate(plan, 1):
        print("\n[%d/%d] %s" % (index, len(plan), title))
        if command is None:
            print("  跳过：找不到 %s" % SKILL_CHECK)
            continue
        result = subprocess.run(command, cwd=REPO_ROOT)
        if result.returncode != 0:
            failed.append(title)
            print("  [失败] %s 退出码 %d，后续环节不再执行" % (title, result.returncode))
            break
        print("  [通过]")

    if failed:
        print("\n发版前检查未通过：%s" % "、".join(failed))
        return 1
    print("\n发版前检查全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
