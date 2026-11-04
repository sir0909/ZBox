# Zbox 1.0.0 正式版发版验收清单

- [x] `namespace` 与 `applicationId` 均为 `com.zbox.ai`，应用显示名称为 `Zbox`，版本为 `1.0.0`（`versionCode = 1`）。
- [x] 使用 Zbox 专属正式发布密钥（`CN=Zbox, OU=Camera Companion, O=Zbox, C=CN`）完成 `:app:assembleRelease` 构建，`apksigner verify`（v2 签名）校验通过，且不包含 `android:debuggable` 或调试工具依赖。
- [x] 更新器仓库地址已指向 `sir0909/ZBox`，下载白名单与正式版附件名 `Zbox-1.0.0.apk` 严格匹配。
- [x] 离线核心算法与策略测试套件（`Test-ReleaseUpdatePolicy` 36 项、`Test-CompositionPolicy` 527 项、`Test-MonitorPolicy` 27 项、`Test-MoviePolicy` 21 项、`Test-NativeBurstProtocol` 23 项、`Test-PhoneToolsPolicy` 201 项、`VerifyCore` 19 项，合计 **854 项断言**）全部通过。
- [x] 真机仪器测试 `com.zbox.ai.CompatibilityCheck` **123 项检查全部通过**。
- [x] 真机 + Nikon Z 30（`V1.20`）全链路验证通过：五大页面 UI 走查、PTP/IP 自动配对、96 操作码日常连接、LiveView 实时取景、App 冷启动一键重连、相机断电重启自动掉线感知与一键重连、`0x90CD`/`0x90CC` 614 字节配方读写均正常。
- [x] 上传源码树与 APK 均已排查清理：不含任何 `.jks`/`.keystore`/`keystore.properties` 密钥材料、构建缓存、本机绝对路径或冗余声明文件。
