# 开发与架构

## 当前基线

- Android applicationId：`com.zbox.ai`
- 版本：`1.0.0`，versionCode `1`
- Android Gradle Plugin `8.5.2`；Kotlin Android `2.0.20`
- compileSdk/targetSdk `34`；minSdk `26`
- Android 单模块：`:app`

## 代码布局

- `ai`：AI 服务及配方能力
- `core`：领域策略、预设与图像逻辑
- `data`：设置、持久化和更新
- `ptp`：USB/PTP 通信
- `ptpip`：网络 PTP/IP 连接与传输
- `ui`：Jetpack Compose 页面和组件
- `util`：通用图像工具

详见 `docs/ARCHITECTURE.md`、`docs/FEATURES.md` 与 `docs/BUILD_TEST.md`。
