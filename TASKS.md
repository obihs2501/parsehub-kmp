# Desktop WebView migration

All compilation, tests and packaging run on GitHub Actions only. Source implementation and runtime acceptance are tracked separately; an unchecked phase is not a verified delivery.

| Phase | Source status | Verification still required |
| --- | --- | --- |
| T1 Shared kernel and download planning | Extracted kernel; Android controller delegates shared plan | CI kernel tests and Android/legacy desktop compilation |
| T2 JVM sidecar | Authenticated loopback API, settings/history, task/download/cancel implemented | CI tests, fat JAR service providers, API/EOF smoke |
| T3 Tauri WebView2 and Java bundle | Native bridge, owned Java lifecycle and NSIS resources implemented | CI Rust/frontend build and executable startup/shutdown smoke; installed path checks |
| T4 Parse and history | Four tabs; media selection, inline tasks, CSV and gallery navigation implemented | CI compilation; real-link parse/download/cancel and file preview acceptance |
| T5 Gallery | Multiple roots, one-level scan, paging, thumbnail queue and refresh implemented | Canonical mtime cache and versioned thumbnail keys reviewed; large-directory/race and WebView2 video checks pending |
| T6 Lightbox/theme/settings | Retained lightbox, theme switching, native dialogs and visible settings return implemented | Pointer-centered zoom and focus handling reviewed; runtime keyboard, theme persistence and dialog checks pending |
| T7 Android glass | Small-screen capture, glass blend/highlight and scroll-content inset fixes implemented | CI APK and device visual validation without changing density/resolution |
| T8 Delivery | GitHub Android/JVM/Windows pipeline and diagnostics implemented | Successful run and artifact links; install/device acceptance |

- [ ] CI Android APK and launcher dex verified
- [ ] CI kernel/sidecar regressions and fat JAR API smoke verified
- [ ] CI Windows installer and graceful JVM shutdown verified
- [ ] Windows installed-app acceptance verified
- [ ] Android small-screen glass visually verified

Do not retire the legacy Compose Desktop compatibility build until the new installed application passes acceptance. Do not equate a successful compilation/startup smoke with completed visual or interaction testing.
