# CHUNITHM 曲库快照

本目录中的 `diving-fish-music-data.json` 与
`diving-fish-latest-version.json` 分别来自以下无需登录的公开接口：

- `https://www.diving-fish.com/api/chunithmprober/music_data`
- `https://www.diving-fish.com/api/chunithmprober/latest_version`

执行统一同步后，本目录还会生成 `lxns-song-list.json` 与
`lxns-alias-list.json`，分别对应：

- `https://maimai.lxns.net/api/v0/chunithm/song/list`
- `https://maimai.lxns.net/api/v0/chunithm/alias/list`

快照用于离线启动和联网失败时回退。交互搜索的联网结果使用 30 分钟进程内缓存；
后台在 Asia/Taipei 每天 00:00、12:00 下载并严格校验四份公开资源，全部有效后
才逐文件原子替换。任一下载、校验或发布失败都会保留上一份可用内存与磁盘数据。
本地曲库不会包含用户成绩、OAuth 参数、Cookie 或其他认证信息。

联网刷新时保留 Diving-Fish 作为主曲库，并使用 LXNS 的两个免登录公共接口做增强：

- `https://maimai.lxns.net/api/v0/chunithm/song/list`
- `https://maimai.lxns.net/api/v0/chunithm/alias/list`

LXNS 用于补充曲目别名、`disabled` 状态、逐难度谱面版本，以及
WORLD'S END 的 `origin_id`。LXNS 当前曲目是 Diving-Fish 曲库的子集，
因此不能直接用它替换主快照。交互搜索时 LXNS 暂时失败仍可使用本次有效的
Diving-Fish 结果；00:00/12:00 定时同步与根目录手动同步采用更严格的全有或全无
策略，LXNS 失败时不会发布一组不完整的新磁盘快照。

根目录手动同步命令为：

```powershell
.\sync-song-catalogs.ps1
```

它同时同步舞萌与中二，默认用分区中文报告逐项显示 Diving-Fish、LXNS 每个 API
的验证与发布状态，以及封面总数、已有数、本次下载数和剩余缺失数；需要原始机器可读
结果时添加 `-Json`。旧的舞萌单游戏脚本已经删除，避免名称与实际“双游戏同步”行为
产生歧义。

统一曲库命令只访问 Diving-Fish、LXNS 的公开曲库接口与 LXNS 封面资源，绝不访问
华立官网或读取玩家成绩、OAuth 参数和 Cookie。玩家官网同步属于独立的 Helper 流程，
不出现在本命令的结果中。

Diving-Fish 开发文档没有公开 CHUNITHM 封面端点；其 `/covers` 契约仅适用于
maimai 歌曲 ID，不能直接套用到 CHUNITHM。LXNS 文档给出的中二资源基础地址为
`https://assets2.lxns.net/chunithm`。统一脚本会从固定的 `/jacket/{coverSongId}.png`
补齐当前曲库所需封面，并缓存在项目的 `cache/chunithm-covers/`；有效 PNG 不会重复下载，
网站后端仍可在单张缓存缺失时通过同一固定来源按需修复。

WORLD'S END 不能使用其成绩 Song ID 请求曲绘，优先使用 LXNS
`SongDifficulty.origin_id`。旧式六难度离线数据缺少该字段时，仅在去除开头 `[标记]` 后的
标题按 NFKC 精确且唯一对应一首普通歌曲时，才复用该普通歌 Song ID；歧义或无匹配仍使用
本地占位图，不会模糊猜测或反复请求必然 404 的地址。`disabled=true` 的歌曲仍可展示
历史成绩，但不会进入 B30/N20。

## 曲库搜索与成绩匹配

系统会对曲名、别名以及常见的全角、空白和标点差异进行模糊检索，但只有结果
唯一且相似度足够高时才会自动匹配。匹配成功后，Song ID、谱面 CID、定数、
逐难度版本和封面均以曲库记录为准。

没有达到自动匹配阈值，或最高候选仍有歧义时，用户需要在曲库搜索结果中明确
选择正确歌曲，再按难度补全谱面资料。联网增强不可用时搜索会回退到本地快照；
曲库中不存在的 Song ID 不会被当作权威歌曲标识，以免把不同歌曲的成绩合并到
同一谱面。

数据结构和调用约束参考：

`https://maimai.diving-fish.com/manual/docs/developer/zh-api-document`

`https://maimai.lxns.net/docs/api/chunithm`
