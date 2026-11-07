package com.aicode.core.branding

/**
 * 品牌标识的单一真源。
 *
 * 之前这些标识散落在各处字面量里：容器内路径 `/root/.aicode` 出现在
 * [com.aicode.feature.workspace.domain.WorkspacePathMapper]、
 * [com.aicode.core.watch.FileChangeHub]、[com.aicode.feature.terminal.domain.TerminalSessionManager]
 * 等多个文件，宿主目录名 `.aicode` 出现在 ProjectAicodeRoot 与 McpConfigRepository，
 * assets 目录 `aicode/` 与环境变量前缀 `AICODE_` 又是另外几处。
 *
 * 改包名时要同时改这些位置，漏一处就会出现「容器里找不到配置」这类难查的问题。
 * 收拢到这里后，改品牌只改这一处字面量，其余代码引用常量。
 *
 * 注意：这些是**用户可见且可迁移**的标识，改动时必须配合迁移逻辑
 * （旧目录只搬不删、新目录优先旧目录兜底），不能直接替换了事。
 */
object BrandPaths {
    /** AI 配置目录在容器内的路径。 */
    const val CONFIG_ROOT = "/root/.aicode"

    /** AI 配置目录在宿主/服务器上的目录名（容器内与远端 $HOME 下同为此名）。 */
    const val CONFIG_DIR_NAME = ".aicode"

    /** 宿主 filesDir 下的配置目录名。 */
    const val HOST_DIR_NAME = "aicode"

    /** assets 里的配置目录名。 */
    const val ASSETS_DIR_NAME = "aicode"

    /** 环境变量前缀。 */
    const val ENV_PREFIX = "AICODE_"

    /**
     * 旧标识，仅供迁移读取使用。
     *
     * 迁移期必须还能认出旧路径——否则用户升级后配置目录「凭空消失」。
     * 迁移完成后这些常量可以保留一个版本，别急着删。
     */
    object Legacy {
        const val CONFIG_ROOT = "/root/.aicode"
        const val CONFIG_DIR_NAME = ".aicode"
        const val HOST_DIR_NAME = "aicode"
        const val ASSETS_DIR_NAME = "aicode"
        const val ENV_PREFIX = "AICODE_"
    }
}
