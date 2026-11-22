# 架构

Zbox 采用 Android 单模块 `:app` 工程结构（`com.zbox.ai`），按职责划分为八个子包：

- `ui`：Jetpack Compose 页面、可复用控件与交互状态管理（5-Tab 主导航：`监看` / `修图` / `逆向配方` / `相册` / `预设库`，以及 `设置` 与 `USB 调试台` 覆盖层）；`ui/components/` 存放 `CurveEditorCanvas`（曲线编辑器）与 `EditableSliderRow`（点击输入数值 + 双击重置通用滑杆）；`Theme.kt` 定义深色/浅色双主题语义令牌 `ZColors`。
- `editor`：Lightroom 风格非破坏性本地修图引擎，包含 `LrEditParams`（参数模型、局部蒙版 `LocalMask`、`MaskAlphaCodec` 与 `LrPresetCodec`）、`SmartMaskSegmenter`（基于 ML Kit Selfie Segmentation 与本地视觉分割的离线人物/天空/主体/背景选区）、`LrImageEngine`（基础影调、白平衡、8 色相 HSL、样条曲线、局部蒙版渲染、细节/暗角/颗粒与几何裁切管线）、`PhotoExporter`（全尺寸导出与 EXIF/MediaStore 写入）及 `PhotoEditorController`（编辑状态、防抖预览渲染、蒙版交互与对比切换）。
- `core`：不依赖页面的领域模型与纯策略逻辑（256-LUT 单调样条、614 字节优化校准编解码、监看帧预算、机身属性描述符校验、构图安全门禁 `CompositionPolicy` 与本地构图数据模型 `LocalCompositionModels`、更新安全策略）。
- `ai`：多供应商 AI 服务接入（DeepSeek、GLM、小米及 OpenAI 兼容接口）、图像影调配方逆向编排、`CompositionRepository`（AI Agent 构图建议）以及 `LocalCompositionEngine`（全场景离线实时本地智能构图引擎：集成 ML Kit 人像分割、ML Kit 离线物体检测、Achanta Lab 边界抑制显著性、地平线与水平仪及规则冲突消解）。
- `ptp`：USB OTG / PTP 相机指令、设备能力解析、槽位读写（`0x90CC`/`0x90CD`）与安全备份校验。
- `ptpip`：Wi-Fi 热点 PTP/IP 端点发现、双通道帧传输、客户端身份握手与自动配对注册。
- `data`：DataStore 设置持久化（含 `AppSettings`、AI 账户、优化校准预设、`SavedLrPreset` 及更新说明已读版本 `seen_changelog_version`）与 GitHub Release 最新版本查询。
- `util`：通用图像采样、直方图与编码辅助工具。
