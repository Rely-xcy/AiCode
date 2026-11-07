#!/usr/bin/env python3
"""架构门禁执行器：规则在 architecture-policy.json，存量豁免在 .architecture-baseline.json。

用法：
    python3 scripts/check_architecture.py                    # 普通检查（mode=check）
    python3 scripts/check_architecture.py --mode ratchet      # 额外要求基线已同步收缩
    python3 scripts/check_architecture.py --write-baseline    # 重新生成基线文件
    python3 scripts/check_architecture.py --only layering     # 只跑一类规则
    python3 scripts/check_architecture.py -v                  # 连基线已豁免的条目一起列出

退出码：0 通过；1 有违规；2 规则或基线文件不可用。

覆盖形态：import 行、`as` 别名、包级通配 import、正文里直接写全限定类名。
不覆盖：反射拼类名、注解参数里的类名字符串 —— 静态检查拦不到这类写法。

注释与字符串用逐行状态机剥离，注释里出现的类名不算违规；多行原始字符串（三引号）
没有单独处理，其中的文本会被当作代码扫描，这是已知的误报来源，目前仓库里没有命中。
"""

from __future__ import annotations

import argparse
import fnmatch
import glob
import json
import os
import re
import sys
from dataclasses import dataclass
from functools import lru_cache

POLICY_FILE = "architecture-policy.json"
BASELINE_FILE = ".architecture-baseline.json"

EXIT_OK = 0
EXIT_VIOLATION = 1
EXIT_CONFIG = 2

RULES = ("layering", "banned", "fileSize", "i18n")
NEVER_BASELINED = frozenset({"i18n"})


@dataclass(frozen=True)
class Violation:
    rule: str
    key: str
    path: str
    line: int
    message: str


@dataclass(frozen=True)
class EdgeHit:
    path: str
    line: int
    kind: str


def rel_posix(path: str, root: str) -> str:
    return os.path.relpath(path, root).replace(os.sep, "/")


def read_text(path: str) -> str:
    return _read_text(path)


@lru_cache(maxsize=None)
def _read_text(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


@lru_cache(maxsize=None)
def _collect_files(root: str, patterns) -> tuple:
    found = set()
    for pattern in patterns:
        for path in glob.glob(os.path.join(root, pattern), recursive=True):
            if os.path.isfile(path):
                found.add(os.path.normpath(path))
    return tuple(sorted(found))


def collect_files(root: str, patterns) -> list:
    return list(_collect_files(root, tuple(patterns)))


def is_allowed(rel_path: str, allow_patterns) -> bool:
    return any(fnmatch.fnmatch(rel_path, pattern) for pattern in allow_patterns)


def matches_module(target: str, patterns) -> bool:
    for pattern in patterns:
        if pattern == target:
            return True
        if pattern.endswith("/*"):
            prefix = pattern[:-2]
            if target == prefix or target.startswith(prefix + "/"):
                return True
    return False


def code_lines(text: str):
    """逐行产出 (行号, 去掉注释后的代码)。块注释按 Kotlin 规则计嵌套深度。"""
    depth = 0
    in_string = False
    escaped = False
    for number, raw in enumerate(text.splitlines(), 1):
        out = []
        index = 0
        while index < len(raw):
            char = raw[index]
            nxt = raw[index + 1] if index + 1 < len(raw) else ""
            if depth > 0:
                if char == "/" and nxt == "*":
                    depth += 1
                    index += 2
                    continue
                if char == "*" and nxt == "/":
                    depth -= 1
                    index += 2
                    continue
                index += 1
                continue
            if in_string:
                out.append(char)
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                index += 1
                continue
            if char == "/" and nxt == "/":
                break
            if char == "/" and nxt == "*":
                depth += 1
                index += 2
                continue
            if char == '"':
                in_string = True
                out.append(char)
                index += 1
                continue
            out.append(char)
            index += 1
        in_string = False
        escaped = False
        yield number, "".join(out)


@lru_cache(maxsize=None)
def code_lines_cached(path: str) -> tuple:
    return tuple(code_lines(_read_text(path)))


def module_of_path(rel_path: str, package_prefix: str):
    parts = rel_path.split("/")
    prefix = package_prefix.split(".")
    for start in range(len(parts) - len(prefix) + 1):
        if parts[start:start + len(prefix)] == prefix:
            rest = parts[start + len(prefix):]
            break
    else:
        return None
    if not rest or not rest[0]:
        return None
    if rest[0] == "feature" and len(rest) >= 2:
        return "feature/" + rest[1]
    return rest[0]


def module_of_fqn(fqn: str, package_prefix: str):
    if not fqn.startswith(package_prefix + "."):
        return None
    rest = fqn[len(package_prefix) + 1:].split(".")
    if not rest or not rest[0]:
        return None
    if rest[0] == "feature" and len(rest) >= 2:
        return "feature/" + rest[1]
    return rest[0]


IMPORT_RX = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*)(?:\s+as\s+([A-Za-z_]\w*))?\s*$")


