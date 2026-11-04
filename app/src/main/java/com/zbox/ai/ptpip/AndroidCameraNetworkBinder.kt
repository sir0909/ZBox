// Adapted from Fluoro-Antimonic-Acid/Shotdeck (GPL-3.0-or-later), Copyright (C) 2026 Shotdeck contributors.
// Adapted and integrated with the Zbox PTP/IP transport.
package com.zbox.ai.ptpip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.zbox.ai.ptpip.SocketBinder
import java.net.Socket

/**
 * Puts the PTP/IP sockets on the camera's Wi-Fi network.
 *
 * This is the fix for the single most common failure of this kind of app: the phone
 * joins the camera's hotspot, but because that hotspot has no internet the system
 * keeps routing traffic out of the cellular interface, so the second (event) socket
 * lands on the wrong network and the session dies. Binding the socket to the Wi-Fi
 * network before connect removes the guesswork.
 *
 * A camera access point looks like a Wi-Fi network without validated internet, so
 * that is preferred; any Wi-Fi network is used as a fallback so the app still works on
 * a home network with the camera attached to it.
 *
 * NOT YET the productised flow: the plan calls for WifiNetworkSpecifier and an
 * explicit user-granted request before shipping, which is not implemented here.
 */
class AndroidCameraNetworkBinder(
    private val context: Context,
) : SocketBinder {

    override fun bind(socket: Socket) {
        val network = cameraNetwork() ?: return
        runCatching { network.bindSocket(socket) }
    }

    /** The Wi-Fi network most likely to be the camera, or null when there is none. */
    fun cameraNetwork(): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = manager.allNetworks.filter { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@filter false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        if (candidates.isEmpty()) return null

        // Prefer a Wi-Fi network that is up but has no validated internet: that is
        // what a camera access point looks like from the system's point of view.
        val withoutInternet = candidates.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        return withoutInternet ?: candidates.first()
    }

    /** A default internet route is insufficient: the endpoint must be on this Wi-Fi subnet. */
    fun hasCameraRoute(host: String): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cameraNetwork() ?: return false
        return runCatching {
            val address = java.net.InetAddress.getByName(host)
            manager.getLinkProperties(network)?.routes?.any {
                it.destination.prefixLength > 0 && it.matches(address)
            } == true
        }.getOrDefault(false)
    }

    /** Human-readable name for the connection screen, e.g. "Z30_01234567". */
    fun describe(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "未知网络"
        val network = cameraNetwork() ?: return "未连接到 Wi-Fi"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "未知网络"
        return when {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                "已连接 Wi-Fi（有互联网，可能不是相机热点）"
            else -> "已连接 Wi-Fi（无互联网，符合相机热点特征）"
        }
    }
}
