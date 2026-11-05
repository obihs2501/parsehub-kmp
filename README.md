# parsehub-kmp

ParseHub 多端版（Kotlin Multiplatform）：**一套共享解析/下载内核**，Android 与桌面端共用。

- `shared/` — 共享内核（android + desktop JVM 双目标）
  - 16 平台解析：抖音（原画/图集/实况/精选页真值）、快手、微博、哔哩哔哩（分P）、贴吧、今日头条、皮皮虾、最右、小红书、豆瓣、酷安、微信公众号、TikTok、Instagram、X/Twitter、Threads
  - 海外 4 平台对齐 [ParseHub-Studio](https://github.com/z-mio/parsehub) 的纯 HTTP 路径：Instagram/Threads 走 GraphQL、TikTok 走移动端 feed + 网页回退、X 走公开嵌入接口；均无需硬编码凭据，受限内容可在设置里按平台填 Cookie
  - 上游另外 5 个平台（Facebook / YouTube / Snapchat 依赖 yt-dlp 子进程，小黑盒 / 知乎依赖设备指纹或登录签名）不属于纯 Kotlin HTTP 可移植范围，未纳入
  - 解析逻辑移植自 [z-mio/parsehub](https://github.com/z-mio/parsehub)（本地参考克隆）与 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos)
  - 共享下载器：Range 续传、≥16MB 自动 4 路分片、MP4 容器校验、多线路重试
  - 共享状态控制器 `HubController`（Compose runtime 状态 + 协程编排）
  - 同一创作者多号合并、命名/目录模板、Cookie 池、别名、解析/下载记录
- `app-android/` — Miuix（HyperOS 风格）Android 客户端
  - 下载写入 `Download/<子目录>/`（MediaStore，或 SAF 自选目录，均保证同名不覆盖）
  - 图库：文件夹层级浏览、搜索/类型/排序/列数、多选分享删除、全屏看图（左右滑、Telephoto 双指缩放、内嵌视频播放、返回键关闭、自动隐藏顶栏）
  - 滚动时头部自动收起；浅色/深色状态栏图标反色；悬浮胶囊 / 液态玻璃底栏可选；宽屏自动切换窄侧栏（顶栏只覆盖内容区）
  - 记录页可直接点开已下载的图片/视频预览
  - 「所有文件访问权限」入口，保证清数据后仍可读到其它应用写入的媒体
- `app-desktop/` — ParseHub Studio 桌面版（Compose Desktop + Miuix）
  - 参考 x-spider 的图库：侧栏 + 面包屑 + 工具栏 + 子文件夹 + 媒体网格/列表 + 应用内看图窗口（缩放/键盘/相邻预载）
  - 下载目录、图库偏好持久化到 `~/.parsehub/`

架构说明：内核与编排层全部在 `shared` 的 commonMain/同构层，UI 各自实现（Android/桌面），后续如需 iOS/Web 只需补 UI 层。

本地运行：
```bash
./gradlew :app-desktop:run          # 桌面版
./gradlew :app-android:assembleDebug  # Android APK
```

APK 与 Windows 安装包由 GitHub Actions 构建（见 `.github/workflows/build.yml`）。
