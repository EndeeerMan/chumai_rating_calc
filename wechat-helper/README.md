# 微信本地同步辅助程序

这是主项目的可选本地组件，支持舞萌 DX 与中二节奏。它接收网站创建的
15 分钟一次性会话，在微信完成 OAuth 后自动读取官方最佳成绩与最近游玩，
通过本地曲库交叉验证并转换为规范 SongID，再一次性提交给本机 Java 后端。

正式模式只处理以下入口：

- 本机或局域网 `GET /start?sessionId=...&token=...`
- `tgk-wcaime.wahlap.com:80` 下经过审计的 OAuth 回调：
  `/wc_auth/oauth/callback/maimai-dx`、兼容的
  `/wc_auth/oauth/callback/maidx` 与
  `/wc_auth/oauth/callback/chunithm`
- OAuth 回调页同源轮询的 `/progress/<随机短期标识>`；不接受查询参数

其他普通 HTTP 代理请求全部返回 403。Helper 只劫持一次 HTTP 回调：标准 HTTP 代理模式下，
Helper 收到的所有目标端口不是 80 的 CONNECT（包括常见的 HTTPS 443）都会作为不透明
隧道原样透传，不解密、不记录；Clash 的受限 AND 规则则只把 Wahlap `:80` 的 CONNECT
送入同一个受审计 addon，443 与其他非 80 普通流量不由这条 Clash 规则接管，也不送入 Helper，
从而避免 Helper 的上游连接再次命中代理而形成自代理循环。
因此不需要安装 mitmproxy CA。OAuth 参数、Cookie 和一次性 token 只保存在内存，
不会写入文件或日志。

## 安装与启动

要求 Python 3.12。首次在项目根目录运行：

```powershell
.\setup-wechat-helper.ps1
```

脚本创建 `wechat-helper/.venv`、安装锁定依赖并执行离线检查。以后可单独检查：

```powershell
.\run-wechat-helper.ps1 -DryRun
```

正式同步先启动网站，再启动 Helper：

```powershell
.\run-web.ps1
.\run-wechat-helper.ps1
```

网站使用自定义端口时，两边保持一致：

```powershell
.\run-web.ps1 -Port 8090
.\run-wechat-helper.ps1 -BackendUrl http://127.0.0.1:8090
```

Helper 固定监听 `0.0.0.0:8081`。Windows 防火墙如有提示，只允许专用网络。

从手机等局域网设备同步时，先确认同步页显示的是运行 Helper 的电脑当前私网 IPv4，
并让手机代理或 Clash 配置使用该地址和 `8081`。直接访问 Helper 根路径得到 `403` 是
预期的安全拒绝，不代表端口没有监听。需要建立固定的最小权限 Windows 入站规则时，
在项目根目录运行：

```powershell
.\setup-lan-access.ps1 -Helper
```

该模式只管理固定名称 `MaimaiRatingCalc-WeChatHelper-PrivateLAN`，范围严格限定为
Private 配置文件、LocalSubnet 来源、Inbound Allow TCP 8081、Edge Traversal Block；
它与网站端口使用的 `MaimaiRatingCalc-Web-PrivateLAN` 规则完全独立。重复运行会更新同一条
Helper 规则，不会改动网站的默认 TCP 8080 规则。不要同时传入 `-Helper` 与 `-Port`。

暂时禁用或彻底移除 Helper 规则：

```powershell
.\setup-lan-access.ps1 -Helper -Disable
.\setup-lan-access.ps1 -Helper -Remove
```

排查已经运行的 Helper 时，应先检查页面显示的 Helper 地址、手机代理／Clash 规则和 Helper
控制台状态，不能只根据根路径的 `403` 认定防火墙故障。

## Clash 或标准 HTTP 代理（二选一）

同步页统一使用一个由玩家手动填写的 Helper 地址，页面不会自动探测或回填：Helper 与当前设备位于同一台电脑时可填
`127.0.0.1`，从局域网连接时填写运行 Helper 的电脑私网 IPv4。页面可直接唤起
当前设备注册的 Clash 客户端安装临时配置，也可复制 YAML 手动合并。核心规则为：

