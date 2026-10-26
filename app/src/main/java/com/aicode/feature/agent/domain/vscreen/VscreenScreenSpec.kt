package com.aicode.feature.agent.domain.vscreen

/**
 * 虚拟屏几何参数的合法区间与校验。
 *
 * 上界按「一块屏的帧队列不把内存吃掉」定：取帧器按 [MAX_IMAGES] 份 RGBA_8888 缓冲预分配，
 * 一块 w×h 的屏约占 `3×w×h×4` 字节——1080×2400 约 31MB，4K UHD（3840×2160）约 100MB，
 * 而 8K（7680×4320）要将近 400MB，低端机直接 OOM。因此按 4K UHD 的像素数封顶，单边各再限 4096。
 *
 * 下界按「还装得下一份可用布局」定：小于 64 像素的屏给不出目标 app 可用的窗口尺寸，
 * 结果只会是布局错乱或直接崩溃，测出来的东西没有意义；与其建一块注定不可用的屏，
 * 不如在建屏前就把参数问题退回去。
 *
 * 密度取 Android 标准档位的两端：120dpi 是 ldpi（再低没有对应的系统档位），
 * 640dpi 是 xxxhdpi（再高不属于任何标准分级，只会让目标 app 按错误的比例排版）。
 *
 * 抽成纯函数：越界是参数问题不是设备问题，必须在建屏前回一条可读错误，
 * 而不是让 `ImageReader.newInstance` 抛异常、或者先分配几百 MB 再失败。
 */
internal object VscreenScreenSpec {

    const val MIN_DIMENSION_PX = 64
    const val MAX_DIMENSION_PX = 4096

    /** 4K UHD 像素数：单边限 4096 还不够，8K 这类「每边都不超但合计爆表」的尺寸要在总量上挡。 */
    const val MAX_PIXELS = 3840L * 2160L

    const val MIN_DENSITY_DPI = 120
    const val MAX_DENSITY_DPI = 640

    private const val MAX_IMAGES = 3

    /** 校验通过返回 null；不通过返回可读原因，直接作为工具错误文案回给模型。 */
    fun violation(width: Int, height: Int, density: Int): String? {
        if (width !in MIN_DIMENSION_PX..MAX_DIMENSION_PX) {
            return "虚拟屏宽度 $width 超出允许范围（$MIN_DIMENSION_PX–$MAX_DIMENSION_PX 像素）。"
        }
        if (height !in MIN_DIMENSION_PX..MAX_DIMENSION_PX) {
            return "虚拟屏高度 $height 超出允许范围（$MIN_DIMENSION_PX–$MAX_DIMENSION_PX 像素）。"
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_PIXELS) {
            return "虚拟屏 ${width}x$height 共 $pixels 像素，超过上限 $MAX_PIXELS（约 4K UHD）：" +
                "帧队列按 $MAX_IMAGES×宽×高×4 字节预分配，再大约需 ${pixels * MAX_IMAGES * 4 / (1024 * 1024)}MB 内存。" +
                "请改用更小的宽高。"
        }
        if (density !in MIN_DENSITY_DPI..MAX_DENSITY_DPI) {
            return "虚拟屏密度 ${density}dpi 超出允许范围（$MIN_DENSITY_DPI–$MAX_DENSITY_DPI dpi）。"
        }
        return null
    }
}
