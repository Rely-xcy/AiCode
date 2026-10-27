package com.aicode.feature.agent.domain.tool.root

/**
 * 状态变更命令的「回读」：命令执行完再读一次实际值，把「命令跑了」与「状态真的变了」分开告诉模型。
 *
 * 为什么要有：`am force-stop`、`settings put`、`wm size` 这类命令的退出码只表示「命令被系统接受」，
 * 退出码 0 不代表那块状态真的变成了期望值（force-stop 后进程可能还活着、settings put 可能被 ROM 忽略、
 * wm size 可能被拒绝）。模型拿着「成功」二字往下做事，错会一路传下去。
 *
 * 覆盖面刻意收窄：只对**能廉价回读**的写命令生成探针，回读一律是一两条只读查询
 * （`settings get` / `wm size` / `pidof` / `pm list packages` / `cmd uimode night`）；
 * 回读不了的（`input` 注入、`svc` 开关、广播副作用、`am start` 的前台状态等）如实标注「未确认」，
 * 不伪装成成功。判据是「一条查询就能读完」：要靠 `dumpsys activity activities` 这类重量级 dump 才看得出的
 * 状态，宁可不回读、直说未确认，也不让每次写命令都多扛一份 dump 的开销。
 *
 * [RootTool] 与 [com.aicode.feature.agent.domain.tool.shizuku.ShizukuTool] 共用本对象：两者都是
 * 「把一条命令丢给设备执行」，状态语义一致。本对象只做命令解析与判决，执行由调用方注入（便于单测）。
 *
 * 只读判定直接复用 [RootCommandGuard.isAllowlisted]：整条已在白名单内的命令本身不会改状态，不生成探针；
 * 因此白名单是唯一的「哪些算只读」的真相来源，本对象不再复述一遍（避免两边慢慢走偏）。
 */
internal object DeviceStateReadback {

    /** 一次回读命令的实际结果。 */
    data class Observation(val output: String, val exitCode: Int)

    /** 回读与实际是否对得上。[UNDETERMINED] 表示回读到了东西但判定不了（不是成功）。 */
    enum class Consistency { CONSISTENT, INCONSISTENT, UNDETERMINED }

    data class Verdict(
        val consistency: Consistency,
        /** 回读到的实际值（给模型看的原文摘要）。 */
        val observed: String,
        /** 不一致或无法判定时的补充说明。 */
        val note: String? = null
    )

    /** 一条检查：能回读的探针，或已明确无法回读的原因。 */
    sealed interface Check {
        /** 期望的状态，写给人看的一句话。 */
        val expectation: String
    }

    class Probe(
        override val expectation: String,
        /** 只读回读命令。 */
        val command: String,
        val interpret: (Observation) -> Verdict
    ) : Check

    class Unverifiable(
        override val expectation: String,
        /** 为什么回读不了（要具体到「看哪儿才能确认」）。 */
        val reason: String
    ) : Check

    /** 段分隔符：与 [RootCommandGuard] 的拆段口径一致（`;`、`&&`、`||`、换行）。 */
    private val SEGMENT_SEPARATORS = Regex("(?:&&|\\|\\||[;\\n])")

    private const val OBSERVED_LIMIT = 200

    /** Java 包名形态；回读命令要拼包名，先卡死形态，避免把任意文本拼进 shell。 */
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

    /** `包名/类名` 形态（`com.foo/.MainActivity` 与 `com.foo/com.foo.MainActivity` 都算）。 */
    private val COMPONENT = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")

    private val SETTING_NAMESPACE = Regex("system|secure|global")

    private val SETTING_KEY = Regex("[A-Za-z0-9_.\\-]+")

    /** 这些开关的取值要吃掉下一个 token（用于跳过参数找位置参数）。 */
    private val FLAGS_WITH_VALUE = setOf("--user", "--display", "-d")

