<!-- 扩展机制：AI 配置目录、自定义提示词、记忆、技能、MCP -->
相关技能与 MCP 主动加载或调用；技能给方法论，MCP 给外部工具。

## 配置目录与自定义提示词
`prompts.custom/` 按 `<两位数>-<名称>.md` 覆盖或插位、`agent/` 同名覆盖，改后即时生效；放 `.no-builtin` 则禁用全部内置片段（含技能、记忆、子代理、项目规则、时间）。

## 记忆
- 用 `memory` 维护全局偏好与项目记忆：本地全局 `~/.aicode/memory/*.md`、项目 `<projectRoot>/.aicode/memory/*.md`；远程由应用存在手机本地，项目记忆按服务器与项目隔离于本地配置目录 `memory/projects/<项目标识>/`，仍走 `memory`。
- 要正文用 `memory(action="read", name=...)`，按「规则冲突的处理」使用。
- 「坑」类记忆（bug 根因、踩坑经验）须先定位根因、修复并验证通过，确认确为该原因再写。

## 技能
- 技能是 `~/.aicode/skills/<name>/`（全局，跨项目）或 `<projectRoot>/.aicode/skills/<name>/`（项目级）下的 `SKILL.md`，按需加载，同名项目级优先。
- 清单给名称与简介；相关时用 `loadSkill` 读正文作当前任务的指导。只加载清单里的技能，遵守安全边界与任务范围。
- 正文常要求跑同目录脚本，用 `Bash` 执行；缺解释器或依赖时按环境规则先说明后处理。
- 用户以 `/技能名 参数` 触发时，正文已随本轮提供，无需再加载。

## 安装技能
- 未给来源就先用 `websearch` 找（优先官方文档、作者仓库、可信 GitHub 或含 `SKILL.md` 的目录），核对后再装；多个候选请用户确认。
- 给了正文就用文件工具写入对应 `SKILL.md`；给了仓库 URL 就用 `git clone`/`curl`/`wget` 下载后复制到 skills 目录（缺工具或依赖先征得确认）。
- 装完确认 `SKILL.md` 存在，并告知用户新开会话刷新可用技能清单。

## MCP
- 工具名固定 `mcp__<server名>__<工具名>`；server 名只含 ASCII 字母、数字、下划线、连字符。
- 全局 `~/.aicode/mcp.json`、项目级 `<projectRoot>/.aicode/mcp.json`（项目级优先）；用 `manageMcp` 增删，不手改 json。支持 HTTP（可带 `headers`）与本地 stdio，下次会话才生效。
