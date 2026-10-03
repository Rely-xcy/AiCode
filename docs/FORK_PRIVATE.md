# fork 私有改动清单（本文件只存在于 fork/release，不进上游 PR）

> 本文件放在 `fork/release` 上维护：上游 PR 一律从 `main` 开分支，而 `main` 没有这个文件，
> 所以它天然不会出现在任何 PR 里。改动私有内容时同步更新本清单。

## 一条命令列出全部私有改动

```bash
git diff main fork/release --name-status
```

辅助定位（更新源专属的符号级清单）：

```bash
rg -n "FORK_UPDATE_SUPPORTS_CHANNEL|ForkUpdateCheckService|forkUpdateCheckService|fork_update_|downloadUrl" app docs-site
```

## 当前全部私有改动（2026-10-04 核对）

| 类别 | 文件 | 说明 |
|---|---|---|
| 更新源 | `app/src/main/java/com/aicode/feature/settings/data/remote/ForkUpdateCheckService.kt` | fork 专属自建更新接口（新增文件）；文件头 KDoc 写有完整改回步骤 |
| 更新源 | `app/src/main/java/com/aicode/feature/settings/data/remote/UpdateCheckService.kt` | `UpdateInfo` 加 `downloadUrl` 字段 |
| 更新源 | `app/src/main/java/com/aicode/feature/settings/presentation/SettingsViewModel.kt` | 注入与 `checkUpdate` 换数据源；`NewVersion` 加 `downloadUrl` |
| 更新源 | `app/src/main/java/com/aicode/feature/settings/presentation/component/UpdateCheckDialog.kt` | 「打开下载页」改开 APK 直链 |
| 更新源 | `app/src/main/java/com/aicode/feature/settings/presentation/component/AboutSection.kt` | 通道开关按 `FORK_UPDATE_SUPPORTS_CHANNEL` 隐藏 |
| 更新源 | `app/src/main/java/com/aicode/MainActivity.kt` | 弹窗按钮回调改走 `onOpenDownload` |
| 更新源 | `app/src/main/res/values/strings.xml`、`values-en/strings.xml` | `fork_` 前缀文案（中英各 2 条，键集对齐） |
| 更新源 | `docs-site/docs/guide/about.md` | 关于页文档改描述自建更新源 |
| CI | `.github/workflows/beta.yml` | Beta 由 `fork/release` 触发（`main` 上是上游原样 `[ main ]`） |
| CI | `.github/workflows/ci.yml` | CI 触发分支含 `fork/release`（`main` 只有 `[ main ]`） |
| 包名 | `app/build.gradle.kts` | `applicationId = "Rely.aicode"`（namespace 仍 `com.aicode` 不动） |
| 包名 | `docs-site/docs/guide/container.md`、`guide/files.md`、`guide/logs.md` | 文档里的宿主路径 `/data/data/<包名>`、`Android/data/<包名>` 写的是 fork 实际包名 |
| 版本号 | ~~`app/build.gradle.kts` 的 `forkVersionCode/forkVersionName`~~ | 已删（363bd354），回到派生 `gitCommitCount()` / `gitVersionName()` |

## 提上游 PR 的正确姿势

`main` 对上游只含「我们的功能/修复提交」、零 fork 私有内容（验证见下），所以：

1. **从 `main` 开分支**提 PR，不要从 `fork/release` 开——后者带着上表全部私有改动；
2. 只挑**依赖闭合的子集**（被依赖的提交必须一起带上；在 fork main 上能编译 ≠ 上游能编译）；
3. 编译验证必须在上游基线做：`git checkout -b upstream-probe upstream/main` → cherry-pick 候选集 →
   推到 origin → 触发 CI 构建（本容器无 JDK，本地验证不了）；
4. 上游已前进时先处理落后（`git log main..upstream/main` 非空就先合并/rebase）。

### 「main 是否干净」的验证命令（随时可重跑）

```bash
# 差异文件数（2026-10-04：250 个，全部是 fork 的功能/修复工作）
git diff upstream/main main --name-only
# fork/Rely 痕迹扫描（应为空；beta.yml 改回上游原样后已确认无命中）
git diff upstream/main main | grep '^+' | grep -iE 'fork|rely'
```

## 已知取舍

- 换包名 = 对设备来说是**另一个 App**：旧 `com.aicode` 的数据不继承、需要备份迁移；新旧包可共存。
- 版本号回到派生后，`gitCommitCount()` 会随 fork 提交数虚高（比上游大）；本 fork 不打正式 tag、
  只走 Beta 分发，`android-release.yml` 的 versionCode 单调校验不会在 fork 上触发。若将来要在
  `fork/release` 上打正式 tag，需要重新评估写死版本号。