```yaml
proxies:
  - name: rhythm-wechat-local
    type: http
    server: 192.168.1.23
    port: 8081

rules:
  - AND,((DOMAIN,tgk-wcaime.wahlap.com),(DST-PORT,80)),rhythm-wechat-local
  - MATCH,DIRECT
```

合并进已有配置时，把 AND 规则放在已有 `MATCH` 等终止规则之前。Clash 使用
规则模式，并开启系统代理或 TUN。这里的 `type: http` 和 `port: 8081` 不能改成
SOCKS5 或 `8082`：Clash 会对规则命中的 Wahlap `:80` 建立 CONNECT，Helper 再将
隧道内的明文回调交给受审计 addon。`DST-PORT,80` 不能删除；HTTPS 443 与其他非 80
普通流量不由这条 Clash 规则接管、不送入 Helper，避免 Helper 上游回连形成自代理循环。
直接使用标准 HTTP 代理时，Helper 收到的非 80 CONNECT 仍会原样透明透传。Helper 不需要
SOCKS5 `8082` 或第二个监听端口，Windows 防火墙也只需开放现有 TCP 8081。

Clash 的安装链接只负责导入配置，不能替用户激活配置。导入后必须在订阅页点选新出现的
Helper 配置，确认它处于当前选中状态；如果仍激活原订阅，回调会直连 Wahlap 的 HTTP
端口并常见为 `504`。Clash 与下面的手动 HTTP 代理只能选一种；同步时不要同时运行
Fiddler、Charles 或另一套系统代理。

不使用 Clash 时，先关闭 Clash、Fiddler、Charles 与其他系统代理，再在 Wi-Fi 或移动网络的代理设置中选择手动 HTTP 代理，服务器
填写同一个 Helper 地址，端口填写 `8081`。Helper 会让 HTTPS 通过 CONNECT 原样
直通，不解密也不记录；只有经过审计的 Wahlap 明文 HTTP 回调会被处理，其他明文
HTTP 请求会被拒绝。同步结束后关闭该代理。

标准 HTTP 代理是推荐方式，也与早期抓取流程一致。正式启动脚本会检测当前用户会话中的
Fiddler、Fiddler Everywhere 和 Charles；这些程序会抢走 OAuth 回调并产生 504，因此检测到时
脚本会拒绝启动。同步页在签发会话前还会访问手填地址的 `/health`，只有确认目标确实是本
项目 Helper 后才继续。Helper 终端会记录不含查询参数和令牌的 start/callback 路由命中日志。

## 使用流程

1. 启动 Java 网站与 Helper，在 Clash 规则和标准 HTTP 代理中只选择一种并启用。
2. 登录成绩网站，在“同步游戏数据”选择舞萌 DX 或中二节奏并创建会话。
3. 在微信内置浏览器打开网页给出的 `/start` 链接并授权。
4. 授权回调页先显示“抓取中”状态；详情总数确定后，微信回调页和电脑同步页都会
   显示当前游戏、已处理数量、成功数、跳过数和百分比；存在跳过时还会显示受控原因。
   Helper 自动读取官方页面并导入，
   无需手动浏览成绩页。
5. 后端确认导入后，回调页自动切换为“上传完成”，可点击按钮关闭微信页面；
   失败时会显示脱敏错误并提示返回成绩页重试。
6. 返回成绩网站查看本次导入数量和新增游玩记录。

首次 `waiting_auth` 请求不声明游戏，Helper 只接受后端签发会话响应里的
`session.game`。之后 OAuth state、回调 slug、进度事件、抓取页面和导入 payload
均绑定到该游戏，避免跨游戏回调混用。

逐局进度沿用 `fetching` 状态，只发送 `game`、`stage=play_details`、`completed`、
`total`、`succeeded`、`skipped` 和固定白名单内的 `failureReasons` 计数。
`completed = succeeded + skipped`，原因计数之和必须等于 `skipped`，所有计数固定总数并
单调递增。进度不包含详情 `idx`、Helper Token、Cookie、URL、HTML 或异常原文；单次
进度上报失败不会丢弃已经抓到的游戏数据。同步成功或失败后，两个页面都会保留最后
一次安全计数和受控原因。

