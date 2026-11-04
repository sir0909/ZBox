package com.zbox.ai.ptpip

/** Complete a scan before deciding; never choose the first of several cameras. */
internal object HotspotConnectionPolicy {
    enum class Action { NONE, AUTO_CONNECT, CHOOSE }
    data class Decision(val action: Action, val candidates: List<HotspotCameraEndpoint>)

    fun decide(found: List<HotspotCameraEndpoint>): Decision {
        val unique = found.distinctBy { it.cameraAddress }
        val action = when (unique.size) {
            0 -> Action.NONE
            1 -> Action.AUTO_CONNECT
            else -> Action.CHOOSE
        }
        return Decision(action, unique)
    }
}
