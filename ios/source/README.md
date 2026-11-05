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


## M2～M4 云端开发增量（尚未真机验证）

- **M2**：新增显式启动/停止、JPEG 边界解析和串行轮询的取景页面，返回页面、App 不活跃或断开时尽力停止；iOS 后台可能立即挂起，无法保证停止命令送达。竖横屏可自适应，提供系统横屏全屏请求；系统可能拒绝，暂不保证方向锁；可在停止取景后手动读取 ISO、光圈、曝光程序、曝光补偿、白平衡和 Nikon 快门值（只读，异常值不猜测）；增加仅 STA 模式的 Nikon 0x9434 相册列表与 0x9421/0x9431 分块下载到临时文件，再由用户分享保存；只展示最多 500 项，每页串行加载最多 12 项缩略图，单页缓存有上限；可在已读取的最多 500 项内按 JPEG、RAW、视频筛选并按时间排序，未知时间始终排在最后；可从前 256 KiB 或 TIFF 明确指向的受限区间尝试提取内嵌 JPEG（支持部分 Nikon MakerNote / MPF 的有界区间，不保证每张文件均可预览），文件名为句柄占位名，32 GiB 上限，显示下载接收数据的 SHA-256 以供后续手动比对（不是磁盘重读，也没有相机源端哈希，因此不证明内容一致），新增可取消的本地文件重读校验，比较落盘重读和接收摘要，但没有源端校验和仍无法证明与相机原文件一致；取景屏竖横布局和曝光/i 菜单入口改善。未验证真机兼容。增加用户二次确认的单次拍摄到机身卡：先停止取景、检查 DeviceReady，再发 0x9207；命令接受不等于核实落卡，超时不自动重试。功能未经真机验证，曝光设置新增实验性的描述符范围与当前值校验、用户确认、单次写入和读回核对；只允许相机声明的已识别候选值，断线或超时时结果未知，不自动重试。增加实验性 i 菜单标量枚举：机身允许的未知值以原始代码显示，PTP 字符串使用严格 UTF-16 与枚举校验，不识别的格式不允许写入；完整相册体验尚未实现。操作会改变相机取景状态，不会自动启动；整个功能尚需 CI 和真机验证。
- **M3**：新增 614 字节优化校准载荷的只读名称、15 位 LUT 和控制点解析；不生成或写入相机配方，AI、预设库和导入导出尚未实现。
- **M4**：新增 USB 会话纯状态门禁与旧回调隔离测试；尚无 ImageCaptureCore 设备发现、命令发送或真实 USB 连接实现。此状态机是待真机验证的设计假设。
- 这些增量以对应提交的 CI 结果为准；没有 iPhone 与 Z 30 的实际联调结果。

- 相机操作采用统一可单测的准入策略：同一时间仅允许一种普通操作，注册准备阶段仅允许确认，取景稳定运行时仅允许单次快门（先停取景）；这不是完整 App 生命周期或真机互斥验证。

## M2 模拟器与窗口方向增量

- 取景横屏使用当前窗口的方向掩码与场景旋转请求；退出取景或跳转设置时释放，失败时回滚。系统、iPad 多任务和设备策略仍可能拒绝或覆盖，不能保证设备上方向锁成功。
- CI 在 iOS 模拟器运行 App-hosted 假相机测试：取景启动期间失活、相册列表/原文件下载期间失活、单次快门前取消、曝光写入前取消、后台拒绝新操作、断线防旧结果等。此为模拟器模拟传输，既不是全覆盖，也不是实际相机验证。
- 相册依然是有限范围的 Nikon STA 直读；原文件使用占位名，下载后可计算摘要并选择本地重读校验，但缺少相机源端摘要，不保证与机身源文件一致。

## M2 取景辅助与单次 AF 增量

- 取景画面支持本地九宫格和 16:9 画幅框，按实际图片的 aspect-fit 矩形绘制，不画在黑边；开关位于连接与设置页，默认关闭，只在手机屏幕显示，不改变相机或照片。
- 增加实验性单次 AF（0x90C1）：仅设备信息声明支持且取景稳定时手动触发，一次命令不重试；排队后在持事务锁时再次检查场景和会话状态，后台或停止取景前可取消未发送命令。OK 只表示机身接受，不证明已合焦；功能未在 Z 30 真机验证。
- 录像、触点对焦、斑马纹、峰值、直方图、完整 UI v2 与真机验证仍未完成。
