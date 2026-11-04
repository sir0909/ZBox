# 架构

Zbox 采用 Android 单模块 `:app` 工程结构（`com.zbox.ai`），按职责划分为七个子包：

- `ui`：Jetpack Compose 页面、可复用控件与交互状态管理（竖屏监看、横屏 Fit 全屏、AI 调色、相册、预设库、设置）。
- `core`：不依赖页面的领域模型与纯策略逻辑（256-LUT 单调样条、614 字节优化校准编解码、监看帧预算、机身属性描述符校验、更新安全策略）。
- `ai`：多供应商 AI 服务接入（DeepSeek、GLM、小米及 OpenAI 兼容接口）与图像影调配方逆向编排。
- `ptp`：USB OTG / PTP 相机指令、设备能力解析、槽位读写（`0x90CC`/`0x90CD`）与安全备份校验。
- `ptpip`：Wi-Fi 热点 PTP/IP 端点发现、双通道帧传输、客户端身份握手与自动配对注册。
- `data`：DataStore 设置持久化、本地偏好与 GitHub Release 最新版本查询。
- `util`：通用图像采样、直方图与编码辅助工具。
