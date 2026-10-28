# AiCode Remote Agent 协议 v1

远端守护进程与 Android 端之间的二进制帧协议。**头部是 JSON（人可读、日志可解），body 是裸二进制（大文件不膨胀）。**

## 1. 帧格式

```
┌──────────┬──────────────┬─────────────────┬──────────────┐
│ MAGIC(4) │ headerLen(4) │  header(JSON)   │  body(raw)   │
│ "AICD"   │ 大端 uint32   │  headerLen 字节  │ bodyLen 字节  │
└──────────┴──────────────┴─────────────────┴──────────────┘
```

- `MAGIC` = `0x41 0x49 0x43 0x44`（ASCII `AICD`）
- `headerLen` / `bodyLen` 均为大端无符号 32 位
- `headerLen` 上限 1 MiB；`bodyLen` 上限 64 MiB（超过返回 `E_TOO_LARGE`，不读 body）
- 编解码约定：先读 8 字节定长头 → 读 header → 按 `header.bodyLen` 读 body

## 2. header 结构

**请求**
```json
{"id":1,"op":"read","path":"/home/u/workspace/a.txt","offset":0,"len":65536,"bodyLen":0}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | int | 是 | 请求序号，1 起单调递增；响应原样带回 |
| `op` | string | 是 | 见第 3 节 |
| `path` | string | 视 op | 绝对路径；支持 `~` 前缀展开为远端 home |
| `offset` | int | 否 | 仅 `read`，默认 0 |
| `len` | int | 否 | 仅 `read`，默认 65536，上限 1 MiB |
| `mode` | int | 否 | 仅 `write`，`0o644` 默认 |
| `overwrite` | bool | 否 | 仅 `write`，默认 true |
| `maxDepth` | int | 否 | 仅 `list`，默认 1 |
| `recursive` | bool | 否 | 仅 `remove`/`list`，默认 false |
| `pattern` | string | 否 | 仅 `grep` |
| `maxResults` | int | 否 | 仅 `grep`，默认 200 |
| `bodyLen` | int | 是 | body 字节数；无 body 时写 0 |

**响应**
```json
{"id":1,"ok":true,"size":1234,"mtime":1699999999,"bodyLen":0}
{"id":1,"ok":false,"err":"ENOENT","msg":"no such file","bodyLen":0}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | int | 与请求一致 |
| `ok` | bool | 成功/失败 |
| `err` | string | 失败时的错误码，见第 4 节 |
| `msg` | string | 失败时的可读说明（英文，120 字符内） |
| `size` | int | `stat`/`read`/`write` 的文件总大小 |
| `mtime` | int | `stat` 的修改时间（epoch 秒） |
| `isDir` | bool | `stat` 用 |
| `bodyLen` | int | body 字节数 |

## 3. op 定义

| op | 入参 | 成功响应 | body |
|---|---|---|---|
| `hello` | — | `{"ok":true}` + 能力字段 | 无 |
| `stat` | path | `ok` + `size`/`mtime`/`isDir`/`perm` | 无 |
| `list` | path, maxDepth, recursive | `ok` | JSON 数组（见下） |
| `read` | path, offset, len | `ok` + `size` | 文件片段原始字节 |
| `write` | path, body, mode, overwrite | `ok` + `size` | 待写字节 |
| `mkdir` | path | `ok` | 无 |
| `remove` | path, recursive | `ok` | 无 |
| `rename` | path, to | `ok` | 无 |
| `hash` | path | `ok` + `size`（`hash` 字段为 `sha256:<hex>`） | 无 |
| `grep` | pattern, path, maxResults | `ok` | JSON 数组（见下） |

**`hello` 的能力字段**：`{"ok":true,"proto":1,"go":"1.22","os":"linux","arch":"amd64","hasRg":true,"home":"/home/u","canHash":true}`

**`list` 的 body**（JSON 数组，紧凑无空格）
```json
[{"n":"a.txt","t":0,"s":1234,"m":1699999999},{"n":"src","t":1,"s":0,"m":1699999000}]
```
`n`=名字，`t`=类型（0 普通文件 / 1 目录 / 2 符号链接 / 3 其它），`s`=大小，`m`=mtime 秒。

**`grep` 的 body**
```json
[{"p":"/abs/path/a.txt","l":42,"t":"matched line text"}]
```

## 4. 错误码

| 码 | 含义 |
|---|---|
| `ENOENT` | 路径不存在 |
| `EACCES` | 无权限 |
| `EISDIR` | 期望文件但拿到目录 |
| `ENOTDIR` | 期望目录但拿到文件 |
| `EEXIST` | 目标已存在且 `overwrite=false` |
| `EINVAL` | 参数非法（含越界路径） |
| `ETOOBIG` | body 或文件超出上限 |
| `EIO` | 底层 IO 失败 |
| `EUNSUPPORTED` | 不认识的 op |

## 5. 安全边界（硬规则）

- **所有 `path` 必须落在启动时传入的根目录内**（`-root /home/u/workspace`）。收到根外路径一律返回 `EINVAL`，不做「尽力而为」。
- 校验用**规范化后的绝对路径前缀比较**（`filepath.Clean` + 分隔符边界），不是字符串 `HasPrefix`。
- **符号链接不跟随越界**：解析后用 `filepath.EvalSymlinks` 复核一次，仍须在根内。
- `remove` 带 `recursive` 时，根目录本身永远拒绝（`EINVAL`）。
- 不做 shell 拼接：所有操作走 Go 标准库 `os`/`filepath`，`grep` 用内置实现或 `rg` 的 argv 直传（不经 `sh -c`）。

## 6. 日志（人可读，这是二进制协议的可观测性保障）

agent 每处理一帧，往 **stderr** 打一行：

```
[agent] id=12 op=read path=/home/u/workspace/a.txt len=65536 -> ok size=1234 body=4096 ms=3
[agent] id=13 op=read path=/home/u/workspace/b.txt -> ENOENT msg="no such file" ms=1
```

- 只打 stderr，**stdout 只跑协议帧**，不能混。
- 每行都要能直接 `grep`/`tail`，字段顺序固定。
- Android 端把 stderr 收进 `FileLogger.d("RemoteAgent", ...)`，出问题看日志即可，不用解帧。

## 7. 版本协商

- 协议版本固定为 `1`。`hello` 返回 `proto` 字段。
- Android 端发现 `proto != 1` 时**不降级使用**，直接回落到 L3（SFTP），并记 WARN。
- agent 二进制文件名带版本：`aicode-agent-v1-linux-amd64`，便于并存与回滚。
