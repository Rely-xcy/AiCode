package com.aicode.feature.agent.domain.tool.root

/**
 * Root 命令安全闸门：以 uid 0 在**设备**上执行前的最后一道硬拦截。
 *
 * 与容器里的 `Bash` 不同，这里的命令直接作用于用户手机的系统分区与存储；一旦写坏分区表、
 * 刷坏 boot、或误删系统目录，损失不可逆（轻则开不了机、重则变砖）。因此本闸门**只认代码**，
 * 不依赖提示词劝说，也不受 AUTO 模式影响。
 *
 * 设计原则（按优先级）：
 * 1. **白名单**：AI 只能执行首 token 在 [ALLOWED_COMMANDS] 内的命令（只读调试、只读过滤、受控暂存写）；
 * 2. **黑名单**：即使白名单内，仍逐条检查不可逆动作（删系统/刷机/写块设备/进刷机模式/清数据）；
 * 3. **拼接拦截**：命令替换、eval、管道进 shell 一律拒——它们能让上面的静态判定失效；普通管道按段审核；
 * 4. **受控暂存写**：`mkdir`/`cp`/`mv`/`rm`/`chmod` 只允许 `/data/local/tmp` 与 `Download`，其余路径仍拒；
 * 5. **无解锁口**：不存在 allowUnsafe 之类的参数；能改系统的操作不由 AI 决定，一律回给用户。
 *
 * 纯字符串逻辑、无 IO，便于单测与逐条 review。
 */
object RootCommandGuard {

    /** 设备上不允许被命令写入/删除的系统面（命中即拒）。含 /data 下的系统级存储（删除等同恢复出厂的一部分）。 */
    private val PROTECTED_PATHS = listOf(
        "/system", "/vendor", "/product", "/system_ext", "/odm",
        "/boot", "/recovery", "/init_boot", "/dtbo", "/vbmeta",
        "/persist", "/efs", "/firmware", "/modem", "/modemst1", "/modemst2",
        "/dev/block", "/dev/mtd", "/proc", "/sys", "/metadata",
        "/data/system", "/data/property", "/data/adb"
    )

    /** 用户数据根：只对「整树删除」生效（子路径照常放行），见 [isWholeTreeDataRoot]。 */
    private val WHOLE_TREE_DATA_ROOTS = listOf("/data", "/sdcard", "/storage", "/mnt")

    /** 直接写块设备 / 分区表 / 刷机的工具：这些命令写错一个字节就是变砖。 */
    private val FLASH_TOOLS = listOf(
        "fastboot", "flash_image", "flash_erase", "mkfs", "mke2fs", "make_ext4fs",
        "tune2fs", "fdisk", "sgdisk", "parted"
    )

    /** 会清掉数据或进特殊刷机模式的命令。 */
    private val DESTRUCTIVE_KEYWORDS = listOf(
        "MASTER_CLEAR", "--wipe_data", "wipe_data", "wipe_cache", "factory_reset"
    )

    private val REBOOT_TARGETS = listOf("bootloader", "fastboot", "edl", "recovery", "recovery-mode")

    /**
     * 默认放行的命令**首 token**（只读调试、只读过滤与受控暂存写）。
     *
     * 不在名单里的命令默认拒绝且**无解锁口**（不存在 allowUnsafe 之类参数）——这是「可控范围」的
     * 主要手段：AI 默认只能做只读/调试类的事与暂存目录内的整理；能改系统的操作必须由用户自己在终端执行。
     *
     * 维护注意：新增条目必须是「只读、只读过滤或受控暂存写」的命令首 token；有写能力但不在暂存目录的
     * （改系统设置、装应用、service call 等）一律不进名单。首 token 进名单**不**等于整条放行：`settings`/`cmd`/`pm`
     * 只放只读子命令，`service`/`wm`/`am`/`logcat`/`mount` 另有子命令判定，见 [decideSegment]。
     */
    private val ALLOWED_COMMANDS = setOf(
        // 只读系统信息
        "dumpsys", "getprop", "ls", "cat", "ps", "pidof", "top", "df", "du", "stat",
        "id", "date", "uptime", "uname", "whoami", "logcat", "service",
        // 输入注入与取帧（虚拟屏自动化测试用）；settings/cmd 另按只读子命令放行
        "input", "screencap", "wm", "settings", "cmd",
        // 应用信息与控制（不改系统分区）
        "pm", "am", "monkey",
        // 其它只读工具
        "netstat", "ifconfig", "ip", "mount", "free", "md5sum", "sha256sum",
        // 只读过滤：查日志/查报错必须有管道过滤，否则大输出只能被截断
        "grep", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "sed", "find",
        // 受控暂存写：只在 STAGING_ROOTS 内放行，见 decideSegment
        "mkdir", "cp", "mv", "rm", "chmod"
    )

    /** 只允许读的 `settings` 子命令；写子命令一律不在默认白名单。 */
    private val SETTINGS_READ_ONLY = listOf("settings get", "settings list")

