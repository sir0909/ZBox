# 开发与架构

## 当前基线

- Android applicationId：`com.zbox.ai`
- 版本：`1.0.1`，versionCode `2`
- 更新说明持久化标识：`1.0.1-r1`（见 `MainViewModel.currentChangelogId`）
- Android Gradle Plugin `8.5.2`；Kotlin Android `2.0.20`
- compileSdk/targetSdk `34`；minSdk `26`
- Android 单模块：`:app`

## 代码布局

- `ai`：AI 服务及配方能力
- `core`：领域策略、预设与图像逻辑
- `data`：设置、持久化和更新
- `editor`：Lightroom 风格非破坏性本地修图参数、渲染引擎、导出与控制器
- `ptp`：USB/PTP 通信
- `ptpip`：网络 PTP/IP 连接与传输
- `ui`：Jetpack Compose 页面、主题（`Theme.kt` 深/浅双色语义令牌）和组件（`ui/components/` 含曲线画布与可编辑滑杆）
- `util`：通用图像工具

详见 `docs/ARCHITECTURE.md`、`docs/FEATURES.md`、`docs/UI_DESIGN.md` 与 `docs/BUILD_TEST.md`。
