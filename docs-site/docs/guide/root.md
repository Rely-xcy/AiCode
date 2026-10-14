# Root 工具（设备 root 命令）

`Root` 是给 AI 的一个工具：**以设备 root（uid 0）身份在宿主 Android 系统上执行命令**。适合调试系统本身——查看窗口与显示（`dumpsys`）、模拟按键与点击（`input`）、截屏（`screencap`）、管理应用（`pm`/`am`）等。

## 与 Shizuku 工具的区别

| | `Shizuku` 工具 | `Root` 工具 |
| --- | --- | --- |
| 前提 | 安装并启动 Shizuku，且授权本应用 | 设备已 root（Magisk 等），`su` 可用 |
| 身份 | adb shell（uid 2000） | root（uid 0） |
| 能力 | `pm`/`am`/`cmd` 等常规系统命令 | 上面全部，另加只有 root 做得到的事：建交互式虚拟屏、读其它应用私有目录、`dumpsys`/`input`/`screencap` 全量 |
| 授权 | 每次调用弹窗，不可「始终允许」 | 同左 |

两者互不影响：设备没 root 就继续用 Shizuku；有 root 时 `Root` 覆盖面更广。

## 怎么判断设备是否 root

「设置 → 工具授权 → 虚拟屏 root 通道」点一下即可——它在后台跑一次 `su -c id`，看能不能拿到 uid 0：

- **可用**：设备已 root，AI 的 `Root` 工具可用；
- **不可用**：没有可用的 `su`，`Root` 工具会直接返回失败原因（不会反复重试）。

## 权限与安全

- **每次调用都会弹窗确认**，且**不支持「始终允许」**——它直接以 root 改宿主系统，误放行的代价太大。
- 命令会先过权限策略引擎：命中「灾难性删除」（如 `rm -rf /`）会被直接拒绝；命中项目 / 全局授权规则里的 DENY 同样拒绝。
- 在 AUTO（自动）模式下这一点也不放宽：`Root` 与 `Shizuku` 一样仍逐次弹窗。
- PLAN（计划）模式下禁止调用（与 `Bash`、`terminal` 写操作同级）。

### 内置安全闸门（代码级，不靠劝说）

为了防止把手机搞坏，下列命令**在发出前就被直接拒绝**（不看权限规则、不受 AUTO 模式影响），并在工具结果里说明原因：

| 拦截面 | 例子 |
| --- | --- |
| 递归删除根目录 / 系统分区 | `rm -rf /`、`rm -rf /system`、`rm -rf /data/system` |
| 刷机、分区、格式化 | `fastboot …`、`mkfs.ext4 …`、`mke2fs …`、`parted …` |
| 直接写块设备 | `dd … of=/dev/block/…` |
| 向系统分区写入 | `echo x > /system/…`、`cp x /system/…`、`chmod 777 /system/…` |
| 进刷机 / 恢复模式、清数据 | `reboot bootloader/edl/recovery`、`MASTER_CLEAR`、`--wipe_data` |

**放行**的是普通调试与数据目录操作：`dumpsys`/`getprop`/`ls`/`cat`（只读）、`input`、`screencap`、`pm list`/`am start`、对 `/sdcard` 等数据目录的读写。

普通重启（`reboot`）不拦，但会弹窗由你确认；刷机这类操作请照旧在电脑上用官方工具手动做。

## 使用示例

让 AI 去做这类事时它就会用 `Root`：

- 「用 root 看一下现在有几块显示屏」（`dumpsys display`）
- 「模拟按一下返回键」（`input keyevent KEYCODE_BACK`）
- 「截一张当前屏幕」（`screencap -p /sdcard/shot.png`）

虚拟屏功能（在你看不见的屏幕上自动测试 app）也依赖这条 root 通道，见[软件权限](/guide/app-permissions)里的〈虚拟屏 root 通道〉。
