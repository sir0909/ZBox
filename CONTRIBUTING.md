# 贡献与维护

1. 修改前检查 `app/build.gradle.kts` 中的包名（`com.zbox.ai`）、`versionName` 与 `versionCode`。
2. UI 改动须遵循 `docs/UI_DESIGN.md` 与 `docs/Zbox_UI_v2.html` 确立的设计层级与控件保留约束。
3. 将协议/传输、AI 供应商、持久化、领域逻辑和 Compose 表现放在各自模块边界内。
4. 变更后运行 `tools/` 下的离线策略测试与 `androidTest` 仪器测试，记录真实运行结果。
5. 不提交构建缓存、本机密钥或设备临时数据。发布前核对版本号、APK、源码归档及 SHA-256。

项目没有 Gradle Wrapper；构建命令和工具链要求见 `docs/BUILD_TEST.md`。
