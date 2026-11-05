# Zbox iOS 源码根目录说明

> **M1 状态（网络与会话层已写入，待 macOS 云端编译与真机验证）**
> - 已写入：`project.yml`；仓库根的 `.github/workflows/ios-build.yml`；`Packages/ZboxCore`（PTP 容器与 PTP/IP 编解码、会话状态机、Nikon 连接策略、热点地址规划、DeviceInfo 解析，共 36 项单元测试）；`ZboxApp`（SwiftUI 界面、Keychain GUID、Network.framework 字节通道、热点扫描、日志控制台）。
> - `ZboxCore` 的 36 项单元测试已在 Linux Swift 工具链上通过；`ZboxApp` 与 macOS/Xcode 构建尚未在 CI 上验证。
> - 未完成：15 秒保活、电池与 C-1…C-9 设置槽读取、Bonjour（`_ptp._tcp`）发现、真机验收（iPhone 热点 + Nikon Z 30）。
> - 应用目标暂用 Swift 5.10 语言模式；`ZboxCore` 包为 Swift 6 工具链。待编译通过后再切换应用目标为 Swift 6。
> - 本目录已合并进安卓仓库 `sir0909/ZBox` 的 `ios/` 目录（工程目录即 `ios/source`）。云端工作流位于仓库根 `.github/workflows/ios-build.yml`。发布 iOS 版请使用标签 `ios-v*`，且不要标记为 latest（安卓版更新检查读取 `/releases/latest`）。

本目录（`E:\StandaloneBridge\workspace\ZBox\github\repo\ios\source`）用于存放 Zbox iOS 版的全部工程配置、Swift 核心包、SwiftUI 应用代码与 GitHub Actions 自动构建工作流。

## 当前目录结构

```text
source/
├── project.yml                              # XcodeGen 工程配置文件
├── Packages/
│   └── ZboxCore/                            # 平台无关纯 Swift 核心库（可独立 swift test）
│       ├── Package.swift
│       ├── Sources/ZboxCore/
│       │   ├── PTP/                         # PtpConstants, PtpContainer
│       │   ├── PTPIP/                       # PtpIpPacket（编解码与帧切分）, PtpIpByteChannel,
│       │   │                                # PtpIpSession（握手 / OpenSession / 主机注册）,
│       │   │                                # PtpIpConnector（InitFail reason=1 时兼容名称重试一次）,
│       │   │                                # PtpIpOperation, PtpIpSessionError, PtpDeviceInfo
│       │   ├── Nikon/                       # NikonConnectionPolicy（会话号、8.5 秒等待、配对码解析）
│       │   ├── Hotspot/                     # HotspotConnectionPolicy（0 / 1 / 多台决策）, HotspotAddressPlanner
│       │   └── Support/                     # DataLittleEndian
│       └── Tests/ZboxCoreTests/             # 协议、会话（假相机）、Nikon 与热点策略测试
└── ZboxApp/                                 # iOS 应用主体（SwiftUI + Apple 系统框架适配）
    ├── ZboxIOSApp.swift                     # 应用入口
    ├── Network/                             # NetworkPtpIpChannel（TCP 通道，可绑定热点源地址）, HotspotCameraScanner（子网探测）
    ├── Session/                             # CameraConnectionModel（连接流程状态）
    ├── Store/                               # ClientIdentityStore（Keychain GUID，支持保存与重置）
    ├── Log/                                 # DiagnosticLog, LogConsoleView（日志控制台与导出）
    └── UI/                                  # AppRootView
```

## 已知限制

- 热点扫描只做 TCP 15740 端口探测，发现结果不代表已通过 PTP 握手。
- App 层（`Network/`、`Session/`、`UI/`）无法在 Linux 沙盒编译，必须以 macOS 云端构建结果为准。

> **边界原则**：本目录内的代码严格独立于外层 `ZBox/source/project-files/Zbox-1.0.0-test` 安卓工程；任何 iOS 改动均只在本目录（`ios/source`）内进行；iOS 文档、日志与发布产物位于 `ZBox/IOS/`。