    /**
     * 把回读结果拼成交给模型的一段文本；命令里没有可回读的状态变更时返回 null（此时调用方什么也不加）。
     */
    suspend fun report(command: String, run: suspend (String) -> Observation): String? {
        val checks = checksFor(command)
        if (checks.isEmpty()) return null
        val lines = checks.map { check ->
            when (check) {
                is Unverifiable -> "- 未确认｜${check.expectation}：${check.reason}"
                is Probe -> render(check, run(check.command))
            }
        }
        return "[状态回读]\n" + lines.joinToString("\n")
    }

    private fun render(probe: Probe, observation: Observation): String {
        val verdict = probe.interpret(observation)
        val label = when (verdict.consistency) {
            Consistency.CONSISTENT -> "一致"
            Consistency.INCONSISTENT -> "不一致"
            Consistency.UNDETERMINED -> "未确认"
        }
        // 只有非一致结论才报退出码：`pidof` 无进程时本来就返回 1，对「一致」而言那是正常输出。
        val exitNote = if (observation.exitCode != 0 && verdict.consistency != Consistency.CONSISTENT) {
            "（回读命令 exit=${observation.exitCode}）"
        } else {
            ""
        }
        val note = verdict.note?.let { "，$it" }.orEmpty()
        return "- $label｜${probe.expectation}：回读 `${probe.command}` 得到「${digest(verdict.observed)}」$exitNote$note"
    }

    private fun digest(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "无输出"
        return if (trimmed.length <= OBSERVED_LIMIT) trimmed else trimmed.take(OBSERVED_LIMIT) + "…"
    }

    /** 命令里能回读的状态变更检查项；空表示这条命令不需要回读。 */
    fun checksFor(command: String): List<Check> =
        command.split(SEGMENT_SEPARATORS)
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .flatMap { checksForSegment(it) }

    private fun checksForSegment(segment: String): List<Check> {
        val tokens = segment.split(' ').filter { it.isNotEmpty() }
        val head = tokens.firstOrNull() ?: return emptyList()
        // `input` 是白名单内的「无害界面操作」，但它是真的在改界面状态，单独处理。
        if (head == "input") {
            return listOf(
                Unverifiable(
                    "界面已响应注入的输入",
                    "`input` 注入的事件没有命令级回读口径；要确认请用 vscreen_observe 截图或读控件树看界面是否变化"
                )
            )
        }
        // `am` 不能靠白名单跳过：启动/停止应用这类真的改状态，而它们本身就在白名单里（见 [RootCommandGuard.advisory]）。
        if (head == "am") return amChecks(tokens)
        // 其余整条已在白名单内 = 只读调试命令，不改状态，不生成回读。
        if (RootCommandGuard.isAllowlisted(segment)) return emptyList()
        return when (head) {
            "settings" -> settingsChecks(tokens)
            "wm" -> windowChecks(tokens)
            "pm" -> packageManagerChecks(tokens)
            "cmd" -> cmdChecks(tokens)
            "svc" -> listOf(
                Unverifiable(
                    "系统开关已切换",
                    "`svc` 没有稳定的只读回读命令；要确认请针对具体项查（如 `settings get global stay_on_while_plugged_in`、`cmd wifi status`）"
                )
            )
            else -> emptyList()
        }
    }

    // ---- settings ----

    private fun settingsChecks(tokens: List<String>): List<Check> {
        val sub = tokens.getOrNull(1) ?: return emptyList()
        val namespace = tokens.getOrNull(2)
        val key = tokens.getOrNull(3)
        if (namespace == null || key == null) return emptyList()
        if (!SETTING_NAMESPACE.matches(namespace) || !SETTING_KEY.matches(key)) return emptyList()
        val readback = "settings get $namespace $key"
        return when (sub) {
            "put" -> {
                if (tokens.size < 5) return emptyList()
                val expected = tokens.drop(4).joinToString(" ")
                listOf(
                    Probe("`$namespace/$key` 应为 $expected", readback) { observation ->
                        compareSetting(observation, expected)
                    }
                )
            }
            "delete", "reset" -> listOf(
                Probe("`$namespace/$key` 应已无值（回读 null）", readback) { observation ->
                    compareSetting(observation, "null")
                }
            )
            else -> emptyList()
        }
    }