    /**
     * 只允许读的 `cmd` 子命令。
     *
     * `cmd` 是分发器：`cmd package clear` 抹掉应用数据、`cmd notification post` 发通知、
     * `cmd wifi set-wifi-enabled` 改开关——写子命令一律不在默认白名单（维护时只加读类前缀）。
     *
     * `cmd window` 不在这张表里：它与 `wm` 是同一批窗口管理命令，`cmd window size 1080x1920`
     * 同样会持久改写显示配置，因此交给 [decideWindow] 逐个子命令判定。
     *
     * 同一服务的读/写两种子命令共用同一个词根时（`cmd uimode night` 查询 vs `night yes` 写入）
     * 无法用前缀区分，改由 [CMD_QUERY_ONLY_SERVICES] 与 [CMD_QUERY_EXACT] 判定。
     */
    private val CMD_READ_ONLY = listOf(
        "cmd package list", "cmd package path", "cmd package dump", "cmd package query-",
        "cmd activity get-", "cmd activity dump", "cmd activity lastanr",
        "cmd activity stack list", "cmd activity task list", "cmd activity package",
        "cmd overlay list",
        "cmd wifi status", "cmd wifi list-", "cmd notification list", "cmd notification get-",
        "cmd stats print", "cmd stats read", "cmd deviceidle get",
        "cmd user list", "cmd dpm list",
        "cmd netpolicy list", "cmd shortcut get-", "cmd lock_settings get-",
        "cmd bluetooth_manager get-"
    )

    /**
     * `cmd` 里「裸调用与 `help` 只打印、再带一个取值才写」的服务。
     *
     * 这几条以前按前缀放行整段，等于把写分支一起放行：`cmd power set-mode 2` 改电源模式、
     * `cmd uimode night yes` 改深色模式、`cmd webviewupdate set-webview-implementation` 换 WebView 实现、
     * `cmd deviceidle whitelist +pkg` 改电池白名单。与「只放明确只读子命令」相悖，改为只认裸调用与 `help`，
     * 真正的查询子命令另有 [CMD_QUERY_EXACT] 逐条列。
     */
    private val CMD_QUERY_ONLY_SERVICES = setOf(
        "power", "uimode", "thermalservice", "webviewupdate", "input_method", "deviceidle"
    )

    /** [CMD_QUERY_ONLY_SERVICES] 里「不带取值就是查询」的子命令；带上取值即写，不能按前缀放行。 */
    private val CMD_QUERY_EXACT = setOf(
        "cmd uimode night", "cmd uimode car", "cmd deviceidle whitelist"
    )

    /**
     * 只允许读的 `pm` 子命令：应用信息查询。
     *
     * `pm clear` / `pm uninstall` 会不可逆地抹掉应用数据，`pm install`/`enable`/`grant` 改写包状态，
     * 一律不在默认白名单——注释里写的就是「应用信息与控制（不改系统分区）」，读不到的子命令就该拒。
     */
    private val PM_READ_ONLY = listOf(
        "pm list", "pm path", "pm dump", "pm get-", "pm features", "pm query-", "pm resolve-activity"
    )

    /**
     * `wm` / `cmd window` 里 `size`、`density` 这类子命令：不带参数（或只带 `-d <displayId>`）时
     * 只打印当前值，带 `WxH` / 数值 / `reset` 才走 setForcedDisplaySize / setForcedDisplayDensity——
     * 那是持久改写，重启后仍然生效，改错还会让界面点不到。
     */
    private val WINDOW_QUERY_SUBCOMMANDS = setOf("size", "density")

    /** `wm` / `cmd window` 里纯打印的子命令；带别的参数就可能是写分支，只放查询口径。 */
    private val WINDOW_READ_ONLY_SUBCOMMANDS = setOf(
        "get-ignore-orientation-request", "get-letterbox-style",
        "get-multi-window-config", "get-display-windowing-mode", "help"
    )

    /**
     * `am` 里会改变运行状态、但属常用调试手段的子命令：启动或停掉某个 app、发条广播看现象。
     *
     * 这类**不**直接拒——一刀切会把「启动/停掉目标 app 再观察」这条基本调试链砍掉；改为放行并在
     * 授权弹窗里标注（[advisory]），让用户点「允许」前知道它会改状态。
     */
    private val AM_STATE_CHANGING = setOf(
        "start", "start-activity", "startservice", "start-service",
        "stopservice", "stop-service", "broadcast", "force-stop",
        "kill", "kill-all", "send-trim-memory"
    )

    /** `am` 里纯打印的只读子命令。 */
    private val AM_READ_ONLY = setOf("help", "get-config", "get-current-user")

    /**
     * `am` 里能加载代码 / attach 进程 / 常驻不返回 / 改调试状态的子命令：连同拒绝理由一起列出来,
     * 好让模型看懂为什么被拒，而不是换条写法继续试。
     */
    private val AM_REJECTED_REASONS = mapOf(
        "instrument" to "会在目标进程里加载并执行 instrumentation 代码",
        "monitor" to "会常驻不返回，一直占住这条 root 通道",
        "dumpheap" to "会 attach 到目标进程并把堆转储成文件",
        "profile" to "会 attach 到目标进程并写采样文件",
        "set-debug-app" to "会把目标应用改成等待调试器，影响它的启动行为",
        "clear-debug-app" to "会改目标应用的调试状态"
    )