def scan_layering(root: str, policy: dict) -> list:
    config = policy["layering"]
    prefix = config.get("packagePrefix", "com.aicode")
    extensions = config.get("extensions") or [".kt"]
    patterns = []
    for source_root in config["sourceRoots"]:
        for extension in extensions:
            patterns.append(source_root.rstrip("/") + "/**/*" + extension)
    body_rx = re.compile(re.escape(prefix) + r"\.[A-Za-z_][\w.]*")

    edges = {}
    for path in collect_files(root, patterns):
        rel_path = rel_posix(path, root)
        source = module_of_path(rel_path, prefix)
        if source is None:
            continue
        for number, code in code_lines_cached(path):
            match = IMPORT_RX.match(code)
            if match:
                fqn = match.group(1)
                if fqn.endswith(".*"):
                    fqn = fqn[:-2]
                target = module_of_fqn(fqn, prefix)
                if target and target != source:
                    edges.setdefault((source, target), []).append(EdgeHit(rel_path, number, "import"))
                continue
            for hit in body_rx.finditer(code):
                target = module_of_fqn(hit.group(0), prefix)
                if target and target != source:
                    edges.setdefault((source, target), []).append(
                        EdgeHit(rel_path, number, "正文写全限定名")
                    )

    violations = []
    for (source, target) in sorted(edges):
        for rule in config.get("forbidden", []):
            if matches_module(source, rule["from"]) and matches_module(target, rule["to"]):
                reason = rule["reason"]
                for hit in edges[(source, target)]:
                    violations.append(
                        Violation(
                            "layering",
                            source + " -> " + target,
                            hit.path,
                            hit.line,
                            "%s 依赖 %s（%s）：%s" % (source, target, hit.kind, reason),
                        )
                    )

    cycle_config = config.get("featureCycles") or {}
    if cycle_config.get("enabled"):
        for (source, target) in sorted(edges):
            if not (source.startswith("feature/") and target.startswith("feature/")):
                continue
            if (target, source) not in edges:
                continue
            forward = len(edges[(source, target)])
            backward = len(edges[(target, source)])
            for hit in edges[(source, target)]:
                violations.append(
                    Violation(
                        "layering",
                        source + " -> " + target,
                        hit.path,
                        hit.line,
                        "%s 与 %s 互相依赖（%s→%s %d 处，反向 %d 处）：%s"
                        % (source, target, source, target, forward, backward, cycle_config["reason"]),
                    )
                )
    return violations


def scan_banned(root: str, policy: dict) -> list:
    violations = []
    for rule in policy.get("bannedComponents", []):
        rule_id = rule["id"]
        files = collect_files(root, rule["scope"])
        allow = rule.get("allow") or []
        if rule.get("type") == "regex":
            pattern = re.compile(rule["pattern"])
            for path in files:
                rel_path = rel_posix(path, root)
                if is_allowed(rel_path, allow):
                    continue
                for number, code in code_lines_cached(path):
                    for _ in pattern.finditer(code):
                        violations.append(
                            Violation(rule_id, rel_path, rel_path, number, rule["reason"])
                        )
            continue

        symbol = rule["symbol"]
        package, _simple = symbol.rsplit(".", 1)
        import_rx = re.compile(
            r"^\s*import\s+" + re.escape(symbol) + r"\s*(?:as\s+([A-Za-z_]\w*))?\s*$"
        )
        wildcard_rx = re.compile(r"^\s*import\s+" + re.escape(package) + r"\.\*\s*$")
        body_rx = re.compile(r"(?<![\w.])" + re.escape(symbol) + r"(?![\w])")
        for path in files:
            rel_path = rel_posix(path, root)
            if is_allowed(rel_path, allow):
                continue
            for number, code in code_lines_cached(path):
                match = import_rx.match(code)
                if match:
                    alias = match.group(1)
                    suffix = "（并用 %s 别名引用）" % alias if alias else ""
                    violations.append(
                        Violation(
                            rule_id,
                            rel_path,
                            rel_path,
                            number,
                            "import 了禁用组件 %s%s：%s" % (symbol, suffix, rule["reason"]),
                        )
                    )
                    continue
                if wildcard_rx.match(code):
                    violations.append(
                        Violation(
                            rule_id,
                            rel_path,
                            rel_path,
                            number,
                            "用通配符 import 了 %s.*，禁用清单无法静态核对，改成显式 import 项目封装：%s"
                            % (package, rule["reason"]),
                        )
                    )
                    continue
                for _ in body_rx.finditer(code):
                    violations.append(
                        Violation(
                            rule_id,
                            rel_path,
                            rel_path,
                            number,
                            "正文直接写全限定类名 %s：%s" % (symbol, rule["reason"]),
                        )
                    )
    return violations