    /** `settings get` 对未设置的键返回 `null`；写空串时回读可能是空行，两者都算「空」。 */
    private fun compareSetting(observation: Observation, expected: String): Verdict {
        val actual = observation.output.trim()
        val want = expected.trim()
        val consistent = if (want.isEmpty() || want == "null") {
            actual.isEmpty() || actual == "null"
        } else {
            actual == want
        }
        return if (consistent) {
            Verdict(Consistency.CONSISTENT, actual.ifEmpty { "null" })
        } else {
            Verdict(Consistency.INCONSISTENT, actual.ifEmpty { "null" }, "期望 $want")
        }
    }

    // ---- wm ----

    private fun windowChecks(tokens: List<String>): List<Check> {
        val sub = tokens.getOrNull(1) ?: return emptyList()
        val args = tokens.drop(2)
        if (args.size != 1) return emptyList()
        val value = args[0]
        val isReset = value.equals("reset", ignoreCase = true)
        return when (sub) {
            "size" -> {
                if (!isReset && !Regex("\\d+x\\d+").matches(value)) return emptyList()
                val expectation = if (isReset) "`wm size` 应无 Override size 行（已恢复物理尺寸）" else "`wm size` 的 Override size 应为 $value"
                listOf(Probe(expectation, "wm size") { observation -> compareWindow(observation, "Override size", value, isReset) })
            }
            "density" -> {
                if (!isReset && value.toIntOrNull() == null) return emptyList()
                val expectation = if (isReset) "`wm density` 应无 Override density 行（已恢复物理密度）" else "`wm density` 的 Override density 应为 $value"
                listOf(Probe(expectation, "wm density") { observation -> compareWindow(observation, "Override density", value, isReset) })
            }
            else -> emptyList()
        }
    }

    /**
     * `wm size` 打印 `Physical size: 1080x2400` 与（设过覆盖值时）`Override size: 720x1600`。
     * `reset` 之后 Override 行消失，用「有没有这一行」判定，比对比数值可靠。
     */
    private fun compareWindow(observation: Observation, label: String, expected: String, isReset: Boolean): Verdict {
        val line = observation.output.lineSequence()
            .firstOrNull { it.trim().startsWith("$label:", ignoreCase = true) }
            ?.trim()
        if (isReset) {
            return if (line == null) {
                Verdict(Consistency.CONSISTENT, "无 $label 行")
            } else {
                Verdict(Consistency.INCONSISTENT, line, "覆盖值仍在生效")
            }
        }
        if (line == null) {
            return Verdict(Consistency.INCONSISTENT, "无 $label 行", "期望的覆盖值没有出现在回读里")
        }
        val actual = line.substringAfter(':').trim()
        return if (actual == expected) {
            Verdict(Consistency.CONSISTENT, line)
        } else {
            Verdict(Consistency.INCONSISTENT, line, "期望 $expected")
        }
    }

    // ---- am ----

    private fun amChecks(tokens: List<String>): List<Check> {
        val sub = tokens.getOrNull(1) ?: return emptyList()
        return when (sub) {
            "start", "start-activity" -> startChecks(tokens)
            "force-stop", "kill" -> stopChecks(tokens)
            "startservice", "start-service", "stopservice", "stop-service" -> listOf(
                Unverifiable(
                    "目标服务状态已改变",
                    "服务启停没有稳定的只读回读口径（`dumpsys activity services` 的输出随 Android 版本与 ROM 变化）；" +
                        "要确认请自己跑 `dumpsys activity services <包名>` 对照"
                )
            )
            "broadcast" -> listOf(
                Unverifiable(
                    "广播接收方已处理",
                    "广播的副作用在接收方，工具侧读不到；命令 output 里的 `Broadcast completed: result=…` 只表示投递结果"
                )
            )
            "kill-all" -> listOf(
                Unverifiable("后台进程已被清理", "`am kill-all` 清理的是一批后台进程，没有对应的只读回读口径")
            )
            "send-trim-memory" -> listOf(
                Unverifiable("目标进程已被要求回收内存", "内存回收由系统异步执行，没有只读命令能证明回收已发生")
            )
            else -> emptyList()
        }
    }