    /** 允许 AI 写入/整理的暂存目录；其余目录仍拒绝。 */
    private val STAGING_ROOTS = listOf(
        "/data/local/tmp",
        "/sdcard/download",
        "/storage/emulated/0/download",
        "/storage/self/primary/download"
    )

    /** find 里会写文件或执行命令的谓词，一律拒绝。 */
    private val FIND_WRITE_PREDICATES = setOf(
        "-delete", "-exec", "-execdir", "-ok", "-okdir",
        "-fprint", "-fprintf", "-fls", "-fprint0"
    )

    /** sed 只放行「行号/范围 + p」的静默打印脚本，堵住 w/e/r/s 等写分支。 */
    private val SED_SAFE_SCRIPT = Regex("^[0-9,$;!~* ]*p$")

    private val CHMOD_MODE = Regex("^[0-7]{3,4}$")

    /** 重定向目标：只允许暂存目录与 /dev/null。 */
    private val REDIRECT_TARGET = Regex("(?:^|\\s)\\d?>>?\\s*([^\\s;&|]+)")

    data class Rejection(val reason: String)

    /** 单段判定结果：放行，或拒绝（[reason] 为空表示由调用方给通用文案）。 */
    private class Decision(val allowed: Boolean, val reason: String? = null)

    /** 段分隔符：`;`、`&&`、`||`、`|`、换行。 */
    private val SEGMENT_SEPARATORS = Regex("(?:&&|\\|\\||[;|\\n])")

    /**
     * 命令是否**整条**都在默认白名单内：先按 [SEGMENT_SEPARATORS] 拆段，再逐段判定。
     *
     * 只看首个 token 是不够的：`ls ;pm clear com.foo`、`ls &&rm -rf /data/system` 会以 `ls`
     * 混过闸门，而后半段仍以 uid 0 执行。
     */
    fun isAllowlisted(command: String): Boolean {
        val segments = splitSegments(command)
        if (segments.isEmpty()) return false
        return segments.all { isSegmentAllowlisted(it) }
    }

    /**
     * 首 token 在白名单内、但子命令越权时的**具体**拒绝原因；null 表示没有这类拒绝。
     *
     * 与 [isAllowlisted] 共用 [splitSegments] 与 [decideSegment]：子命令约束必须落在拼接拆分之后，
     * 否则 `ls ;service call ...` 能借前半段混过判定。首 token 不在白名单的段不产生原因，
     * 由调用方给通用文案。
     */
    fun rejectionReason(command: String): String? {
        for (segment in splitSegments(command)) {
            decideSegment(segment).reason?.let { return it }
        }
        return null
    }

    /**
     * 放行但会改变运行状态的命令（目前是 `am` 的写子命令）：上层把它并进授权弹窗文案。
     *
     * 这类命令是常用调试手段，不直接拒；但用户点「允许」前应当知道它不是只读查询。
     */
    fun advisory(command: String): String? {
        val changesState = splitSegments(command).any { segment ->
            val tokens = segment.split(' ')
            tokens.firstOrNull() == "am" && tokens.getOrNull(1)?.let { it in AM_STATE_CHANGING } == true
        }
        return if (changesState) {
            "注意：该命令会改变设备运行状态（启动/停止应用、发送广播等），不是只读查询。"
        } else {
            null
        }
    }

    /** 按 [SEGMENT_SEPARATORS] 拆段并归一化（小写、单空格、去空段）。 */
    private fun splitSegments(command: String): List<String> =
        command.split(SEGMENT_SEPARATORS)
            .map { it.replace(Regex("\\s+"), " ").trim().lowercase() }
            .filter { it.isNotEmpty() }

    /** 单段判定：首 token 在名单内，且该命令的子命令（有约束的）也在放行面内。 */
    private fun isSegmentAllowlisted(segment: String): Boolean = decideSegment(segment).allowed

