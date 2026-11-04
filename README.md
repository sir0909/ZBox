# Zbox

**Zbox** 是一款面向尼康微单（以 Nikon Z 30 / EXPEED 6 为核心实测机型）的 Android 影调与监看伴侣应用，支持 **USB OTG（PTP）** 与 **Wi-Fi 热点（PTP/IP）** 双模连接、竖屏/横屏专业取景监看、AI 影调逆向生成以及 **614 字节尼康优化校准（Picture Control）直写**。

- **应用名称**：`Zbox`
- **Android 包名**：`com.zbox.ai`
- **当前正式版本**：`v1.0.0`（`versionCode = 1`）
- **系统要求**：Android 8.0+（`minSdk 26`，`targetSdk 34`）
- **安装包下载**：[GitHub Releases (`sir0909/ZBox`)](https://github.com/sir0909/ZBox/releases/latest)

---

## 核心功能

### 1. 双模连接与专业取景监看
- **有线 / 无线无缝切换**：支持 Type-C OTG 有线直连与手机热点 Wi-Fi（PTP/IP STA，`TCP 15740`）连接，后台实时监测连接状态并在相机断电或断网时自动干净回退。
- **竖屏主控 + 横屏 3:2 等比全屏**：默认采用单手友好的竖屏监看主界面；点击全屏入口进入独立横屏取景（`ContentScale.Fit` 保留原始 3:2 画幅与左右控制黑边，快门居中于右侧握持区）。
- **手机端实时取景辅助**：内置九宫格构图线、高光过曝斑马纹、峰值对焦边缘提示、实时 256 阶亮度直方图、多画幅遮罩（16:9 / 1:1 / 4:5）及触控自动对焦、快门、曝光参数同步与录像控制。

### 2. AI 影调实验室与机身槽位直写
- **参考图逆向生成配方**：选择本地参考图（支持按住实时对比原图），提取压缩视觉特征与缩略图，调用用户自配的 AI 模型逆向生成尼康优化校准参数与色调曲线。
- **256 点单调样条色调曲线**：基于 Fritsch–Carlson 单调三次样条算法构建无过冲 `256-LUT` 与 18 点机身曲线段，支持手动微调锐化、清晰度、对比度、亮度、饱和度与色相。
- **C-1 ～ C-9 机身槽位读写**：通过 Nikon PTP 扩展指令 `0x90CC` / `0x90CD` 直接扫描与写入 614 字节优化校准载荷，默认启用**写入前机身原槽备份**与**写入后回读校验**双重保护。

### 3. 预设库、快传码与人像引导参考
- **10 大内置影调分类**：内置人像、电影、胶片、日系、黑白、风光、街拍、暗调、美食、夜景分类预设，支持本地 `.ncp` 预设导入管理与 `C-1 ～ C-9` 批量刷入。
- **多供应商自由切换**：支持分别配置并切换 DeepSeek、GLM、小米及任意 OpenAI 兼容接口（API Key 仅保存在本机）。
- **配方快传码与摆姿库**：支持一键生成、复制、分享与解析配方快传码；内置离线优先的人像姿势与拍摄引导话术库。

### 4. 机身相册高速浏览与回传
- 支持按批次加载机身存储卡照片缩略图、双图同屏对比、4 MiB 自适应分块流式原图下载（避免大体积 RAW/NEF 占用内存峰值）及手机端直方图检查。

---

## 快速上手与首次连接说明

1. **下载安装**：前往 [Releases](https://github.com/sir0909/ZBox/releases/latest) 下载 `Zbox-1.0.0.apk` 并安装。
2. **USB OTG 连接**：使用支持数据传输的 Type-C 线连接手机与相机，开机授权 USB 权限即可直接监看与刷入配方。
3. **Wi-Fi（PTP/IP）首次配对**：
   - 将相机连入手机热点；
   - **首次连接**时，在相机菜单依次进入 **「网络菜单 → 连接到计算机 → 配对」**；
   - 点击 Zbox 顶栏的 **「连接」**，应用将在约 9 秒内自动读取 PIN 并完成机身白名单注册，随后自动进入完整会话；
   - **日常连接**：完成首次配对后，后续无论重启 App 还是开关机重启相机，只需点击一次「连接」即可直连，无需重复配对。
   - 如需在同机迁移已有配对身份或重置本机随机 GUID，可在 **「设置 → PTP/IP 客户端身份」** 中直接查看与配置。

---

## 隐私与安全设计

- **零遥测、无账号**：不收集设备标识符、相册内容或使用行为数据。
- **参考图本地优先**：参考图与自定义姿势图片均保存在手机本地；仅在用户主动点击 AI 分析时，向用户自己配置的模型接口发送压缩特征与缩略图。
- **受控版本检查**：启动时最多执行一次轻量级 GitHub Release 最新版本查询（仅限 `https://github.com/sir0909/ZBox/releases/download/.../*.apk` 白名单下载地址，取景与拍摄忙时绝不弹窗打断）。

---

## 从源码构建

环境要求：**JDK 17**、**Gradle 8.7**、**Android SDK 34**（`compileSdk 34` / `Build Tools 34`）。

```powershell
# 1. 编译调试包
gradle :app:assembleDebug

# 2. 编译正式签名包（需先参考 keystore.properties.example 在本地配置 keystore.properties，切勿提交密钥）
gradle :app:assembleRelease

# 3. 运行离线核心策略与协议校验套件
powershell -ExecutionPolicy Bypass -File tools/Test-ReleaseUpdatePolicy.ps1
powershell -ExecutionPolicy Bypass -File tools/Test-CompositionPolicy.ps1
powershell -ExecutionPolicy Bypass -File tools/Test-MonitorPolicy.ps1
powershell -ExecutionPolicy Bypass -File tools/Test-MoviePolicy.ps1
powershell -ExecutionPolicy Bypass -File tools/Test-NativeBurstProtocol.ps1
powershell -ExecutionPolicy Bypass -File tools/Test-PhoneToolsPolicy.ps1
```

---

## 文档索引

- [版本更新日志 (`CHANGELOG.md`)](CHANGELOG.md)
- [1.0.0 正式版发布说明 (`RELEASE_1.0.0.md`)](RELEASE_1.0.0.md)
- [1.0.0 发版验收清单 (`RELEASE_READINESS_1.0.0.md`)](RELEASE_READINESS_1.0.0.md)
- [开发与模块架构 (`DEVELOPMENT.md`)](DEVELOPMENT.md)
- [贡献与维护指南 (`CONTRIBUTING.md`)](CONTRIBUTING.md)
- [详细技术文档 (`docs/`)](docs/README.md)（含架构边界、功能索引、构建测试、UI v2 规范与 PTP/IP 配对指南）
