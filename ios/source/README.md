# Zbox iOS 源码根目录说明

> **M1 状态(骨架已生成,待云端编译验证)**
> - 已写入:`project.yml`、`.github/workflows/ios-build.yml`、`Packages/ZboxCore`(PTP 容器、PTP/IP 编解码、TCP 帧切分、单元测试)、`ZboxApp`(SwiftUI 入口、Keychain 持久化 GUID)。
> - 未完成:Wi-Fi 发现(bridge100/en0)、PTP/IP 握手与会话、自动主机注册、健康探活。
> - 应用目标暂用 Swift 5.10 语言模式;`ZboxCore` 包为 Swift 6。待编译通过后再切换为 Swift 6。
> - 已合并进安卓仓库 `sir0909/ZBox` 的 `ios/` 目录（工程目录即 `ios/source`）。云端工作流位于仓库根 `.github/workflows/ios-build.yml`。发布 iOS 版请使用标签 `ios-v*`，且不要标记为 latest（安卓版更新检查读取 `/releases/latest`）。

本目录（`E:\StandaloneBridge\workspace\ZBox\github\repo\ios\source`）用于存放 Zbox iOS 版的全部工程配置、Swift 核心包、SwiftUI 应用代码与 GitHub Actions 自动构建工作流。

## 预定工程结构（自 M1 里程碑起按此结构生成代码）

```text
source/
├── project.yml                          # XcodeGen 工程配置文件
├── .github/
│   └── workflows/
│       └── ios-build.yml                # GitHub Actions 云端单测与免签 .ipa 打包流水线
├── Packages/
│   └── ZboxCore/                        # 平台无关纯 Swift 核心库（可独立 swift test）
│       ├── Package.swift
│       ├── Sources/ZboxCore/
│       │   ├── PTP/                     # PtpConstants, PtpContainer, NikonStaDirectRead, MoviePolicy, NativeBurstProtocol
│       │   ├── PTPIP/                   # PtpIpProtocol, NikonConnectionPolicy, HotspotConnectionPolicy
│       │   ├── Recipe/                  # PcPayload614, NcpParserAndBuilder, CubicSplineLut, BuiltInPresets, RecipeShareCode
│       │   ├── Camera/                  # CameraAperturePolicy, CameraIMenuPolicy, CameraStorage, MonitorPolicy, TouchFocusPolicy, PhoneToolsPolicy
│       │   ├── AI/                      # LlmProvider, OpenAiCompatibleModelApi, AiColorGradingRepository, CompositionRepository, CompositionPolicy
│       │   ├── Store/                   # AppSettings
│       │   ├── Update/                  # ReleaseUpdatePolicy
│       │   └── Diagnostics/             # HexLog
│       └── Tests/ZboxCoreTests/         # 对应安卓 CompatibilityCheck (123项) 与策略单测集
└── ZboxApp/                             # iOS 应用主体（SwiftUI + Apple 系统框架适配）
    ├── ZboxIOSApp.swift                 # 应用入口
    ├── OrientationController.swift      # 竖屏默认锁与全屏取景动态横屏控制器
    ├── Transport/                       # HotspotCameraDiscovery (bridge100/en0), IOSCameraNetworkBinder, PtpIpTransport, UsbImageCaptureTransport
    ├── Driver/                          # NikonPtpDriver, NativeBurstController, SessionKeepAlive
    ├── Pipeline/                        # ImagePipeline (CoreImage/vImage), ImageLoader, ImageFeatureExtractor
    ├── Store/                           # SettingsStore (UserDefaults + Keychain), GitHubReleaseUpdater
    ├── ViewModel/                       # MainViewModel, PhoneWorkbench, PoseRepository
    ├── UI/                              # Theme, AppRootView, Monitor, AiLab, Gallery, Presets, Poses, Settings
    └── Resources/                       # Assets.xcassets, pose-catalog.json
```

> **边界原则**：本目录内的代码严格独立于外层 `ZBox/source/project-files/Zbox-1.0.0-test` 安卓工程；任何 iOS 改动均只在本目录（`ios/source`）内进行；iOS 文档、日志与发布产物位于 `ZBox/IOS/`。