    /**
     * 一行（已拆段、已归一化的）命令的放行判定。
     *
     * 只有首 token 进 [ALLOWED_COMMANDS] 是不够的：`service call` 能调任意 binder、`wm size WxH`
     * 会持久改显示配置、`monkey` 能向任意 app 灌事件。因此每类有写能力的命令按「已知安全才放行」
     * 逐个子命令判定，拒绝时带上可读原因（[Decision.reason]）。
     */
    private fun decideSegment(segment: String): Decision {
        val tokens = segment.split(' ').filter { it.isNotEmpty() }
        val first = tokens.firstOrNull() ?: return Decision(false)
        if (first !in ALLOWED_COMMANDS) return Decision(false)
        return when (first) {
            "settings" -> Decision(SETTINGS_READ_ONLY.any { segment.startsWith(it) })
            "cmd" -> decideCmd(tokens)
            "pm" -> Decision(PM_READ_ONLY.any { segment.startsWith(it) })
            "wm" -> decideWindow("wm", tokens.drop(1))
            "service" -> decideService(tokens)
            "logcat" -> decideLogcat(tokens)
            "mount" -> decideMount(tokens)
            "am" -> decideAm(tokens)
            "grep" -> decideFilter(tokens)
            "head", "tail", "wc", "sort", "uniq", "cut", "tr" -> decideFilter(tokens)
            "sed" -> decideSed(tokens)
            "find" -> decideFind(tokens)
            "mkdir" -> decideMkdir(tokens)
            "cp" -> decideCopy(tokens)
            "mv" -> decideMove(tokens)
            "rm" -> decideRemove(tokens)
            "chmod" -> decideChmod(tokens)
            "monkey" -> Decision(
                false,
                "拒绝执行：`monkey` 会向任意应用灌入随机事件（等价于脚本化点击/输入，且不区分目标），已拦截。" +
                    "界面自动化请用 `input`（可按 `input -d <displayId> ...` 指定显示屏）。"
            )
            else -> Decision(true)
        }
    }

    /**
     * `cmd` 逐个子命令判定：`cmd window` 交给 [decideWindow]（与 `wm` 是同一批窗口管理命令），
     * 其余按 [CMD_READ_ONLY] 的只读前缀放行。
     */
    private fun decideCmd(tokens: List<String>): Decision {
        if (tokens.getOrNull(1) == "window") return decideWindow("cmd window", tokens.drop(2))
        val segment = tokens.joinToString(" ")
        if (segment in CMD_QUERY_EXACT) return Decision(true)
        val service = tokens.getOrNull(1)
        if (service in CMD_QUERY_ONLY_SERVICES) {
            val isBareQuery = tokens.size == 2
            val isHelp = tokens.size == 3 && tokens[2] == "help"
            if (isBareQuery || isHelp) return Decision(true)
            return Decision(
                false,
                "拒绝执行：`cmd $service` 只放行不带参数的查询与 `help`（`$segment` 会改写系统状态），已拦截。" +
                    "看当前状态用 `cmd $service` 或 `dumpsys`，改状态请让用户在终端执行。"
            )
        }
        return Decision(CMD_READ_ONLY.any { segment.startsWith(it) })
    }

    /**
     * `wm` 与 `cmd window` 共用这套判定：两者是同一批窗口管理命令，只收紧 `wm` 而继续放行
     * `cmd window` 等于没收紧（`cmd window size 1080x1920` 同样会持久改写显示配置）。
     *
     * [label] 只用于错误文案，让模型看到的拒绝理由和它实际写的那条命令对得上。
     */
    private fun decideWindow(label: String, args: List<String>): Decision {
        val sub = args.firstOrNull()
            ?: return Decision(
                false,
                "拒绝执行：`$label` 需要子命令；只放行只读查询（如 `$label size`、`$label density`），" +
                    "改显示配置请让用户在终端执行。"
            )
        val rest = args.drop(1)
        return when {
            sub in WINDOW_QUERY_SUBCOMMANDS -> {
                if (isDisplayQueryOnly(rest)) {
                    Decision(true)
                } else {
                    Decision(
                        false,
                        "拒绝执行：`$label $sub ${rest.joinToString(" ")}` 会持久改写显示配置" +
                            "（改错后界面可能无法操作，重启后仍然生效），已拦截。" +
                            "只放行不带参数的查询 `$label $sub`；要改请让用户在终端执行。"
                    )
                }
            }
            sub in WINDOW_READ_ONLY_SUBCOMMANDS -> {
                val allowed = if (sub == "help") rest.isEmpty() else isDisplayQueryOnly(rest)
                if (allowed) {
                    Decision(true)
                } else {
                    Decision(false, "拒绝执行：`$label $sub` 只放行不带参数或仅带 `-d <displayId>` 的查询。")
                }
            }
            else -> Decision(
                false,
                "拒绝执行：`$label $sub` 不在只读白名单内（只放行 `size`/`density` 的参数查询与 `get-*` 查询）；" +
                    "看显示屏参数请用 `dumpsys display`，改配置请让用户在终端执行。"
            )
        }
    }

    /** 只带显示屏选择参数（`-d <id>`，`density` 另支持 `-u <uniqueId>`）时仍是查询，不是改写。 */
    private fun isDisplayQueryOnly(rest: List<String>): Boolean {
        if (rest.isEmpty()) return true
        if (rest.size != 2) return false
        return when (rest[0]) {
            "-d" -> rest[1].all { it.isDigit() }
            "-u" -> rest[1].isNotEmpty()
            else -> false
        }
    }

