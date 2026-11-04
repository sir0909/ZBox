package com.zbox.ai.ptp

import android.os.SystemClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** ONE native 0x9207, not a loop of single shots. STOP is always attempted after dispatch,
 * including lost START responses. Restore writes are bound to the original connection identity.
 * File-list auditing is opt-in: production does not enumerate/download files while held.
 */
class NativeBurstController(private val driver: NikonPtpDriver, private val auditNewFiles: Boolean = false) {
    data class Summary(
        val started: Boolean, val addedFiles: Int?, val stopAccepted: Boolean,
        val ready: Boolean, val restored: Boolean,
    )
    private class Released : RuntimeException()
    private var connectionToken: Any? = null
    private fun bindConnection() {
        check(driver.isOpen) { "相机未连接" }
        connectionToken = driver.burstConnectionToken
        check(connectionToken != null)
    }
    private fun sameConnection(): Boolean = driver.isOpen && driver.burstConnectionToken === connectionToken
    private fun checkConnection() = check(sameConnection()) { "原连拍会话已断开" }
    private suspend fun prop(code: Int): NativeBurstProtocol.U16Prop {
        val result = driver.transact(Ptp.OP_GET_DEVICE_PROP_DESC, intArrayOf(code), null, true, 2500,
            expectedConnection = connectionToken)
        check(result.ok && result.data != null) { "连拍属性 0x%04X 不可用".format(code) }
        return NativeBurstProtocol.descriptor(result.data!!, code)
    }
    private suspend fun write(code: Int, value: Int) {
        val payload = byteArrayOf(value.toByte(), (value ushr 8).toByte())
        val result = driver.transact(Ptp.OP_SET_DEVICE_PROP_VALUE, intArrayOf(code), payload, false, 2500,
            expectedConnection = connectionToken)
        check(result.ok) { "Write 0x%04X failed: %s".format(code, Ptp.responseName(result.responseCode)) }
        val read = driver.transact(Ptp.OP_GET_DEVICE_PROP_VALUE, intArrayOf(code), null, true, 2500,
            expectedConnection = connectionToken)
        check(read.ok && read.data != null && NativeBurstProtocol.u16(read.data!!, 0) == value) {
            "Burst property readback mismatch"
        }
        HexLog.info("NativeBurst", "PROPERTY 0x%04X=$value readback OK".format(code))
    }
    private suspend fun handles(storages: List<Long>): Set<Long> = buildSet {
        checkConnection()
        for (storage in storages) addAll(driver.getObjectHandles(storage, 0L).getOrThrow())
        checkConnection()
    }
    suspend fun ensureSingleShot() {
        bindConnection()
        val count = prop(0x5018)
        if (count.current != 1) {
            check(count.writable) { "无法保证单击只拍一张" }
            write(0x5018, 1)
        }
    }

