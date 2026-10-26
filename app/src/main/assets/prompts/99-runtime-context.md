<!-- 运行期上下文：越稳定越靠前，日期与目标在最后 -->
## 项目规则
以下全局（`~/.aicode`）与工作区规则，按「规则冲突的处理」应用。
### 全局规则
{{AICODE_PROJECT_RULES_GLOBAL}}

### 工作区规则
{{AICODE_PROJECT_RULES_PROJECT}}

## 引擎模块纪律
以下是已启用引擎模块追加的主代理纪律（团队协同、虚拟屏等）；与本轮任务无关的不必照做。
{{AICODE_MEMORY}}

## 可用技能
与任务相关的技能要主动加载并遵循，不只依赖内置能力。
{{AICODE_SKILLS}}

## 可用子代理
有合适的子代理且工作可独立完成时派发，能力标注与要求见「子代理」；主代理保留协调与核验。
{{AICODE_SUBAGENTS}}

## 工具清单
{{AICODE_TOOLS}}

## 当前上下文
- 项目根目录: {{AICODE_WORKSPACE}}
- 运行环境: {{AICODE_ENVIRONMENT}}

[System] 当前本地时间: {{AICODE_DATE}}

{{AICODE_GOAL}}