    /**
     * 只读过滤命令：允许从 stdin 读（管道场景），带绝对路径的参数只允许落在暂存目录。
     *
     * 这条是「查日志/查报错」的关键：没有它，`logcat -d` 的大输出只能整套回传，
     * 再被 BoundedOutput 截成头尾，真正的异常常常正好被截掉。
     */
    private fun decideFilter(tokens: List<String>): Decision {
        val head = tokens.first()
        val args = tokens.drop(1)
        if (head == "tail" && args.any { it == "-f" || it == "-F" || it.startsWith("--follow") }) {
            return Decision(
                false,
                "拒绝执行：`tail -f` 会常驻不返回并占住 root 通道；请用 `tail -n <行数>` 取完即退。"
            )
        }
        if (args.any { it.contains("..") }) {
            return Decision(false, "拒绝执行：路径不允许包含 `..`，请使用暂存目录内的明确路径。")
        }
        val absolute = args.filter { it.startsWith("/") }
        val bad = absolute.firstOrNull { !isStagingPath(it) }
        if (bad != null) {
            return Decision(
                false,
                "拒绝执行：`$head` 只允许读取暂存目录内的文件（/data/local/tmp 或 Download）；`$bad` 不在其中。"
            )
        }
        // sort -o 是写文件，目标必须是暂存目录（绝对路径已在上面的 absolute 检查里被约束）。
        if (head == "sort") {
            val outIndex = args.indexOfFirst { it == "-o" || it.startsWith("-o") }
            if (outIndex >= 0) {
                val target = if (args[outIndex] == "-o") args.getOrNull(outIndex + 1) else args[outIndex].removePrefix("-o")
                if (target.isNullOrBlank() || !isStagingPath(target)) {
                    return Decision(false, "拒绝执行：`sort -o` 只能写到暂存目录。")
                }
            }
            val longOutput = args.firstOrNull { it.startsWith("--output=") }
            if (longOutput != null && !isStagingPath(longOutput.substringAfter('='))) {
                return Decision(false, "拒绝执行：`sort --output=` 只能写到暂存目录。")
            }
        }
        return Decision(true)
    }

    /** `sed` 只放行 `-n` + 行号/范围 + `p`，堵住 `-i`、`s///w`、`e`、`r` 等写分支。 */
    private fun decideSed(tokens: List<String>): Decision {
        val args = tokens.drop(1)
        val quiet = args.firstOrNull() == "-n" || args.firstOrNull() == "--quiet"
        if (!quiet) {
            return Decision(false, "拒绝执行：`sed` 只放行 `-n` 静默打印，不支持 `-i` 或写文件。")
        }
        val script = args.getOrNull(1)?.trim('\'', '"') ?: return Decision(false, "拒绝执行：`sed -n` 缺少脚本。")
        if (!SED_SAFE_SCRIPT.matches(script)) {
            return Decision(false, "拒绝执行：`sed` 只放行行号/范围加 `p` 的打印脚本，避免 `s///w`、`e`、`r` 等写分支。")
        }
        val files = args.drop(2).filterNot { it.startsWith("-") }
        if (files.any { it.contains("..") }) {
            return Decision(false, "拒绝执行：`sed` 的文件路径不允许包含 `..`。")
        }
        val bad = files.filter { it.startsWith("/") }.firstOrNull { !isStagingPath(it) }
        if (bad != null) {
            return Decision(false, "拒绝执行：`sed` 只允许读取暂存目录内的文件；`$bad` 不在其中。")
        }
        return Decision(true)
    }

    /** `find` 只读：路径必须在暂存目录，且不允许 `-delete`/`-exec` 等写谓词。 */
    private fun decideFind(tokens: List<String>): Decision {
        val args = tokens.drop(1)
        val writePredicate = args.firstOrNull { it in FIND_WRITE_PREDICATES }
        if (writePredicate != null) {
            return Decision(false, "拒绝执行：`find $writePredicate` 会写文件或执行命令，已拦截。")
        }
        val paths = args.takeWhile { !it.startsWith("-") }
        if (paths.isEmpty()) {
            return Decision(false, "拒绝执行：`find` 必须指定暂存目录（/data/local/tmp 或 Download），不接受根目录/相对路径。")
        }
        val bad = paths.firstOrNull { it.contains("..") || !isStagingPath(it) }
        if (bad != null) {
            return Decision(false, "拒绝执行：`find` 只允许在暂存目录内检索；`$bad` 不在其中。")
        }
        return Decision(true)
    }

    /** `mkdir` 只允许 `-p`，目标必须在暂存目录。 */
    private fun decideMkdir(tokens: List<String>): Decision {
        val args = tokens.drop(1)
        if (args.any { it.startsWith("-") && it != "-p" }) {
            return Decision(false, "拒绝执行：`mkdir` 只放行 `-p`，不接受改权限等其它参数。")
        }
        return decideStagingPaths(
            args.filterNot { it.startsWith("-") },
            "`mkdir` 只允许在暂存目录内创建目录"
        )
    }