    /**
     * 启动**不回读**：判断「目标是否真的到了前台」只能靠 `dumpsys activity activities`，
     * 而那份 dump 每跑一次都是几万个字符，给每次 `am start` 都加一份不划算。
     * 所以这里如实标「已执行，未确认生效」，并说明模型自己怎么确认，而不是从 `exit=0` 推出启动成功。
     */
    private fun startChecks(tokens: List<String>): List<Check> {
        val pkg = valueOfFlag(tokens, "-p")?.takeIf { it.isNotEmpty() } ?: componentPackage(tokens)
        val expectation = if (pkg != null && PACKAGE.matches(pkg)) "`$pkg` 是否已在前台" else "目标 Activity 是否已到前台"
        return listOf(
            Unverifiable(
                expectation,
                "已执行但未回读：`am start` 的退出码只表示系统接受了启动请求；确认前台要读 `dumpsys activity activities`，" +
                    "代价太大，本工具不额外跑。需要确认请自行执行 dumpsys，或用截图 / 控件树看界面"
            )
        )
    }

    /** 停止后的回读：`pidof` 无输出即进程已不在（无进程时 pidof 退出码为 1，属正常）。 */
    private fun stopChecks(tokens: List<String>): List<Check> {
        val pkg = firstPositional(tokens, from = 2) ?: return listOf(
            Unverifiable("目标应用已停止", "命令里解析不出目标包名，无法回读进程状态")
        )
        if (!PACKAGE.matches(pkg)) {
            return listOf(Unverifiable("目标应用已停止", "包名形态不合法（$pkg），不回读"))
        }
        return listOf(
            Probe("`$pkg` 应已无进程", "pidof $pkg") { observation ->
                val pid = observation.output.trim()
                if (pid.isEmpty()) {
                    Verdict(Consistency.CONSISTENT, "无输出（无该包进程）")
                } else {
                    Verdict(Consistency.INCONSISTENT, "pid=$pid", "进程仍在运行")
                }
            }
        )
    }

    // ---- pm ----

    private fun packageManagerChecks(tokens: List<String>): List<Check> {
        val sub = tokens.getOrNull(1) ?: return emptyList()
        val pkg = firstPositional(tokens, from = 2)
        return when (sub) {
            "disable" -> disabledProbe(pkg, expectedDisabled = true)
            "enable" -> disabledProbe(pkg, expectedDisabled = false)
            "uninstall" -> {
                if (pkg == null || !PACKAGE.matches(pkg)) {
                    listOf(Unverifiable("应用已卸载", "命令里解析不出目标包名，无法回读包是否还在"))
                } else {
                    listOf(
                        Probe("`$pkg` 不应再出现在已安装列表里", "pm list packages $pkg") { observation ->
                            if (listsPackage(observation, pkg)) {
                                Verdict(Consistency.INCONSISTENT, "仍列出 package:$pkg", "包还在")
                            } else {
                                Verdict(Consistency.CONSISTENT, "未列出 $pkg")
                            }
                        }
                    )
                }
            }
            "clear" -> listOf(
                Unverifiable(
                    "应用数据已清空",
                    "`pm clear` 抹的是 `/data/data/<包名>`，没有只读命令能证明它为空；命令 output 里的 Success 是唯一信号"
                )
            )
            "install" -> listOf(
                Unverifiable(
                    "apk 已安装",
                    "安装的目标包名要从 apk 里读，工具侧解析不出；看命令 output 的 Success / Failure"
                )
            )
            "grant", "revoke" -> listOf(
                Unverifiable(
                    "权限已变更",
                    "`dumpsys package` 里 grantedPermissions 段的输出随版本变化，没有稳定判定口径"
                )
            )
            else -> emptyList()
        }
    }

