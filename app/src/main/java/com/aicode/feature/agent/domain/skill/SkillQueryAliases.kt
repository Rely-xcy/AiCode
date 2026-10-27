package com.aicode.feature.agent.domain.skill

/**
 * 问法表：同一个能力在不同口径下的常见说法（中英混用、口语说法、工具名）。
 *
 * 为什么要这张表：技能名与正文多是英文术语（decompile、unpack、proxy），而模型的检索词常是中文口语
 * （反编译、脱壳、抓包），只做子串匹配时两边对不上，检索看起来「明明装了却搜不到」。本地轻量检索不上
 * 嵌入模型（设备侧要 150ms 内跑完），于是一个人能手改的对照表就是性价比最高的那一层。
 *
 * 表按组写，组内任意两条互相视为同义；同一个词可以出现在多组里（别名会合并）。加词只改 [GROUPS]。
 */
internal object SkillQueryAliases {

    private val GROUPS: List<List<String>> = listOf(
        // 逆向 / 反编译 / 脱壳 / 加固
        listOf("反编译", "decompile", "decompiler", "jadx", "反编译成"),
        listOf("逆向", "逆向工程", "reverse", "reversing", "reverse-engineering"),
        listOf("脱壳", "unpack", "unpacking", "加壳", "unpacker"),
        listOf("加固", "packer", "protect", "protection", "壳"),
        listOf("混淆", "obfuscate", "obfuscation", "ollvm", "混淆器"),
        listOf("静态分析", "static", "static-analysis", "静态"),
        listOf("动态调试", "debug", "debugger", "frida", "hook", "调试"),
        listOf("补丁", "patch", "patchfile", "打补丁"),
        // 流量 / 网络
        listOf("抓包", "capture", "mitm", "burp", "fiddler"),
        listOf("代理", "proxy", "proxychains", "转发"),
        listOf("端口扫描", "portscan", "port-scan", "nmap", "端口"),
        listOf("扫描", "scan", "scanner", "探测"),
        listOf("网络", "network", "http", "https", "tcp", "udp"),
        // 样本 / 漏洞
        listOf("漏洞", "vulnerability", "vuln", "cve", "exploit"),
        listOf("病毒", "malware", "virus", "trojan", "样本"),
        // 文档 / 数据
        listOf("表格", "excel", "spreadsheet", "xlsx", "csv"),
        listOf("文档", "docx", "document", "word", "markdown"),
        listOf("数据库", "database", "sql", "sqlite", "mysql"),
        listOf("报告", "report", "reports"),
        // 文件 / 压缩
        listOf("解压", "unzip", "decompress", "extract", "解包"),
        listOf("压缩", "zip", "compress", "archive", "归档"),
        listOf("下载", "download", "wget", "curl", "拉取"),
        listOf("上传", "upload", "scp", "rsync", "推送"),
        listOf("文件", "file", "files", "文件系统"),
        // 图像 / 媒体
        listOf("截图", "screenshot", "截屏", "screencap"),
        listOf("图像", "image", "picture", "图片", "photo"),
        listOf("识别", "ocr", "recognition", "检测"),
        listOf("录屏", "screenrecord", "录像", "录制"),
        // 工程流程
        listOf("构建", "build", "gradle", "compile", "编译"),
        listOf("测试", "test", "testing", "pytest", "单测"),
        listOf("部署", "deploy", "deployment", "发布"),
        listOf("备份", "backup", "restore", "恢复"),
        listOf("日志", "log", "logs", "logcat"),
        // 通用动作
        listOf("分析", "analyze", "analyse", "analysis", "解析"),
        listOf("搜索", "search", "grep", "检索", "查找"),
        listOf("翻译", "translate", "translation"),
        listOf("爬虫", "crawler", "spider", "scrape", "爬取")
    )

    /** 词 -> 同义写法（不含自身）。同一个词落在多组时合并结果。 */
    private val BY_TERM: Map<String, List<String>> = LinkedHashMap<String, MutableList<String>>().apply {
        for (group in GROUPS) {
            for (term in group) {
                val bucket = getOrPut(term) { mutableListOf() }
                for (other in group) {
                    if (other != term && other !in bucket) bucket.add(other)
                }
            }
        }
    }

    /** [term] 的同义写法；没登记过的新词返回空表，调用方按「没有别名」处理。 */
    fun aliasesOf(term: String): List<String> = BY_TERM[term] ?: emptyList()
}