    /** `cp`：源可读任意非受保护路径，目标必须在暂存目录。 */
    private fun decideCopy(tokens: List<String>): Decision {
        val args = tokens.drop(1)
        val allowedFlags = setOf("-r", "-R", "-a", "-p", "-f", "-v", "--recursive", "--archive", "--preserve")
        val badFlag = args.firstOrNull { it.startsWith("-") && it !in allowedFlags }
        if (badFlag != null) return Decision(false, "拒绝执行：`cp $badFlag` 不在受控参数内。")
        val paths = args.filterNot { it.startsWith("-") }
        if (paths.size < 2) return Decision(false, "拒绝执行：`cp` 需要源路径和目标路径。")
        val dest = paths.last()
        if (!isStagingPath(dest)) {
            return Decision(false, "拒绝执行：`cp` 的目标只能是暂存目录（/data/local/tmp 或 Download）；`$dest` 不在其中。")
        }
        val badSource = paths.dropLast(1).firstOrNull { !isReadableSource(it) }
        if (badSource != null) {
            return Decision(false, "拒绝执行：`cp` 不能从根目录或受保护系统路径复制；`$badSource` 不允许。")
        }
        return Decision(true)
    }

    /** `mv`：源和目标都必须在暂存目录，避免把系统/应用文件移走。 */
    private fun decideMove(tokens: List<String>): Decision {
        val args = tokens.drop(1)
        val allowedFlags = setOf("-f", "-v", "--force")
        val badFlag = args.firstOrNull { it.startsWith("-") && it !in allowedFlags }
        if (badFlag != null) return Decision(false, "拒绝执行：`mv $badFlag` 不在受控参数内。")
        val paths = args.filterNot { it.startsWith("-") }
        if (paths.size < 2) return Decision(false, "拒绝执行：`mv` 需要源路径和目标路径。")
        return decideStagingPaths(paths, "`mv` 的源和目标都必须在暂存目录内")
    }

    /** `rm`：只删除暂存目录内的内容。 */
    private fun decideRemove(tokens: List<String>): Decision {
        val args = tokens.drop(1)
        val allowedFlags = setOf("-r", "-R", "-f", "-rf", "-fr", "--recursive", "--force")
        val badFlag = args.firstOrNull { it.startsWith("-") && it !in allowedFlags }
        if (badFlag != null) return Decision(false, "拒绝执行：`rm $badFlag` 不在受控参数内。")
        return decideStagingPaths(
            args.filterNot { it.startsWith("-") },
            "`rm` 只允许删除暂存目录内的内容"
        )
    }

    /** `chmod`：只改暂存目录内文件的权限，模式只接受三位/四位八进制。 */
    private fun decideChmod(tokens: List<String>): Decision {
        val args = tokens.drop(1)
        val mode = args.firstOrNull()
        if (mode == null || !CHMOD_MODE.matches(mode)) {
            return Decision(false, "拒绝执行：`chmod` 只接受三位/四位八进制权限，例如 `chmod 600 <暂存目录文件>`。")
        }
        return decideStagingPaths(args.drop(1), "`chmod` 只允许修改暂存目录内的文件")
    }

    private fun decideStagingPaths(paths: List<String>, message: String): Decision {
        if (paths.isEmpty()) return Decision(false, "拒绝执行：$message；未提供路径。")
        val bad = paths.firstOrNull { !isStagingPath(it) }
        if (bad != null) {
            return Decision(false, "拒绝执行：$message；`$bad` 不在暂存目录内。")
        }
        return Decision(true)
    }

    /** 暂存目录：只允许这两类目录，防止把系统或应用私有数据当成可写区。 */
    private fun isStagingPath(raw: String): Boolean {
        val path = raw.trim().trim('"', '\'').lowercase()
        if (path.isEmpty() || path.contains("..")) return false
        if (path.any { it == '*' || it == '?' }) return false
        val normalized = path.trimEnd('/')
        return STAGING_ROOTS.any { normalized == it || normalized.startsWith("$it/") }
    }

    /** `cp` 的源：必须是绝对路径、非根、非受保护系统路径。 */
    private fun isReadableSource(raw: String): Boolean {
        val path = raw.trim().trim('"', '\'').lowercase()
        if (!path.startsWith("/") || path == "/" || path.contains("..")) return false
        if (path.any { it == '*' || it == '?' }) return false
        return PROTECTED_PATHS.none { path == it || path.startsWith("$it/") }
    }

    /**
     * `service` 只放 `list`（列出已注册的系统服务）。
     *
     * `service call` 能直接调用任意系统 binder 的任意方法——等于把整个系统服务面交给 AI，一律拒。
     */
    private fun decideService(tokens: List<String>): Decision {
        val sub = tokens.getOrNull(1)
        if (sub == "list" && tokens.size == 2) return Decision(true)
        if (sub == "call") {
            return Decision(
                false,
                "拒绝执行：`service call` 能直接调用任意系统 binder 的任意方法（写面覆盖整个系统服务），已拦截。" +
                    "查有哪些服务用 `service list`，看某个服务的实现细节用 `dumpsys <service>`。"
            )
        }
        return Decision(
            false,
            "拒绝执行：`service` 只放行 `service list`（列已注册服务），其余子命令不自动执行。"
        )
    }