    private fun disabledProbe(pkg: String?, expectedDisabled: Boolean): List<Check> {
        if (pkg == null || !PACKAGE.matches(pkg)) {
            return listOf(Unverifiable("应用启停状态已改变", "命令里解析不出目标包名，无法回读"))
        }
        val expectation = if (expectedDisabled) "`$pkg` 应出现在 `pm list packages -d` 里" else "`$pkg` 不应出现在 `pm list packages -d` 里"
        return listOf(
            Probe(expectation, "pm list packages -d $pkg") { observation ->
                val listed = listsPackage(observation, pkg)
                when {
                    listed == expectedDisabled -> Verdict(
                        Consistency.CONSISTENT,
                        if (listed) "package:$pkg" else "未列出 $pkg"
                    )
                    else -> Verdict(
                        Consistency.INCONSISTENT,
                        if (listed) "package:$pkg" else "未列出 $pkg",
                        if (expectedDisabled) "仍处于启用状态" else "仍处于停用状态"
                    )
                }
            }
        )
    }

    private fun listsPackage(observation: Observation, pkg: String): Boolean =
        observation.output.lineSequence().any { it.trim() == "package:$pkg" }

    // ---- cmd ----

    private fun cmdChecks(tokens: List<String>): List<Check> {
        val service = tokens.getOrNull(1) ?: return emptyList()
        val sub = tokens.getOrNull(2) ?: return emptyList()
        if (service == "uimode" && sub == "night" && tokens.size >= 4) {
            val want = tokens[3]
            return listOf(
                Probe("夜间模式应为 $want", "cmd uimode night") { observation ->
                    compareNight(observation, want)
                }
            )
        }
        return listOf(
            Unverifiable(
                "系统状态已改变",
                "`cmd $service $sub` 没有统一的只读回读口径；要确认请改用对应的查询子命令（如 `cmd wifi status`、`cmd uimode night`）"
            )
        )
    }

    private fun compareNight(observation: Observation, expected: String): Verdict {
        val line = observation.output.lineSequence().firstOrNull { it.contains("Night mode", ignoreCase = true) }
            ?: return Verdict(Consistency.UNDETERMINED, observation.output, "回读里没有 Night mode 行，无法判定")
        val actual = line.substringAfter(':', "").trim()
        return if (actual.equals(expected, ignoreCase = true)) {
            Verdict(Consistency.CONSISTENT, line.trim())
        } else {
            Verdict(Consistency.INCONSISTENT, line.trim(), "期望 $expected")
        }
    }

    // ---- 参数解析 ----

    private fun valueOfFlag(tokens: List<String>, flag: String): String? {
        val index = tokens.indexOf(flag)
        if (index < 0) return null
        return tokens.getOrNull(index + 1)
    }

    private fun componentPackage(tokens: List<String>): String? {
        valueOfFlag(tokens, "-n")?.let { component ->
            if (COMPONENT.matches(component)) return component.substringBefore('/')
        }
        val component = tokens.firstOrNull { COMPONENT.matches(it) } ?: return null
        return component.substringBefore('/')
    }

    /** 找到位置参数（跳过 `-x` 这类开关，以及 `--user 0` 这种带值的开关）。 */
    private fun firstPositional(tokens: List<String>, from: Int): String? {
        var index = from
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.startsWith("-")) {
                index += if (token in FLAGS_WITH_VALUE) 2 else 1
                continue
            }
            return token
        }
        return null
    }
}
