# Zbox iOS 源码根目录说明

> **M1 状态（云端编译已通过，待真机验证）**
> - 已写入：`project.yml`；仓库根的 `.github/workflows/ios-build.yml`；`Packages/ZboxCore`（PTP 容器与 PTP/IP 编解码、会话状态机、Nikon 连接策略、热点地址规划、DeviceInfo 解析，基线 36 项单元测试及后续新增的电池和超时测试）；`ZboxApp`（SwiftUI 界面、Keychain GUID、Network.framework 字节通道、热点扫描、日志控制台）。
> - 提交 `902ed25` 的 46 项 `ZboxCore` 测试、macOS/Xcode 编译与未签名 IPA 打包已在 [Actions 41998345957](https://github.com/sir0909/ZBox/actions/runs/41998345957) 成功。
> - 已接入只读电池读取、15 秒空闲连接监测、C-1…C-9 只读读取和 Bonjour 候选服务发现。核心层假相机测试和 iOS 云端编译通过；Bonjour、连接监测、槽位和电池等功能仍需 iPhone + Nikon Z 30 真机验证。拒绝访问不等于确认空槽；Bonjour 未找到服务时使用热点扫描或手动 IP。
> - 应用目标暂用 Swift 5 语言模式（Xcode 设置 `SWIFT_VERSION=5.0`）；`ZboxCore` 包为 Swift 6 工具链。后续可在严格并发检查与回归通过后评估切换应用目标为 Swift 6。
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
│       │   ├── Nikon/                       # NikonConnectionPolicy、PictureControlSlot（只读槽位解析）
│       │   ├── Hotspot/                     # HotspotConnectionPolicy（0 / 1 / 多台决策）, HotspotAddressPlanner
│       │   └── Support/                     # DataLittleEndian
│       └── Tests/ZboxCoreTests/             # 协议、会话（假相机）、Nikon 与热点策略测试
└── ZboxApp/                                 # iOS 应用主体（SwiftUI + Apple 系统框架适配）
    ├── ZboxIOSApp.swift                     # 应用入口
    ├── Network/                             # TCP 通道、热点扫描与 Bonjour 候选服务浏览
    ├── Session/                             # CameraConnectionModel（连接流程状态）
    ├── Store/                               # ClientIdentityStore（Keychain GUID，支持保存与重置）
    ├── Log/                                 # DiagnosticLog, LogConsoleView（日志控制台与导出）
    └── UI/                                  # AppRootView
```

## 已知限制

- 热点扫描只做 TCP 15740 端口探测，发现结果不代表已通过 PTP 握手。
- 15 秒空闲监测只在应用运行时工作；iOS 后台挂起时不保证定期执行。读取过程中不会并行发心跳。`0x200F` 可能为空槽也可能为权限问题，不自动视为已确认空槽。
- Bonjour 是否可发现 Z 30、热点扫描、会话绑定、iPhone 真机连接及各项响应均未验证；发现候选不代表已经识别为 Nikon 相机。
- CI 编译仍有 iPad 全方向/全屏相关警告；后续需在真机上决定界面方向与多任务支持。App 层必须以对应提交的 macOS 云端构建结果为准。

> **边界原则**：本目录内的代码严格独立于外层 `ZBox/source/project-files/Zbox-1.0.0-test` 安卓工程；任何 iOS 改动均只在本目录（`ios/source`）内进行；iOS 文档、日志与发布产物位于 `ZBox/IOS/`。
