package com.aicode.feature.agent.domain.container

internal fun buildRemoteWorkspaceCdCommand(workspacePath: String): String =
    "cd ~/workspace 2>/dev/null || cd ${remoteShellQuote(workspacePath.trimEnd('/'))} 2>/dev/null"

internal fun buildRemoteWorkspaceSymlinkCommand(workspacePath: String): String =
    "if [ -d ~/workspace ] && [ ! -L ~/workspace ]; then echo skip; " +
        "else ln -sfn -- ${remoteShellQuote(workspacePath.trimEnd('/'))} ~/workspace 2>/dev/null && echo done; fi"

internal fun buildRemoteDocsPreparationCommand(destDir: String, subDirs: List<String>): String {
    val directories = (listOf(destDir) + subDirs.map { "$destDir/$it" }).joinToString(" ", transform = ::remoteShellQuote)
    return "mkdir -p -- $directories && find ${remoteShellQuote(destDir)} -type f -name '*.md' -delete"
}

internal fun buildRemoteFileWriteCommand(dest: String, content: String, privateFile: Boolean = false): String {
    val path = remoteShellQuote(dest)
    val write = "printf %s ${remoteShellQuote(content)} > $path"
    if (!privateFile) return write
    // umask 只影响新文件；已有文件须在截断前收紧权限，且不跟随符号链接写入。
    return "(umask 077 && [ ! -L $path ] && " +
        "{ [ ! -e $path ] || { [ -f $path ] && chmod 600 -- $path; }; } && $write)"
}

internal fun buildRemoteBuiltinFileCommand(dest: String, base64: String, executable: Boolean): String {
    val path = remoteShellQuote(dest)
    val chmod = if (executable) " && chmod +x -- $path" else ""
    return "if [ ! -e $path ]; then printf %s ${remoteShellQuote(base64)} | base64 -d > $path$chmod; fi"
}

internal fun buildRemoteRemoveCredentialCommand(aicodeDir: String): String {
    val paths = listOf("gitconfig", "gitconfig.credential", "git-credentials", "git-credential-aicode")
        .joinToString(" ") { remoteShellQuote("$aicodeDir/$it") }
    return "rm -f -- $paths"
}

internal fun buildRemoteGitConfig(workspaceRoot: String): String =
    "[include]\n    path = ~/.gitconfig\n" +
        "[includeIf ${gitConfigQuote("gitdir:${workspaceRoot.trimEnd('/')}/")}]\n" +
        "    path = ~/.aicode/gitconfig.credential\n"

internal fun buildRemoteCredentialConfig(aicodeDir: String): String =
    "[credential]\n    helper = ${gitConfigQuote("!AICODE_CRED_NO_PROMPT=1 ${remoteShellQuote("$aicodeDir/git-credential-aicode")}")}\n"

internal fun remoteShellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

private fun gitConfigQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("\b", "\\b") + "\""
