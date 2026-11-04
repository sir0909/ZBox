package com.zbox.ai.ptpip

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A PTP/IP endpoint found on a phone-hosted Wi-Fi hotspot. */
data class HotspotCameraEndpoint(
    val cameraAddress: String,
    val phoneAddress: String,
    val interfaceName: String,
)

/**
 * Searches the phone's private IPv4 subnets for Nikon PTP/IP TCP port 15740.
 * Android does not expose hotspot DHCP lease lists to ordinary apps, so discovery is
 * an active, short-timeout port probe. Large networks (/21 and wider) are skipped.
 */
object HotspotCameraDiscovery {
    private const val PTP_IP_PORT = 15740
    private const val CONNECT_TIMEOUT_MS = 250
    private const val MAX_PREFIX_HOSTS = 1022

    fun discover(context:android.content.Context?=null): List<HotspotCameraEndpoint> {
        val started=System.nanoTime()
        val deadline=started+14_000_000_000L
        val subnets = runCatching {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !NikonConnectionPolicy.isCellularOrTunnel(it.name) }
                .flatMap { networkInterface ->
                    networkInterface.interfaceAddresses.asSequence().mapNotNull { ifaceAddress ->
                        val address = ifaceAddress.address as? Inet4Address ?: return@mapNotNull null
                        val prefix = ifaceAddress.networkPrefixLength.toInt()
                        if (prefix !in 16..30 || !isPrivateIpv4(address)) return@mapNotNull null
                        val hostCount = (1 shl (32 - prefix)) - 2
                        if (hostCount < 1) return@mapNotNull null
                        Subnet(networkInterface.name, address, prefix)
                    }
                }
                .distinctBy { it.localAddress.hostAddress to it.prefix }
                .toList()
        }.getOrDefault(emptyList())
        if (subnets.isEmpty()) { com.zbox.ai.ptp.HexLog.info("连接发现","没有可用私有IPv4接口；请检查手机热点及相机IP");return emptyList() }