## 数据规则

- 舞萌读取五个难度的最佳成绩和最近游玩，谱面键为
  `SongID + chartType + difficulty`。
- 中二读取 `ratingDetailBest`、`ratingDetailRecent`、`ratingDetailNext` 和
  `playlog`，谱面键为 `SongID + difficulty`。
- 中二 Rating 页的隐藏 `idx` 只有在标题和难度也与本地曲库一致时才作为 SongID。
- 中二 playlog 的隐藏 `idx` 只是 0 到 49 的位置号，永远不会保存为记录 ID；
  历史记录由后端根据语义字段去重。
- 舞萌最近游玩的稳定官方 `idx` 会保存为 `sourceRecordId`；标题读取直接文本，
  避免等级文字混入歌曲名。
- Helper 会用这个稳定 `idx` 打开舞萌官方游玩详情页，保存 TAP/HOLD/SLIDE/
  TOUCH/BREAK 各自的 CRITICAL PERFECT、PERFECT、GREAT、GOOD、MISS 汇总。
- 舞萌详情页中能可靠识别时，还会保存 FAST、LATE、MAX COMBO、MAX SYNC、
  DX SCORE、本局后 DX Rating、Rating 变化、同步时玩家总 Rating，以及最多五名
  旅行伙伴的星数、等级和官方静态头像。缺少的单项保持缺失，不按截图位置猜值。
- 中二会在本次抓取期间使用 playlog 的临时 `idx` 打开官方详情页，保存全局
  JUSTICE CRITICAL/JUSTICE/ATTACK/MISS、TAP/HOLD/SLIDE/AIR/FLICK 达成率和
  MAX COMBO；官网没有提供“音符类型 × 判定”的矩阵，程序不会伪造该数据。
- 单条详情页暂时不可用时仍会导入该局基础成绩；以后再次同步抓到详情时会原位
  补全，并在结果中的 `recordsEnriched` 计数，不会生成一条重复历史记录。
- 相同谱面由后端忽略重复或保留更高成绩，并独立保留更强徽章状态。

曲库文件为：

- `cache/maimai-catalog.json`
- `web/chunithm-catalog/diving-fish-music-data.json`

曲库不完整或官方标题、难度、SongID 无法安全交叉验证时，本次同步明确失败，
不会猜测或静默漏曲。

回调进度页只持有随机、短期、内存内的状态标识，不包含网站同步 token、OAuth
参数或游戏 Cookie。完成或失败状态五分钟后自动清除，Helper 退出时立即清空。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MAIMAI_WECHAT_LISTEN_HOST` | `0.0.0.0` | 仅允许通配、回环或私网 IP |
| `MAIMAI_WECHAT_LISTEN_PORT` | 固定 `8081` | Helper 端口 |
| `MAIMAI_WECHAT_BACKEND_URL` | `http://127.0.0.1:8080` | 仅允许回环或私网后端 |
| `MAIMAI_WECHAT_SESSION_TTL_SECONDS` | 固定 `900` | 一次性会话有效期 |
| `MAIMAI_WECHAT_MAX_PENDING_SESSIONS` | `32` | 等待授权会话上限 1 到 128 |
| `MAIMAI_WECHAT_UPSTREAM_TIMEOUT_SECONDS` | `300` | 上游请求超时 10 到 600 秒 |
| `MAIMAI_WECHAT_BACKEND_TIMEOUT_SECONDS` | `30` | 后端请求超时 2 到 120 秒 |
| `MAIMAI_WECHAT_PLAY_UTC_OFFSET_MINUTES` | `480` | 官方无时区时间按 UTC+8 解释 |

上游客户端固定 `trust_env=False`，不会继承 Windows 系统代理。运行 Helper 的电脑
必须能直连 Wahlap 与微信 OAuth。

## 测试

```powershell
cd wechat-helper
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m compileall -q wechat_helper tests
```

本工具抓取的是官方详情页提供的玩家判定汇总，不是每一个音符的时间序列，也不会
用曲库中的原谱面物量代替玩家成绩。评论、排行榜和谱面评价不在同步范围内。
