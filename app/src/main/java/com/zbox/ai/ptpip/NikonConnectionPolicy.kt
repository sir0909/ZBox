package com.zbox.ai.ptpip

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Capability and framing policy, not a claim that any camera has passed hardware tests. */
object NikonConnectionPolicy {
    const val HOST_SETTLE_MS=8500L
    const val CONNECT_SETTLE_MS=350L
    const val MAX_PACKET_BYTES=64*1024*1024
    fun packetLength(length:Long):Int { require(length in 8L..MAX_PACKET_BYTES.toLong()) { "PTP/IP packet length out of bounds: $length" };return length.toInt() }
    fun isCellularOrTunnel(name:String):Boolean = Regex("^(rmnet|ccmni|pdp|tun|ppp|wg|ipsec)",RegexOption.IGNORE_CASE).containsMatchIn(name)
    fun sessionId(sta:Boolean, random:Int):Long = if(sta) (random.toLong() and 0x7fffffffL).coerceAtLeast(2L) else 1L
    fun remainingSettle(elapsed:Long):Long=(HOST_SETTLE_MS-elapsed.coerceAtLeast(0L)).coerceAtLeast(0L)
    /** Empty success payload is host registration, not a zero-length/invalid PIN. */
    fun pairingCode(data:ByteArray):String {
        if(data.isEmpty())return ""
        require(data.size>=4){"配对准备数据不足"}
        val count=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
        require(count in 4..8 && data.size>=4+count && data.size<=12+count && data.drop(4+count).all{it==0.toByte()}){"无法识别配对数据长度；未确认注册"}
        return buildString {for(i in 0 until count){val n=data[4+i].toInt() and 255
            append(when(n){in 0..9 -> ('0'.code+n).toChar();in 48..57 -> n.toChar();else -> error("配对码含非数字；未确认注册")})}}
    }
    fun permitsLegacyPictureControlWrite(model:String):Boolean = model.uppercase(Locale.ROOT).replace("NIKON","").replace(Regex("\\s+"),"")=="Z30"
    fun model(raw:String):String {
        var s=raw.trim().replace("Ⅱ","II").replace("Ⅲ","III").replace("Ⅳ","IV").replace("Ⅰ","I")
        s=s.replace(Regex("_\\d{6,}$"),"").replace(Regex("_(?=[IVX]+$)",RegexOption.IGNORE_CASE),"")
        return s.take(80) // Never strip an unknown generation, or guess that Z502 means Z50II.
    }
}
