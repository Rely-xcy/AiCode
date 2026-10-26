package com.aicode.feature.agent.domain.vscreen

/**
 * 虚拟屏在系统里的名字约定。
 *
 * 名字是唯一稳定的身份：displayId 每次建屏都不同、进程重启还会被复用，尺寸和位置又可能与别的
 * 应用（投屏、录屏、Presentation）建的屏完全一致，按位置或尺寸猜会误伤别人的屏。
 * 孤儿对账因此只认这里写死的前缀。
 */
internal object VscreenDisplayNames {

    /** 建屏名 = 前缀 + 建屏时刻毫秒数，后缀的用处是让 `dumpsys display` 里能按时间看出先后。 */
    const val PREFIX = "aicode-vscreen-"

    fun create(nowMs: Long): String = "$PREFIX$nowMs"

    /** 是否是本应用建的屏（null 名视为不是：系统没给名字的屏不可能是我们建的）。 */
    fun isOwned(name: String?): Boolean = name != null && name.startsWith(PREFIX)
}
