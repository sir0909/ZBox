# Changelog

## [1.0.1] - 2026-11-22

### Added
- **全场景离线「本地智能构图」与全屏构图支持**：
  - 新增 `core/LocalCompositionModels.kt`、`ai/LocalCompositionEngine.kt` 与 `ui/CompositionPanel.kt`（含 `LocalCompositionHudOverlay`），集成本地 **ML Kit 人像分割（眼线锚点）** + **ML Kit 离线物体检测（`object-detection:17.0.2`，支持美食/植物/静物/服饰/建筑）** + **Achanta Lab 边界背景抑制显著性 20×14 聚类** + **地平线三分与实时 HUD 水平仪** + **多规则优先级冲突消解**，提供「自动构图 / 人像居中 / 极简留白 / 风景三分」四种实时引导模式（0 流量、0 Token）。
  - 监看页与全屏横屏取景界面均新增「构图」按钮，点击立即生效；构图模式统一移至 **「设置 → 构图模式」** 中切换并持久化（支持「本地智能构图」与「AI Agent 构图」）。
- **「修图」局部蒙版与离线 AI 智能选区**：
  - 新增 `editor/SmartMaskSegmenter.kt`，支持离线 AI 选区（**人物 / 天空 / 主体 / 背景**）与手动蒙版（**径向 / 线性 / 画笔 / 亮度**），每层独立局部调色（曝光/对比度/高光/阴影/色温/饱和度/锐化），默认隐藏红膜、仅拖动蒙版参数或按住眼睛预览时临时显影。
- **新增「修图」工作台（Lightroom 风格非破坏性本地调色引擎）**：
  - 新增 `editor` 包（`LrEditParams.kt`、`LrImageEngine.kt`、`PhotoExporter.kt`、`PhotoEditorController.kt`）及 `ui/PhotoEditorScreen.kt`，底栏升级为 5-Tab 结构（`监看` / `修图` / `逆向配方` / `相册` / `预设库`）。
  - 支持基础（曝光、对比度、高光、阴影、白色阶、黑色阶）、色彩（色温、色调、自然饱和度、饱和度）、8 色相 HSL（红/橙/黄/绿/青/蓝/紫/品红的色相/饱和度/明亮度）、单调样条曲线、细节效果（清晰度、锐化、暗角、颗粒）及几何变换（90° 旋转、水平翻转、原图/自由/1:1/4:5/3:2/16:9 比例裁切）。
  - 支持长按原图对比、分屏对比、LR 预设保存/套用（`SavedLrPreset`）及高画质导出至手机相册 `Pictures/Zbox`（保留 EXIF 与 MediaStore 写入）。
  - 打通全链路联动：支持从相机「相册」或「逆向配方」直接将照片与当前配方送入「修图」精修并保存。
- **滑杆精准数值编辑与双击归零**：新增 `ui/components/EditableSliderRow.kt`，统一接入「修图」与「逆向配方」所有调色滑杆，支持点击右侧数字弹窗键入精确值（带 `±` 正负号快捷切换与范围校验）以及双击滑杆快速复位默认值。
- **首次打开版本更新说明弹窗**：在 `data/SettingsStore.kt` 新增 `seen_changelog_version` 持久化标记（当前 `1.0.1-r1`），由 `MainViewModel` 与 `AppRoot` 在升级或首次安装后自动弹出「ZBOX 更新说明」弹窗，并在「设置 → 关于 → 查看本版更新说明」提供随时重看入口。

