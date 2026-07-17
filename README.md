# ChuMai Rating Calc

<p align="center">
  <img src="web/assets/maimai-mark.png" width="92" alt="舞萌 DX">
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="web/assets/chunithm-mark.png" width="92" alt="中二节奏">
</p>

舞萌 DX 与中二节奏的本地成绩管理、Rating 计算及微信公众号成绩同步工具。

项目使用原生 HTML、CSS、JavaScript 与 JDK 标准库实现，不依赖 Maven、Gradle 或第三方
Java Web 框架。网站必须登录后使用，每位用户的成绩、游玩记录和个人资料均独立保存。

> [!IMPORTANT]
> 这是非官方社区项目，与 SEGA、华立科技、Diving-Fish 或 LXNS 无隶属关系。
> 微信公众号页面或第三方 API 改版后，同步功能可能需要跟随调整。

## 快速导航

- [主要功能](#主要功能)
- [快速开始](#快速开始)
- [配置邮箱验证](#配置邮箱验证)
- [局域网访问](#局域网访问)
- [同步公开曲库与封面](#同步公开曲库与封面)
- [微信公众号成绩同步](#微信公众号成绩同步可选)
- [开发与测试](#开发与测试)
- [常见问题](#常见问题)

## 主要功能

| 游戏 | Rating 构成 | 支持内容 |
| --- | --- | --- |
| 舞萌 DX | B35 + B15 | 成绩卡、未入选成绩、游玩历史、玩家判定、谱面详情、FC/AP 与评级徽章 |
| 中二节奏 | B30 + N20 | 成绩卡、未入选成绩、游玩历史、玩家判定、谱面详情、FC/AJ 与评级徽章 |

- 使用规范 SongID 作为歌曲唯一标识，同一谱面重复导入时保留更高成绩
- 支持曲名与别名模糊匹配；无法可靠命中时由玩家自行搜索选择
- BASIC、ADVANCED、EXPERT、MASTER、Re:MASTER 等难度使用独立配色
- 自动展示歌曲封面，并缓存已经校验的舞萌和中二封面
- 支持注册、登录、邮箱验证码、换绑邮箱、修改密码及注销账号
- 支持头像、昵称，以及舞萌／中二相互独立的桌面端和移动端背景
- 可选微信公众号 Helper：同步最佳成绩、最近游玩、Rating 变化和逐局玩家判定
- 每天 `00:00`、`12:00`（Asia/Taipei）自动刷新两款游戏的公开曲库

AI 图片识别功能已经移除，当前成绩来源为玩家录入、文件导入或微信公众号同步。

## 运行要求

- Windows 10/11
- JDK 26，且 `java`、`javac` 已加入 `PATH`
- Windows PowerShell
- Python 3.12（仅微信公众号 Helper 需要）

先确认 Java 版本：

```powershell
java -version
javac -version
```

脚本使用 `javac --release 26`，低于 JDK 26 的版本无法完成编译。

## 快速开始

在项目根目录运行：

```powershell
.\run-web.ps1
```

脚本会将 `src/main/java` 中的源码编译到 `out/`，然后在所有本机 IPv4 接口上启动服务。
浏览器打开：

```text
http://localhost:8080
```

指定其他端口：

```powershell
.\run-web.ps1 -Port 8090
```

`8081` 固定保留给微信公众号 Helper，不能作为网站端口使用。

### 页面入口

| 地址 | 内容 |
| --- | --- |
| `/` | 舞萌 DX 分数构成、成绩列表和游玩记录 |
| `/chunithm.html` | 中二节奏分数构成、成绩列表和游玩记录 |
| `/sync.html` | 微信公众号成绩同步 |
| `/profile.html` | 头像、昵称、邮箱、密码、背景和账号管理 |

所有页面都要求登录。未登录或旧账号尚未绑定邮箱时，后端会限制成绩和同步接口。

## 配置邮箱验证

注册和邮箱换绑需要 SMTP。复制示例文件：

```powershell
Copy-Item .\verifycaton_email.env.example .\verifycaton_email.env
```

编辑根目录的 `verifycaton_email.env`：

```dotenv
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USERNAME=your-account@example.com
SMTP_PASSWORD=replace-with-an-app-password
SMTP_FROM=your-account@example.com
SMTP_FROM_NAME=B50 工作台
SMTP_SECURITY=STARTTLS
```

推荐填写邮箱服务商提供的应用专用密码，而不是网页登录密码。真实配置文件已经加入
`.gitignore`，请勿强制提交到 GitHub。修改配置后需要重启网站。

验证码为 6 位数字，10 分钟内有效；同一邮箱重新发送需要等待 2 分钟。

## 局域网访问

确认 Windows 当前网络为“专用网络”，然后以管理员权限创建仅限本地子网的防火墙规则：

```powershell
.\setup-lan-access.ps1
```

同一局域网设备可通过电脑的私网 IPv4 访问，例如：

```text
http://192.168.1.12:8080
```

使用自定义网站端口时，防火墙脚本和启动脚本需要填写相同端口。

## 同步公开曲库与封面

手动同步舞萌 DX、中二节奏曲库及缺失封面：

```powershell
.\sync-song-catalogs.ps1
```

需要经过 Clash HTTP 代理时：

```powershell
.\sync-song-catalogs.ps1 -ProxyHost 127.0.0.1 -ProxyPort 7890
```

该脚本只读取 Diving-Fish 与 LXNS 的公开 API，不读取华立官网账号、Cookie 或玩家成绩。
同步采用完整校验和原子发布；失败时保留上一份可用曲库。

## 微信公众号成绩同步（可选）

首次安装 Helper：

```powershell
.\setup-wechat-helper.ps1
```

正式使用时分别启动网站和 Helper：

```powershell
.\run-web.ps1
.\run-wechat-helper.ps1
```

Helper 固定监听 `0.0.0.0:8081`，每次授权会话有效期为 15 分钟。它只处理经过审计的
舞萌／中二 OAuth 回调；令牌、Cookie 和 OAuth 参数仅保存在内存中，不写入文件或日志。

手机等局域网设备需要开放 Helper 的专用网络入站规则：

```powershell
.\setup-lan-access.ps1 -Helper
```

Clash 与标准 HTTP 代理的完整配置、端口规则和故障排查请阅读
[微信公众号 Helper 文档](wechat-helper/README.md)。

## 项目结构

```text
chumai_rating_calc/
├─ src/main/java/          Java 后端与领域逻辑
├─ src/test/java/          不依赖第三方测试框架的 Java 测试
├─ web/                    前端页面、样式、脚本、曲库快照与静态资源
├─ wechat-helper/          可选的 Python 微信同步组件
├─ run-web.ps1             编译并启动网站
├─ sync-song-catalogs.ps1  同步两款游戏的公开曲库与封面
├─ setup-wechat-helper.ps1 安装 Helper 环境
├─ run-wechat-helper.ps1   启动 Helper
└─ setup-lan-access.ps1    管理网站或 Helper 的专用网络防火墙规则
```

更完整的实现规则与数据格式说明见 [说明文件.txt](说明文件.txt)。

## 开发与测试

Java 业务源码和测试源码都使用默认包，测试类各自提供 `main` 方法，不依赖 JUnit。可在
PowerShell 中编译并运行全部 Java 测试：

```powershell
$testOut = Join-Path $env:TEMP "chumai-rating-tests"
$mainSources = @(Get-ChildItem .\src\main\java\*.java | ForEach-Object FullName)
$testFiles = @(Get-ChildItem .\src\test\java\*Test.java | Sort-Object Name)
$testSources = @($testFiles | ForEach-Object FullName)

Remove-Item $testOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item $testOut -ItemType Directory | Out-Null
try {
    javac --release 26 -Xlint:all -encoding UTF-8 `
        -d $testOut $mainSources $testSources
    if ($LASTEXITCODE -ne 0) { throw "Java 测试编译失败" }

    foreach ($test in $testFiles) {
        java -cp $testOut $test.BaseName
        if ($LASTEXITCODE -ne 0) { throw "$($test.BaseName) 失败" }
    }
} finally {
    Remove-Item $testOut -Recurse -Force -ErrorAction SilentlyContinue
}
```

已经安装 Node.js 时，可运行根目录中不依赖 npm 包的前端契约测试：

```powershell
Get-ChildItem .\*Test.js | Sort-Object Name | ForEach-Object {
    node $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "$($_.Name) 失败" }
}
```

提交前至少确认 Java 全量编译通过、所有测试通过，并检查 `git status` 中没有
`verifycaton_email.env`、`user_data/`、Cookie、OAuth 地址或一次性令牌。

## 数据与备份

- `user_data/`：账号、邮箱映射、成绩、游玩记录、头像和自定义背景，必须定期备份
- `cache/`：可重新获取的在线曲库与封面缓存
- `out/`：JDK 编译产物，启动脚本会重新生成
- `verifycaton_email.env`：SMTP 密钥配置，不得上传至公开仓库

`user_data/`、`cache/`、Helper 虚拟环境和真实邮箱配置均已加入 `.gitignore`。不要同时启动
两个后端实例写入同一个 `user_data/`，也不要手工修改其中的 JSON 文件。

## 数据来源

- [Diving-Fish 开发者 API](https://maimai.diving-fish.com/manual/docs/developer/zh-api-document)
- [LXNS API 文档](https://maimai.lxns.net/docs)
- 玩家主动授权时读取的华立舞萌 DX／中二节奏官方页面

公开 API 只用于同步曲库、别名、谱面和封面信息；玩家成绩只会在玩家主动创建的短时
OAuth 会话中读取。

## 部署提示

本项目后端需要 JVM、本地持久化目录和可选的 Python Helper，不能把整个项目直接部署到
Cloudflare Workers。若要公开部署，应将 Java 后端放在支持 JDK 26 和持久化磁盘的服务器上，
再使用 Cloudflare 提供域名、HTTPS 或反向代理。

本仓库不应直接塞入完整 JDK：用户应自行安装 JDK 26。常见完整 JDK 包含超过 GitHub
普通单文件 100 MiB 上限的文件，也会让仓库体积大幅增加。

## 常见问题

### `java` 或 `javac` 无法识别

安装 JDK 26，将其 `bin` 目录加入系统 `PATH`，关闭并重新打开 PowerShell，再运行
`java -version` 与 `javac -version`。只有 JRE、版本低于 26 或仅设置 `JAVA_HOME` 而未更新
`PATH` 都不足以运行当前编译脚本。

### 电脑可以打开，手机无法访问网站

确认手机与电脑位于同一局域网、Windows 网络类型为“专用网络”，并重新运行
`.\setup-lan-access.ps1`。访问地址应填写电脑当前私网 IPv4，而不是 `127.0.0.1`。

### Helper 根地址返回 `403`

这是正常的最小权限行为。Helper 不提供普通网页，只接受网站创建的短时 `/start` 地址、
受审计的 Wahlap OAuth 回调和同源进度轮询。检查状态应访问同步页或 Helper 的 `/health`。

### 微信同步出现 `502`、`504` 或 `ERR_EMPTY_RESPONSE`

- 停止会抢占系统代理的 Fiddler 等工具
- 确认 Clash 使用规则模式，并已激活网站生成的配置
- Wahlap 规则必须保留 `DST-PORT,80`，并放在 `MATCH` 等终止规则之前
- Helper 只使用 HTTP `8081`，不要改成 SOCKS5 或虚构第二个端口
- 重启 Helper 后重新创建会话，不要复用旧 `/start` 地址或 OAuth 回调链接

完整排查步骤以 [微信公众号 Helper 文档](wechat-helper/README.md) 为准。

### 收不到邮箱验证码

检查 `verifycaton_email.env` 的主机、端口、安全模式、发件地址和应用专用密码，确认配置不是
示例占位值，然后重启网站。第三方 SMTP 应使用 `STARTTLS` 或 `SSL`。

### 曲库或封面缺失

运行 `.\sync-song-catalogs.ps1`。若公共 API 在当前网络不可达，可显式填写 Clash HTTP 代理。
同步失败不会覆盖上一份通过校验的曲库；再次运行会继续补齐尚未下载的封面。

## 安全与隐私

- 不要公开包含 `sessionId`、`token`、`code` 或 `state` 的同步／OAuth URL
- 不要提交 `verifycaton_email.env`、`user_data/`、抓包文件或带 Cookie 的网页内容
- 网站与 Helper 的防火墙规则只应开放给可信的本地子网，不要直接暴露到公网
- Helper 授权完成后应退出官方页面；一次性会话过期后重新创建，不复用旧凭证
- 对外部署时必须配置 HTTPS、可信反向代理、持久化备份和额外的访问控制

## 反馈与贡献

提交问题时请提供：游戏类型、复现步骤、预期结果、实际结果、JDK/Python 版本及已经脱敏的
控制台错误。请先删除截图或日志中的用户名、邮箱、Cookie、OAuth 参数和一次性令牌。

修改曲库匹配逻辑时，必须继续以 SongID 作为唯一歌曲标识；标题与别名只能用于寻找候选项，
不能取代 SongID 持久化。修改成绩合并逻辑时，应保持“完全相同则忽略，冲突时取最高成绩”的
现有规则，并同时补充舞萌与中二对应测试。

## 第三方内容说明

歌曲信息、封面、游戏名称、图标与相关素材的权利归各自权利人所有。Diving-Fish、LXNS 和
华立官方页面的数据使用应遵守各自服务条款与访问限制。本项目仅用于个人成绩管理、学习和
研究，不保证第三方服务永久可用。
