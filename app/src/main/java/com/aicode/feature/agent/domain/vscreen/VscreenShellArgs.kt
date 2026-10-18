package com.aicode.feature.agent.domain.vscreen

import com.aicode.feature.agent.domain.tool.explorer.shellQuote

/**
 * 虚拟屏注入命令的**参数拼装与校验**。
 *
 * 注入命令最终以 `su -c <整串>` 交给宿主 shell（见 [RootShell.run]），所以模型给的字符串
 * 在拼进命令串之前必须先过这一关：一个没转义的引号就能在 uid 0 下追加任意命令
 * （`vscreen_input(text = "x';rm -rf /sdcard;#")`）。
 *
 * 单独成件而不是写在 [VirtualScreenController] 里：这段是纯字符串处理，抽出来才能被单测钉住
 * （控制器带 root 通道与屏管理器，单测成本高）。
 */
internal object VscreenShellArgs {

    /** `input keyevent` 的按键名：只接受字母开头的字母/数字/下划线（`KEYCODE_HOME` 与 `HOME` 都合法）。 */
    private val KEY_CODE = Regex("[A-Za-z][A-Za-z0-9_]{0,40}")

    /** Android 包名：字母/数字/下划线/点，首字符不是点。 */
    private val PACKAGE = Regex("[A-Za-z0-9_][A-Za-z0-9_.]*")

    /** 校验按键名并归一为大写；不合法返回 null（调用方据此回错，不做任何截断/猜测）。 */
    fun keyCode(raw: String): String? = raw.trim().uppercase().takeIf { KEY_CODE.matches(it) }

    /** 校验包名；不合法返回 null。 */
    fun packageName(raw: String): String? = raw.trim().takeIf { PACKAGE.matches(it) }

    /**
     * `input text` 的文本参数：空格按 `input` 自己的约定转成 `%s`，整体交给 POSIX 单引号转义。
     *
     * 不能用「把 `'` 换成 `\'`」那种写法：单引号内的反斜杠没有转义力，payload 里的 `'`
     * 会直接闭合引号。唯一正确的做法是 [shellQuote]（`'` → `'"'"'`）。
     */
    fun inputText(content: String): String = shellQuote(content.replace(" ", "%s"))

    /** `am start` 的包名/组件参数：整体单引号转义（含 `.` 与 `$` 的嵌套类名也安全）。 */
    fun component(raw: String): String = shellQuote(raw)
}