### Changed
- **实时取景目标帧率升级**：`MonitorPolicy` 三档取景帧率调整为 **流畅 · 60fps**（`16ms`）、**标准 · 30fps**（`33ms`）、**省电 · 2fps**（`500ms`），并平滑迁移旧版存储值。
- **版本号升级**：`versionCode` 升级至 `2`，`versionName` 升级至 `1.0.1`。
- **「AI 调色 / AI 炼丹炉」全面更名为「逆向配方」**：统一更新底栏 Tab（`AppRoot.kt`）、工作台眉题（`AiLabScreen.kt`）、预设库空态提示（`PresetLibraryScreen.kt`）、修图页套用按钮（`PhotoEditorScreen.kt`）及 `MainViewModel.useLastCaptureAsReference`（同步修复误跳转到 Tab 0 的问题，现正确跳转到 Tab 2）。
- **双主题视觉体系升级（浅色 Material 3 Mono 中对比度 + 深色还原最初尼康暗黑炭灰/尼康黄）**：重构 `ui/Theme.kt` 中的 `zcColorScheme` 与 `ZColors`：浅色模式采用 Material 3 Mono（中对比度）角色（`primary #4A4459`、`primaryContainer #E7DFF8`、`secondaryContainer #E4E1EA`、`tertiaryContainer #F0DEE2`、`surface #FAF8FE`～`surfaceContainerHighest #E3E2E7`），按钮/选中胶囊统一为克制的 `#4A4459`；深色模式完整还原最初 `v1.0.0` 尼康暗黑炭灰（`Bg #0A0B0C`、`Surface #141517`、`SurfaceHigh #1C1E20`、`SurfaceHigher #26282B`、`Outline #303236`、`TextPrimary #F1F2F3`）与尼康黄（`#FFE600`）主强调色。
- **工程清理**：移除未使用的 C++/Rust JNI 目录（`app/src/main/rust`、`app/src/main/cpp`、`app/.cxx`）与废弃的 `editor/calib`、`editor/raw` 模块。

### Fixed
- **修复监看页顶栏未连接提示条底色异常**：在 `LiveMonitorScreen.kt` 将 `ConnectBar` 卡片容器背景与边框还原为中性深色卡片表面（仅保留连接按钮高亮）。
- **修复「预设库 → 拍照姿势」切换图片自动弹回顶部**：在 `ui/PoseReferenceViews.kt` 固定 `290.dp` 预览图容器高度并在加载中显示 `CircularProgressIndicator`，将「上一张 / 随机姿势 / 下一张 / 收藏姿势」按钮行置于固定容器下方，并将标签区固定为 2 行高度（`minLines = 2, maxLines = 2`），彻底消除切换姿势时布局高度塌缩导致外层滚动位置弹回顶部的问题。
- **修复「设置 / USB 调试台」系统返回手势直接退出应用**：在 `ui/AppRoot.kt` 为 `settingsOpen` 与 `consoleOpen` 覆盖层注册 `BackHandler`，侧滑返回或按手机返回键时正常退回上一页面。

## [1.0.0] - 2026-11-04

### Added
- **Zbox 正式版首发（`com.zbox.ai`，`versionCode = 1`）**：确立暗黑炭灰 + 尼康黄强调色的 UI v2 全栈界面体系，涵盖「监看」「AI 调色」「相册」「预设库」「设置」五大核心模块。
- **双模相机通信引擎**：支持 USB OTG（PTP）与 Wi-Fi 热点（PTP/IP STA 模式，`TCP 15740`）连接，内置会话心跳监测与相机断电自动掉线回退。
- **竖屏监看与独立横屏 3:2 全屏取景**：支持 30fps / 16fps / 2fps 三档帧预算调度，提供九宫格、高光斑马纹、峰值对焦、实时亮度直方图、画幅遮罩、触控对焦、曝光同步、快门与录像控制。
- **AI 影调实验室与 614 字节载荷引擎**：内置 Fritsch–Carlson 单调三次样条 256-LUT 曲线构建器与 `0x90CC` / `0x90CD` 机身 `C-1 ～ C-9` 槽位读写能力，默认开启写前原槽备份与写后回读校验。
- **多供应商预设库与快传体系**：保留全部 10 类内置影调预设，支持 DeepSeek、GLM、小米及 OpenAI 兼容供应商独立配置，提供配方快传码同排生成/复制/分享与离线人像摆姿参考库。
- **PTP/IP 客户端身份管理与内联恢复**：在监看页顶栏支持 `InitFail [reason=1]` 内联引导与一键重配，在设置页提供客户端名称与 32 位十六进制 GUID（兼容标准带连字符 UUID）查看、自定义迁移与恢复默认能力。
- **受控版本检查**：对接 `sir0909/ZBox` GitHub Releases，支持冷启动单次非打断检测与设置页手动检查更新。

### Fixed & Verified
- 修复跨包或重置 Wi-Fi 配置后 `ptpip_auto_pairing` 偏好锁导致的自动配对重连阻塞问题，增加 `"Zbox"` / `"Z-Cloud AI"` 握手名称自动兼容回退。
- 完成真机 123 项 `CompatibilityCheck` 仪器测试、7 套共 854 项离线协议/策略断言，以及 Nikon Z 30 真机配对、96 操作码日常会话、实时取景、App 冷启动一键重连、相机断电重启一键重连与 614 字节配方读写闭环验证。
