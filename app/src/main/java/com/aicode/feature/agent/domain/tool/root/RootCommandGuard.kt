package com.aicode.feature.agent.domain.tool.root

/**
 * Root 命令安全闸门：以 uid 0 在**设备**上执行前的最后一道硬拦截。
 *
 * 与容器里的 `Bash` 不同，这里的命令直接作用于用户手机的系统分区与存储；一旦写坏分区表、
 * 刷坏 boot、或误删系统目录，损失不可逆（轻则开不了机、重则变砖）。因此本闸门**只认代码**，
 * 不依赖提示词劝说，也不受 AUTO 模式影响。
 *
 * 设计原则：
 * - **只拦「不可逆且后果严重」的动作**，不做白名单式封禁——否则 Root 工具没法用来调试系统；
 * - 规则基于「动词 + 目标路径」判定，宁可多拦（拦下后把原因回给模型，模型可换安全的等价做法）；
 * - 纯字符串逻辑、无 IO，便于单测与逐条 review。
 */
object RootCommandGuard {

    /** 设备上不允许被命令写入/删除的系统面（命中即拒）。 */
    private val PROTECTED_PATHS = listOf(
        "/system", "/vendor", "/product", "/system_ext", "/odm",
        "/boot", "/recovery", "/init_boot", "/dtbo", "/vbmeta",
        "/persist", "/efs", "/firmware", "/modem", "/modemst1", "/modemst2",
        "/dev/block", "/dev/mtd", "/proc", "/sys", "/metadata"
    )

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

    data class Rejection(val reason: String)

    /**
     * 判定一条命令是否予以拒绝；null 表示放行。
     *
     * 拦截面（每条都给可照做的原因，方便模型换做法）：
     * 1. 递归删除根目录 / 受保护系统目录；
     * 2. 写块设备（`dd of=/dev/block/...`）、刷机/格式化工具；
     * 3. 重定向、`cp`/`mv`/`chmod`/`chown`/`rm` 触碰受保护路径；
     * 4. 进 bootloader / EDL / recovery，或清数据（`MASTER_CLEAR` 等）。
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
        return PROTECTED_PATHS.any { path ->
            target == path || target.startsWith("$path/") || target.startsWith("$path/*")
        }
    }
}