def scan_file_size(root: str, policy: dict):
    config = policy["fileSize"]
    limit = int(config["limit"])
    allow = config.get("allow") or []
    violations = []
    lines_by_file = {}
    for path in collect_files(root, config["scope"]):
        rel_path = rel_posix(path, root)
        if is_allowed(rel_path, allow):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            count = sum(1 for _ in handle)
        if count > limit:
            lines_by_file[rel_path] = count
            violations.append(
                Violation(
                    "fileSize",
                    rel_path,
                    rel_path,
                    count,
                    "共 %d 行，超过上限 %d 行：%s" % (count, limit, config["reason"]),
                )
            )
    return violations, lines_by_file


STRING_RX = re.compile(r'<string\s+name="([^"]+)"')


def scan_i18n(root: str, policy: dict) -> list:
    config = policy["i18n"]
    base_locale = config["baseLocale"]
    tables = {}
    for locale, patterns in config["localeDirs"].items():
        table = {}
        for path in collect_files(root, patterns):
            rel_path = rel_posix(path, root)
            for number, line in enumerate(read_text(path).splitlines(), 1):
                for match in STRING_RX.finditer(line):
                    table.setdefault(match.group(1), (rel_path, number))
        tables[locale] = table

    if base_locale not in tables:
        return []
    base = tables[base_locale]
    violations = []
    for locale in sorted(tables):
        if locale == base_locale:
            continue
        other = tables[locale]
        for key in sorted(base):
            if key not in other:
                rel_path, line = base[key]
                violations.append(
                    Violation(
                        "i18n",
                        key,
                        rel_path,
                        line,
                        '文案键 "%s" 在 %s 侧缺失（中英必须成对）：%s'
                        % (key, locale, config["reason"]),
                    )
                )
        for key in sorted(other):
            if key not in base:
                rel_path, line = other[key]
                violations.append(
                    Violation(
                        "i18n",
                        key,
                        rel_path,
                        line,
                        '文案键 "%s" 在 %s 侧缺失（中英必须成对）：%s'
                        % (key, base_locale, config["reason"]),
                    )
                )
    return violations


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="执行 architecture-policy.json 里的架构规则",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--root", default=None, help="仓库根目录，默认取脚本上级目录")
    parser.add_argument("--policy", default=None, help="规则文件，默认 <root>/" + POLICY_FILE)
    parser.add_argument("--baseline", default=None, help="基线文件，默认 <root>/" + BASELINE_FILE)
    parser.add_argument(
        "--mode",
        choices=("check", "ratchet"),
        default="check",
        help="check=只拦新增与增长；ratchet=额外要求基线已同步收缩",
    )
    parser.add_argument("--write-baseline", action="store_true", help="按当前实测结果重写基线")
    parser.add_argument(
        "--force",
        action="store_true",
        help="允许 --write-baseline 写出比旧基线更大的条目（仅在确实放宽了规则时用）",
    )
    parser.add_argument(
        "--only",
        action="append",
        choices=RULES,
        default=None,
        help="只跑指定规则（可重复），用于旧入口转发或局部排查",
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="连基线已豁免的条目也列出")
    return parser


def load_json(path: str):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        return None
    except json.JSONDecodeError as error:
        print("[错误] %s 不是合法 JSON：%s" % (path, error), file=sys.stderr)
        raise SystemExit(EXIT_CONFIG)


