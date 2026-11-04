package com.zbox.ai

import android.app.Instrumentation
import android.os.Bundle
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.ptpip.HotspotCameraDiscovery
import kotlinx.coroutines.runBlocking

/**
 * Explicit, read-only hardware-link smoke probe. It only discovers port 15740 and calls
 * openWifi (PTP/IP init, OpenSession, GetDeviceInfo); it never reads a PIN, registers a host,
 * writes properties, or invokes capture/delete/reset operations.
 */
class PtpIpReadOnlySmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val endpoints = HotspotCameraDiscovery.discover(targetContext)
            if (endpoints.isEmpty()) {
                result.putString("stream", "NO_PTP_ENDPOINT: bounded private-network discovery found no TCP/15740 listener; no PTP init was sent.\n")
                finish(-1, result)
                return
            }
            val outcomes = mutableListOf<String>()
            runBlocking {
                endpoints.forEach { endpoint ->
                    val driver = NikonPtpDriver(targetContext.applicationContext)
                    try {
                        val info = driver.openWifi(
                            host = endpoint.cameraAddress,
                            port = 15740,
                            bindLocalAddress = endpoint.phoneAddress,
                        ).getOrThrow()
                        outcomes += "${endpoint.cameraAddress} [${endpoint.interfaceName}]: PTP/IP OK; ${info.manufacturer} ${info.model}; DeviceInfo read-only."
                    } catch (t: Throwable) {
                        outcomes += "${endpoint.cameraAddress} [${endpoint.interfaceName}]: ${t.javaClass.simpleName}: ${t.message ?: "no detail"}"
                    } finally {
                        runCatching { driver.close() }
                    }
                }
            }
            result.putString("stream", outcomes.joinToString("\n", postfix = "\nNo PIN read or host registration was attempted.\n"))
            finish(-1, result)
        } catch (t: Throwable) {
            result.putString("stream", "PROBE_FAILED: ${t.javaClass.simpleName}: ${t.message}\n")
            finish(0, result)
        }
    }
}
