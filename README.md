# ChuMai Rating Calc

舞萌 DX 与中二节奏的本地成绩管理、Rating 计算及微信公众号成绩同步工具。

项目使用原生 HTML、CSS、JavaScript 与 JDK 标准库实现，不依赖 Maven、Gradle 或第三方
Java Web 框架。网站必须登录后使用，每位用户的成绩、游玩记录和个人资料均独立保存。

> [!IMPORTANT]
> 这是非官方社区项目，与 SEGA、华立科技、Diving-Fish 或 LXNS 无隶属关系。
> 微信公众号页面或第三方 API 改版后，同步功能可能需要跟随调整。

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