    /**
     * `logcat` 必须带 `-d` 或 `-t`：两者都是取完即退出。
     *
     * 不带的话会一直输出到工具超时，日志无界；`-c` 清空缓冲、`-g`/`-G` 读写缓冲大小，属于写操作。
     */
    private fun decideLogcat(tokens: List<String>): Decision {
        val args = tokens.drop(1)
        if (args.any { it == "-c" || it == "-g" || it == "-G" }) {
            return Decision(
                false,
                "拒绝执行：`logcat` 的 `-c`（清空缓冲）与 `-g`/`-G`（读写缓冲大小）属于写操作，已拦截。"
            )
        }
        if (args.any { it == "-d" || it == "-t" }) return Decision(true)
        return Decision(
            false,
            "拒绝执行：`logcat` 必须带 `-d` 或 `-t <行数>`（取完即退出），否则会持续输出到超时、日志无界；" +
                "例如 `logcat -d -t 200`。"
        )
    }

    /**
     * `mount` 只放行「列挂载点」：不带参数、`-l`/`-h`，或按类型过滤的 `-t <type>`。
     *
     * 带 `-o remount,rw` 会把系统分区重新挂成可写——那是后续所有系统改写的入口；带设备/挂载点参数
     * 则是真的执行挂载。两者都改设备状态，因此只放行「不带参数」「`-l`/`-h`」「`-t <type>`」三种写法。
     */
    private fun decideMount(tokens: List<String>): Decision {
        val args = tokens.drop(1)
        val listsOnly = args.isEmpty() || args.all { it == "-l" || it == "-h" || it == "--help" }
        val typeFilter = args.size == 2 && args[0] == "-t"
        if (listsOnly || typeFilter) return Decision(true)
        return Decision(
            false,
            "拒绝执行：`mount` 只放行列挂载点的用法（`mount`、`mount -l`、`mount -t <type>`）；" +
                "带其它参数会改写挂载状态（如 `mount -o remount,rw /system` 会把系统分区挂成可写），已拦截。"
        )
    }

    /**
     * `am` 逐个子命令判定：
     * - 只读（`help`/`get-config`、`stack list`/`task list`）放行；
     * - 会改运行状态但属常用调试手段的（[AM_STATE_CHANGING]）放行，由 [advisory] 在弹窗里标注；
     * - 能加载代码、attach 进程、常驻不退出、改调试状态的（[AM_REJECTED_REASONS]）拒绝；
     * - 其余未评估的子命令一律拒绝（「已知安全才放行」）。
     */
    private fun decideAm(tokens: List<String>): Decision {
        val sub = tokens.getOrNull(1)
            ?: return Decision(
                false,
                "拒绝执行：`am` 需要子命令（只读查询如 `am stack list`；启动/停止应用如 `am start`）。"
            )
        if (sub in AM_STATE_CHANGING) return Decision(true)
        if (sub in AM_READ_ONLY) return Decision(tokens.size == 2)
        if (sub == "stack" || sub == "task") {
            return if (tokens.getOrNull(2) == "list" && tokens.size == 3) {
                Decision(true)
            } else {
                Decision(false, "拒绝执行：`am $sub` 只放行只读的 `am $sub list`。")
            }
        }
        AM_REJECTED_REASONS[sub]?.let { why ->
            return Decision(false, "拒绝执行：`am $sub` $why，已拦截。")
        }
        return Decision(
            false,
            "拒绝执行：`am $sub` 不在已评估的白名单内（放行 start/startservice/stopservice/broadcast/" +
                "force-stop/kill/kill-all/send-trim-memory 与只读的 stack list/task list/get-config/help）。"
        )
    }

