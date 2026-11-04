package com.zbox.ai.data

import com.zbox.ai.core.MonitorPolicy

/**
 * 常规设置。
 *
 * 全部落在 DataStore 里（一个 JSON 字符串），App 重启后保留。
 * 每一项都对应一个「用户真的会想改」的取舍，没有为了填满设置页而存在的项：
 *
 * - [galleryLimit] 每批显示的条目数；向下滚动继续浏览，可见缩略图逐张读取。
 * - [liveViewIntervalMs] 取景目标档位：流畅 30fps、标准 16fps、极省 2fps。
 *   轮询使用整帧预算（包含传输与解码）；实际帧率受机身和链路限制。
 * - [backupBeforeWrite] 写入前先读一遍机身原样模板。默认开：万一写坏了还能照着原样写回去。
 * - [verifyAfterWrite] 写完立刻回读校验名称。默认开：宁慢 300ms，不要静默失败。
 * - [autoSaveCapture] 拍完自动进手机相册。默认关：很多人只是先看一眼，不想污染相册。
 * - [logToFile] 持续落盘。默认开：出问题时日志是唯一的证据（手机只有一个 USB-C 口，
 *   接电脑就没法接相机，所以必须 App 自己留证据）。
 * - [hexInLog] 日志里是否带 Hex dump。关掉后文件能小一个数量级，代价是看不到字节级细节。
 * - [defaultTargetSlot] 每次进 App 的默认目标槽位。
 * - [keepScreenOn] 监看页常亮。拍延时/等光的时候屏幕自己灭掉很烦。
 */
data class AppSettings(
    val galleryLimit: Int = 18,
    val liveViewIntervalMs: Int = 33,
    val backupBeforeWrite: Boolean = true,
    val verifyAfterWrite: Boolean = true,
    val autoSaveCapture: Boolean = false,
    val logToFile: Boolean = true,
    val hexInLog: Boolean = true,
    val defaultTargetSlot: Int = 1,
    val keepScreenOn: Boolean = true,
    /** Use a soft warm-gray palette instead of the default dark palette. */
    val lightTheme: Boolean = false,
) {
    companion object {
        /** Each batch reveals lightweight entries; only visible thumbnails are fetched. */
        val GALLERY_LIMITS = listOf(18, 50, 100, 200)

        /** Target profile identifiers; legacy stored settings are normalized on load. */
        val LIVE_VIEW_INTERVALS = MonitorPolicy.intervals

        fun galleryLimitLabel(v: Int): String = when (v) {
            18 -> "18 张 / 批"
            50 -> "50 张 / 批"
            100 -> "100 张 / 批"
            200 -> "200 张 / 批"
            else -> "$v 张"
        }

        fun liveViewLabel(v: Int): String = MonitorPolicy.intervalLabel(v)
    }
}
