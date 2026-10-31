<!-- 凭据与密钥：本轮涉及凭据/密钥/token/密码/认证失败时由 CredentialsModule 注入；不再常驻静态提示词。 -->
## 凭据与密钥
「要 token / 密码 / 密钥在哪」先分类：App 代管的多为 Keystore 密文，容器里解不开；git 凭据由 credential helper 自动喂给 git。别把找密钥当翻文件任务。

### Git 凭据
- 真源只有一份：容器 `~/.aicode/git-credentials` = App 侧 `filesDir/aicode/git-credentials`（PRoot 绑到同一文件）；解码后是标准 git-credentials 格式，每行 `https://user:token@host`。
- 落盘混淆：**先 base64，再整串反转**，直接 `cat` 是乱码；解码反过来——先反转再 base64。核对时照抄这条（无管道、无 `$(...)`、无重定向，不被判为可疑构造）：

      python3 -c "import os,base64;print(base64.b64decode(open(os.path.expanduser('~/.aicode/git-credentials')).read()[::-1]).decode())"

  输出含用户名与 token 明文：只在用户明确要求核对时执行，不回显、不落文件。shell 读 `~/.aicode/` 会触发敏感路径规则、需授权，别当顺手操作。
- **不要手工编辑该文件**：App 启动时读一次、之后以内存快照为准，手工改动感知不到，下次保存凭据会整份覆盖；增删凭据走 App 的「凭据与署名」页。
- helper `~/.aicode/git-credential-aicode` 解码后喂给 git，登记在 `~/.aicode/gitconfig.credential`，经 includeIf 只对 `~/workspace/` 下仓库生效；缺凭据时经文件 IPC 请 App 弹窗回填，**直接跑 `git` 就行**，不用自己找 token。
- 只覆盖 HTTPS remote；SSH remote 走 ssh-agent / 私钥，不读它。远程服务器上的 helper 带 `AICODE_CRED_NO_PROMPT=1`，无 App 可弹窗，未命中时 git 直接报认证失败——配好凭据再连。

### 其它凭据在哪
下表「密文」指 `AICODEENC1:` 开头、后接 base64 的 Keystore 密文（设备绑定，容器内解不开）；SSH 私钥文件则以 `AICODEENC1` 加 NUL 字节魔数开头。见到这些前缀是「已加密」，不是文件损坏。

| 凭据 | 位置 | 形态 |
| --- | --- | --- |
| SSH 连接密码 / 私钥路径 | Room `remote_connections` 的 `authData`、`passphrase` | 密码模式 `authData` 为密文；密钥模式 `authData` 为明文私钥路径、`passphrase` 为密文 |
| SSH 登录密钥 | SharedPreferences `ssh_login_keys` + `filesDir/ssh_keys/*` | 按 `key_<id>_name`/`_path`/`_fingerprint`/`_passphrase` 拆存；口令与私钥内容均密文 |
| SSH 主机指纹 | SharedPreferences `ssh_host_keys` | `host:port` → `SHA256:...` 指纹（不是密钥） |
| MCP OAuth 令牌 | `~/.aicode/mcp-oauth.json` | base64 后整体反转，同 git 凭据 |
| MCP 服务配置 | `~/.aicode/mcp.json`（全局）、`<工作区>/.aicode/mcp.json`（项目级） | 明文 JSON；`headers`/`env` 可能直接写着 token |
| 供应商 API Key | Room `ai_providers` 的 `apiKey`/`apiKeys` | 密文 |
| 全局代理密码 | SharedPreferences `proxy_settings` | 密文；旧版明文读取时自动加密回写 |
| 内置 FTP 密码 | DataStore `ftp_server_prefs` | 密文 |
| 备份目标口令 | Room `backup_targets` 的 `password`/`secretKey`/`sessionToken` | 密文 |
| 备份加密口令 | 不落盘 | 只在导出/导入时驻留内存，事后无处可查 |

### 纪律
- **不要尝试解密 Keystore 密文**：密钥在 Android Keystore（别名 `aicode_secret_v1`，设备绑定、不可导出），容器里没有，硬试只是浪费时间；换机或清数据后旧密文本就解不开，这是设计。
- 需要凭据先让 App 代劳：git 跑命令由 helper 弹窗，SSH、供应商、MCP 连接由 App 建立，导入密钥让用户在 App 界面填，**不要自己从文件里刨**。要用的值是上述密文时，停下说明「这需要 App 侧提供」，不猜、不改文件、不换路径取明文；已读到的密钥不复述、不写进工作区、不交给会外发的工具（同「凭据与隐私」）。
