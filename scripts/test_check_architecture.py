"""架构门禁的自测：故意违规的样例必须 FAIL，合规样例必须 PASS。

跑法：
    python3 scripts/test_check_architecture.py
    python3 scripts/test_check_architecture.py -v

每个用例在临时目录里搭一个最小仓库（规则文件 + 源码 + 可选基线），再以子进程调用
check_architecture.py，断言退出码与输出里的 file:line。不依赖 gradle、不依赖真实仓库。
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(REPO_ROOT, "scripts", "check_architecture.py")

TEST_POLICY = {
    "version": 1,
    "layering": {
        "sourceRoots": ["app/src/main/java"],
        "packagePrefix": "com.aicode",
        "extensions": [".kt"],
        "forbidden": [{"from": ["core"], "to": ["feature/*"], "reason": "core 是底座"}],
        "featureCycles": {"enabled": True, "reason": "两个 feature 互相依赖说明有一边放错层"},
    },
    "bannedComponents": [
        {
            "id": "raw-textfield",
            "symbol": "androidx.compose.material3.TextField",
            "scope": ["app/src/main/java/**/*.kt"],
            "allow": ["app/src/main/java/com/aicode/core/ui/AppTextField.kt"],
            "reason": "统一走 AppTextField",
        },
        {
            "id": "hardcoded-color",
            "type": "regex",
            "pattern": "(?<![\\w.])Color\\s*\\(\\s*(?:0x[0-9A-Fa-f]{6,8}|\\d)",
            "scope": ["app/src/main/java/**/*.kt"],
            "allow": ["app/src/main/java/com/aicode/core/theme/**"],
            "reason": "色值用主题令牌",
        },
    ],
    "fileSize": {
        "limit": 20,
        "scope": ["app/src/main/java/**/*.kt"],
        "allow": [],
        "reason": "单文件过大就拆分",
    },
    "i18n": {
        "baseLocale": "zh",
        "localeDirs": {
            "zh": ["app/src/main/res/values/**/*.xml"],
            "en": ["app/src/main/res/values-en/**/*.xml"],
        },
        "reason": "中英文案必须成对",
    },
}

SRC = "app/src/main/java/com/aicode/"
ZH = "app/src/main/res/values/strings.xml"
EN = "app/src/main/res/values-en/strings.xml"


def kt(*lines):
    return "\n".join(lines) + "\n"


def kotlin(package_tail, *body):
    return kt("package com.aicode." + package_tail, "", *body)


def strings_xml(*keys):
    rows = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    rows += ['    <string name="%s">值</string>' % key for key in keys]
    rows.append("</resources>")
    return kt(*rows)


class GateTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="archgate-")
        self.addCleanup(shutil.rmtree, self.root, True)
        self.write("architecture-policy.json", json.dumps(TEST_POLICY, ensure_ascii=False, indent=2))

    def write(self, rel_path, text):
        path = os.path.join(self.root, rel_path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)

    def run_gate(self, *extra):
        return subprocess.run(
            [sys.executable, SCRIPT, "--root", self.root] + list(extra),
            capture_output=True,
            text=True,
        )

    def assert_fail(self, result, *needles):
        self.assertEqual(result.returncode, 1, "期望 FAIL，实际输出：\n" + result.stdout + result.stderr)
        for needle in needles:
            self.assertIn(needle, result.stdout)

    def assert_pass(self, result):
        self.assertEqual(result.returncode, 0, "期望 PASS，实际输出：\n" + result.stdout + result.stderr)

    # ---------- 分层方向 ----------

    def test_clean_tree_passes(self):
        self.write(SRC + "core/ui/AppSwitch.kt", kotlin("core.ui", "fun AppSwitch() = Unit"))
        self.write(SRC + "feature/agent/Foo.kt", kotlin("feature.agent", "class Foo"))
        self.assert_pass(self.run_gate())

    def test_core_import_feature_fails(self):
        self.write(
            SRC + "core/ui/Leaky.kt",
            kotlin("core.ui", "import com.aicode.feature.agent.Foo"),
        )
        result = self.run_gate()
        self.assert_fail(result, "core -> feature/agent", "core/ui/Leaky.kt:3")

    def test_core_wildcard_import_fails(self):
        self.write(SRC + "core/ui/Leaky.kt", kotlin("core.ui", "import com.aicode.feature.*"))
        self.assert_fail(self.run_gate(), "core -> feature")

    def test_core_body_fully_qualified_fails(self):
        self.write(
            SRC + "core/ui/Leaky.kt",
            kotlin("core.ui", "val x = com.aicode.feature.agent.Foo()"),
        )
        self.assert_fail(self.run_gate(), "Leaky.kt:3", "全限定名")

    def test_feature_cycle_fails(self):
        self.write(
            SRC + "feature/a/A.kt",
            kotlin("feature.a", "import com.aicode.feature.b.B"),
        )
        self.write(
            SRC + "feature/b/B.kt",
            kotlin("feature.b", "import com.aicode.feature.a.A"),
        )
        result = self.run_gate()
        self.assert_fail(result, "feature/a 与 feature/b 互相依赖")

    # ---------- 禁用组件 ----------

    def test_banned_exact_import_fails(self):
        self.write(
            SRC + "feature/agent/Ui.kt",
            kotlin("feature.agent", "import androidx.compose.material3.TextField"),
        )
        self.assert_fail(self.run_gate(), "raw-textfield", "feature/agent/Ui.kt:3")

    def test_banned_alias_import_fails(self):
        self.write(
            SRC + "feature/agent/Ui.kt",
            kotlin("feature.agent", "import androidx.compose.material3.TextField as RawField"),
        )
        self.assert_fail(self.run_gate(), "别名", "Ui.kt:3")

    def test_banned_wildcard_import_fails(self):
        self.write(
            SRC + "feature/agent/Ui.kt",
            kotlin("feature.agent", "import androidx.compose.material3.*"),
        )
        self.assert_fail(self.run_gate(), "通配符")

    def test_banned_body_fully_qualified_fails(self):
        self.write(
            SRC + "feature/agent/Ui.kt",
            kotlin("feature.agent", "val field = androidx.compose.material3.TextField()"),
        )
        self.assert_fail(self.run_gate(), "Ui.kt:3", "全限定类名")

    def test_banned_symbol_inside_comment_is_ignored(self):
        self.write(
            SRC + "feature/agent/Ui.kt",
            kotlin(
                "feature.agent",
                "// import androidx.compose.material3.TextField 是禁止的",
                "val note = 1",
            ),
        )
        self.assert_pass(self.run_gate())

    def test_banned_symbol_in_allowlisted_file_passes(self):
        self.write(
            SRC + "core/ui/AppTextField.kt",
            kotlin("core.ui", "import androidx.compose.material3.TextField"),
        )
        self.assert_pass(self.run_gate())

    def test_hardcoded_color_fails(self):
        self.write(
            SRC + "feature/agent/Ui.kt",
            kotlin("feature.agent", "val accent = Color(0xFF112233)"),
        )
        self.assert_fail(self.run_gate(), "hardcoded-color", "Ui.kt:3")

    def test_hardcoded_color_in_theme_passes(self):
        self.write(
            SRC + "core/theme/Palette.kt",
            kotlin("core.theme", "val accent = Color(0xFF112233)"),
        )
        self.assert_pass(self.run_gate())

    def test_banned_count_growing_past_baseline_fails(self):
        self.write(
            SRC + "feature/agent/Ui.kt",
            kotlin(
                "feature.agent",
                "import androidx.compose.material3.TextField",
                "val second = androidx.compose.material3.TextField()",
            ),
        )
        self.write(
            ".architecture-baseline.json",
            json.dumps({"version": 1, "bannedComponents": {"raw-textfield": {SRC + "feature/agent/Ui.kt": 1}}}),
        )
        self.assert_fail(self.run_gate(), "raw-textfield", "实测 2，基线 1")

    def test_new_file_using_banned_symbol_fails(self):
        self.write(
            SRC + "feature/agent/Ui.kt",
            kotlin("feature.agent", "import androidx.compose.material3.TextField"),
        )
        self.write(
            ".architecture-baseline.json",
            json.dumps(
                {
                    "version": 1,
                    "bannedComponents": {"raw-textfield": {SRC + "feature/agent/Old.kt": 1}},
                }
            ),
        )
        self.assert_fail(self.run_gate(), "feature/agent/Ui.kt:3")

    def test_layering_count_growing_past_baseline_fails(self):
        self.write(
            SRC + "core/ui/Leaky.kt",
            kotlin(
                "core.ui",
                "import com.aicode.feature.agent.Foo",
                "import com.aicode.feature.agent.Bar",
            ),
        )
        self.write(
            ".architecture-baseline.json",
            json.dumps({"version": 1, "layering": {"core -> feature/agent": 1}}),
        )
        self.assert_fail(self.run_gate(), "core -> feature/agent", "实测 2，基线 1")

    # ---------- 单文件行数 ----------

    def oversized(self, rel_path, lines):
        self.write(rel_path, kt(*["// 行 %d" % index for index in range(1, lines + 1)]))

    def test_new_oversized_file_fails(self):
        self.oversized(SRC + "feature/agent/Big.kt", 21)
        self.assert_fail(self.run_gate(), "fileSize", "Big.kt:21", "超过上限 20")

    def test_oversized_file_within_baseline_passes(self):
        self.oversized(SRC + "feature/agent/Big.kt", 21)
        self.write(
            ".architecture-baseline.json",
            json.dumps({"version": 1, "fileLines": {SRC + "feature/agent/Big.kt": 21}}),
        )
        self.assert_pass(self.run_gate())
        self.assert_pass(self.run_gate("--mode", "ratchet"))

    def test_oversized_file_growing_past_baseline_fails(self):
        self.oversized(SRC + "feature/agent/Big.kt", 25)
        self.write(
            ".architecture-baseline.json",
            json.dumps({"version": 1, "fileLines": {SRC + "feature/agent/Big.kt": 21}}),
        )
        self.assert_fail(self.run_gate(), "实测 25，基线 21")

    def test_shrinking_below_baseline_passes_check_but_fails_ratchet(self):
        self.oversized(SRC + "feature/agent/Big.kt", 10)
        self.write(
            ".architecture-baseline.json",
            json.dumps({"version": 1, "fileLines": {SRC + "feature/agent/Big.kt": 21}}),
        )
        self.assert_pass(self.run_gate("--mode", "check"))
        result = self.run_gate("--mode", "ratchet")
        self.assert_fail(result, "实测 0 < 基线 21")

    # ---------- 中英文案 ----------

    def test_missing_english_key_fails(self):
        self.write(ZH, strings_xml("app_name", "settings_title"))
        self.write(EN, strings_xml("app_name"))
        result = self.run_gate()
        self.assert_fail(result, "settings_title", "values/strings.xml:4")

    def test_missing_chinese_key_fails(self):
        self.write(ZH, strings_xml("app_name"))
        self.write(EN, strings_xml("app_name", "only_en"))
        result = self.run_gate()
        self.assert_fail(result, "only_en", "values-en/strings.xml:4")

    def test_keys_across_multiple_files_pass(self):
        self.write(ZH, strings_xml("app_name"))
        self.write("app/src/main/res/values/extra.xml", strings_xml("extra_title"))
        self.write(EN, strings_xml("app_name"))
        self.write("app/src/main/res/values-en/extra.xml", strings_xml("extra_title"))
        self.assert_pass(self.run_gate())

    # ---------- 基线生成 ----------

    def test_write_baseline_then_gate_passes(self):
        self.write(
            SRC + "feature/agent/Ui.kt",
            kotlin("feature.agent", "import androidx.compose.material3.TextField"),
        )
        written = self.run_gate("--write-baseline")
        self.assert_pass(written)
        baseline_path = os.path.join(self.root, ".architecture-baseline.json")
        self.assertTrue(os.path.isfile(baseline_path))
        with open(baseline_path, encoding="utf-8") as handle:
            payload = json.load(handle)
        self.assertEqual(payload["bannedComponents"]["raw-textfield"][SRC + "feature/agent/Ui.kt"], 1)
        self.assert_pass(self.run_gate())
        self.assert_pass(self.run_gate("--mode", "ratchet"))

    def test_i18n_is_never_baselined(self):
        self.write(ZH, strings_xml("app_name", "only_zh"))
        self.write(EN, strings_xml("app_name"))
        self.assert_pass(self.run_gate("--write-baseline"))
        self.assert_fail(self.run_gate(), "only_zh")

    def test_write_baseline_refuses_with_only(self):
        result = self.run_gate("--write-baseline", "--only", "layering")
        self.assertEqual(result.returncode, 2)

    def test_write_baseline_refuses_to_grow(self):
        self.write(
            ".architecture-baseline.json",
            json.dumps({"version": 1, "fileLines": {SRC + "feature/agent/Big.kt": 21}}),
        )
        self.oversized(SRC + "feature/agent/Big.kt", 25)
        result = self.run_gate("--write-baseline")
        self.assert_fail(result, "拒绝写入", "基线 21 → 25")
        self.assert_fail(self.run_gate(), "实测 25，基线 21")

    def test_write_baseline_force_allows_growth(self):
        self.write(
            ".architecture-baseline.json",
            json.dumps({"version": 1, "fileLines": {SRC + "feature/agent/Big.kt": 21}}),
        )
        self.oversized(SRC + "feature/agent/Big.kt", 25)
        self.assert_pass(self.run_gate("--write-baseline", "--force"))
        self.assert_pass(self.run_gate())

    def test_write_baseline_allows_shrink(self):
        self.write(
            ".architecture-baseline.json",
            json.dumps({"version": 1, "fileLines": {SRC + "feature/agent/Big.kt": 40}}),
        )
        self.oversized(SRC + "feature/agent/Big.kt", 25)
        self.assert_pass(self.run_gate("--write-baseline"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
