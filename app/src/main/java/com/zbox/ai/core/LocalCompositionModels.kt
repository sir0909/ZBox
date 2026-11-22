package com.zbox.ai.core

enum class CompositionMode(val label: String) {
    LOCAL("本地智能构图"),
    AGENT("AI Agent 构图"),
}

enum class LocalCompStyle(val label: String) {
    AUTO("自动构图"),
    PORTRAIT("人像居中"),
    MINIMAL("极简留白"),
    LANDSCAPE("风景三分"),
}

/**
 * 离线实时本地智能构图结果（类似 Doka 相机实时机位引导）。
 * 所有坐标均为 [0f, 1f] 归一化到当前取景帧宽高。
 */
data class LocalCompositionGuide(
    val subjectBox: CompositionBox,
    val subjectCenterX: Float,
    val subjectCenterY: Float,
    val targetX: Float,
    val targetY: Float,
    val horizonY: Float? = null,
    val tiltDeg: Float,
    val score: Int,
    val aligned: Boolean,
    val hasPerson: Boolean,
    val subjectLabel: String = "主体",
    val sceneTag: String,
    val moveHint: String,
    val detailHint: String,
)