def validate_policy(policy: dict, path: str) -> None:
    missing = [key for key in ("layering", "bannedComponents", "fileSize", "i18n") if key not in policy]
    if missing:
        print("[错误] %s 缺少字段：%s" % (path, ", ".join(missing)), file=sys.stderr)
        raise SystemExit(EXIT_CONFIG)


def collect(root: str, policy: dict, only) -> tuple:
    def selected(group: str) -> bool:
        return not only or group in only

    violations = []
    lines_by_file = {}
    if selected("layering"):
        violations.extend(scan_layering(root, policy))
    if selected("banned"):
        violations.extend(scan_banned(root, policy))
    if selected("fileSize"):
        size_violations, lines_by_file = scan_file_size(root, policy)
        violations.extend(size_violations)
    if selected("i18n"):
        violations.extend(scan_i18n(root, policy))
    return violations, lines_by_file


def group_violations(violations) -> dict:
    groups = {}
    for violation in violations:
        groups.setdefault(violation.rule, {}).setdefault(violation.key, []).append(violation)
    return groups


def build_baseline(groups: dict, lines_by_file: dict) -> dict:
    payload = {
        "version": 1,
        "comment": [
            "存量违规登记，只减不增：修好一条就删一条，重新生成本文件必须确认没有放水。",
            "由 python3 scripts/check_architecture.py --write-baseline 生成，不要手写。",
            "i18n（中英文案对齐）不允许登记豁免，永远要求全绿。",
        ],
        "layering": {},
        "bannedComponents": {},
        "fileLines": {},
    }
    for rule in sorted(groups):
        if rule in NEVER_BASELINED or rule == "fileSize":
            continue
        if rule == "layering":
            target = payload["layering"]
        else:
            target = payload["bannedComponents"].setdefault(rule, {})
        for key in sorted(groups[rule]):
            target[key] = len(groups[rule][key])
    payload["fileLines"] = dict(sorted(lines_by_file.items()))
    return payload


def growths(previous: dict, payload: dict) -> list:
    """列出新基线比旧基线「变大」的条目——这些正是放水的痕迹。"""
    found = []
    for section in ("layering", "fileLines"):
        old = previous.get(section, {}) or {}
        new = payload.get(section, {}) or {}
        for key in sorted(new):
            if int(new[key]) > int(old.get(key, 0)):
                found.append((section, key, int(old.get(key, 0)), int(new[key])))
    old_banned = previous.get("bannedComponents", {}) or {}
    for rule in sorted(payload.get("bannedComponents", {})):
        old = old_banned.get(rule, {}) or {}
        for key in sorted(payload["bannedComponents"][rule]):
            if int(payload["bannedComponents"][rule][key]) > int(old.get(key, 0)):
                found.append((rule, key, int(old.get(key, 0)), int(payload["bannedComponents"][rule][key])))
    return found


def write_baseline(path: str, payload: dict) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))
        handle.write("\n")


