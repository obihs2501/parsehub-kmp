# ParseHub

ParseHub 多端版：Android 与 Windows 共用 Kotlin Multiplatform 解析、下载、命名和归档内核。

- `kernel/`：纯业务内核，Android / JVM 双目标。支持抖音、快手、微博、哔哩哔哩、贴吧、今日头条、皮皮虾、最右、小红书、豆瓣、酷安、微信公众号、TikTok、Instagram、X/Twitter、Threads。支持图集/实况、多线路、Range 续传与分片下载、同创作者别名和命名模板。
- `shared/`：Android 与旧桌面共用的 Compose UI 和 `HubController`，通过 kernel 复用业务逻辑。
- `app-android/`：Miuix Android 客户端，包名 `com.parsehub.app`。MediaStore / SAF 保存、图库与应用内媒体预览、自适应侧栏及小屏悬浮导航。
- `studio/`：新的 Windows 桌面端，Tauri 1 + React + WebView2。四个入口为解析、图库、记录、设置；下载进度显示在解析页面。图库基于用户自有 x-spider 的实现迁移，来源说明见 `studio/src/REFERENCE.txt`。
- `sidecar/`：Java 21 / Ktor 后台，调用 kernel。Rust 启动随应用打包的 Java，保存每次启动生成的令牌，通过受限 Tauri IPC 转发本机接口，前端不接触端口和令牌。关闭应用通过 stdin EOF 停止后台，超时则回收进程。
- `app-desktop/`：原 Compose Desktop 源码，保留作兼容与迁移参考；Windows 安装包由 `studio/` 生成。

解析逻辑参考 [z-mio/parsehub](https://github.com/z-mio/parsehub) 与 [jiuhunwl/short_videos](https://github.com/jiuhunwl/short_videos)。海外平台使用纯 HTTP 路径；受限内容可自行配置 Cookie。Facebook / YouTube / Snapchat 的 yt-dlp 子进程方案及小黑盒 / 知乎的设备签名方案未移植。不提供预置 Cookie 或访问令牌。

## 获取安装包

所有编译、测试和打包仅在 [GitHub Actions](https://github.com/obihs2501/parsehub-kmp/actions/workflows/build.yml) 执行。打开成功的 build 运行并下载：

| Artifact | 内容 |
| --- | --- |
| `parsehub-apk` | Android debug APK |
| `parsehub-studio-windows` | Windows x64 NSIS 安装程序，含 Java 21 runtime 和 sidecar JAR |
| `parsehub-sidecar` | 供 Windows job 使用的后台 JAR |
| `jvm-test-reports` / `windows-diagnostics` | 回归测试与启动诊断 |

Windows 需要 WebView2；Tauri 安装程序使用其默认 WebView2 引导安装流程，缺少运行时时需要网络。无需另装 Java。首次 Windows 安装建议先安装再启动，检查中文或空格路径下的资源加载、退出后无 Java 残留。

设置和历史写入 Tauri 应用数据目录 `com.parsehub.studio` 下；JVM 的错误日志为该目录内 `sidecar-stderr.log`。首次启动读取旧 `~/.parsehub/` 配置和历史进行迁移，保留原文件。下载目录可在设置中通过原生文件夹选择器更改，图库可添加多个本地根目录。

## CI 验证范围

工作流执行 Android APK 和 launcher dex 校验、kernel JVM 测试、sidecar 测试、fat JAR API 认证/配置/无效输入/EOF 退出 smoke，以及 Windows 前端与 Rust 构建和应用启动/后台退出 smoke。锁定 pnpm 9 与前端依赖；Java runtime 来自 setup-java 的 Temurin 21，随包携带其许可证目录。

自动化启动通过不等于视觉验收通过。安装后的待验项目包括：原生目录选择与 CSV 保存、真实公开链接解析/下载/取消、历史打开已保存文件、根目录媒体与深层目录浏览、超过 800 项的分页、视频首帧兼容性、灯箱缩放/拖动/键盘、深浅主题重启保持、设置返回，以及 Android 小屏有纹理内容经过玻璃栏时的效果。设备验收不得改变显示分辨率或密度。阶段进度见 `TASKS.md`。