    suspend fun run(held: () -> Boolean, started: (Int) -> Unit): Summary {
        bindConnection()
        check(held()) { "Burst request already released" }
        val raw = driver.getDeviceInfoRaw().getOrThrow()
        checkConnection()
        val ops = NativeBurstProtocol.operations(raw)
        check(Ptp.OP_NIKON_CAPTURE_TO_MEDIA in ops && Ptp.OP_NIKON_TERMINATE_CAPTURE in ops) {
            "当前会话没有同时通告原生拍摄和停止命令，不能安全启用长按连拍"
        }
        check(driver.waitUntilDeviceReady(6000)) { "Camera not ready" }
        checkConnection()
        val storages = driver.getStorageIds().getOrThrow()
        check(storages.isNotEmpty()) { "No camera card" }
        val before = if (auditNewFiles) handles(storages) else emptySet()
        val mode = prop(0x5013)
        val count = prop(0x5018)
        check(mode.writable && count.writable && 2 in mode.choices) { "Remote native burst configuration unavailable" }
        // 2 = Nikon native continuous/high; retain an already selected Z30 extended-H enum.
        // Other release modes are temporarily changed to continuous, then restored.
        val burstMode = if (mode.current == 2 || (mode.current == 0x8019 && mode.current in mode.choices)) mode.current else 2
        val maxBytes = driver.getDeviceProp(0xD069, 2000).getOrNull()
        checkConnection()
        val cameraMaximum = maxBytes?.firstOrNull()?.let { it.toInt() and 255 }
        val limit = count.frameLimit(cameraMaximum)
        var countTouched = false
        var modeTouched = false
        var dispatched = false
        var stopAccepted = false
        var ready = true
        var restored = true
        var failure: Throwable? = null
        try {
            if (!held()) throw Released()
            if (mode.current != burstMode) {
                modeTouched = true
                countTouched = true // A release-mode change may itself reset the burst count.
                write(0x5013, burstMode)
            }
            if (!held()) throw Released()
            countTouched = true
            write(0x5018, limit) // Count LAST: mode changes must not silently turn this into one shot.
            if (!held()) throw Released()
            checkConnection()
            dispatched = true // A lost response is NOT proof that no photo was taken.
            val t0 = SystemClock.elapsedRealtime()
            HexLog.info("NativeBurst", "START ONE 0x9207 card; mode=0x%04X limit=$limit".format(burstMode))
            val result = driver.transact(Ptp.OP_NIKON_CAPTURE_TO_MEDIA, intArrayOf(-1, 0), null, false, 4000,
                expectedConnection = connectionToken)
            check(result.ok) { "Native burst start rejected: ${Ptp.responseName(result.responseCode)}" }
            HexLog.info("NativeBurst", "START response ${SystemClock.elapsedRealtime() - t0} ms")
            started(limit)
            // Physical camera's per-burst limit is primary protection; watchdog covers lost UI events.
            val deadline = SystemClock.elapsedRealtime() + 120000
            while (held() && sameConnection() && SystemClock.elapsedRealtime() < deadline) delay(15)
        } catch (error: Throwable) {
            if (error !is Released) failure = error
        } finally {
            withContext(NonCancellable) {
                if (dispatched) {
                    try {
                        val t0 = SystemClock.elapsedRealtime()
                        val result = driver.transact(Ptp.OP_NIKON_TERMINATE_CAPTURE, intArrayOf(0, 0), null, false, 2500,
                            expectedConnection = connectionToken)
                        stopAccepted = result.ok
                        HexLog.info("NativeBurst", "STOP 0x920C ${Ptp.responseName(result.responseCode)} ${SystemClock.elapsedRealtime() - t0} ms")
                    } catch (error: Exception) {
                        HexLog.error("NativeBurst", "STOP not confirmed: ${error.message}")
                    }
                    ready = sameConnection() && runCatching { driver.waitUntilDeviceReady(8000) }.getOrDefault(false)
                }
                if (modeTouched) restored = runCatching { write(0x5013, mode.current) }.isSuccess && restored
                if (countTouched) restored = runCatching { write(0x5018, count.current) }.isSuccess && restored
                HexLog.info("NativeBurst", "CLEANUP ready=$ready restored=$restored oldCount=${count.current} oldMode=${mode.current}")
            }
        }
        failure?.let { throw it }
        var added: Int? = null
        if (auditNewFiles && dispatched && ready && sameConnection()) {
            val first = handles(storages) - before
            delay(750)
            val settled = handles(storages) - before
            added = settled.size
            HexLog.info("NativeBurst", "AUDIT filesFirst=${first.size} filesSettled=${settled.size} " +
                settled.joinToString(",") { "0x%08X:%s".format(it, NikonStaDirectRead.formatFromHandle(it.toInt())) })
        }
        HexLog.info("NativeBurst", "RESULT started=$dispatched addedCardObjects=$added stopAccepted=$stopAccepted ready=$ready restored=$restored")
        return Summary(dispatched, added, stopAccepted, ready, restored)
    }
}