def print_summary(groups: dict, lines_by_file: dict, baseline: dict, only) -> None:
    def selected(group: str) -> bool:
        return not only or group in only

    if selected("layering"):
        total = sum(len(items) for items in groups.get("layering", {}).values())
        print("  分层方向         %d 处（基线登记 %d 处）" % (total, len(baseline.get("layering", {}))))
    if selected("banned"):
        for rule in sorted(groups):
            if rule in ("layering", "fileSize", "i18n"):
                continue
            total = sum(len(items) for items in groups[rule].values())
            print("  禁用组件 %-22s %d 处（基线登记 %d 个文件）"
                  % (rule, total, len(baseline.get("bannedComponents", {}).get(rule, {}))))
        if not [rule for rule in groups if rule not in ("layering", "fileSize", "i18n")]:
            print("  禁用组件         0 处")
    if selected("fileSize"):
        print("  单文件行数       %d 个文件超限（基线登记 %d 个）"
              % (len(lines_by_file), len(baseline.get("fileLines", {}))))
    if selected("i18n"):
        print("  中英文案对齐     %d 处不齐（不豁免，必须为 0）"
              % sum(len(items) for items in groups.get("i18n", {}).values()))


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    script_dir = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.expanduser(args.root or os.path.dirname(script_dir)))
    policy_path = os.path.abspath(args.policy or os.path.join(root, POLICY_FILE))
    baseline_path = os.path.abspath(args.baseline or os.path.join(root, BASELINE_FILE))

    policy = load_json(policy_path)
    if policy is None:
        print("[错误] 找不到规则文件 %s" % policy_path, file=sys.stderr)
        return EXIT_CONFIG
    validate_policy(policy, policy_path)

    if args.write_baseline and args.only:
        print("[错误] --write-baseline 不能和 --only 同用（会写出一份残缺基线）", file=sys.stderr)
        return EXIT_CONFIG

    violations, lines_by_file = collect(root, policy, args.only)
    groups = group_violations(violations)

    if args.write_baseline:
        payload = build_baseline(groups, lines_by_file)
        previous = load_json(baseline_path) or {}
        # 首次生成（基线不存在）时没有可比对象，全部算新增是预期行为，不能拦。
        grown = growths(previous, payload) if previous else []
        if grown and not args.force:
            print("\n拒绝写入：新基线比旧基线多登记了 %d 处，这是放水而不是收缩。" % len(grown))
            for section, key, old, new in grown:
                print("  [%s] %s 基线 %d → %d" % (section, key, old, new))
            print("确属合法放宽（例如调高了 fileSize.limit）时加 --force 重跑。")
            return EXIT_VIOLATION
        write_baseline(baseline_path, payload)
        print("已写入基线：%s" % baseline_path)
        print_summary(groups, lines_by_file, {}, args.only)
        print("确认这份基线只含存量违规，再提交。")
        return EXIT_OK

    baseline = load_json(baseline_path) or {}
    if not baseline:
        print("[提示] 基线文件 %s 不存在或为空，按空基线处理：所有存量都会报违规。" % baseline_path)

    def selected(group: str) -> bool:
        return not args.only or group in args.only

    # 选中但当前零违规的规则也要参与对比，否则基线里的死条目永远发现不了
    rules_present = set(groups)
    if selected("fileSize"):
        rules_present.add("fileSize")
    if selected("layering"):
        rules_present.add("layering")
    if selected("banned"):
        rules_present |= set(baseline.get("bannedComponents", {}) or {})

    failures = []
    stale = []
    for rule in sorted(rules_present):
        if rule == "fileSize":
            baseline_section = baseline.get("fileLines", {}) or {}
            actual_of = lambda key: lines_by_file.get(key, 0)  # noqa: E731
        elif rule in NEVER_BASELINED:
            baseline_section = {}
            actual_of = lambda key: len(groups[rule][key])  # noqa: E731
        else:
            section_name = "layering" if rule == "layering" else "bannedComponents"
            section = baseline.get(section_name, {}) or {}
            baseline_section = section.get(rule, {}) if section_name == "bannedComponents" else section
            actual_of = lambda key: len(groups.get(rule, {}).get(key, []))  # noqa: E731

        keys = set(groups.get(rule, {})) | set(baseline_section)
        for key in sorted(keys):
            items = groups.get(rule, {}).get(key, [])
            actual = actual_of(key)
            allowed = int(baseline_section.get(key, 0))
            if actual > allowed:
                failures.append((rule, key, actual, allowed, items))
            elif actual < allowed:
                stale.append((rule, key, actual, allowed))

    print("== 架构门禁（mode=%s）==" % args.mode)
    print("规则：%s" % policy_path)
    print("基线：%s" % baseline_path)
    print_summary(groups, lines_by_file, baseline, args.only)

    if args.verbose:
        print("\n全部违规明细：")
        for rule in sorted(groups):
            for key in sorted(groups[rule]):
                for item in groups[rule][key]:
                    print("  [%s] %s:%d: %s" % (rule, item.path, item.line, item.message))

    if failures:
        print("\n未通过（%d 项）：" % len(failures))
        for rule, key, actual, allowed, items in failures:
            print("  [%s] %s 实测 %d，基线 %d" % (rule, key, actual, allowed))
            for item in items:
                print("    %s:%d: %s" % (item.path, item.line, item.message))

    if stale:
        if args.mode == "ratchet":
            print("\n棘轮要求基线同步收缩（%d 项）：" % len(stale))
        else:
            print("\n基线已可收缩（%d 项，普通模式只提示、不失败）：" % len(stale))
        for rule, key, actual, allowed in stale:
            print("  [%s] %s 实测 %d < 基线 %d，请重跑 --write-baseline 删掉这条" % (rule, key, actual, allowed))

    if failures:
        print("\n结论：FAIL")
        return EXIT_VIOLATION
    if stale and args.mode == "ratchet":
        print("\n结论：FAIL（基线没跟着收缩）")
        return EXIT_VIOLATION
    print("\n结论：PASS")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
