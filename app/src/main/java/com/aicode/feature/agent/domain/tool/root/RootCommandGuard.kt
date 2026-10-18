package com.aicode.feature.agent.domain.tool.root

/**
 * Root 命令安全闸门：以 uid 0 在**设备**上执行前的最后一道硬拦截。
 *
 * 与容器里的 `Bash` 不同，这里的命令直接作用于用户手机的系统分区与存储；一旦写坏分区表、
 * 刷坏 boot、或误删系统目录，损失不可逆（轻则开不了机、重则变砖）。因此本闸门**只认代码**，
 * 不依赖提示词劝说，也不受 AUTO 模式影响。
 *
 * 设计原则（按优先级）：
 * 1. **白名单**：AI 只能执行首 token 在 [ALLOWED_COMMANDS] 内的命令（只读调试 + 无害的界面操作）；
 * 2. **黑名单**：即使白名单内，仍逐条检查不可逆动作（删系统/刷机/写块设备/进刷机模式/清数据）；
 * 3. **拼接拦截**：命令替换、eval、管道进 shell 一律拒——它们能让上面的静态判定失效；
 * 4. **无解锁口**：不存在 allowUnsafe 之类的参数；能改系统的操作不由 AI 决定，一律回给用户。
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
     * 默认放行的命令**首 token**（只读调试与无害的界面操作）。
     *
     * 不在名单里的命令默认拒绝且**无解锁口**（不存在 allowUnsafe 之类参数）——这是「可控范围」的
     * 主要手段：AI 默认只能做只读/调试类的事；能改系统的操作必须由用户自己在终端执行。
     *
     * 维护注意：新增条目必须是「只读或无害」的命令首 token；`settings` 只放只读子命令；
     * 有写能力的（cp/mv/chmod/kill 等）一律不进名单——需求属正当也应回给用户。
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
        "netstat", "ifconfig", "ip", "mount", "free", "md5sum", "sha256sum", "toybox"
    )

    /** 只允许读的 `settings` 子命令；写子命令一律不在默认白名单。 */
    private val SETTINGS_READ_ONLY = listOf("settings get", "settings list")

    /**
     * 只允许读的 `cmd` 子命令。
     *
     * `cmd` 是分发器：`cmd package clear` 抹掉应用数据、`cmd notification post` 发通知、
     * `cmd wifi set-wifi-enabled` 改开关——写子命令一律不在默认白名单（维护时只加读类前缀）。
     */
    private val CMD_READ_ONLY = listOf(
        "cmd package list", "cmd package path", "cmd package dump", "cmd package query-",
        "cmd activity get-", "cmd activity dump", "cmd activity lastanr",
        "cmd activity stack list", "cmd activity task list", "cmd activity package",
        "cmd window", "cmd overlay list", "cmd uimode",
        "cmd wifi status", "cmd wifi list-", "cmd notification list", "cmd notification get-",
        "cmd stats print", "cmd stats read", "cmd deviceidle get", "cmd deviceidle whitelist",
        "cmd power", "cmd thermalservice", "cmd user list", "cmd dpm list",
        "cmd netpolicy list", "cmd webviewupdate", "cmd shortcut get-", "cmd lock_settings get-",
        "cmd input_method", "cmd bluetooth_manager get-"
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

    data class Rejection(val reason: String)

    /** 段分隔符：`;`、`&&`、`||`、`|`、换行。 */
    private val SEGMENT_SEPARATORS = Regex("(?:&&|\\|\\||[;|\\n])")

    /**
     * 命令是否**整条**都在默认白名单内：先按 [SEGMENT_SEPARATORS] 拆段，再逐段判定。
     *
     * 只看首个 token 是不够的：`ls ;pm clear com.foo`、`ls &&rm -rf /data/system` 会以 `ls`
     * 混过闸门，而后半段仍以 uid 0 执行。
     */
    fun isAllowlisted(command: String): Boolean {
        val segments = command.split(SEGMENT_SEPARATORS)
            .map { it.replace(Regex("\\s+"), " ").trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        return segments.all { isSegmentAllowlisted(it) }
    }

    /** 单段判定：首 token 在名单内，且 `settings`/`cmd`/`pm` 只放只读子命令。 */
    private fun isSegmentAllowlisted(segment: String): Boolean {
        val first = segment.substringBefore(' ')
        if (first !in ALLOWED_COMMANDS) return false
        return when (first) {
            "settings" -> SETTINGS_READ_ONLY.any { segment.startsWith(it) }
            "cmd" -> CMD_READ_ONLY.any { segment.startsWith(it) }
            "pm" -> PM_READ_ONLY.any { segment.startsWith(it) }
            else -> true
        }
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
