# 构建与测试

需要 JDK 17、Android SDK 34 和与 Android Gradle Plugin 8.5.2 兼容的系统 Gradle。此源码包没有 Gradle Wrapper。

```powershell
# 请先确保环境变量 JAVA_HOME（JDK 17）与 ANDROID_SDK_ROOT（Android SDK 34）已指向本机工具链
gradle :app:assembleDebug
gradle :app:assembleRelease
gradle --no-daemon '-PtestRunner=com.zbox.ai.CompatibilityCheck' :app:assembleDebugAndroidTest
```

注意：
- 打包正式签名的 `assembleRelease` 前，请复制根目录 `keystore.properties.example` 为 `keystore.properties` 并填入你自己的签名密钥配置（`keystore/` 与 `keystore.properties` 已加入 `.gitignore`，切勿提交入库）。
- 在 PowerShell 中传递含 `.` 的 `-PtestRunner=...` 参数时请加单引号（如 `'-PtestRunner=com.zbox.ai.CompatibilityCheck'`）。

## 离线策略校验与真机仪器测试

1. **离线核心与协议测试（无需连接手机/相机）**：
   编译 `:app:assembleRelease` 或 `:app:assembleDebug` 后，可直接执行 `tools/` 目录下的校验脚本：
   ```powershell
   powershell -ExecutionPolicy Bypass -File tools/Test-ReleaseUpdatePolicy.ps1
   powershell -ExecutionPolicy Bypass -File tools/Test-CompositionPolicy.ps1
   powershell -ExecutionPolicy Bypass -File tools/Test-MonitorPolicy.ps1
   powershell -ExecutionPolicy Bypass -File tools/Test-MoviePolicy.ps1
   powershell -ExecutionPolicy Bypass -File tools/Test-NativeBurstProtocol.ps1
   powershell -ExecutionPolicy Bypass -File tools/Test-PhoneToolsPolicy.ps1
   ```
2. **Android 真机仪器测试**：
   `CompatibilityCheck` 覆盖 614 字节载荷编解码、UI 状态不变量、客户端身份归一化与持久化以及 `InitFail [reason=1]` 分类断言（共 123 项检查）：
   ```powershell
   gradle --no-daemon '-PtestRunner=com.zbox.ai.CompatibilityCheck' :app:assembleDebugAndroidTest
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   adb shell am instrument -w -r com.zbox.ai.test/com.zbox.ai.CompatibilityCheck
   ```

PTP/IP 客户端身份机制与首次配对流程详见 [PTP/IP 客户端身份与配对连接说明](PTPIP_TROUBLESHOOTING.md)，完整发版验收结论见上级 [`RELEASE_READINESS_1.0.0.md`](../RELEASE_READINESS_1.0.0.md)。
