# parsehub-kmp

ParseHub 多端版（Kotlin Multiplatform）：**一套共享解析/下载内核**，Android 与桌面端共用。

- `shared/` — 共享内核（android + desktop JVM 双目标）
  - 8 平台解析：抖音（原画/图集/实况/精选页真值）、快手、微博、哔哩哔哩（分P）、今日头条、皮皮虾、最右、小红书
  - 解析逻辑移植自 [z-mio/parsehub](https://github.com/z-mio/parsehub)（本地参考克隆）与 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos)
  - 共享下载器：Range 续传、≥16MB 自动 4 路分片、MP4 容器校验、多线路重试
  - 共享状态控制器 `HubController`（Compose runtime 状态 + 协程编排）
- `app-android/` — Miuix（HyperOS 风格）Android 客户端，下载写入 `Download/<平台>_<作者>/`（MediaStore，无需存储权限）
- `app-desktop/` — ParseHub Studio 桌面版（Compose Desktop + Miuix），选目录直接下载

架构说明：内核与编排层全部在 `shared` 的 commonMain/同构层，UI 各自实现（Android/桌面），后续如需 iOS/Web 只需补 UI 层。

本地运行：
```bash
./gradlew :app-desktop:run          # 桌面版
./gradlew :app-android:assembleDebug  # Android APK
```

APK 由 GitHub Actions 构建（见 `.github/workflows/build.yml`）。