    /**
     * 判定一条命令是否予以拒绝；null 表示放行。
     *
     * 注意：这是**白名单内的命令**才走的深度检查（白名单外的在 [isAllowlisted] 就被拒了）；
     * 它负责拦截「首 token 看起来无害、但参数/写法危险」的情况（如 `ls` 后接管道、
     * `am` 带清数据广播等）。
     *
     * 拦截面：
     * 1. 递归删除根目录 / 受保护系统目录；
     * 2. 写块设备（`dd of=/dev/block/...`）、刷机/格式化工具；
     * 3. 重定向、`cp`/`mv`/`chmod`/`chown`/`rm` 触碰受保护路径；
     * 4. 进 bootloader / EDL / recovery，或清数据（`MASTER_CLEAR` 等）；
     * 5. 命令替换 / eval / 管道进 shell——静态判定失效，一律拒。
     */
    fun check(command: String): Rejection? {
        val normalized = command.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return null
        val lower = normalized.lowercase()

        // 1) 递归删除根目录或受保护路径
        if (Regex("(^|[\\s;&|])rm\\s+(-[a-z]*[rf][a-z]*\\s+)+").containsMatchIn(lower)) {
            val targets = extractTargetsAfter(lower, "rm")
            if (targets.any { isRootOrProtected(it) }) {
                return Rejection(
                    "拒绝执行：不允许以 root 递归删除根目录或系统分区目录（${targets.first { isRootOrProtected(it) }}）。" +
                        "如需清理普通数据，请指定具体的数据目录（如 /sdcard 下的路径）。"
                )
            }
        }

        // 2) 刷机 / 格式化 / 写块设备
        FLASH_TOOLS.firstOrNull { lower.contains(it) }?.let { tool ->
            return Rejection(
                "拒绝执行：`$tool` 属于分区/刷机级操作，风险不可逆（可能变砖）。" +
                    "调试系统请改用只读命令（dumpsys/getprop/ls），需要刷机请在电脑上用官方工具手动完成。"
            )
        }
        if (Regex("(^|[\\s;&|])dd\\s").containsMatchIn(lower) && Regex("of=/dev/(block|mtd)").containsMatchIn(lower)) {
            return Rejection("拒绝执行：`dd` 写入块设备会直接改分区数据，风险不可逆，已拦截。")
        }

        // 3) 写系统分区：重定向 / 常见写工具触碰受保护路径
        if (Regex(">>?\\s*/(system|vendor|product|system_ext|odm|boot|recovery|init_boot|dtbo|vbmeta|persist|efs|firmware|modem|dev/block|proc|sys|metadata)\\b")
                .containsMatchIn(lower)
        ) {
            return Rejection("拒绝执行：不允许向系统分区/受保护目录重定向写入，已拦截。")
        }
        val writeVerbs = listOf("cp", "mv", "chmod", "chown", "chgrp", "rm", "mkdir", "touch", "ln", "install", "dd")
        if (writeVerbs.any { Regex("(^|[\\s;&|])$it\\s").containsMatchIn(lower) }) {
            val hit = PROTECTED_PATHS.firstOrNull { path ->
                Regex("(^|[\\s;&|=])${Regex.escape(path)}(/|\\s|$)").containsMatchIn(lower)
            }
            if (hit != null) {
                return Rejection(
                    "拒绝执行：写操作触碰受保护路径 `$hit`（系统分区/引导/基带等），已拦截。" +
                        "只读查看没问题，但不允许改动。"
                )
            }
        }

        // 4) 刷机模式与清数据
        if (Regex("(^|[\\s;&|])reboot\\s+(${REBOOT_TARGETS.joinToString("|")})").containsMatchIn(lower)) {
            return Rejection("拒绝执行：`reboot bootloader/fastboot/edl/recovery` 会进入刷机/恢复模式，已拦截（普通重启请让用户手动操作）。")
        }
        if (lower.contains("fastboot") || DESTRUCTIVE_KEYWORDS.any { lower.contains(it.lowercase()) }) {
            return Rejection("拒绝执行：该命令涉及刷机模式或清除设备数据，风险不可逆，已拦截。")
        }
        // `am`/`cmd` 等白名单首 token 也可能承载清数据广播（如 am broadcast -a MASTER_CLEAR）——
        // 关键字已在上面的 DESTRUCTIVE_KEYWORDS 检查里兜住；这里不再单独列。

        // 5) 命令拼接/替换/管道进 shell：静态拦不了里面藏什么，一律拒绝
        //    （否则 `rm -rf $(echo /system)` 这类写法能绕过上面的路径判定）。
        if (Regex("\\$\\(").containsMatchIn(normalized) || normalized.contains('`') ||
            Regex("(^|[\\s;&|])(eval|exec)\\s").containsMatchIn(lower) ||
            Regex("\\|\\s*(sh|bash|ash|busybox)\\b").containsMatchIn(lower)
        ) {
            return Rejection(
                "拒绝执行：命令包含命令替换 / eval / 管道进 shell，无法静态判定其实际作用，已拦截。" +
                    "请改写成不依赖拼接的单条命令。"
            )
        }

        // 6) 重定向目标：只允许暂存目录或 /dev/null，避免借 `ls > /data/...` 绕过写路径限制。
        for (match in REDIRECT_TARGET.findAll(normalized)) {
            val target = match.groupValues[1].trim('"', '\'')
            if (target.startsWith("&") || target == "/dev/null") continue
            if (!isStagingPath(target)) {
                return Rejection(
                    "拒绝执行：输出重定向只允许写入暂存目录（/data/local/tmp 或 Download）或 /dev/null；" +
                        "`$target` 不在其中。"
                )
            }
        }

        return null
    }

    private fun extractTargetsAfter(command: String, verb: String): List<String> {
        val index = command.indexOf(verb)
        if (index < 0) return emptyList()
        return command.substring(index + verb.length)
            .split(' ')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() && !it.startsWith("-") }
    }

    private fun isRootOrProtected(target: String): Boolean {
        if (target == "/" || target == "/*" || target == "/*/") return true
        if (isWholeTreeDataRoot(target)) return true
        return PROTECTED_PATHS.any { path ->
            target == path || target.startsWith("$path/") || target.startsWith("$path/*")
        }
    }

    /**
     * 整树删除用户数据根（`rm -rf /data`、`rm -rf /sdcard` 及其整树通配形式）会一次抹掉全部照片与文件，
     * 不可逆。
     *
     * 只拦「整棵树」这一种写法：`rm -rf /sdcard/Download/x` 这类子树仍放行，否则正常清理都用不了。
     */
    private fun isWholeTreeDataRoot(target: String): Boolean =
        WHOLE_TREE_DATA_ROOTS.any { root -> target == root || target == "$root/" || target == "$root/*" }
}
