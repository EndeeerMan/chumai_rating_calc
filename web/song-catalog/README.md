# 舞萌DX2026歌曲数据库
QQ群BOT随机roll歌功能用，舞萌DX国服歌曲数据库  
数据抓取自国服舞萌DX微信公众号maimaiNET  
最后更新：2026年6月16日 (版本: ****Ver**.**CN1.55**)

## 字段说明
- `image_file`: 封面文件, 前面加上 `https://maimai.wahlap.com/maimai-mobile/img/Music/` 即为官方的封面图片访问链接 (也对应`cover`文件夹里的图片文件)
- 其它字段一眼就看得懂, 没什么好说的

本目录保存随项目附带的离线快照与历史封面。根目录的统一同步命令：

```powershell
.\sync-song-catalogs.ps1
```

只使用 Diving-Fish（水鱼）和 LXNS（落雪）的免登录公开曲库数据，并从
`https://assets2.lxns.net/maimai/jacket/{SongID}.png` 补齐当前曲库尚未随附的封面。
下载内容按规范 SongID 缓存在 `cache/maimai-covers/`，通过本机
`/api/maimai/covers/{SongID}.png` 提供给网页；有效缓存不会重复下载，缺失或损坏的文件会
重新校验并补抓。该命令不访问华立官网，也不读取玩家成绩、OAuth 参数或 Cookie。
