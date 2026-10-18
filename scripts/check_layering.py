"""分层方向检查：core 不许依赖 feature；feature 之间不许「互相」依赖。

用法：
    python3 scripts/check_layering.py                # 报告现状，有违规退出码 1
    python3 scripts/check_layering.py --baseline     # 打印当前违规（用于更新白名单）
    python3 scripts/check_layering.py --root <repo>

为什么不引 Konsist/ArchUnit：本仓没有这两个依赖，而为一条架构检查加依赖不划算；
这里只做「读 import 行 + 比对模块」的静态扫描，零依赖、秒级跑完。

判据（刻意保守，只报能确定的）：
1. core/** 里出现 import com.aicode.feature.* → 反向依赖（core 是底座，不该认识上层）。
2. feature/A 与 feature/B 同时存在 A→B 与 B→A 的 import → 循环依赖（必然有一边放错了层）。
单向的 feature→feature 不报：拆分它需要先定谁在上层，属重构决策，不在脚本职责内。
"""
import argparse
import os
import re
import sys

IMPORT_RE = re.compile(r"^import\s+([\w.]+)", re.M)

# 已知例外：确有必要（比如 core 里的事件总线需要 feature 的实现类做注册）。
# 新增例外必须在这里显式登记，否则脚本报错——目的是挡住「增量」。
ALLOWED_CORE_TO_FEATURE = set()


def module_of(path, root):
    """返回模块名：core / feature/<name> / 其它顶层包名；解析不出返回 None。"""
    rel = os.path.relpath(path, root)
    parts = rel.split(os.sep)
    # 期望形如 app/src/main/java/com/aicode/<module>/...
    if "com" not in parts or "aicode" not in parts:
        return None
    index = parts.index("aicode")
    rest = parts[index + 1:]
    if not rest:
        return None
    if rest[0] == "feature" and len(rest) >= 2:
        return "feature/" + rest[1]
    return rest[0]


def scan(root):
    """返回 [(模块, 目标模块, 文件)]，只保留 com.aicode.* 的 import。"""
    edges = []
    for base, _dirs, names in os.walk(root):
        for name in names:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(base, name)
            source_module = module_of(path, root)
            if source_module is None:
                continue
            try:
                with open(path, encoding="utf-8") as handle:
                    text = handle.read()
            except OSError:
                continue
            for imported in IMPORT_RE.findall(text):
                if not imported.startswith("com.aicode."):
                    continue
                rest = imported[len("com.aicode."):].split(".")
                if rest[0] == "feature" and len(rest) >= 2:
                    target = "feature/" + rest[1]
                else:
                    target = rest[0]
                if target == source_module:
                    continue
                edges.append((source_module, target, os.path.relpath(path, root)))
    return edges


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=os.path.expanduser("~/workspace/AiCode"))
    parser.add_argument("--baseline", action="store_true", help="打印当前违规，便于更新已知例外")
    args = parser.parse_args()
    source_root = os.path.join(args.root, "app/src/main/java")
    if not os.path.isdir(source_root):
        print(f"找不到源码目录：{source_root}")
        return 2

    edges = scan(source_root)

    core_to_feature = [(s, t, f) for (s, t, f) in edges if s == "core" and t.startswith("feature/")]
    unknown = [item for item in core_to_feature if item[2] not in ALLOWED_CORE_TO_FEATURE]

    # 循环依赖：A→B 且 B→A
    pairs = {}
    for source, target, path in edges:
        if source.startswith("feature/") and target.startswith("feature/"):
            pairs.setdefault((source, target), []).append(path)
    cycles = []
    for (a, b) in sorted(pairs):
        if a < b and (b, a) in pairs:
            cycles.append((a, b, pairs[(a, b)], pairs[(b, a)]))

    print(f"扫描 import：{len(edges)} 条跨模块边")
    print(f"\n[1] core → feature（{len(core_to_feature)} 处，其中未登记 {len(unknown)} 处）")
    for source, target, path in sorted(core_to_feature):
        flag = "  " if path in ALLOWED_CORE_TO_FEATURE else "!!"
        print(f"  {flag} {path} → {target}")

    print(f"\n[2] feature 互相依赖（循环 {len(cycles)} 对）")
    for a, b, a_to_b, b_to_a in cycles:
        print(f"  !! {a} ↔ {b}：{len(a_to_b)} 条 / {len(b_to_a)} 条")
        for path in (a_to_b[:2] + b_to_a[:2]):
            print(f"       {path}")

    single = sorted({(s, t) for (s, t, _f) in edges
                     if s.startswith("feature/") and t.startswith("feature/")})
    if single:
        print(f"\n[3] 单向 feature → feature（不报错，供定层级参考，共 {len(single)} 对）")
        for s, t in single:
            print(f"    {s} → {t}")

    if args.baseline:
        print("\n（--baseline：以下两份清单用于更新白名单）")
        print("ALLOWED_CORE_TO_FEATURE = {")
        for _s, _t, path in sorted(core_to_feature):
            print(f'    "{path}",')
        print("}")
        return 0

    if unknown or cycles:
        print(f"\n失败：{len(unknown)} 处未登记的 core→feature，{len(cycles)} 对循环依赖")
        return 1
    print("\n通过：core 不反向依赖 feature，feature 之间无循环依赖。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
