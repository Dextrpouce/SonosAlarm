package ch.srs.sonosalarm.sonos

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Finds Sonos speakers on the Wi-Fi with an SSDP M-SEARCH. */
object SonosDiscovery {
    private const val SEARCH =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"

    suspend fun discover(context: Context, timeoutMs: Long = 3000): List<Speaker> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("sonos-ssdp")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val ips = linkedSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val data = SEARCH.toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(data, data.size, group, 1900)) }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text.contains("ZonePlayer", ignoreCase = true) || text.contains("Sonos", ignoreCase = true)) {
                            packet.address.hostAddress?.let { ips += it }
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } finally {
            lock?.release()
        }
        ips.mapNotNull { ip -> runCatching { SonosClient(ip).describe() }.getOrNull() }
            .sortedBy { it.name }
    }
}
