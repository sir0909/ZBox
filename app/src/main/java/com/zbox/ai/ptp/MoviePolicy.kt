package com.zbox.ai.ptp

/** Nikon movie prerequisites; no I/O or camera side effects. */
object MoviePolicy {
    fun permitsU8(bytes: ByteArray, code: Int, value: Int): Boolean {
        require(value in 0..255)
        require(bytes.size >= 8 && NativeBurstProtocol.u16(bytes, 0) == code && NativeBurstProtocol.u16(bytes, 2) == 2) { "无效 UINT8 属性描述" }
        if (bytes[4].toInt() != 1) return false
        fun u8(i: Int): Int { require(i < bytes.size); return bytes[i].toInt() and 255 }
        return when (u8(7)) {
            0 -> true
            1 -> { val lo=u8(8); val hi=u8(9); val step=u8(10); require(lo <= hi && step > 0); value in lo..hi && (value-lo)%step == 0 }
            2 -> { val n=NativeBurstProtocol.u16(bytes,8); require(n in 1..256 && 10+n <= bytes.size); (0 until n).any { u8(10+it)==value } }
            else -> error("未知属性枚举格式")
        }
    }
    fun prohibitReason(mask: Long): String {
        if (mask == 0L) return ""
        val reasons = listOf(0 to "未插存储卡", 1 to "存储卡错误", 2 to "存储卡未就绪", 3 to "存储卡已满",
            9 to "机身正在保存文件", 10 to "机身已经在录像", 11 to "存储卡写保护", 12 to "请退出放大取景",
            13 to "请在机身切换到录像模式", 14 to "机身尚未进入录像应用模式")
            .filter { mask and (1L shl it.first) != 0L }.joinToString("；") { it.second }
        return (if (reasons.isBlank()) "机身当前不允许录像" else reasons) + "（0x%08X）".format(mask)
    }
}