        val executor = Executors.newFixedThreadPool(32)
        return try {
            val mdns=executor.submit(Callable { if(context==null) emptyList() else nsdCandidates(context,subnets) })
            val futures = subnets.filter { ((1L shl (32-it.prefix))-2)<=MAX_PREFIX_HOSTS }.flatMap { subnet ->
                candidateAddresses(subnet).map { candidate ->
                    executor.submit(Callable {
                        if (System.nanoTime()<deadline && !Thread.currentThread().isInterrupted && portOpen(candidate, subnet.localAddress)) {
                            HotspotCameraEndpoint(
                                cameraAddress = candidate.hostAddress ?: return@Callable null,
                                phoneAddress = subnet.localAddress.hostAddress ?: return@Callable null,
                                interfaceName = subnet.interfaceName,
                            )
                        } else null
                    })
                }
            }
            val scanned=futures.mapNotNull { future ->
                val remaining=deadline-System.nanoTime()
                if(remaining<=0L)null else runCatching{future.get(remaining,TimeUnit.NANOSECONDS)}.getOrNull()
            }
            val resolved=runCatching{mdns.get(1,TimeUnit.SECONDS)}.getOrDefault(emptyList())
            val found=(scanned+resolved).distinctBy{it.cameraAddress}.sortedBy{it.cameraAddress}
            com.zbox.ai.ptp.HexLog.info("连接发现","私有接口=${subnets.size} TCP探测=${futures.size} NSD候选=${resolved.size} 结果=${found.size} 耗时ms=${(System.nanoTime()-started)/1000000}；TCP/NSD发现不代表已通过PTP握手")
            found
        } finally {
            executor.shutdownNow()
        }
    }

    private data class Subnet(val interfaceName: String, val localAddress: Inet4Address, val prefix: Int)

    private fun candidateAddresses(subnet: Subnet): List<InetAddress> {
        val local = ipv4ToLong(subnet.localAddress.address)
        val mask = (-1L shl (32 - subnet.prefix)) and 0xFFFF_FFFFL
        val network = local and mask
        val broadcast = network or (mask xor 0xFFFF_FFFFL)
        return (network + 1 until broadcast).asSequence()
            .filter { it != local }
            .map(::longToIpv4)
            .toList()
    }

    /** NSD supplements bounded subnet scanning; no InitCommand or host registration during discovery. */
    @Suppress("DEPRECATION")
    private fun nsdCandidates(context:android.content.Context,subnets:List<Subnet>):List<HotspotCameraEndpoint> {
        val manager=context.getSystemService(android.content.Context.NSD_SERVICE) as? android.net.nsd.NsdManager ?: return emptyList()
        val results=java.util.concurrent.ConcurrentHashMap<String,HotspotCameraEndpoint>()
        val active=java.util.concurrent.atomic.AtomicBoolean(true)
        val attempts=java.util.concurrent.atomic.AtomicInteger()
        val listeners=mutableListOf<android.net.nsd.NsdManager.DiscoveryListener>()
        for(type in listOf("_ptp._tcp.","_nikon._tcp.")) {
            val listener=object:android.net.nsd.NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t:String){}
                override fun onDiscoveryStopped(t:String){}
                override fun onStartDiscoveryFailed(t:String,e:Int){}
                override fun onStopDiscoveryFailed(t:String,e:Int){}
                override fun onServiceLost(info:android.net.nsd.NsdServiceInfo){}
                override fun onServiceFound(info:android.net.nsd.NsdServiceInfo) {
                    if(!active.get() || attempts.incrementAndGet()>16)return
                    runCatching{manager.resolveService(info,object:android.net.nsd.NsdManager.ResolveListener {
                        override fun onResolveFailed(i:android.net.nsd.NsdServiceInfo,e:Int){}
                        override fun onServiceResolved(i:android.net.nsd.NsdServiceInfo) {
                            if(!active.get() || i.port!=PTP_IP_PORT)return
                            val host=i.host as? Inet4Address ?: return
                            val target=ipv4ToLong(host.address)
                            val subnet=subnets.firstOrNull{n ->
                                val mask=(-1L shl (32-n.prefix)) and 0xffffffffL
                                (target and mask)==(ipv4ToLong(n.localAddress.address) and mask) && host!=n.localAddress
                            } ?: return
                            val address=host.hostAddress ?: return
                            results[address]=HotspotCameraEndpoint(address,subnet.localAddress.hostAddress ?: return,subnet.interfaceName)
                        }
                    })}
                }
            }
            runCatching{manager.discoverServices(type,android.net.nsd.NsdManager.PROTOCOL_DNS_SD,listener);listeners.add(listener)}
        }
        try{java.util.concurrent.CountDownLatch(1).await(4,TimeUnit.SECONDS)}
        catch(_:InterruptedException){Thread.currentThread().interrupt()}
        finally{active.set(false);listeners.forEach{runCatching{manager.stopServiceDiscovery(it)}}}
        return results.values.toList()
    }

    private fun portOpen(host: InetAddress, source: InetAddress): Boolean = runCatching {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.bind(InetSocketAddress(source, 0))
            socket.connect(InetSocketAddress(host, PTP_IP_PORT), CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    private fun isPrivateIpv4(address: Inet4Address): Boolean {
        val b = address.address.map { it.toInt() and 0xFF }
        return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && b[1] == 168) || (b[0] == 100 && b[1] in 64..127)
    }

    private fun ipv4ToLong(bytes: ByteArray): Long = bytes.fold(0L) { acc, byte ->
        (acc shl 8) or (byte.toLong() and 0xFF)
    }

    private fun longToIpv4(value: Long): InetAddress = InetAddress.getByAddress(
        byteArrayOf(
            (value shr 24).toByte(), (value shr 16).toByte(),
            (value shr 8).toByte(), value.toByte(),
        ),
    )
}

/** Binds both PTP/IP sockets to the phone's hotspot-side local address. */
class HotspotSocketBinder(private val phoneAddress: InetAddress) : SocketBinder {
    override fun bind(socket: Socket) {
        socket.bind(InetSocketAddress(phoneAddress, 0))
    }
}
