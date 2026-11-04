package com.zbox.ai.ptpip

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import java.net.InetAddress

/**
 * Frequency is useful only when it belongs to the network routing to the CAMERA.
 * A hosted hotspot is normally absent from ConnectivityManager's client networks:
 * return unknown rather than reporting the phone's unrelated 5 GHz internet uplink.
 */
internal fun cameraLinkFrequencyMhz(context: Context, host: String, bindLocalAddress: String?): Int = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching 0
    val target = InetAddress.getByName(host)
    @Suppress("DEPRECATION")
    for (network in manager.allNetworks) {
        val caps = manager.getNetworkCapabilities(network) ?: continue
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
        val links = manager.getLinkProperties(network) ?: continue
        if (!bindLocalAddress.isNullOrBlank() && links.linkAddresses.none { it.address.hostAddress == bindLocalAddress }) continue
        if (links.routes.none { it.destination.prefixLength > 0 && it.matches(target) }) continue
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps.transportInfo as? WifiInfo
            val frequency = info?.frequency ?: 0
            if (frequency > 0) return@runCatching frequency
        }
    }
    0
}.getOrDefault(0)
